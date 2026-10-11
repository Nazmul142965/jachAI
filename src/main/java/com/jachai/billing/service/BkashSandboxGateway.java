package com.jachai.billing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.jachai.billing.model.PaymentOrder;
import com.jachai.shared.error.ApiException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class BkashSandboxGateway {
    private static final String API_HOST = "tokenized.sandbox.bka.sh";
    private static final String CHECKOUT_HOST = "sandbox.payment.bkash.com";
    private static final String API_PATH = "/v1.2.0-beta/tokenized/checkout";
    private final RestClient client;
    private final String appKey;
    private final String appSecret;
    private final String username;
    private final String password;
    private final String publicBaseUrl;
    private volatile GrantToken cachedToken;

    public BkashSandboxGateway(@Value("${app.billing.bkash.app-key:}") String appKey,
                               @Value("${app.billing.bkash.app-secret:}") String appSecret,
                               @Value("${app.billing.bkash.username:}") String username,
                               @Value("${app.billing.bkash.password:}") String password,
                               @Value("${app.public-base-url}") String publicBaseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.client = RestClient.builder().baseUrl("https://" + API_HOST)
                .requestFactory(factory).build();
        this.appKey = appKey;
        this.appSecret = appSecret;
        this.username = username;
        this.password = password;
        this.publicBaseUrl = publicBaseUrl;
    }

    public boolean configured() {
        return !appKey.isBlank() && !appSecret.isBlank()
                && !username.isBlank() && !password.isBlank()
                && SandboxCallbackOrigin.valid(publicBaseUrl);
    }

    public CheckoutSession start(PaymentOrder order) {
        requireConfigured();
        String callback = callbackBase() + "/api/v1/billing/bkash/callback?orderId=" + order.id();
        try {
            JsonNode response = client.post().uri(API_PATH + "/create")
                    .headers(headers -> authorize(headers, token()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("mode", "0011", "payerReference", order.id(),
                            "callbackURL", callback, "amount", order.amountBdt().toPlainString(),
                            "currency", "BDT", "intent", "sale", "merchantInvoiceNumber", order.id()))
                    .retrieve().body(JsonNode.class);
            if (response == null || !"0000".equals(response.path("statusCode").asText())
                    || !order.id().equals(response.path("merchantInvoiceNumber").asText())
                    || !"BDT".equals(response.path("currency").asText())
                    || !matchesAmount(response.path("amount"), order.amountBdt())) {
                throw providerFailure();
            }
            String paymentId = response.path("paymentID").asText("");
            String checkoutUrl = response.path("bkashURL").asText("");
            URI uri = URI.create(checkoutUrl);
            if (!validPaymentId(paymentId) || !"https".equals(uri.getScheme())
                    || !CHECKOUT_HOST.equals(uri.getHost())) throw providerFailure();
            return new CheckoutSession(paymentId, checkoutUrl);
        } catch (RestClientException | IllegalArgumentException exception) {
            throw providerFailure();
        }
    }

    public VerifiedPayment verify(PaymentOrder order, String paymentId) {
        requireConfigured();
        if (!validPaymentId(paymentId) || !paymentId.equals(order.gatewaySessionId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAYMENT_REFERENCE",
                    "Payment reference does not match checkout");
        }
        String token = token();
        // Execute may have succeeded even when its response was lost. Query is the source of truth.
        try {
            client.post().uri(API_PATH + "/execute")
                    .headers(headers -> authorize(headers, token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("paymentID", paymentId)).retrieve().body(JsonNode.class);
        } catch (RestClientException ignored) {
            // A second callback/reconciliation can still query a successfully executed payment.
        }
        try {
            JsonNode response = client.post().uri(API_PATH + "/payment/status")
                    .headers(headers -> authorize(headers, token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("paymentID", paymentId)).retrieve().body(JsonNode.class);
            return verifiedResponse(order, paymentId, response);
        } catch (RestClientException exception) {
            throw providerFailure();
        }
    }

    static VerifiedPayment verifiedResponse(PaymentOrder order, String paymentId, JsonNode response) {
        if (response == null || !"0000".equals(response.path("statusCode").asText())
                || !"Completed".equals(response.path("transactionStatus").asText())
                || !paymentId.equals(response.path("paymentID").asText())
                || !order.id().equals(response.path("merchantInvoiceNumber").asText())
                || !"BDT".equals(response.path("currency").asText())
                || !"sale".equalsIgnoreCase(response.path("intent").asText())
                || response.path("trxID").asText("").isBlank()
                || !matchesAmount(response.path("amount"), order.amountBdt())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PAYMENT_NOT_VERIFIED",
                    "bKash could not confirm this payment.");
        }
        return new VerifiedPayment(paymentId, response.path("trxID").asText());
    }

    private synchronized String token() {
        GrantToken current = cachedToken;
        if (current != null && Instant.now().isBefore(current.expiresAt())) return current.value();
        requireConfigured();
        try {
            JsonNode response = client.post().uri(API_PATH + "/token/grant")
                    .header("username", username).header("password", password)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("app_key", appKey, "app_secret", appSecret))
                    .retrieve().body(JsonNode.class);
            String value = response == null ? "" : response.path("id_token").asText("");
            if (value.isBlank()) throw providerFailure();
            long seconds = response.path("expires_in").asLong(600);
            cachedToken = new GrantToken(value, Instant.now().plusSeconds(Math.max(30, seconds - 60)));
            return value;
        } catch (RestClientException exception) {
            throw providerFailure();
        }
    }

    private void authorize(org.springframework.http.HttpHeaders headers, String token) {
        headers.set("authorization", token);
        headers.set("x-app-key", appKey);
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
    }

    private String callbackBase() {
        return SandboxCallbackOrigin.require(publicBaseUrl);
    }

    private void requireConfigured() {
        if (appKey.isBlank() || appSecret.isBlank() || username.isBlank() || password.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "BILLING_UNAVAILABLE", "bKash practice payments are not set up yet.");
        }
        SandboxCallbackOrigin.require(publicBaseUrl);
    }

    private static boolean matchesAmount(JsonNode node, BigDecimal expected) {
        try {
            return new BigDecimal(node.asText()).compareTo(expected) == 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static boolean validPaymentId(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{8,80}");
    }

    private static ApiException providerFailure() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "BILLING_PROVIDER_ERROR",
                "bKash did not respond. Try again.");
    }

    private record GrantToken(String value, Instant expiresAt) { }
    public record CheckoutSession(String paymentId, String checkoutUrl) { }
    public record VerifiedPayment(String paymentId, String gatewayTransactionId) { }
}
