package com.jachai.support.controller;

import com.jachai.platform.service.PlatformAdminService;
import com.jachai.shared.error.ApiException;
import com.jachai.support.dto.CreateSupportReplyRequest;
import com.jachai.support.dto.CreateSupportTicketRequest;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/support")
public class SupportTicketController {

    private final SupportTicketService service;
    private final PlatformAdminService platformAdmin;

    public SupportTicketController(SupportTicketService service, PlatformAdminService platformAdmin) {
        this.service = service;
        this.platformAdmin = platformAdmin;
    }

    @PostMapping("/tickets")
    public SupportTicketView createTicket(@AuthenticationPrincipal Jwt jwt,
                                          @Valid @RequestBody CreateSupportTicketRequest request) {
        String actorUserId = jwt.getSubject();
        boolean isOperator = platformAdmin.isPlatformAdmin(actorUserId);
        return service.createTicket(actorUserId, request);
    }

    @GetMapping("/tickets")
    public Page<SupportTicketView> getMyTickets(@AuthenticationPrincipal Jwt jwt,
                                                 @RequestParam(defaultValue = "0") @Min(0) @Max(500) int page) {
        return service.getMyTickets(jwt.getSubject(), page);
    }

    @GetMapping("/tickets/{ticketId}")
    public SupportTicketView getTicket(@AuthenticationPrincipal Jwt jwt,
                                       @PathVariable String ticketId) {
        String actorUserId = jwt.getSubject();
        boolean isOperator = platformAdmin.isPlatformAdmin(actorUserId);
        return service.getTicket(actorUserId, ticketId, isOperator);
    }

    @PostMapping("/tickets/{ticketId}/replies")
    public SupportTicketView addReply(@AuthenticationPrincipal Jwt jwt,
                                      @PathVariable String ticketId,
                                      @Valid @RequestBody CreateSupportReplyRequest request) {
        String actorUserId = jwt.getSubject();
        boolean isOperator = platformAdmin.isPlatformAdmin(actorUserId);
        return service.addReply(actorUserId, ticketId, request, isOperator);
    }

    @GetMapping("/tickets/{ticketId}/replies")
    public Page<SupportTicketReplyView> getPublicReplies(@AuthenticationPrincipal Jwt jwt,
                                                          @PathVariable String ticketId,
                                                          @RequestParam(defaultValue = "0") @Min(0) @Max(500) int page) {
        String actorUserId = jwt.getSubject();
        boolean isOperator = platformAdmin.isPlatformAdmin(actorUserId);
        return service.getPublicReplies(actorUserId, ticketId, page);
    }

    @PostMapping("/tickets/{ticketId}/reopen")
    public SupportTicketView reopenTicket(@AuthenticationPrincipal Jwt jwt,
                                           @PathVariable String ticketId) {
        return service.reopenTicket(jwt.getSubject(), ticketId);
    }
}