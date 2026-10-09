package com.jachai.shared.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jachai.shared.error.ApiError;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.config.Customizer;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain demoFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/api/v1/demo/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain apiFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        return http
                .securityMatcher("/api/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/index.html", "/app.css", "/app.js", "/public.css", "/engineering.css",
                                "/theme.css", "/theme.js",
                                "/favicon.svg", "/favicon.ico",
                                // The React Practice pilot's bundle. A static asset exactly like
                                // /app.js above: it is fetched by a dynamic import, which carries
                                // no Authorization header, and it holds no secret. The data it
                                // shows is still fetched through the authenticated API.
                                "/pilot/practice.js",
                                "/api/v1/health", "/actuator/health", "/actuator/health/**", "/verify-email.html", "/reset-password.html",
                                "/api/v1/auth/register", "/api/v1/auth/verify-email",
                                "/api/v1/auth/email-delivery-mode",
                                "/api/v1/auth/google", "/api/v1/auth/google/config",
                                "/api/v1/auth/resend-verification", "/api/v1/auth/login",
                                "/api/v1/auth/refresh", "/api/v1/auth/request-password-reset",
                                "/api/v1/auth/reset-password", "/api/v1/public/certificates/*",
                                "/certificates/*",
                                "/academy.html", "/talent.html", "/plans.html", "/how.html", "/engineering.html",
                                "/api/v1/billing/sslcommerz/ipn",
                                "/api/v1/billing/sslcommerz/callback",
                                "/api/v1/billing/bkash/callback").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()))
                .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, exception) -> {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    objectMapper.writeValue(response.getWriter(), new ApiError("UNAUTHORIZED", "Authentication required"));
                }).accessDeniedHandler((request, response, exception) -> {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    objectMapper.writeValue(response.getWriter(), new ApiError("FORBIDDEN", "Access denied"));
                }))
                .build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain staticFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/**")
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }
}
