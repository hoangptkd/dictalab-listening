package com.dictalab.dto;

import com.dictalab.domain.AppUser;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank(message = "Vui lòng nhập tên hiển thị.")
            @Size(min = 2, max = 80, message = "Tên hiển thị cần từ 2 đến 80 ký tự.")
            String displayName,

            @NotBlank(message = "Vui lòng nhập email.")
            @Email(message = "Email không hợp lệ.")
            @Size(max = 160, message = "Email quá dài.")
            String email,

            @NotBlank(message = "Vui lòng nhập mật khẩu.")
            @Size(min = 8, max = 72, message = "Mật khẩu cần từ 8 đến 72 ký tự.")
            @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$",
                    message = "Mật khẩu cần có ít nhất một chữ cái và một chữ số.")
            String password
    ) {
    }

    public record LoginRequest(
            @NotBlank(message = "Vui lòng nhập email.")
            @Email(message = "Email không hợp lệ.")
            String email,

            @NotBlank(message = "Vui lòng nhập mật khẩu.")
            String password
    ) {
    }

    public record CurrentUserResponse(
            Long id,
            String email,
            String displayName,
            String role,
            Instant createdAt,
            Instant lastLoginAt
    ) {
        public static CurrentUserResponse from(AppUser user) {
            return new CurrentUserResponse(
                    user.getId(), user.getEmail(), user.getDisplayName(), user.getRole(),
                    user.getCreatedAt(), user.getLastLoginAt()
            );
        }
    }

    public record CsrfResponse(String headerName, String token) {
    }
}
