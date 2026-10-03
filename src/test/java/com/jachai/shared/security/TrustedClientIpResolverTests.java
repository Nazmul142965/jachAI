package com.jachai.shared.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.autoconfigure.web.ServerProperties.ForwardHeadersStrategy;
import org.springframework.mock.web.MockHttpServletRequest;

class TrustedClientIpResolverTests {
    private static TrustedClientIpResolver resolver(String cidrs) {
        ServerProperties server = new ServerProperties();
        server.setForwardHeadersStrategy(ForwardHeadersStrategy.NONE);
        return new TrustedClientIpResolver(cidrs, server);
    }

    private static MockHttpServletRequest request(String peer, String header) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(peer);
        if (header != null) request.addHeader("X-Forwarded-For", header);
        return request;
    }

    @Test
    void directPeerIgnoresSpoofedForwardingHeader() {
        assertEquals("203.0.113.7", resolver("10.0.0.0/24")
                .resolve(request("203.0.113.7", "198.51.100.250")));
        assertEquals("203.0.113.7", resolver("")
                .resolve(request("203.0.113.7", "198.51.100.250")));
    }

    @Test
    void trustedProxyChainSelectsFirstUntrustedHopFromRight() {
        assertEquals("203.0.113.7", resolver("10.0.0.0/24")
                .resolve(request("10.0.0.2", "203.0.113.7, 10.0.0.3")));
        assertEquals("203.0.113.7", resolver("10.0.0.0/24")
                .resolve(request("10.0.0.2", "198.51.100.250, 203.0.113.7, 10.0.0.3")));
    }

    @Test
    void malformedMissingAndDuplicateHeadersFallBackToSocketPeer() {
        TrustedClientIpResolver resolver = resolver("10.0.0.0/24");
        assertEquals("10.0.0.2", resolver.resolve(request("10.0.0.2", null)));
        assertEquals("10.0.0.2", resolver.resolve(request("10.0.0.2", "203.0.113.7, unknown")));
        assertEquals("10.0.0.2", resolver.resolve(request("10.0.0.2", "203.0.113.7,,10.0.0.3")));
        MockHttpServletRequest duplicate = request("10.0.0.2", "203.0.113.7");
        duplicate.addHeader("X-Forwarded-For", "10.0.0.3");
        assertEquals("10.0.0.2", resolver.resolve(duplicate));
    }

    @Test
    void ipv6AddressesAndCidrAreParsedWithoutHostnameResolution() {
        TrustedClientIpResolver resolver = resolver("2001:db8:0:1::/64");
        assertEquals("2001:db8:0:2:0:0:0:7", resolver.resolve(
                request("2001:db8:0:1::2", "[2001:db8:0:2::7], 2001:db8:0:1::3")));
        assertEquals("2001:db8:0:2:0:0:0:7", resolver.resolve(
                request("2001:db8:0:2::7", "198.51.100.250")));
    }

    @Test
    void invalidProxyConfigFailsAtStartup() {
        assertThrows(IllegalArgumentException.class, () -> resolver("example.com"));
        assertThrows(IllegalArgumentException.class, () -> resolver("10.0.0.0/33"));
        assertThrows(IllegalArgumentException.class, () -> resolver("10.0.0.0/24,"));
    }

    @Test
    void bootForwardingStrategyOverrideIsRejectedBeforeHeadersCanBeUsed() {
        ServerProperties server = new ServerProperties();
        server.setForwardHeadersStrategy(ForwardHeadersStrategy.FRAMEWORK);
        assertThrows(IllegalStateException.class, () -> new TrustedClientIpResolver("10.0.0.0/24", server));
    }
}
