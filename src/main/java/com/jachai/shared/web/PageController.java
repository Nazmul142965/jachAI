package com.jachai.shared.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {
    @GetMapping({"/", "/index.html"})
    public String index() {
        return "index";
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
}
