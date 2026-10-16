package com.dictalab.config;

import org.springframework.amqp.core.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "dictalab.ai.queue.enabled", havingValue = "true")
public class AiRabbitConfig {
    @Bean
    @ConditionalOnProperty(name = "dictalab.listening.queue.enabled", havingValue = "false", matchIfMissing = true)
    org.springframework.boot.amqp.health.RabbitHealthIndicator aiRabbitHealthIndicator(
            org.springframework.amqp.rabbit.core.RabbitTemplate rabbit) {
        return new org.springframework.boot.amqp.health.RabbitHealthIndicator(rabbit);
    }

    @Bean
    Declarables aiJobQueues(AiQueueProperties config) {
        var exchange = new DirectExchange(config.exchange(), true, false);
        var audio = workQueue(config.audioQueue());
        var coach = workQueue(config.coachQueue());
        return new Declarables(exchange, audio, coach,
                QueueBuilder.durable(config.audioQueue() + ".dlq").maxLength(1000).build(),
                QueueBuilder.durable(config.coachQueue() + ".dlq").maxLength(1000).build(),
                BindingBuilder.bind(audio).to(exchange).with(config.audioQueue()),
                BindingBuilder.bind(coach).to(exchange).with(config.coachQueue()));
    }

    private Queue workQueue(String name) {
        return QueueBuilder.durable(name).maxLength(1000).overflow(QueueBuilder.Overflow.rejectPublish)
                .deadLetterExchange("").deadLetterRoutingKey(name + ".dlq").build();
    }
}
