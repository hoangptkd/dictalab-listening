package com.dictalab.service;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** RabbitMQ transport for Listening grading IDs; PostgreSQL remains the source of truth. */
@Component
@ConditionalOnProperty(name = "dictalab.listening.queue.enabled", havingValue = "true")
public class ListeningGradingQueue {
    private final RabbitTemplate rabbit;
    private final ListeningComprehensionService grading;
    private final String exchange;
    private final String queue;
    private final io.micrometer.core.instrument.MeterRegistry metrics;

    public ListeningGradingQueue(RabbitTemplate rabbit, ListeningComprehensionService grading,
            @org.springframework.beans.factory.annotation.Value("${dictalab.listening.queue.exchange:dictalab.jobs}") String exchange,
            @org.springframework.beans.factory.annotation.Value("${dictalab.listening.queue.name:dictalab.listening.grading}") String queue,
            io.micrometer.core.instrument.MeterRegistry metrics) {
        this.rabbit = rabbit;
        this.grading = grading;
        this.exchange = exchange;
        this.queue = queue;
        this.metrics = metrics;
    }

    public void publish(String sessionId, int passNumber, int revision, int attempt) {
        var confirmation = new CorrelationData(java.util.UUID.randomUUID().toString());
        try {
            rabbit.convertAndSend(exchange, queue, sessionId + "/" + passNumber + "/" + revision + "/" + attempt,
                    message -> {
                        message.getMessageProperties().setDeliveryMode(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT);
                        return message;
                    }, confirmation);
            if (!confirmation.getFuture().get(5, java.util.concurrent.TimeUnit.SECONDS).ack()
                    || confirmation.getReturned() != null) throw new IllegalStateException("Broker did not accept grading job");
            metrics.counter("dictalab.listening.queue.published").increment();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Grading publication interrupted", interrupted);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException unavailable) {
            metrics.counter("dictalab.listening.queue.publish_failures").increment();
            throw new IllegalStateException("Grading publication unconfirmed", unavailable);
        } catch (RuntimeException unavailable) {
            metrics.counter("dictalab.listening.queue.publish_failures").increment();
            throw unavailable;
        }
    }

    @RabbitListener(queues = "${dictalab.listening.queue.name:dictalab.listening.grading}",
            concurrency = "${dictalab.listening.workers:2}")
    public void consume(String job) {
        String[] parts = job == null ? new String[0] : job.split("/", -1);
        int pass, revision, attempt;
        try {
            if (parts.length != 4 || !java.util.UUID.fromString(parts[0]).toString().equals(parts[0]))
                throw new IllegalArgumentException();
            pass = Integer.parseInt(parts[1]);revision = Integer.parseInt(parts[2]);attempt = Integer.parseInt(parts[3]);
            if (pass < 1 || pass > 2 || revision < 0 || revision > 2 || attempt < 0 || attempt > 2)
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) {
            metrics.counter("dictalab.listening.queue.invalid").increment();
            throw new AmqpRejectAndDontRequeueException("Invalid grading job");
        }
        // AUTO acknowledgement happens after this returns and the DB result/state has been persisted.
        // DB failures go to the DLQ; the pending DB row is republished by the dispatcher after its timeout.
        grading.gradeQueued(parts[0], pass, revision, attempt);
        metrics.counter("dictalab.listening.queue.consumed").increment();
    }
}
