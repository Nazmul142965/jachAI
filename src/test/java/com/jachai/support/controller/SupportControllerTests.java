package com.jachai.support.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jachai.platform.controller.PlatformSupportController;
import com.jachai.platform.service.PlatformAdminService;
import com.jachai.shared.error.ApiException;
import com.jachai.support.dto.CreateSupportReplyRequest;
import com.jachai.support.service.SupportTicketService;
import com.jachai.support.type.TicketStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;

@ExtendWith(MockitoExtension.class)
class SupportControllerTests {
    @Mock SupportTicketService service;
    @Mock PlatformAdminService admins;

    private Jwt jwt(String subject) {
        return Jwt.withTokenValue("token").header("alg", "none").subject(subject).build();
    }

    @Test
    void platformRoutesRejectNonAdminsBeforeCallingService() {
        PlatformSupportController controller = new PlatformSupportController(service, admins);
        assertEquals("PLATFORM_ADMIN_REQUIRED", assertThrows(ApiException.class,
                () -> controller.getInbox(jwt("user"), null, null, 0)).code());
        assertEquals("PLATFORM_ADMIN_REQUIRED", assertThrows(ApiException.class,
                () -> controller.getAllReplies(jwt("user"), "ticket", 0)).code());
        assertEquals("PLATFORM_ADMIN_REQUIRED", assertThrows(ApiException.class,
                () -> controller.changeStatus(jwt("user"), "ticket", TicketStatus.RESOLVED)).code());
        verify(service, never()).getOperatorInbox("user", null, null, 0);
        verify(service, never()).getAllReplies("user", "ticket", 0);
        verify(service, never()).changeStatus("user", "ticket", TicketStatus.RESOLVED);
    }

    @Test
    void platformReplyPassesAdminIdentityAndOperatorFlag() {
        when(admins.isPlatformAdmin("admin")).thenReturn(true);
        CreateSupportReplyRequest reply = new CreateSupportReplyRequest("Investigating", false);
        new PlatformSupportController(service, admins).addReply(jwt("admin"), "ticket", reply);
        verify(service).addReply("admin", "ticket", reply, true);
    }

    @Test
    void customerReplyPassesVerifiedOperatorFlagToService() {
        CreateSupportReplyRequest reply = new CreateSupportReplyRequest("Please help", true);
        new SupportTicketController(service, admins).addReply(jwt("user"), "ticket", reply);
        verify(service).addReply("user", "ticket", reply, false);
    }
}
