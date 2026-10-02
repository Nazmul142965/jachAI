package com.jachai.shared.config;

import com.jachai.auth.repository.UserRepository;
import com.jachai.auth.service.AccessRevocationService;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;

@Configuration
public class JwtConfig {
    @Bean
    SecretKey jwtSigningKey(@Value("${app.jwt.secret}") String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalArgumentException("JWT_SECRET must contain at least 32 UTF-8 bytes");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(jwtSigningKey));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSigningKey, UserRepository users,
                          AccessRevocationService revocations) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSigningKey)
                .macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer("jachai"), token -> {
                    Object securityVersion = token.getClaim("sv");
                    if (!"access".equals(token.getClaimAsString("purpose"))
                            || token.getSubject() == null || token.getSubject().isBlank()
                            || token.getId() == null || token.getId().isBlank()
                            || !(securityVersion instanceof Number)) {
                        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token",
                                "JWT is not a JachAI access token", null));
                    }
                    try {
                        boolean current = users.findById(token.getSubject())
                                .filter(user -> user.emailVerified() && !user.disabled()
                                        && user.securityVersion() == ((Number) securityVersion).intValue())
                                .isPresent();
                        if (!current || revocations.isRevoked(token.getId())) {
                            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token",
                                    "JWT is revoked or account is unavailable", null));
                        }
                        return OAuth2TokenValidatorResult.success();
                    } catch (RuntimeException exception) {
                        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token",
                                "Token security state is unavailable", null));
                    }
                }));
        return decoder;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
