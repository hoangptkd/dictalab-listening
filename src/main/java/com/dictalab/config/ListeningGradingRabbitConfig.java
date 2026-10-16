package com.dictalab.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "dictalab.listening.queue.enabled", havingValue = "true")
public class ListeningGradingRabbitConfig {
    @Bean
    DirectExchange dictalabJobsExchange(
            @org.springframework.beans.factory.annotation.Value("${dictalab.listening.queue.exchange:dictalab.jobs}") String name) {
        return new DirectExchange(name, true, false);
    }

    @Bean
    Queue listeningGradingDeadLetterQueue(
            @org.springframework.beans.factory.annotation.Value("${dictalab.listening.queue.dead-letter-queue:dictalab.listening.grading.dlq}") String name) {
        return QueueBuilder.durable(name).maxLength(1000).build();
    }

    @Bean
    Queue listeningGradingWorkQueue(
            @org.springframework.beans.factory.annotation.Value("${dictalab.listening.queue.name:dictalab.listening.grading}") String name,
            @org.springframework.beans.factory.annotation.Value("${dictalab.listening.queue.dead-letter-queue:dictalab.listening.grading.dlq}") String deadLetterQueue) {
        return QueueBuilder.durable(name)
                .maxLength(1000).overflow(QueueBuilder.Overflow.rejectPublish)
                .deadLetterExchange("")
                .deadLetterRoutingKey(deadLetterQueue)
                .build();
    }

    @Bean
    Binding listeningGradingBinding(@org.springframework.beans.factory.annotation.Qualifier("listeningGradingWorkQueue") Queue listeningGradingQueue,
            DirectExchange dictalabJobsExchange,
            @org.springframework.beans.factory.annotation.Value("${dictalab.listening.queue.name:dictalab.listening.grading}") String routingKey) {
        return BindingBuilder.bind(listeningGradingQueue).to(dictalabJobsExchange).with(routingKey);
    }
}
