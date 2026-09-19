package io.github.lonlyrunner.wynn;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import java.time.Duration;
import java.util.Date;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
class OssService {
    @Value("${app.oss.endpoint:}") String endpoint;
    @Value("${app.oss.bucket:}") String bucket;
    @Value("${app.oss.access-key-id:}") String accessKeyId;
    @Value("${app.oss.access-key-secret:}") String accessKeySecret;
    String presignedPutUrl(String objectKey){if(endpoint.isBlank()||bucket.isBlank()||accessKeyId.isBlank()||accessKeySecret.isBlank())throw new IllegalStateException("OSS is not configured");var client=new OSSClientBuilder().build(endpoint,accessKeyId,accessKeySecret);try{var request=new GeneratePresignedUrlRequest(bucket,objectKey,HttpMethod.PUT);request.setExpiration(new Date(System.currentTimeMillis()+Duration.ofMinutes(10).toMillis()));return client.generatePresignedUrl(request).toString();}finally{client.shutdown();}}
}
