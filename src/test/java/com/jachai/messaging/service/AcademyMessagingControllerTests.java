package com.jachai.messaging.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jachai.messaging.controller.AcademyMessagingController;
import com.jachai.messaging.dto.ChatMessageView;
import com.jachai.messaging.dto.SendMessageRequest;
import com.jachai.shared.error.ApiException;
import com.jachai.shared.security.RateLimitPolicy;
import com.jachai.shared.security.RateLimitService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;

@ExtendWith(MockitoExtension.class)
class AcademyMessagingControllerTests {
    @Mock AcademyMessagingService messaging;
    @Mock RateLimitService limits;
    @Mock Jwt jwt;

    @Test
    void sendConsumesActorLimitBeforeWritingMessage() {
        when(jwt.getSubject()).thenReturn("teacher");
        ChatMessageView message = new ChatMessageView("id", "teacher", "Hello", Instant.now());
        when(messaging.send("teacher", "org", "conversation", "Hello")).thenReturn(message);

        var response = new AcademyMessagingController(messaging, limits).send(
                jwt, "org", "conversation", new SendMessageRequest("Hello"));

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals(message, response.getBody());
        var order = inOrder(limits, messaging);
        order.verify(limits).consume(RateLimitPolicy.MESSAGE_ACTOR, "org:teacher");
        order.verify(messaging).send("teacher", "org", "conversation", "Hello");
        assertEquals(30, RateLimitPolicy.MESSAGE_ACTOR.limit());
    }

    @Test
    void unavailableLimiterPreventsMessageWrite() {
        when(jwt.getSubject()).thenReturn("teacher");
        doThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "RATE_LIMIT_UNAVAILABLE",
                "Request protection is temporarily unavailable"))
                .when(limits).consume(RateLimitPolicy.MESSAGE_ACTOR, "org:teacher");

        ApiException error = assertThrows(ApiException.class,
                () -> new AcademyMessagingController(messaging, limits).send(
                        jwt, "org", "conversation", new SendMessageRequest("Hello")));

        assertEquals("RATE_LIMIT_UNAVAILABLE", error.code());
        verify(messaging, never()).send("teacher", "org", "conversation", "Hello");
    }
}
