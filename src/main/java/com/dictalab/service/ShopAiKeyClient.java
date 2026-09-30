package com.dictalab.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
public class ShopAiKeyClient {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json;
    private final String key;
    private final String model;
    private final String url;

    public ShopAiKeyClient(ObjectMapper json,
                           @Value("${dictalab.shopaikey.api-key:}") String key,
                           @Value("${dictalab.shopaikey.model:claude-sonnet-4-5}") String model,
                           @Value("${dictalab.shopaikey.url:https://api.shopaikey.com/v1/chat/completions}") String url) {
        this.json = json;
        this.key = key;
        this.model = model;
        this.url = url;
    }

    public boolean configured() {
        return !key.isBlank();
    }

    public String model() { return model; }

    public JsonNode completeJson(String instruction, String input) {
        if (!configured()) throw new IllegalStateException("ShopAIKey chưa được cấu hình trên máy chủ.");
        try {
            String body = json.writeValueAsString(Map.of(
                    "model", model, "temperature", 0, "max_tokens", 2200,
                    "messages", List.of(Map.of("role", "system", "content", instruction),
                            Map.of("role", "user", "content", input))));
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + key)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("ShopAIKey trả HTTP " + response.statusCode());
            }
            JsonNode answer = json.readTree(response.body()).path("choices").path(0).path("message").path("content");
            if (!answer.isTextual()) throw new IllegalStateException("ShopAIKey không trả nội dung hợp lệ.");
            String content = answer.asText().trim();
            if (content.startsWith("```")) {
                int firstLine = content.indexOf('\n');
                int closingFence = content.lastIndexOf("```");
                if (firstLine >= 0 && closingFence > firstLine) {
                    content = content.substring(firstLine + 1, closingFence).trim();
                }
            }
            int objectStart = content.indexOf('{');
            int objectEnd = content.lastIndexOf('}');
            if (objectStart >= 0 && objectEnd >= objectStart) {
                content = content.substring(objectStart, objectEnd + 1);
            }
            return json.readTree(content);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Yêu cầu ShopAIKey bị gián đoạn.", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Không thể đọc phản hồi ShopAIKey.", exception);
        }
    }
}
