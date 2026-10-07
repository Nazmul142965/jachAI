package com.jachai.platform.controller;

import com.jachai.platform.service.PlatformAdminService;
import com.jachai.shared.error.ApiException;
import com.jachai.support.dto.CreateSupportReplyRequest;
import com.jachai.support.dto.SupportTicketReplyView;
import com.jachai.support.dto.SupportTicketView;
import com.jachai.support.type.TicketStatus;
import com.jachai.support.service.SupportTicketService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/platform/support")
public class PlatformSupportController {

    private final SupportTicketService service;
    private final PlatformAdminService platformAdmin;

    public PlatformSupportController(SupportTicketService service, PlatformAdminService platformAdmin) {
        this.service = service;
        this.platformAdmin = platformAdmin;
    }

    private void requireAdmin(String actorUserId) {
        if (!platformAdmin.isPlatformAdmin(actorUserId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PLATFORM_ADMIN_REQUIRED",
                    "Platform administrator access required");
        }
    }

    @GetMapping("/tickets")
    public Page<SupportTicketView> getInbox(@AuthenticationPrincipal Jwt jwt,
                                             @RequestParam(required = false) TicketStatus status,
                                             @RequestParam(required = false) String organizationId,
                                             @RequestParam(defaultValue = "0") @Min(0) @Max(500) int page) {
        String actorUserId = jwt.getSubject();
        requireAdmin(actorUserId);
        return service.getOperatorInbox(actorUserId, status, organizationId, page);
    }

    @GetMapping("/tickets/{ticketId}")
    public SupportTicketView getTicket(@AuthenticationPrincipal Jwt jwt,
                                       @PathVariable String ticketId) {
        String actorUserId = jwt.getSubject();
        requireAdmin(actorUserId);
        return service.getTicket(actorUserId, ticketId, true);
    }

    @GetMapping("/tickets/{ticketId}/replies")
    public Page<SupportTicketReplyView> getAllReplies(@AuthenticationPrincipal Jwt jwt,
                                                       @PathVariable String ticketId,
                                                       @RequestParam(defaultValue = "0") @Min(0) @Max(500) int page) {
        String actorUserId = jwt.getSubject();
        requireAdmin(actorUserId);
        return service.getAllReplies(actorUserId, ticketId, page);
    }

    @PostMapping("/tickets/{ticketId}/replies")
    public SupportTicketView addReply(@AuthenticationPrincipal Jwt jwt,
                                      @PathVariable String ticketId,
                                      @Valid @RequestBody CreateSupportReplyRequest request) {
        String actorUserId = jwt.getSubject();
        requireAdmin(actorUserId);
        return service.addReply(actorUserId, ticketId, request, true);
    }

    @PutMapping("/tickets/{ticketId}/status")
    public SupportTicketView changeStatus(@AuthenticationPrincipal Jwt jwt,
                                           @PathVariable String ticketId,
                                           @RequestParam TicketStatus status) {
        String actorUserId = jwt.getSubject();
        requireAdmin(actorUserId);
        return service.changeStatus(actorUserId, ticketId, status);
    }
}