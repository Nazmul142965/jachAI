package com.jachai.billing.service;

import java.net.URI;
import java.util.Locale;

/** Only a public HTTPS origin or an explicit loopback HTTP origin may receive sandbox callbacks. */
final class SandboxCallbackOrigin {
    private SandboxCallbackOrigin() { }

    static boolean valid(String raw) {
        if (raw == null || raw.isBlank()) return false;
        try {
            URI uri = URI.create(raw);
            String host = uri.getHost();
            if (host == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || uri.getPort() > 65535
                    || uri.getPort() == 0 || uri.getPort() < -1
                    || uri.getRawPath() != null && !uri.getRawPath().isEmpty()
                    && !"/".equals(uri.getRawPath())) return false;
            String scheme = uri.getScheme();
            if ("https".equalsIgnoreCase(scheme)) return true;
            if (!"http".equalsIgnoreCase(scheme)) return false;
            String normalized = host.toLowerCase(Locale.ROOT);
            return "localhost".equals(normalized) || "127.0.0.1".equals(normalized)
                    || "[::1]".equals(normalized) || "::1".equals(normalized);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    static String require(String raw) {
        if (!valid(raw)) {
            throw new com.jachai.shared.error.ApiException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "BILLING_CALLBACK_ORIGIN_INVALID",
                    "Sandbox callback origin must be HTTPS or local loopback HTTP");
        }
        return raw.replaceAll("/+$", "");
    }
}
