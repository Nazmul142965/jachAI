package com.jachai.mail.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jachai.mail.model.EmailPayload;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class EmailPayloadCipher {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKeySpec key;
    private final ObjectMapper mapper;

    public EmailPayloadCipher(@Value("${app.mail.job-encryption-key}") String secret, ObjectMapper mapper) {
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("MAIL_JOB_ENCRYPTION_KEY must contain at least 32 UTF-8 bytes");
        }
        this.mapper = mapper;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            this.key = new SecretKeySpec(mac.doFinal("jachai-email-outbox-v1".getBytes(StandardCharsets.UTF_8)), "AES");
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Email encryption is unavailable", exception);
        }
    }

    public Sealed seal(String jobId, EmailPayload payload) {
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, jobId, nonce);
            byte[] encoded = mapper.writeValueAsBytes(payload);
            return new Sealed(Base64.getEncoder().encodeToString(cipher.doFinal(encoded)),
                    Base64.getEncoder().encodeToString(nonce));
        } catch (GeneralSecurityException | JsonProcessingException exception) {
            throw new IllegalStateException("Could not encrypt email job", exception);
        }
    }

    public EmailPayload open(String jobId, String encryptedPayload, String encodedNonce) {
        try {
            byte[] nonce = Base64.getDecoder().decode(encodedNonce);
            byte[] ciphertext = Base64.getDecoder().decode(encryptedPayload);
            return mapper.readValue(cipher(Cipher.DECRYPT_MODE, jobId, nonce).doFinal(ciphertext), EmailPayload.class);
        } catch (GeneralSecurityException | java.io.IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("Could not decrypt email job", exception);
        }
    }

    private Cipher cipher(int mode, String jobId, byte[] nonce) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(128, nonce));
        cipher.updateAAD(jobId.getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    public record Sealed(String encryptedPayload, String nonce) { }
}
