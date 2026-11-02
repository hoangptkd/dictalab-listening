package com.dictalab.web;

import com.dictalab.config.AiPlatformProperties;
import com.dictalab.security.DictaLabPrincipal;
import com.dictalab.service.BoundedProviderBody;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Admin-only proxy. Provider storage and encryption remain inside the AI service. */
@RestController
@RequestMapping("/api/admin/gemini")
public class GeminiAdminController {
    private final AiPlatformProperties config;
    private final ObjectMapper json;
    private final String token;
    private final String transport;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3)).build();

    public GeminiAdminController(AiPlatformProperties config, ObjectMapper json,
            @Value("${DICTALAB_AI_PROVIDER_ADMIN_TOKEN:}") String token,
            @Value("${dictalab.ai.platform.text-transport:legacy}") String transport) {
        this.config = config; this.json = json; this.token = token; this.transport = transport;
    }

    @ModelAttribute
    void noCache(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }

    @GetMapping
    public JsonNode snapshot(@AuthenticationPrincipal DictaLabPrincipal actor,
            @RequestParam(defaultValue="day") String period, @RequestParam(required=false) String source,
            @RequestParam(required=false) Long before) {
        if (!period.matches("day|month") || source != null && !source.matches("[a-zA-Z0-9_-]{1,64}")
                || before != null && before < 1) throw invalid();
        String query = "?period=" + period + (source == null ? "" : "&source=" + source)
                + (before == null ? "" : "&before=" + before);
        return exchange("GET", query, null, actor);
    }

    @PutMapping("/{id}")
    public JsonNode save(@PathVariable String id, @RequestBody JsonNode body,
                         @AuthenticationPrincipal DictaLabPrincipal actor) {
        if (!id.matches("[a-zA-Z0-9_-]{1,64}") || !body.isObject()) throw invalid();
        String encoded = json.writeValueAsString(body);
        if (encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 8192) throw invalid();
        return exchange("PUT", "/" + id, encoded, actor);
    }

    @PostMapping("/{id}/probe")
    public JsonNode probe(@PathVariable String id, @AuthenticationPrincipal DictaLabPrincipal actor) {
        if (!id.matches("[a-zA-Z0-9_-]{1,64}")) throw invalid();
        return exchange("POST", "/" + id + "/probe", "{}", actor);
    }

    @PutMapping("/pool/config")
    public JsonNode pool(@RequestBody JsonNode body, @AuthenticationPrincipal DictaLabPrincipal actor) {
        if (!body.isObject() || body.toString().length() > 2048) throw invalid();
        return exchange("PUT", "/pool/config", json.writeValueAsString(body), actor);
    }

    private JsonNode exchange(String method, String suffix, String body, DictaLabPrincipal actor) {
        if (actor == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (!token.matches("[!-~]{32,256}") || !transport.equals("python"))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Quản lý Gemini cần cấu hình kết nối quản trị và luồng AI Python.");
        var request = HttpRequest.newBuilder(URI.create(config.baseUrl() + "/v1/admin/gemini" + suffix))
                .timeout(Duration.ofSeconds(20)).header("X-Provider-Admin-Token", token)
                .header("X-Admin-Actor", Long.toString(actor.id())).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
        var pending = http.sendAsync(request, ignored -> BoundedProviderBody.bytes(2_000_000));
        try {
            // The internal endpoint returns at most 90 days of aggregates and 50 audit entries.
            var response = pending.get(20, TimeUnit.SECONDS);
            if (response.body().length > 2_000_000) throw new IllegalStateException("Oversized management response");
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                HttpStatus status = switch (response.statusCode()) { case 400, 422 -> HttpStatus.BAD_REQUEST;
                    case 404 -> HttpStatus.NOT_FOUND; case 409 -> HttpStatus.CONFLICT; default -> HttpStatus.SERVICE_UNAVAILABLE; };
                String message = status == HttpStatus.CONFLICT ? "Cấu hình đã thay đổi hoặc project/model bị trùng. Tải lại trước khi lưu."
                    : status == HttpStatus.BAD_REQUEST ? "Thông tin nguồn Gemini không hợp lệ."
                    : "Dịch vụ quản lý Gemini chưa sẵn sàng. Kiểm tra cấu hình và kết nối.";
                throw new ResponseStatusException(status, message);
            }
            return json.readTree(response.body());
        } catch (ResponseStatusException known) { throw known;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Thao tác quản lý Gemini bị gián đoạn.");
        } catch (Exception unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Chưa xác nhận được kết quả. Tải lại danh sách trước khi lưu lại.");
        } finally { pending.cancel(true); }
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Thông tin nguồn Gemini không hợp lệ.");
    }
}
