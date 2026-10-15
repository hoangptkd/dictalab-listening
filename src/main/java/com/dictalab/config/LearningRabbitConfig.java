package com.dictalab.config;

import org.springframework.amqp.core.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "dictalab.learning.queue-enabled", havingValue = "true")
public class LearningRabbitConfig {
    public static final String QUEUE = "dictalab.learning.evidence";

    @Bean
    Declarables learningQueues() {
        return new Declarables(QueueBuilder.durable(QUEUE).maxLength(1000)
                .overflow(QueueBuilder.Overflow.rejectPublish).deadLetterExchange("")
                .deadLetterRoutingKey(QUEUE + ".dlq").build(),
                QueueBuilder.durable(QUEUE + ".dlq").maxLength(1000).build());
    }
}
