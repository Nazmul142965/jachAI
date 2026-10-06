package com.jachai.billing.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SandboxCallbackOriginTests {
    @Test
    void acceptsLaunchersLoopbackAddressForBrowserSandboxCallbacks() {
        assertEquals("http://127.0.0.1:8080",
                SandboxCallbackOrigin.require("http://127.0.0.1:8080/"));
        assertTrue(SandboxCallbackOrigin.valid("http://localhost:8080"));
        assertTrue(SandboxCallbackOrigin.valid("http://[::1]:8093"));
        assertTrue(SandboxCallbackOrigin.valid("https://pay-demo.example.org"));
    }

    @Test
    void refusesNonLoopbackHttpAndOriginsWithEmbeddedData() {
        assertFalse(SandboxCallbackOrigin.valid("http://example.org"));
        assertFalse(SandboxCallbackOrigin.valid("http://127.0.0.1.evil.example"));
        assertFalse(SandboxCallbackOrigin.valid("https://user:secret@example.org"));
        assertFalse(SandboxCallbackOrigin.valid("https://example.org/callback"));
        assertFalse(SandboxCallbackOrigin.valid("https://example.org?token=secret"));
        assertFalse(SandboxCallbackOrigin.valid("javascript:alert(1)"));
    }

    @Test
    void providerAvailabilityIncludesCallbackOrigin() {
        assertTrue(new SslCommerzSandboxGateway("store", "secret", "http://127.0.0.1:8080").configured());
        assertTrue(new BkashSandboxGateway("key", "secret", "user", "pass",
                "http://127.0.0.1:8080").configured());
        assertFalse(new SslCommerzSandboxGateway("store", "secret", "http://example.org").configured());
        assertFalse(new BkashSandboxGateway("key", "secret", "user", "pass",
                "http://example.org").configured());
    }
}
