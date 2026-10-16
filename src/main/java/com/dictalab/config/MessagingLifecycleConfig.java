package com.dictalab.config;

import org.springframework.amqp.rabbit.config.ContainerCustomizer;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods=false)
public class MessagingLifecycleConfig {
    @Bean
    ContainerCustomizer<SimpleMessageListenerContainer> workerDrain() {
        return container -> {
            container.setShutdownTimeout(70_000); // Provider calls are bounded at 60s; allow the DB commit/ACK.
            container.setForceStop(true); // Finish current delivery, do not start prefetched work during shutdown.
        };
    }
}
