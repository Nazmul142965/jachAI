package com.jachai.auth.controller;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * The refresh token as an HttpOnly cookie, so that reloading the page, or coming back
 * to it from another page, does not end the session.
 *
 * <p>The access token still lives only in script memory and is never written anywhere.
 * What survives a reload is the ability to ask for a new one, and that is held in a cookie
 * script cannot read, which is the property the in-memory design was protecting. A cross-site
 * script that runs on the page can still use the session while the page is open, as it always
 * could; what it cannot do is copy the credential away.
 *
 * <p>The cookie is deliberately narrow:
 * <ul>
 *   <li><b>HttpOnly</b>: not readable from script.
 *   <li><b>Path=/api/v1/auth</b>: sent only to the endpoints that use it, not to every API call.
 *   <li><b>SameSite=Strict</b>: never sent with a request another site causes.
 *   <li><b>Secure</b> whenever the public origin is https. This follows {@code app.public-base-url}
 *       rather than the request, because TLS ends at the hosting platform's proxy and the
 *       application, by design, does not trust forwarded headers about the scheme.
 *   <li><b>No Max-Age</b>: a session cookie, gone when the browser closes. Students use shared
 *       lab machines, and thirty days of staying signed in is the wrong default there.
 * </ul>
 *
 * <p>Using the cookie also requires {@value #MARKER_HEADER} on the request. A browser will not
 * add a custom header to a cross-site request without a CORS preflight this application never
 * answers, so the header is a second, independent proof that the call came from this site's
 * own script. SameSite already blocks the cookie; this makes the endpoint safe even where a
 * browser does not honour it.
 */
@Component
public class SessionCookies {
    public static final String NAME = "jachai_rt";
    public static final String PATH = "/api/v1/auth";
    public static final String MARKER_HEADER = "X-JachAI-Session";

    private final boolean secure;

    public SessionCookies(@Value("${app.public-base-url}") String publicBaseUrl) {
        this.secure = publicBaseUrl != null && publicBaseUrl.regionMatches(true, 0, "https://", 0, 8);
    }

    public ResponseCookie issue(String refreshToken) {
        return base(refreshToken).build();
    }

    /** Expires the cookie now. Attributes must match those it was set with, or the browser keeps it. */
    public ResponseCookie clear() {
        return base("").maxAge(0).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path(PATH);
    }

    public Optional<String> read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return Optional.empty();
        for (Cookie cookie : cookies) {
            if (NAME.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return Optional.of(cookie.getValue());
            }
        }
        return Optional.empty();
    }

    /** True when the caller declared, with a header only same-origin script can set, that it wants the cookie used. */
    public boolean wantsCookie(HttpServletRequest request) {
        return "1".equals(request.getHeader(MARKER_HEADER));
    }
}
