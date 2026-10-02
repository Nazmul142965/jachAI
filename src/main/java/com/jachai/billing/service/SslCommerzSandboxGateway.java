package com.jachai.billing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.jachai.auth.model.User;
import com.jachai.billing.model.PaymentOrder;
import com.jachai.shared.error.ApiException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class SslCommerzSandboxGateway {
    private static final String SANDBOX_HOST = "sandbox.sslcommerz.com";
    private final RestClient client;
    private final String storeId;
    private final String storePassword;
    private final String publicBaseUrl;

    public SslCommerzSandboxGateway(@Value("${app.billing.sslcommerz.store-id:}") String storeId,
                                    @Value("${app.billing.sslcommerz.store-password:}") String storePassword,
                                    @Value("${app.public-base-url}") String publicBaseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.client = RestClient.builder().baseUrl("https://" + SANDBOX_HOST)
                .requestFactory(factory).build();
        this.storeId = storeId;
        this.storePassword = storePassword;
        this.publicBaseUrl = publicBaseUrl;
    }

    public boolean configured() {
        return !storeId.isBlank() && !storePassword.isBlank();
    }

    public CheckoutSession start(PaymentOrder order, User buyer) {
        requireConfigured();
        String base = validatedPublicBase();
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("store_id", storeId);
        form.add("store_passwd", storePassword);
        form.add("total_amount", order.amountBdt().toPlainString());
        form.add("currency", "BDT");
        form.add("tran_id", order.id());
        form.add("success_url", base + "/api/v1/billing/sslcommerz/callback");
        form.add("fail_url", base + "/api/v1/billing/sslcommerz/callback?outcome=failed");
        form.add("cancel_url", base + "/api/v1/billing/sslcommerz/callback?outcome=canceled");
        form.add("ipn_url", base + "/api/v1/billing/sslcommerz/ipn");
        form.add("cus_name", truncate(buyer.displayName(), 50));
        form.add("cus_email", buyer.email());
        // Sandbox-only placeholders; production onboarding must collect real billing details.
        form.add("cus_add1", "Sandbox");
        form.add("cus_city", "Dhaka");
        form.add("cus_country", "Bangladesh");
        form.add("cus_phone", "01700000000");
        form.add("shipping_method", "NO");
        form.add("product_name", "JachAI TEAM demo month");
        form.add("product_category", "Software");
        form.add("product_profile", "non-physical-goods");
        form.add("value_a", order.organizationId());
        try {
            JsonNode response = client.post().uri("/gwprocess/v4/api.php")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                    .retrieve().body(JsonNode.class);
            if (response == null || !"SUCCESS".equals(response.path("status").asText())) {
                throw providerFailure();
            }
            String sessionId = response.path("sessionkey").asText("");
            String checkoutUrl = response.path("GatewayPageURL").asText("");
            URI uri = URI.create(checkoutUrl);
            if (sessionId.isBlank() || !"https".equals(uri.getScheme())
                    || !SANDBOX_HOST.equals(uri.getHost())) {
                throw providerFailure();
            }
            return new CheckoutSession(sessionId, checkoutUrl);
        } catch (RestClientException | IllegalArgumentException exception) {
            throw providerFailure();
        }
    }

    public VerifiedPayment verify(PaymentOrder order, String validationId) {
        requireConfigured();
        if (validationId == null || !validationId.matches("[A-Za-z0-9_-]{1,50}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAYMENT_REFERENCE",
                    "Payment validation reference is invalid");
        }
        try {
            JsonNode response = client.get().uri(builder -> builder
                    .path("/validator/api/validationserverAPI.php")
                    .queryParam("val_id", validationId)
                    .queryParam("store_id", storeId)
                    .queryParam("store_passwd", storePassword)
                    .queryParam("format", "json").build())
                    .retrieve().body(JsonNode.class);
            return verifiedResponse(order, validationId, response, storeId);
        } catch (RestClientException exception) {
            throw providerFailure();
        }
    }

    static VerifiedPayment verifiedResponse(PaymentOrder order, String validationId,
                                            JsonNode response, String expectedStoreId) {
        if (response == null || !("VALID".equals(response.path("status").asText())
                || "VALIDATED".equals(response.path("status").asText()))
                || !order.id().equals(response.path("tran_id").asText())
                || !validationId.equals(response.path("val_id").asText())
                || !"BDT".equals(response.path("currency_type").asText())
                || !"BDT".equals(response.path("currency").asText())
                || !expectedStoreId.equals(response.path("store_id").asText(expectedStoreId))
                || response.path("risk_level").asInt(-1) != 0
                || response.path("bank_tran_id").asText("").isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PAYMENT_NOT_VERIFIED",
                    "Payment was not verified by SSLCOMMERZ sandbox");
        }
        try {
            BigDecimal paid = new BigDecimal(response.path("currency_amount").asText());
            if (paid.compareTo(order.amountBdt()) != 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "PAYMENT_AMOUNT_MISMATCH",
                        "Gateway amount does not match the order");
            }
        } catch (NumberFormatException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PAYMENT_NOT_VERIFIED",
                    "Payment was not verified by SSLCOMMERZ sandbox");
        }
        return new VerifiedPayment(validationId, response.path("bank_tran_id").asText());
    }

    private void requireConfigured() {
        if (!configured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "BILLING_UNAVAILABLE",
                    "SSLCOMMERZ sandbox credentials are not configured");
        }
    }

    private String validatedPublicBase() {
        try {
            URI uri = URI.create(publicBaseUrl);
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())
                    && "localhost".equals(uri.getHost())) || uri.getHost() == null) {
                throw providerFailure();
            }
            return publicBaseUrl.replaceAll("/+$", "");
        } catch (IllegalArgumentException exception) {
            throw providerFailure();
        }
    }

    private static String truncate(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }

    private static ApiException providerFailure() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "BILLING_PROVIDER_ERROR",
                "SSLCOMMERZ sandbox did not complete the request");
    }

    public record CheckoutSession(String sessionId, String checkoutUrl) { }
    public record VerifiedPayment(String validationId, String gatewayTransactionId) { }
}
