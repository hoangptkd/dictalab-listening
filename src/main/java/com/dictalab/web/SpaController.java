package com.dictalab.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Forward các đường dẫn SPA về index.html để History API hoạt động.
 *
 * Chỉ liệt kê rõ ràng từng path — KHÔNG dùng wildcard /** để tránh nuốt
 * /api/**, /ws/**, /audio/**, /css/**, /js/** và các tài nguyên không tồn tại.
 *
 * Bảo mật API vẫn do SecurityConfig kiểm soát ở tầng riêng.
 */
@Controller
public class SpaController {

    private static final String INDEX = "forward:/index.html";
    private static final String ADMIN = "forward:/admin.html";
    @GetMapping("/payments")
    public String payments() { return "forward:/payments.html"; }

    @GetMapping({"/privacy","/terms","/help"})
    public String policies() { return "forward:/policies.html"; }

    /* ── Auth screens ─────────────────────────────────────────────────── */

    @GetMapping("/login")
    public String login() {
        return INDEX;
    }

    @GetMapping("/register")
    public String register() {
        return INDEX;
    }

    @GetMapping({"/reset-password", "/verify-email", "/account", "/support"})
    public String accountAndSupport() { return INDEX; }

    /* ── Catalog ──────────────────────────────────────────────────────── */

    @GetMapping("/catalog")
    public String catalog() {
        return INDEX;
    }

    @GetMapping({"/listening", "/listening/{sessionId}"})
    public String listening() {
        return INDEX;
    }

    @GetMapping({"/conversation", "/conversation/{sessionId}"})
    public String conversation() { return INDEX; }

    /* ── Practice ─────────────────────────────────────────────────────── */

    @GetMapping("/practice/{sessionId}")
    public String practice(@PathVariable String sessionId) {
        return INDEX;
    }

    /* ── History ──────────────────────────────────────────────────────── */

    @GetMapping("/history")
    public String historyList() {
        return INDEX;
    }

    @GetMapping("/history/{sessionId}")
    public String historyDetail(@PathVariable String sessionId) {
        return INDEX;
    }

    /* ── Dashboard ────────────────────────────────────────────────────── */

    @GetMapping("/dashboard")
    public String dashboard() {
        return INDEX;
    }

    /* ── Rooms ────────────────────────────────────────────────────────── */

    @GetMapping("/rooms")
    public String roomsLobby() {
        return INDEX;
    }

    @GetMapping("/rooms/{roomId}")
    public String roomDetail(@PathVariable String roomId) {
        return INDEX;
    }

    @GetMapping("/rooms/join/{inviteCode}")
    public String roomJoin(@PathVariable String inviteCode) {
        return INDEX;
    }

    /* ── Admin ────────────────────────────────────────────────────────── */

    @GetMapping({"/admin", "/admin/"})
    public String adminHome() {
        return "redirect:/admin/lessons";
    }

    @GetMapping({"/admin/users", "/admin/support", "/admin/listening"})
    public String adminAccounts() {
        return ADMIN;
    }

    @GetMapping("/admin/lessons")
    public String adminLessons() {
        return ADMIN;
    }

    @GetMapping("/admin/lessons/new")
    public String adminNewLesson() {
        return ADMIN;
    }

    @GetMapping("/admin/lessons/{lessonId}")
    public String adminLessonDetail(@PathVariable Long lessonId) {
        return ADMIN;
    }
}
