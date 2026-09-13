package com.dictalab.web;

import com.dictalab.dto.AuthDtos;
import com.dictalab.security.DictaLabPrincipal;
import com.dictalab.service.AccountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.context.SecurityContextRepository;
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
    private final SecurityContextRepository contextRepository;

    public AuthController(AccountService accountService, SecurityContextRepository contextRepository) {
        this.accountService = accountService;
        this.contextRepository = contextRepository;
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
        saveAuthentication(account.authentication(), servletRequest, servletResponse);
        return AuthDtos.CurrentUserResponse.from(account.user());
    }

    @PostMapping("/login")
    public AuthDtos.CurrentUserResponse login(@Valid @RequestBody AuthDtos.LoginRequest request,
                                               HttpServletRequest servletRequest,
                                               HttpServletResponse servletResponse) {
        AccountService.AuthenticatedAccount account = accountService.login(request);
        saveAuthentication(account.authentication(), servletRequest, servletResponse);
        return AuthDtos.CurrentUserResponse.from(account.user());
    }

    @GetMapping("/me")
    public AuthDtos.CurrentUserResponse me(@AuthenticationPrincipal DictaLabPrincipal principal) {
        return AuthDtos.CurrentUserResponse.from(accountService.requireUser(principal.id()));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request) {
        SecurityContextHolder.clearContext();
        if (request.getSession(false) != null) {
            request.getSession(false).invalidate();
        }
    }

    private void saveAuthentication(Authentication authentication,
                                    HttpServletRequest request,
                                    HttpServletResponse response) {
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
    }
}
