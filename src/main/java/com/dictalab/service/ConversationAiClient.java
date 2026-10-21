package com.dictalab.service;

import java.time.Duration;

/** Provider-neutral boundary for one conversation generation request. */
interface ConversationAiClient {
    boolean enabled();

    int flowTimeoutSeconds();

    ConversationLocalClient.GeneratedTask completeTask(String level, String input, Duration remaining);
}
