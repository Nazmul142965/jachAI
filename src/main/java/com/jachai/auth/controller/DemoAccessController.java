package com.jachai.auth.controller;

import com.jachai.auth.dto.AuthResponse;
import com.jachai.auth.service.DemoAccountService;
import com.jachai.shared.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("demo & !prod")
@RequestMapping("/api/v1/demo")
public class DemoAccessController {
    private final DemoAccountService accounts;
    private final boolean publicPreview;
    private final String publicBaseUrl;

    public DemoAccessController(DemoAccountService accounts,
                                @Value("${app.demo.public-access:false}") boolean publicPreview,
                                @Value("${app.public-base-url}") String publicBaseUrl) {
        this.accounts = accounts;
        this.publicPreview = publicPreview;
        this.publicBaseUrl = publicBaseUrl;
    }

    @GetMapping("/accounts")
    public List<DemoAccountService.DemoAccount> accounts(HttpServletRequest request) {
        requireAllowed(request);
        return accounts.accounts();
    }

    @PostMapping("/login/{key}")
    public AuthResponse login(@PathVariable String key, HttpServletRequest request) {
        requireAllowed(request);
        return accounts.login(key);
    }

    private void requireAllowed(HttpServletRequest request) {
        if (publicPreview) {
            try {
                URI origin = URI.create(publicBaseUrl);
                if ("https".equalsIgnoreCase(origin.getScheme()) && origin.getHost() != null
                        && origin.getRawUserInfo() == null && origin.getRawQuery() == null
                        && origin.getRawFragment() == null
                        && (origin.getRawPath() == null || origin.getRawPath().isEmpty()
                        || "/".equals(origin.getRawPath()))) return;
            } catch (IllegalArgumentException ignored) { /* Invalid preview origin is denied below. */ }
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DEMO_HTTPS_REQUIRED",
                    "Public demo access requires a public HTTPS origin");
        }
        String remote = request.getRemoteAddr();
        String host = request.getServerName();
        if (!("127.0.0.1".equals(remote) || "0:0:0:0:0:0:0:1".equals(remote) || "::1".equals(remote))
                || !("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DEMO_LOCAL_ONLY", "Demo login is available on localhost only");
        }
    }
}
