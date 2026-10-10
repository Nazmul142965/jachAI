package com.jachai.shared.security;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.jachai.academy.controller.PublicCertificateController;
import com.jachai.academy.service.CertificateService;
import com.jachai.auth.controller.AuthController;
import com.jachai.auth.controller.SessionCookies;
import com.jachai.auth.dto.LoginRequest;
import com.jachai.auth.service.AuthService;
import com.jachai.auth.service.GoogleAuthService;
import com.jachai.shared.error.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.autoconfigure.web.ServerProperties.ForwardHeadersStrategy;
import org.springframework.mock.web.MockHttpServletRequest;

@ExtendWith(MockitoExtension.class)
class RateLimitedControllerTests {
    @Mock AuthService auth;
    @Mock GoogleAuthService google;
    @Mock CertificateService certificates;
    @Mock RateLimitService limits;
    private final SessionCookies cookies = new SessionCookies("https://example.com");

    private TrustedClientIpResolver resolver(String trusted) {
        ServerProperties server = new ServerProperties();
        server.setForwardHeadersStrategy(ForwardHeadersStrategy.NONE);
        return new TrustedClientIpResolver(trusted, server);
    }

    @Test
    void loginLimitRunsBeforePasswordCheck() {
        MockHttpServletRequest servlet = new MockHttpServletRequest();
        servlet.setRemoteAddr("192.0.2.1");
        doThrow(new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many requests"))
                .when(limits).consume(RateLimitPolicy.LOGIN_IP, "192.0.2.1");

        assertThrows(ApiException.class, () -> new AuthController(auth, google, limits, resolver(""), cookies)
                .login(new LoginRequest("person@example.com", "irrelevant-password"), servlet));

        verify(auth, never()).login(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void googleLoginLimitRunsBeforeTokenVerification() {
        MockHttpServletRequest servlet = new MockHttpServletRequest();
        servlet.setRemoteAddr("192.0.2.3");
        doThrow(new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many requests"))
                .when(limits).consume(RateLimitPolicy.LOGIN_IP, "192.0.2.3");

        assertThrows(ApiException.class, () -> new AuthController(auth, google, limits, resolver(""), cookies)
                .googleLogin(new AuthController.GoogleCredentialRequest("credential"), servlet));

        verify(google, never()).login(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void publicCertificateLimitRunsBeforeDatabaseLookup() {
        MockHttpServletRequest servlet = new MockHttpServletRequest();
        servlet.setRemoteAddr("192.0.2.2");
        doThrow(new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many requests"))
                .when(limits).consume(RateLimitPolicy.CERTIFICATE_IP, "192.0.2.2");

        assertThrows(ApiException.class, () -> new PublicCertificateController(certificates, limits, resolver(""))
                .verify("verification-code", servlet));

        verify(certificates, never()).verify("verification-code");
    }

    @Test
    void authLoginUsesResolvedFirstUntrustedHopNotSpoofedPrefix() {
        MockHttpServletRequest servlet = new MockHttpServletRequest();
        servlet.setRemoteAddr("10.0.0.2");
        servlet.addHeader("X-Forwarded-For", "198.51.100.250, 203.0.113.7, 10.0.0.3");

        new AuthController(auth, google, limits, resolver("10.0.0.0/24"), cookies)
                .login(new LoginRequest("person@example.com", "password"), servlet);

        verify(limits).consume(RateLimitPolicy.LOGIN_IP, "203.0.113.7");
    }

    @Test
    void authLoginIgnoresForwardedHeaderFromUntrustedSocketPeer() {
        MockHttpServletRequest servlet = new MockHttpServletRequest();
        servlet.setRemoteAddr("203.0.113.7");
        servlet.addHeader("X-Forwarded-For", "198.51.100.250");

        new AuthController(auth, google, limits, resolver("10.0.0.0/24"), cookies)
                .login(new LoginRequest("person@example.com", "password"), servlet);

        verify(limits).consume(RateLimitPolicy.LOGIN_IP, "203.0.113.7");
    }

    @Test
    void publicCertificateUsesResolvedClientAndNotForgedForwardedPrefix() {
        MockHttpServletRequest servlet = new MockHttpServletRequest();
        servlet.setRemoteAddr("10.0.0.2");
        servlet.addHeader("X-Forwarded-For", "198.51.100.250, 203.0.113.7, 10.0.0.3");

        new PublicCertificateController(certificates, limits, resolver("10.0.0.0/24"))
                .verify("verification-code", servlet);

        verify(limits).consume(RateLimitPolicy.CERTIFICATE_IP, "203.0.113.7");
    }

    @Test
    void publicCertificateFallsBackToProxyPeerOnMalformedChain() {
        MockHttpServletRequest servlet = new MockHttpServletRequest();
        servlet.setRemoteAddr("10.0.0.2");
        servlet.addHeader("X-Forwarded-For", "198.51.100.250, not-an-ip");

        new PublicCertificateController(certificates, limits, resolver("10.0.0.0/24"))
                .verify("verification-code", servlet);

        verify(limits).consume(RateLimitPolicy.CERTIFICATE_IP, "10.0.0.2");
    }
}
