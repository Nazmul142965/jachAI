package com.jachai.auth.controller;

import com.jachai.auth.dto.AuthResponse;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Sets the session cookie on every response that issues a session.
 *
 * <p>Sign-in, Google sign-in, the demo sign-in and refresh all return an {@link AuthResponse}.
 * Doing this here, in one place, means a new way of signing in cannot forget the cookie, and
 * the body is unchanged, so clients that read the tokens from it keep working exactly as before.
 */
@RestControllerAdvice
class SessionCookieAdvice implements ResponseBodyAdvice<Object> {
    private final SessionCookies cookies;

    SessionCookieAdvice(SessionCookies cookies) {
        this.cookies = cookies;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return AuthResponse.class.equals(returnType.getParameterType());
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body instanceof AuthResponse session && session.refreshToken() != null) {
            response.getHeaders().add(HttpHeaders.SET_COOKIE, cookies.issue(session.refreshToken()).toString());
        }
        return body;
    }
}
