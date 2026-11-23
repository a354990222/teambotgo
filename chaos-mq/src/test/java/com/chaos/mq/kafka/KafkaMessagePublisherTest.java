package com.chaos.mq.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chaos.mq.MessageEnvelope;
import com.chaos.mq.MessageHeaders;
import com.chaos.mq.MessagePublishException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/**
 * Kafka 发布器发送确认测试。
 */
class KafkaMessagePublisherTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);

    /**
     * broker 返回失败时必须抛出异常，不能让 outbox 标记为 SENT。
     */
    @Test
    void shouldThrowWhenBrokerRejectsMessage() {
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));
        KafkaMessagePublisher publisher = new KafkaMessagePublisher(kafkaTemplate);

        assertThatThrownBy(() -> publisher.publish(envelope()))
                .isInstanceOf(MessagePublishException.class)
                .hasMessageContaining("broker down");
    }

    /**
     * 线程中断、同步抛异常以及超时配置非法分支应正确处理。
     */
    @Test
    @SuppressWarnings("unchecked")
    void shouldHandleInterruptSyncFailureAndInvalidTimeouts() throws Exception {
        CompletableFuture<SendResult<String, Object>> interruptedFuture = mock(CompletableFuture.class);
        when(interruptedFuture.get(any(Long.class), any(TimeUnit.class)))
                .thenThrow(new InterruptedException("interrupted"));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(interruptedFuture);
        KafkaMessagePublisher publisher = new KafkaMessagePublisher(kafkaTemplate, Duration.ZERO);

        try {
            assertThatThrownBy(() -> publisher.publish(envelope()))
                    .isInstanceOf(MessagePublishException.class)
                    .hasMessageContaining("interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }

        when(kafkaTemplate.send(any(ProducerRecord.class))).thenThrow(new IllegalArgumentException("bad serializer"));
        assertThatThrownBy(() -> new KafkaMessagePublisher(kafkaTemplate, Duration.ofSeconds(-1)).publish(envelope()))
                .isInstanceOf(MessagePublishException.class)
                .hasMessageContaining("bad serializer");

        CompletableFuture<SendResult<String, Object>> nullCauseFuture = mock(CompletableFuture.class);
        when(nullCauseFuture.get(any(Long.class), any(TimeUnit.class)))
                .thenThrow(new ExecutionException("exec", null) {});
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(nullCauseFuture);
        assertThatThrownBy(() -> new KafkaMessagePublisher(kafkaTemplate, null).publish(envelope()))
                .isInstanceOf(MessagePublishException.class);
    }

    /**
     * broker 未在超时时间内确认时应抛出异常。
     */
    @Test
    void shouldThrowWhenSendTimesOut() {
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(new CompletableFuture<>());
        KafkaMessagePublisher publisher = new KafkaMessagePublisher(kafkaTemplate, Duration.ofMillis(20));

        assertThatThrownBy(() -> publisher.publish(envelope()))
                .isInstanceOf(MessagePublishException.class)
                .hasMessageContaining("timeout");
    }

    /**
     * 发送成功时应携带 message-id 和 message-tag 头，并忽略空白 tag 与 null header。
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void shouldWriteMessageIdHeaderAndFilterBlankTagOrNullHeader() {
        ArgumentCaptor<ProducerRecord> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        when(kafkaTemplate.send(captor.capture()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        KafkaMessagePublisher publisher = new KafkaMessagePublisher(kafkaTemplate);

        publisher.publish(envelope());

        ProducerRecord<String, Object> record = captor.getValue();
        assertThat(new String(record.headers().lastHeader(MessageHeaders.MESSAGE_ID).value(), StandardCharsets.UTF_8))
                .isEqualTo("msg-1");
        assertThat(new String(record.headers().lastHeader(MessageHeaders.MESSAGE_TAG).value(), StandardCharsets.UTF_8))
                .isEqualTo("created");
        assertThat(record.key()).isEqualTo("msg-1");

        Map<String, String> headers = Map.of("custom", "v1");
        publisher.publish(new MessageEnvelope<>("msg-2", "order.created", "  ", "payload", headers, null));
        ProducerRecord<String, Object> second = captor.getAllValues().get(1);
        assertThat(second.headers().lastHeader(MessageHeaders.MESSAGE_TAG)).isNull();
        assertThat(new String(second.headers().lastHeader("custom").value(), StandardCharsets.UTF_8)).isEqualTo("v1");
        publisher.publish(new MessageEnvelope<>("msg-3", "order.created", null, "payload", Map.of(), null));
        ProducerRecord<String, Object> third = captor.getAllValues().get(2);
        assertThat(third.headers().lastHeader(MessageHeaders.MESSAGE_TAG)).isNull();
    }

    private static MessageEnvelope<String> envelope() {
        return new MessageEnvelope<>("msg-1", "order.created", "created", "payload", Map.of(), null);
    }
}
