package com.chaos.storage.oss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.ObjectMetadata;
import com.chaos.storage.FileObject;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * OSS 客户端参数处理测试。
 */
class OssObjectStorageClientTest {

    private final OSS oss = mock(OSS.class);

    /**
     * 大小未知时不应设置 Content-Length。
     */
    @Test
    void shouldNotSetContentLengthWhenSizeUnknown() {
        FileObject result = new OssObjectStorageClient(oss)
                .put("bucket", "a.txt", new ByteArrayInputStream(new byte[3]), -1, "text/plain");

        ArgumentCaptor<ObjectMetadata> captor = ArgumentCaptor.forClass(ObjectMetadata.class);
        verify(oss).putObject(eq("bucket"), eq("a.txt"), any(InputStream.class), captor.capture());
        assertThat(captor.getValue().getRawMetadata()).doesNotContainKey("Content-Length");
        assertThat(captor.getValue().getContentType()).isEqualTo("text/plain");
        assertThat(result.size()).isEqualTo(-1);
    }

    /**
     * 大小已知或内容类型为空白时应正确处理元数据，并支持读取、删除与预签名 URL。
     */
    @Test
    void shouldHandleKnownSizeBlankContentTypeGetDeleteAndPresignedUrl() throws Exception {
        OSSObject ossObject = mock(OSSObject.class);
        InputStream expectedStream = new ByteArrayInputStream(new byte[]{1, 2});
        when(ossObject.getObjectContent()).thenReturn(expectedStream);
        when(oss.getObject("bucket", "b.bin")).thenReturn(ossObject);
        URL expectedUrl = new URL("https://oss.local/bucket/b.bin?Expires=123");
        when(oss.generatePresignedUrl(any(GeneratePresignedUrlRequest.class))).thenReturn(expectedUrl);

        OssObjectStorageClient client = new OssObjectStorageClient(oss);
        FileObject putResult = client.put("bucket", "b.bin", new ByteArrayInputStream(new byte[]{1, 2}), 2, "  ");
        ArgumentCaptor<ObjectMetadata> captor = ArgumentCaptor.forClass(ObjectMetadata.class);
        verify(oss).putObject(eq("bucket"), eq("b.bin"), any(InputStream.class), captor.capture());
        assertThat(captor.getValue().getContentLength()).isEqualTo(2);
        assertThat(captor.getValue().getContentType()).isNull();
        assertThat(putResult.size()).isEqualTo(2);

        client.put("bucket", "c.bin", new ByteArrayInputStream(new byte[0]), 0, null);

        assertThat(client.get("bucket", "b.bin")).isSameAs(expectedStream);
        client.delete("bucket", "b.bin");
        verify(oss).deleteObject("bucket", "b.bin");
        assertThat(client.presignedGetUrl("bucket", "b.bin", Duration.ofMinutes(10))).isEqualTo(expectedUrl);
    }

    /**
     * 属性绑定 getter/setter 覆盖。
     */
    @Test
    void shouldBindProperties() {
        ChaosOssProperties props = new ChaosOssProperties();
        props.setEndpoint("https://oss-cn-hangzhou.aliyuncs.com");
        props.setAccessKeyId("ak");
        props.setAccessKeySecret("sk");
        assertThat(props.getEndpoint()).isEqualTo("https://oss-cn-hangzhou.aliyuncs.com");
        assertThat(props.getAccessKeyId()).isEqualTo("ak");
        assertThat(props.getAccessKeySecret()).isEqualTo("sk");
    }

    /**
     * 非法 key 和 TTL 在调用 SDK 前拒绝。
     */
    @Test
    void shouldValidateArgumentsBeforeCallingSdk() {
        OssObjectStorageClient client = new OssObjectStorageClient(oss);

        assertThatThrownBy(() -> client.delete("bucket", "/etc/passwd")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.presignedGetUrl("bucket", "a.txt", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(oss);
    }
}
