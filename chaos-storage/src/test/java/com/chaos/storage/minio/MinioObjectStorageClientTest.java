package com.chaos.storage.minio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.chaos.storage.FileObject;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * MinIO 客户端参数处理测试。
 */
class MinioObjectStorageClientTest {

    private final MinioClient minioClient = mock(MinioClient.class);

    /**
     * 大小未知时必须指定分块大小，否则 MinIO SDK 直接报错。
     */
    @Test
    void shouldUsePartSizeWhenSizeUnknown() throws Exception {
        FileObject result = new MinioObjectStorageClient(minioClient)
                .put("bucket", "a.txt", new ByteArrayInputStream(new byte[3]), -1, "text/plain");

        ArgumentCaptor<PutObjectArgs> captor = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minioClient).putObject(captor.capture());
        assertThat(captor.getValue().partSize()).isEqualTo(MinioObjectStorageClient.UNKNOWN_SIZE_PART_SIZE);
        assertThat(captor.getValue().objectSize()).isEqualTo(-1);
        assertThat(result.size()).isEqualTo(-1);
        assertThat(result.objectKey()).isEqualTo("a.txt");
    }

    /**
     * 大小已知时直接透传对象大小并可读取、删除、预签名与查询元数据。
     */
    @Test
    void shouldSupportKnownSizePutGetDeletePresignedUrlAndStat() throws Exception {
        GetObjectResponse getObjectResponse = mock(GetObjectResponse.class);
        StatObjectResponse statResponse = mock(StatObjectResponse.class);
        ZonedDateTime now = ZonedDateTime.now();
        when(statResponse.contentType()).thenReturn("application/pdf");
        when(statResponse.size()).thenReturn(42L);
        when(statResponse.lastModified()).thenReturn(now);
        when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(getObjectResponse);
        when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                .thenReturn("https://minio.local/bucket/a.txt?sig=1");
        when(minioClient.statObject(any(StatObjectArgs.class))).thenReturn(statResponse);

        MinioObjectStorageClient client = new MinioObjectStorageClient(minioClient);
        FileObject putResult = client.put("bucket", "a.txt", new ByteArrayInputStream(new byte[4]), 4, "text/plain");
        ArgumentCaptor<PutObjectArgs> putCaptor = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minioClient).putObject(putCaptor.capture());
        assertThat(putCaptor.getValue().objectSize()).isEqualTo(4);
        assertThat(putResult.size()).isEqualTo(4);

        try (InputStream stream = client.get("bucket", "a.txt")) {
            assertThat(stream).isSameAs(getObjectResponse);
        }
        client.delete("bucket", "a.txt");
        verify(minioClient).removeObject(any(RemoveObjectArgs.class));

        URL presigned = client.presignedGetUrl("bucket", "a.txt", Duration.ofMinutes(5));
        assertThat(presigned.toString()).isEqualTo("https://minio.local/bucket/a.txt?sig=1");

        FileObject stat = client.stat("bucket", "a.txt");
        assertThat(stat.contentType()).isEqualTo("application/pdf");
        assertThat(stat.size()).isEqualTo(42L);
        assertThat(stat.createdAt()).isEqualTo(now.toInstant());
    }

    /**
     * SDK 调用失败或预签名 URL 非法时统一包装为 IllegalStateException。
     */
    @Test
    void shouldWrapSdkAndMalformedUrlFailures() throws Exception {
        when(minioClient.putObject(any(PutObjectArgs.class))).thenThrow(new RuntimeException("put failed"));
        when(minioClient.getObject(any(GetObjectArgs.class))).thenThrow(new RuntimeException("get failed"));
        doThrow(new RuntimeException("delete failed")).when(minioClient).removeObject(any(RemoveObjectArgs.class));
        when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                .thenReturn("not-a-valid-url")
                .thenThrow(new RuntimeException("presign failed"));
        when(minioClient.statObject(any(StatObjectArgs.class))).thenThrow(new RuntimeException("stat failed"));

        MinioObjectStorageClient client = new MinioObjectStorageClient(minioClient);
        assertThatThrownBy(() -> client.put("bucket", "a.txt", new ByteArrayInputStream(new byte[1]), 1, "text/plain"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MinIO put object failed")
                .hasRootCauseMessage("put failed");
        assertThatThrownBy(() -> client.get("bucket", "a.txt"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MinIO get object failed")
                .hasRootCauseMessage("get failed");
        assertThatThrownBy(() -> client.delete("bucket", "a.txt"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MinIO delete object failed")
                .hasRootCauseMessage("delete failed");
        assertThatThrownBy(() -> client.presignedGetUrl("bucket", "a.txt", Duration.ofMinutes(1)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Invalid MinIO presigned URL");
        assertThatThrownBy(() -> client.presignedGetUrl("bucket", "a.txt", Duration.ofMinutes(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MinIO presigned URL failed")
                .hasRootCauseMessage("presign failed");
        assertThatThrownBy(() -> client.stat("bucket", "a.txt"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MinIO stat object failed")
                .hasRootCauseMessage("stat failed");
    }

    /**
     * 属性绑定默认值与 getter/setter 覆盖。
     */
    @Test
    void shouldBindProperties() {
        ChaosMinioProperties props = new ChaosMinioProperties();
        props.setEndpoint("http://127.0.0.1:9000");
        props.setAccessKey("ak");
        props.setSecretKey("sk");
        assertThat(props.getEndpoint()).isEqualTo("http://127.0.0.1:9000");
        assertThat(props.getAccessKey()).isEqualTo("ak");
        assertThat(props.getSecretKey()).isEqualTo("sk");
    }

    /**
     * 非法 key 和 TTL 在调用 SDK 前拒绝。
     */
    @Test
    void shouldValidateArgumentsBeforeCallingSdk() {
        MinioObjectStorageClient client = new MinioObjectStorageClient(minioClient);

        assertThatThrownBy(() -> client.put("bucket", "../x", new ByteArrayInputStream(new byte[1]), 1, "text/plain"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.presignedGetUrl("bucket", "a.txt", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.presignedGetUrl("bucket", "a.txt", Duration.ofDays(30)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(minioClient);
    }
}
