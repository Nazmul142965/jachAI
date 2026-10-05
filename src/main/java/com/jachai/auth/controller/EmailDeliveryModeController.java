package com.jachai.auth.controller;

import com.jachai.mail.service.EmailTransport;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
public class EmailDeliveryModeController {
    private final EmailTransport transport;

    public record DeliveryModeView(EmailTransport.Mode mode) { }

    @GetMapping("/email-delivery-mode")
    public DeliveryModeView mode() {
        // Reports only the configured transport. SMTP mode does not prove delivery.
        return new DeliveryModeView(transport.mode());
    }
}
