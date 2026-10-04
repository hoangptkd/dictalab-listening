package com.dictalab.web;

import com.dictalab.dto.AuthDtos;
import com.dictalab.security.DictaLabPrincipal;
import com.dictalab.service.AccountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.dictalab.security.JwtTokenService;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AccountService accountService;
    private final JwtTokenService tokens;

    public AuthController(AccountService accountService, JwtTokenService tokens) {
        this.accountService = accountService;
        this.tokens = tokens;
    }

    @GetMapping("/csrf")
    public AuthDtos.CsrfResponse csrf(CsrfToken csrfToken) {
        return new AuthDtos.CsrfResponse(csrfToken.getHeaderName(), csrfToken.getToken());
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthDtos.CurrentUserResponse register(@Valid @RequestBody AuthDtos.RegisterRequest request,
                                                  HttpServletRequest servletRequest,
                                                  HttpServletResponse servletResponse) {
        AccountService.AuthenticatedAccount account = accountService.register(request);
        tokens.issue(account.user(), servletRequest, servletResponse);
        return AuthDtos.CurrentUserResponse.from(account.user());
    }

    @PostMapping("/login")
    public AuthDtos.CurrentUserResponse login(@Valid @RequestBody AuthDtos.LoginRequest request,
                                               HttpServletRequest servletRequest,
                                               HttpServletResponse servletResponse) {
        AccountService.AuthenticatedAccount account = accountService.login(request);
        tokens.issue(account.user(), servletRequest, servletResponse);
        return AuthDtos.CurrentUserResponse.from(account.user());
    }

    @GetMapping("/me")
    public AuthDtos.CurrentUserResponse me(@AuthenticationPrincipal DictaLabPrincipal principal) {
        return AuthDtos.CurrentUserResponse.from(accountService.requireUser(principal.id()));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        tokens.logout(request, response);
    }

    @PostMapping("/refresh")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void refresh(HttpServletRequest request, HttpServletResponse response) {
        tokens.refresh(request, response);
    }
}
