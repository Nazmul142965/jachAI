package com.jachai.auth.controller;

import com.jachai.auth.dto.AuthResponse;
import com.jachai.auth.service.DemoAccountService;
import com.jachai.shared.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Profile("demo & !prod")
@RequestMapping("/api/v1/demo")
public class DemoAccessController {
    private final DemoAccountService accounts;

    @GetMapping("/accounts")
    public List<DemoAccountService.DemoAccount> accounts(HttpServletRequest request) {
        requireLocal(request);
        return accounts.accounts();
    }

    @PostMapping("/login/{key}")
    public AuthResponse login(@PathVariable String key, HttpServletRequest request) {
        requireLocal(request);
        return accounts.login(key);
    }

    private static void requireLocal(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        String host = request.getServerName();
        if (!("127.0.0.1".equals(remote) || "0:0:0:0:0:0:0:1".equals(remote) || "::1".equals(remote))
                || !("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DEMO_LOCAL_ONLY", "Demo login is available on localhost only");
        }
    }
}
