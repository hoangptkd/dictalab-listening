package com.dictalab.service;

import com.dictalab.config.ConversationRolloutProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConversationRolloutTest {
    @Test void canaryIsAllowlistedAndDefaultDisabled() {
        var python = mock(ConversationPythonClient.class);
        var metrics = new SimpleMeterRegistry();
        var disabled = new ConversationRollout(new ConversationRolloutProperties(false, null, null, 0, 0, 0), python, metrics);
        assertFalse(disabled.usePython(9, "s"));
        assertFalse(disabled.shadow("A1", "{}"));
        var enabled = new ConversationRollout(new ConversationRolloutProperties(true, Set.of(9L), Set.of("session"), 0, 0, 0), python, new SimpleMeterRegistry());
        assertTrue(enabled.usePython(9, "other"));
        assertTrue(enabled.usePython(10, "session"));
        assertFalse(enabled.usePython(10, "other"));
        when(python.enabled()).thenReturn(true);
        assertTrue(disabled.usePython(10, "other"));
        assertThrows(IllegalArgumentException.class, () -> new ConversationRolloutProperties(true, null, null, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ConversationRolloutProperties(false, null, null, Double.NaN, 0, 0));
        disabled.close(); enabled.close();
    }

    @Test void shadowIsUnqueuedAndDoesNotReturnOrPersistItsResponse() throws Exception {
        var python = mock(ConversationPythonClient.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            throw new IllegalStateException("failed");
        })
                .when(python).completeShadowTask(anyString(), anyString(), any());
        var rollout = new ConversationRollout(new ConversationRolloutProperties(false, null, null, 1, 1, 5),
                python, new SimpleMeterRegistry());
        try {
            assertTrue(rollout.shadow("A1", "{}"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertFalse(rollout.shadow("A1", "{}"));
            verify(python, times(1)).completeShadowTask(anyString(), anyString(), any());
        } finally {
            release.countDown();
            rollout.close();
        }
    }
}
