package com.jachai.auth.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jachai.auth.dto.AuthResponse;
import com.jachai.auth.service.AuthService;
import com.jachai.auth.service.GoogleAuthService;
import com.jachai.shared.error.ApiException;
import com.jachai.shared.security.RateLimitService;
import com.jachai.shared.security.TrustedClientIpResolver;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The refresh token as an HttpOnly cookie: what it is set with, when it is honoured, and
 * that nothing which worked before it existed stopped working.
 */
class SessionCookieTests {
    private final SessionCookies https = new SessionCookies("https://jachai.example");
    private final SessionCookies http = new SessionCookies("http://127.0.0.1:8080");
    private AuthService auth;
    private AuthController controller;

    @BeforeEach
    void setUp() {
        auth = mock(AuthService.class);
        controller = new AuthController(auth, mock(GoogleAuthService.class), mock(RateLimitService.class),
                mock(TrustedClientIpResolver.class), https);
    }

    private static MockHttpServletRequest request(String cookie, boolean marked) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (cookie != null) request.setCookies(new Cookie(SessionCookies.NAME, cookie));
        if (marked) request.addHeader(SessionCookies.MARKER_HEADER, "1");
        return request;
    }

    // ------------------------------------------------------------------ the cookie itself

    @Test
    void cookieCannotBeReadByScriptNorSentCrossSiteNorToOtherPaths() {
        String header = https.issue("raw-token").toString();
        assertTrue(header.contains("HttpOnly"), header);
        assertTrue(header.contains("SameSite=Strict"), header);
        assertTrue(header.contains("Path=/api/v1/auth"), header);
        assertTrue(header.contains("Secure"), header);
    }

    @Test
    void cookieIsASessionCookieSoItEndsWhenTheBrowserCloses() {
        String header = https.issue("raw-token").toString();
        assertFalse(header.contains("Max-Age"), header);
        assertFalse(header.contains("Expires"), header);
    }

    @Test
    void secureFollowsThePublicOriginNotTheRequest() {
        assertTrue(https.issue("t").toString().contains("Secure"));
        assertFalse(http.issue("t").toString().contains("Secure"));
    }

    @Test
    void clearingMatchesTheAttributesItWasSetWith() {
        String header = https.clear().toString();
        assertTrue(header.contains("Max-Age=0"), header);
        assertTrue(header.contains("Path=/api/v1/auth"), header);
        assertTrue(header.contains("HttpOnly"), header);
        assertTrue(header.contains("Secure"), header);
    }

    // ------------------------------------------------------------------ refresh

    @Test
    void refreshUsesTheCookieWhenTheCallerAsksForIt() {
        controller.refresh(null, request("cookie-token", true));
        verify(auth).refresh("cookie-token");
    }

    @Test
    void theCookieBeatsAStaleTokenInTheBody() {
        // Another tab may have rotated the session since this one last held a token, and
        // a refresh token is single-use, so the body token can be the one that is dead.
        controller.refresh(new AuthController.RefreshRequest("stale-body-token"), request("fresh-cookie", true));
        verify(auth).refresh("fresh-cookie");
    }

    @Test
    void theCookieIsIgnoredWithoutTheMarkerHeader() {
        // The header is what proves the call came from this site's own script.
        controller.refresh(new AuthController.RefreshRequest("body-token"), request("cookie-token", false));
        verify(auth).refresh("body-token");
        verify(auth, never()).refresh("cookie-token");
    }

    @Test
    void aCookieAloneWithoutTheMarkerIsNotEnough() {
        ApiException error = assertThrows(ApiException.class,
                () -> controller.refresh(null, request("cookie-token", false)));
        assertEquals("INVALID_REFRESH", error.code());
        verify(auth, never()).refresh(any());
    }

    @Test
    void aBodyTokenStillWorksExactlyAsBefore() {
        controller.refresh(new AuthController.RefreshRequest("body-token"), request(null, false));
        verify(auth).refresh("body-token");
    }

    @Test
    void markerWithNoCookieFallsBackToTheBodyToken() {
        controller.refresh(new AuthController.RefreshRequest("body-token"), request(null, true));
        verify(auth).refresh("body-token");
    }

    @Test
    void nothingToRefreshWithIsAnUnauthorizedAnswerNotAServerError() {
        ApiException error = assertThrows(ApiException.class,
                () -> controller.refresh(new AuthController.RefreshRequest("  "), request(null, true)));
        assertEquals("INVALID_REFRESH", error.code());
    }

    // ------------------------------------------------------------------ logout

    private static Jwt token() {
        return Jwt.withTokenValue("access").header("alg", "none").subject("user-1").jti("jti-1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build();
    }

    @Test
    void logoutRevokesTheCookieSessionAndTellsTheBrowserToDropIt() {
        Jwt jwt = token();
        ResponseEntity<Map<String, String>> response =
                controller.logout(jwt, null, request("cookie-token", true));
        verify(auth).logout("user-1", "cookie-token", "jti-1", jwt.getExpiresAt());
        String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertTrue(setCookie != null && setCookie.contains("Max-Age=0"), String.valueOf(setCookie));
    }

    @Test
    void logoutStillRevokesTheAccessTokenWhenThereIsNoRefreshTokenAtAll() {
        Jwt jwt = token();
        ResponseEntity<Map<String, String>> response = controller.logout(jwt, null, request(null, false));
        verify(auth).logout("user-1", null, "jti-1", jwt.getExpiresAt());
        assertTrue(response.getHeaders().containsKey(HttpHeaders.SET_COOKIE));
    }

    // ------------------------------------------------------------------ every sign-in sets it

    @Test
    void everyResponseThatIssuesASessionSetsTheCookie() throws Exception {
        SessionCookieAdvice advice = new SessionCookieAdvice(https);
        ServletServerHttpResponse response = new ServletServerHttpResponse(new MockHttpServletResponse());
        Object body = new AuthResponse("access", "refresh-123", "Bearer", 900);

        Object returned = advice.beforeBodyWrite(body, null, null, null, null, response);

        assertEquals(body, returned, "the body is untouched, so clients reading it keep working");
        String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertTrue(setCookie != null && setCookie.startsWith(SessionCookies.NAME + "=refresh-123"),
                String.valueOf(setCookie));
    }

    @Test
    void otherResponsesAreLeftAlone() throws Exception {
        SessionCookieAdvice advice = new SessionCookieAdvice(https);
        ServletServerHttpResponse response = new ServletServerHttpResponse(new MockHttpServletResponse());
        advice.beforeBodyWrite(Map.of("message", "ok"), null, null, null, null, response);
        assertNull(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE));
    }

    @Test
    void theAccessTokenIsNeverPutInTheCookie() {
        assertFalse(https.issue("refresh-only").toString().contains("access"));
    }
}
