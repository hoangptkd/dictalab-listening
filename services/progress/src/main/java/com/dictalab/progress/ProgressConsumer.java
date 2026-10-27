package com.dictalab.progress;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods=false)
public class ProgressConsumer {
    public static final String QUEUE = "dictalab.progress.v1";
    private final ObjectMapper json;
    private final ProgressStore store;
    private final MeterRegistry metrics;

    public ProgressConsumer(ObjectMapper json, ProgressStore store, MeterRegistry metrics) {
        this.json = json; this.store = store; this.metrics = metrics;
    }

    @Bean
    Declarables progressQueues() {
        return new Declarables(QueueBuilder.durable(QUEUE).quorum().maxLength(10000)
                .overflow(QueueBuilder.Overflow.rejectPublish).deadLetterExchange("")
                .withArgument("x-dead-letter-strategy", "at-least-once")
                .deadLetterRoutingKey(QUEUE + ".dlq").build(),
                QueueBuilder.durable(QUEUE + ".dlq").quorum().maxLength(10000)
                        .overflow(QueueBuilder.Overflow.rejectPublish).build());
    }

    @RabbitListener(queues=QUEUE, concurrency="1")
    public void consume(Message message) {
        ProgressEvent event;
        try {
            if (message.getBody().length > 64000) throw new IllegalArgumentException("Progress message too large");
            event = json.readerFor(ProgressEvent.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(message.getBody());
        } catch (RuntimeException invalid) {
            metrics.counter("dictalab.progress.events", "outcome", "invalid").increment();
            throw new AmqpRejectAndDontRequeueException("Invalid progress envelope");
        }
        try {
            String outcome = store.apply(event);
            metrics.counter("dictalab.progress.events", "outcome", outcome).increment();
        } catch (IllegalArgumentException invalid) {
            metrics.counter("dictalab.progress.events", "outcome", "invalid").increment();
            throw new AmqpRejectAndDontRequeueException("Invalid progress payload");
        }
        // Spring AUTO ACK follows the committed projection. Lost ACKs are safe to redeliver.
    }
}
