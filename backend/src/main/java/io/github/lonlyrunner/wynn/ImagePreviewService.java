package io.github.lonlyrunner.wynn;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
    private final Map<String, byte[]> memoryCache = new LinkedHashMap<>(96, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) { return size() > 96; }
    };

    ImagePreviewService(OssService oss) { this.oss = oss; }

    synchronized byte[] preview(MediaItem item, int requestedWidth) {
        int width = requestedWidth <= 480 ? 480 : requestedWidth <= 960 ? 960 : 1200;
        String key = "previews/media-" + item.id + "-" + Integer.toUnsignedString(item.objectKey.hashCode()) + "-" + width + ".jpg";
        byte[] cached = memoryCache.get(key);
        if (cached != null) return cached;
        if (oss.exists(key)) {
            byte[] stored = oss.get(key);
            memoryCache.put(key, stored);
            return stored;
        }
        byte[] preview = resize(oss.get(item.objectKey), width);
        oss.put(key, preview, "image/jpeg");
        memoryCache.put(key, preview);
        return preview;
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
