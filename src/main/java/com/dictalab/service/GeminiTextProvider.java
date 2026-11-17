package com.dictalab.service;

@org.springframework.stereotype.Component
public class GeminiTextProvider extends OpenAiCompatibleTextProvider {
    @Override public String id() { return "gemini"; }
    @Override protected boolean prefixJsonInstruction() { return false; }
    @Override protected String expectedHost() { return "generativelanguage.googleapis.com"; }
    @Override public String normalizeModel(String model) { return model.replaceFirst("^models/", ""); }
    @Override public boolean rotateOn429() { return true; }
}
