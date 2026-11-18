package com.dictalab.service;

/** ShopAIKey's historical API is an OpenAI-compatible chat-completions endpoint. */
@org.springframework.stereotype.Component
public final class ShopAiKeyTextProvider extends OpenAiCompatibleTextProvider {
    @Override public String id() { return "shopaikey"; }
}
