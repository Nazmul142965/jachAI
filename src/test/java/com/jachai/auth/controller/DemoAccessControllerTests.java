package com.jachai.auth.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jachai.auth.service.DemoAccountService;
import com.jachai.shared.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

class DemoAccessControllerTests {
    private final DemoAccessController controller = new DemoAccessController(mock(DemoAccountService.class));
    private final HttpServletRequest request = mock(HttpServletRequest.class);

    @Test
    void remoteClientCannotRequestDemoSession() {
        when(request.getRemoteAddr()).thenReturn("192.0.2.10");
        when(request.getServerName()).thenReturn("localhost");
        ApiException error = assertThrows(ApiException.class,
                () -> controller.login("academy-admin", request));
        assertEquals("DEMO_LOCAL_ONLY", error.code());
    }

    @Test
    void loopbackRequestWithPublicHostCannotRequestDemoSession() {
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(request.getServerName()).thenReturn("public.example");
        ApiException error = assertThrows(ApiException.class,
                () -> controller.login("academy-admin", request));
        assertEquals("DEMO_LOCAL_ONLY", error.code());
    }
}
