package com.dictalab.config;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import static org.mockito.BDDMockito.*;

class MessagingLifecycleConfigTest {
    @Test void listenersDrainCurrentDeliveryWithinShutdownPhase() {
        var container=mock(SimpleMessageListenerContainer.class);
        new MessagingLifecycleConfig().workerDrain().configure(container);
        then(container).should().setShutdownTimeout(70_000);
        then(container).should().setForceStop(true);
    }
}
