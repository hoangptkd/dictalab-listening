package com.dictalab.web;

import com.dictalab.domain.AppUser;
import com.dictalab.dto.AuthDtos;
import com.dictalab.security.DictaLabPrincipal;
import com.dictalab.service.AccountManagementService;
import com.dictalab.service.AdminQueryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.dictalab.security.JwtTokenService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class AccountManagementController {
    private final AccountManagementService service;
    private final AdminQueryService queries;
    private final JwtTokenService tokens;
    public AccountManagementController(AccountManagementService service, AdminQueryService queries, JwtTokenService tokens) {
        this.service = service; this.queries=queries; this.tokens=tokens;
    }

    public record Name(@NotBlank @Size(min = 2, max = 80) String displayName) {}
    public record Password(@NotBlank String currentPassword,
                           @NotBlank @Size(min = 8, max = 72)
                           @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$") String newPassword) {}
    public record EmailRequest(@NotBlank @Email @Size(max = 160) String email) {}
    public record EmailChange(@NotBlank @Email @Size(max = 160) String email, @NotBlank String password) {}
    public record TokenRequest(@NotBlank @Size(max = 128) String token) {}
    public record Reset(@NotBlank @Size(max = 128) String token, @NotBlank @Size(min = 8, max = 72)
                        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$") String password) {}
    public record AccountView(long id, String email, String displayName, String role,
                              boolean enabled, boolean emailVerified, java.time.Instant createdAt,
                              java.time.Instant lastLoginAt) {
        static AccountView from(AppUser user) {
            return new AccountView(user.getId(), user.getEmail(), user.getDisplayName(), user.getRole(),
                    user.isEnabled(), user.isEmailVerified(), user.getCreatedAt(), user.getLastLoginAt());
        }
    }
    public record UsersPage(List<AccountView> users, int page, long total) {}
    public record State(boolean enabled, @NotBlank @Size(max = 500) String reason) {}
    public record Role(@NotBlank @Pattern(regexp = "USER|ADMIN") String role) {}
    public record Revoke(@NotBlank @Size(max=500) String reason) {}
    @GetMapping("/api/admin/usage")
    public AdminQueryService.Usage usage(@RequestParam(defaultValue="30") int days) {
        return queries.usage(days);
    }

    @GetMapping(value="/api/admin/usage/export", produces="text/csv;charset=UTF-8")
    public org.springframework.http.ResponseEntity<String> exportUsage(@RequestParam(defaultValue="30") int days) {
        var data = queries.usage(days);
        String csv = "\uFEFFChỉ số,Giá trị,Từ thời điểm,Đến thời điểm\r\n"
                + "Tài khoản hiện có," + data.registeredUsers() + ",," + data.to() + "\r\n"
                + "Tài khoản đăng ký mới," + data.newUsers() + "," + data.from() + "," + data.to() + "\r\n"
                + "Buổi chép chính tả cá nhân hoàn thành," + data.completedDictationSessions() + "," + data.from() + "," + data.to() + "\r\n"
                + "Phút chép chính tả cá nhân," + data.dictationMinutes() + "," + data.from() + "," + data.to() + "\r\n"
                + "Buổi nghe hiểu hoàn thành," + data.completedListeningSessions() + "," + data.from() + "," + data.to() + "\r\n"
                + "Phiên hội thoại được tạo," + data.conversationSessions() + "," + data.from() + "," + data.to() + "\r\n";
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"dictalab-usage-" + days + "-days.csv\"")
                .header("Cache-Control", "no-store").body(csv);
    }

    @GetMapping("/api/admin/users/page")
    public AdminQueryService.Page page(@RequestParam(defaultValue="") String q, @RequestParam(defaultValue="") String role,
            @RequestParam(required=false) Boolean enabled, @RequestParam(required=false) Boolean verified,
            @RequestParam(defaultValue="created") String sort, @RequestParam(defaultValue="0") int page) {
        return queries.users(q,role,enabled,verified,sort,page);
    }
    @GetMapping("/api/admin/users/{id}")
    public AccountView detail(@PathVariable long id) { return AccountView.from(service.adminView(id)); }
    @PostMapping("/api/admin/users/{id}/revoke-sessions")
    public void revoke(@AuthenticationPrincipal DictaLabPrincipal admin, @PathVariable long id, @Valid @RequestBody Revoke body) {
        service.adminRevokeSessions(admin.id(),id,body.reason());
    }

    @PutMapping("/api/account/profile")
    public AuthDtos.CurrentUserResponse profile(@AuthenticationPrincipal DictaLabPrincipal user,
                                                @Valid @RequestBody Name body) {
        return AuthDtos.CurrentUserResponse.from(service.rename(user.id(), body.displayName()));
    }

    @PostMapping("/api/account/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void password(@AuthenticationPrincipal DictaLabPrincipal user, @Valid @RequestBody Password body,
                         HttpServletResponse response) {
        service.changePassword(user.id(), body.currentPassword(), body.newPassword());
        tokens.clearCookies(response);
    }

    @PostMapping("/api/account/logout-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logoutAll(@AuthenticationPrincipal DictaLabPrincipal user, HttpServletResponse response) {
        service.revokeSessions(user.id());
        tokens.clearCookies(response);
    }

    @PostMapping("/api/account/email/verification")
    public Map<String, String> verification(@AuthenticationPrincipal DictaLabPrincipal user, HttpServletRequest request) {
        service.requestVerification(user.id(), request.getRemoteAddr());
        return Map.of("message", "Nếu email chưa xác minh, liên kết đã được gửi.");
    }

    @PostMapping("/api/account/email/change")
    public Map<String, String> emailChange(@AuthenticationPrincipal DictaLabPrincipal user,
                                           @Valid @RequestBody EmailChange body, HttpServletRequest request) {
        service.requestEmailChange(user.id(), body.email(), body.password(), request.getRemoteAddr());
        return Map.of("message", "Đã gửi liên kết xác nhận đến email mới.");
    }

    @PostMapping("/api/auth/email/confirm")
    public Map<String, String> confirm(@Valid @RequestBody TokenRequest body) {
        service.consumeToken(body.token(), "VERIFY_EMAIL", null);
        return Map.of("message", "Email đã được xác minh.");
    }

    @PostMapping("/api/auth/email/change/confirm")
    public Map<String, String> confirmChange(@Valid @RequestBody TokenRequest body) {
        service.consumeToken(body.token(), "CHANGE_EMAIL", null);
        return Map.of("message", "Email đã thay đổi. Vui lòng đăng nhập lại.");
    }

    @PostMapping("/api/auth/password-reset/request")
    public Map<String, String> requestReset(@Valid @RequestBody EmailRequest body, HttpServletRequest request) {
        service.requestReset(body.email(), request.getRemoteAddr());
        return Map.of("message", "Nếu email đã đăng ký, chúng tôi sẽ gửi liên kết đặt lại mật khẩu.");
    }

    @PostMapping("/api/auth/password-reset/complete")
    public Map<String, String> reset(@Valid @RequestBody Reset body) {
        service.consumeToken(body.token(), "RESET_PASSWORD", body.password());
        return Map.of("message", "Mật khẩu đã đổi. Vui lòng đăng nhập lại.");
    }

    @GetMapping("/api/admin/users")
    public UsersPage users(@RequestParam(defaultValue = "") String q,
                           @RequestParam(required = false) String role,
                           @RequestParam(required = false) Boolean enabled,
                           @RequestParam(defaultValue = "0") int page) {
        var result = service.search(q, role, enabled, page);
        return new UsersPage(result.getContent().stream().map(AccountView::from).toList(), page, result.getTotalElements());
    }

    @PostMapping("/api/admin/users/{id}/password-reset")
    public Map<String, String> adminReset(@AuthenticationPrincipal DictaLabPrincipal admin, @PathVariable long id,
                                           HttpServletRequest request) {
        service.adminSendReset(admin.id(), id, request.getRemoteAddr());
        return Map.of("message", "Đã gửi liên kết đặt lại mật khẩu.");
    }

    @GetMapping("/api/admin/users/{id}/audit")
    public List<Map<String, Object>> audit(@PathVariable long id) { return service.auditFor(id); }

    @PutMapping("/api/admin/users/{id}/state")
    public AccountView state(@AuthenticationPrincipal DictaLabPrincipal admin, @PathVariable long id,
                             @Valid @RequestBody State body) {
        return AccountView.from(service.setEnabled(admin.id(), id, body.enabled(), body.reason()));
    }

    @PutMapping("/api/admin/users/{id}/role")
    public AccountView role(@AuthenticationPrincipal DictaLabPrincipal admin, @PathVariable long id,
                            @Valid @RequestBody Role body) {
        return AccountView.from(service.setRole(admin.id(), id, body.role()));
    }
}
