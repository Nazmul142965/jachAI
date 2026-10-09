package com.jachai.shared.web;

import org.springframework.stereotype.Controller;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import java.net.URI;

@Controller
public class PageController {
    @Value("${app.demo.preview-url:}")
    private String demoPreviewUrl;

    @GetMapping({"/", "/index.html"})
    public String index(Model model) {
        model.addAttribute("demoPreviewUrl", validDemoPreviewUrl(demoPreviewUrl));
        return "index";
    }

    private String validDemoPreviewUrl(String value) {
        try {
            URI uri = URI.create(value);
            if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && uri.getRawUserInfo() == null && uri.getRawQuery() == null
                    && uri.getRawFragment() == null && (uri.getRawPath() == null
                    || uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()))) {
                return uri.toString().replaceAll("/$", "");
            }
        } catch (IllegalArgumentException ignored) { }
        return "";
    }

    @GetMapping("/verify-email.html")
    public String verifyEmail() {
        return "verify-email";
    }

    @GetMapping("/reset-password.html")
    public String resetPassword() {
        return "reset-password";
    }

    @GetMapping("/certificates/{verificationCode}")
    public String certificate() {
        return "certificate";
    }

    @GetMapping("/academy.html")
    public String academy() {
        return "academy";
    }

    @GetMapping("/talent.html")
    public String talent() {
        return "talent";
    }

    @GetMapping("/plans.html")
    public String plans() {
        return "plans";
    }

    @GetMapping("/how.html")
    public String how() {
        return "how";
    }

    @GetMapping("/engineering.html")
    public String engineering() {
        return "engineering";
    }
}
