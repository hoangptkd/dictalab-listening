package com.dictalab.web;

import com.dictalab.domain.AppUser;
import com.dictalab.dto.AuthDtos;
import com.dictalab.security.DictaLabPrincipal;
import com.dictalab.service.AccountManagementService;
import jakarta.servlet.http.HttpServletRequest;
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
    public AccountManagementController(AccountManagementService service) { this.service = service; }

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

    @PutMapping("/api/account/profile")
    public AuthDtos.CurrentUserResponse profile(@AuthenticationPrincipal DictaLabPrincipal user,
                                                @Valid @RequestBody Name body) {
        return AuthDtos.CurrentUserResponse.from(service.rename(user.id(), body.displayName()));
    }

    @PostMapping("/api/account/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void password(@AuthenticationPrincipal DictaLabPrincipal user, @Valid @RequestBody Password body,
                         HttpServletRequest request) {
        service.changePassword(user.id(), body.currentPassword(), body.newPassword());
        request.getSession(false).invalidate();
    }

    @PostMapping("/api/account/logout-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logoutAll(@AuthenticationPrincipal DictaLabPrincipal user, HttpServletRequest request) {
        service.revokeSessions(user.id());
        request.getSession(false).invalidate();
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
