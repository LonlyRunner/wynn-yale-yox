package io.github.lonlyrunner.wynn;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import org.springframework.stereotype.Service;

@Service
class ImagePreviewService {
    private final OssService oss;
    private final Path diskCache = Path.of("data", "preview-cache");
    private final Object[] locks = new Object[32];
    private final Map<String, byte[]> memoryCache = new LinkedHashMap<>(96, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) { return size() > 96; }
    };

    ImagePreviewService(OssService oss) {
        this.oss = oss;
        for (int index = 0; index < locks.length; index++) locks[index] = new Object();
        try {
            Files.createDirectories(diskCache);
        } catch (IOException error) {
            throw new IllegalStateException("Unable to create image preview cache", error);
        }
    }

    byte[] preview(MediaItem item, int requestedWidth) {
        int width = requestedWidth <= 480 ? 480 : requestedWidth <= 960 ? 960 : 1200;
        String key = "previews/media-" + item.id + "-" + Integer.toUnsignedString(item.objectKey.hashCode()) + "-" + width + ".jpg";
        byte[] cached = cached(key);
        if (cached != null) return cached;

        Object lock = locks[Math.floorMod(key.hashCode(), locks.length)];
        synchronized (lock) {
            cached = cached(key);
            if (cached != null) return cached;

            Path file = diskCache.resolve(key.substring("previews/".length()));
            byte[] stored = readDisk(file);
            if (stored == null) {
                if (oss.exists(key)) {
                    stored = oss.get(key);
                } else {
                    stored = resize(oss.get(item.objectKey), width);
                    oss.put(key, stored, "image/jpeg");
                }
                writeDisk(file, stored);
            }
            cache(key, stored);
            return stored;
        }
    }

    private byte[] cached(String key) {
        synchronized (memoryCache) { return memoryCache.get(key); }
    }

    private void cache(String key, byte[] bytes) {
        synchronized (memoryCache) { memoryCache.put(key, bytes); }
    }

    private byte[] readDisk(Path file) {
        try { return Files.isRegularFile(file) ? Files.readAllBytes(file) : null; }
        catch (IOException ignored) { return null; }
    }

    private void writeDisk(Path file, byte[] bytes) {
        try { Files.write(file, bytes); }
        catch (IOException ignored) { }
    }

    private byte[] resize(byte[] sourceBytes, int maxWidth) {
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(sourceBytes));
            if (source == null) throw new IllegalArgumentException("Unsupported image format");
            double scale = Math.min(1d, maxWidth / (double) source.getWidth());
            int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
            int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
            BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = target.createGraphics();
            try {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, width, height);
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                graphics.drawImage(source, 0, 0, width, height, null);
            } finally { graphics.dispose(); }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
            ImageWriter writer = writers.next();
            try (ImageOutputStream stream = ImageIO.createImageOutputStream(output)) {
                writer.setOutput(stream);
                ImageWriteParam params = writer.getDefaultWriteParam();
                params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                params.setCompressionQuality(.82f);
                writer.write(null, new IIOImage(target, null, null), params);
            } finally { writer.dispose(); }
            return output.toByteArray();
        } catch (Exception error) {
            throw new IllegalStateException("Unable to create image preview", error);
        }
    }
}
