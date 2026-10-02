package com.jachai.platform.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jachai.auth.model.User;
import com.jachai.auth.repository.UserRepository;
import com.jachai.organization.model.Organization;
import com.jachai.organization.model.OrganizationType;
import com.jachai.organization.repository.OrganizationRepository;
import com.jachai.platform.dto.PlatformOrganizationView;
import com.jachai.platform.dto.PlatformRoleAuditView;
import com.jachai.platform.dto.PlatformUserView;
import com.jachai.platform.model.PlatformRoleAction;
import com.jachai.platform.model.PlatformRoleEvent;
import com.jachai.platform.repository.PlatformRoleEventRepository;
import com.jachai.shared.error.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
class PlatformAdminServiceTests {
    @Mock UserRepository users;
    @Mock OrganizationRepository organizations;
    @Mock PlatformRoleEventRepository roleEvents;

    private PlatformAdminService service(String configuredIds) {
        return new PlatformAdminService(users, organizations, roleEvents, configuredIds);
    }

    private User user(String id, boolean verified, boolean disabled) {
        return new User(id, id + "@example.com", id, "secret-hash", verified,
                disabled, 0, Instant.parse("2026-01-01T00:00:00Z"), "google-subject");
    }

    @Test
    void configuredVerifiedActiveUserCanInspectOrganizationMetadata() {
        when(users.findById("admin")).thenReturn(Optional.of(user("admin", true, false)));
        Organization organization = new Organization("org", "Academy", OrganizationType.ACADEMY,
                "owner", Instant.parse("2026-01-02T00:00:00Z"));
        when(organizations.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(organization)));

        PlatformAdminService service = service("other, admin ");
        var page = service.organizations("admin", 0);

        assertEquals(List.of(new PlatformOrganizationView("org", "Academy", OrganizationType.ACADEMY,
                "owner", organization.createdAt())), page.getContent());
        verify(organizations).findAll(any(Pageable.class));
    }

    @Test
    void notConfiguredUserCannotReadOtherTenants() {
        PlatformAdminService service = service("admin");

        assertEquals("PLATFORM_ADMIN_REQUIRED", assertThrows(ApiException.class,
                () -> service.organizations("ordinary-user", 0)).code());
        assertEquals("PLATFORM_ADMIN_REQUIRED", assertThrows(ApiException.class,
                () -> service.user("ordinary-user", "target")).code());
        verify(organizations, never()).findAll(any(Pageable.class));
        verify(users, never()).findById("target");
    }

    @Test
    void disabledOrUnverifiedConfiguredUserCannotReadPlatformData() {
        PlatformAdminService service = service("admin");
        when(users.findById("admin")).thenReturn(Optional.of(user("admin", false, false)),
                Optional.of(user("admin", true, true)));

        assertFalse(service.isPlatformAdmin("admin"));
        assertFalse(service.isPlatformAdmin("admin"));
        verify(organizations, never()).findAll(any(Pageable.class));
    }

    @Test
    void emptyConfigurationGrantsNoOne() {
        PlatformAdminService service = service(" ");

        assertFalse(service.isPlatformAdmin("admin"));
        verify(roleEvents, never()).findFirstByTargetUserIdOrderByRevisionDesc("admin");
    }

    @Test
    void userLookupExcludesPasswordAndGoogleIdentity() {
        when(users.findById("admin")).thenReturn(Optional.of(user("admin", true, false)));
        when(users.findById("target")).thenReturn(Optional.of(user("target", true, false)));

        PlatformUserView view = service("admin")
                .user("admin", "target");

        assertEquals("target", view.id());
        assertEquals("target@example.com", view.email());
        assertEquals(6, PlatformUserView.class.getRecordComponents().length);
        assertTrue(List.of(PlatformUserView.class.getRecordComponents()).stream()
                .noneMatch(component -> component.getName().contains("password")
                        || component.getName().contains("google")));
    }

    @Test
    void onlyExistingVerifiedActiveTargetCanBeGrantedRole() {
        when(users.findById("admin")).thenReturn(Optional.of(user("admin", true, false)));
        when(users.findById("target")).thenReturn(Optional.of(user("target", false, false)));

        assertEquals("USER_UNAVAILABLE", assertThrows(ApiException.class,
                () -> service("admin").changeRole("admin", "target", true)).code());
        verify(roleEvents, never()).insert(any(PlatformRoleEvent.class));
    }

    @Test
    void bootstrapAndSelfRoleChangesAreRejected() {
        when(users.findById("admin")).thenReturn(Optional.of(user("admin", true, false)));

        assertEquals("SELF_ROLE_CHANGE", assertThrows(ApiException.class,
                () -> service("admin, backup").changeRole("admin", "admin", false)).code());
        assertEquals("BOOTSTRAP_ADMIN", assertThrows(ApiException.class,
                () -> service("admin, backup").changeRole("admin", "backup", false)).code());
        verify(roleEvents, never()).insert(any(PlatformRoleEvent.class));
    }

    @Test
    void roleGrantIsTheAuditEventAndMakesTargetAdmin() {
        when(users.findById("admin")).thenReturn(Optional.of(user("admin", true, false)));
        when(users.findById("target")).thenReturn(Optional.of(user("target", true, false)));
        PlatformRoleEvent event = new PlatformRoleEvent("event", "target", "admin",
                PlatformRoleAction.GRANTED, 1L, Instant.now());
        when(roleEvents.findFirstByTargetUserIdOrderByRevisionDesc("target"))
                .thenReturn(Optional.empty(), Optional.of(event));

        var view = service("admin").changeRole("admin", "target", true);

        assertTrue(view.platformAdmin());
        ArgumentCaptor<PlatformRoleEvent> inserted = ArgumentCaptor.forClass(PlatformRoleEvent.class);
        verify(roleEvents).insert(inserted.capture());
        assertEquals("target", inserted.getValue().targetUserId());
        assertEquals("admin", inserted.getValue().actorUserId());
        assertEquals(PlatformRoleAction.GRANTED, inserted.getValue().action());
        assertEquals(1L, inserted.getValue().revision());
    }

    @Test
    void repeatedGrantIsIdempotent() {
        when(users.findById("admin")).thenReturn(Optional.of(user("admin", true, false)));
        when(users.findById("target")).thenReturn(Optional.of(user("target", true, false)));
        when(roleEvents.findFirstByTargetUserIdOrderByRevisionDesc("target"))
                .thenReturn(Optional.of(new PlatformRoleEvent("event", "target", "admin",
                        PlatformRoleAction.GRANTED, 1L, Instant.now())));

        assertTrue(service("admin").changeRole("admin", "target", true).platformAdmin());
        verify(roleEvents, never()).insert(any(PlatformRoleEvent.class));
    }

    @Test
    void databaseGrantedAdminCanActAndRevocationRemovesAuthority() {
        when(users.findById("delegate")).thenReturn(Optional.of(user("delegate", true, false)));
        when(roleEvents.findFirstByTargetUserIdOrderByRevisionDesc("delegate"))
                .thenReturn(Optional.of(new PlatformRoleEvent("grant", "delegate", "admin",
                        PlatformRoleAction.GRANTED, 1L, Instant.now())),
                        Optional.of(new PlatformRoleEvent("revoke", "delegate", "admin",
                                PlatformRoleAction.REVOKED, 2L, Instant.now().plusSeconds(1))));

        PlatformAdminService service = service("admin");
        assertTrue(service.isPlatformAdmin("delegate"));
        assertFalse(service.isPlatformAdmin("delegate"));
    }

    @Test
    void ordinaryOrganizationMemberCannotGrantPlatformRole() {
        when(users.findById("ordinary-user")).thenReturn(Optional.of(user("ordinary-user", true, false)));

        assertEquals("PLATFORM_ADMIN_REQUIRED", assertThrows(ApiException.class,
                () -> service("admin").changeRole("ordinary-user", "target", true)).code());
        verify(roleEvents, never()).insert(any(PlatformRoleEvent.class));
        verify(users, never()).findById("target");
    }

    @Test
    void revocationAppendsAnEventAndDisablesDynamicRole() {
        when(users.findById("admin")).thenReturn(Optional.of(user("admin", true, false)));
        when(users.findById("target")).thenReturn(Optional.of(user("target", true, false)));
        PlatformRoleEvent grant = new PlatformRoleEvent("grant", "target", "admin",
                PlatformRoleAction.GRANTED, 1L, Instant.now());
        PlatformRoleEvent revoke = new PlatformRoleEvent("revoke", "target", "admin",
                PlatformRoleAction.REVOKED, 2L, Instant.now().plusSeconds(1));
        when(roleEvents.findFirstByTargetUserIdOrderByRevisionDesc("target"))
                .thenReturn(Optional.of(grant), Optional.of(revoke));

        assertFalse(service("admin").changeRole("admin", "target", false).platformAdmin());
        ArgumentCaptor<PlatformRoleEvent> inserted = ArgumentCaptor.forClass(PlatformRoleEvent.class);
        verify(roleEvents).insert(inserted.capture());
        assertEquals(PlatformRoleAction.REVOKED, inserted.getValue().action());
        assertEquals(2L, inserted.getValue().revision());
    }

    @Test
    void concurrentRoleChangeReturnsConflictWithoutClaimingSuccess() {
        when(users.findById("admin")).thenReturn(Optional.of(user("admin", true, false)));
        when(users.findById("target")).thenReturn(Optional.of(user("target", true, false)));
        when(roleEvents.findFirstByTargetUserIdOrderByRevisionDesc("target"))
                .thenReturn(Optional.empty());
        when(roleEvents.insert(any(PlatformRoleEvent.class)))
                .thenThrow(new DuplicateKeyException("revision already used"));

        assertEquals("ROLE_CHANGED", assertThrows(ApiException.class,
                () -> service("admin").changeRole("admin", "target", true)).code());
    }

    @Test
    void auditHistoryIsOnlyAvailableToPlatformAdminAndContainsEvents() {
        PlatformRoleEvent event = new PlatformRoleEvent("event", "target", "admin",
                PlatformRoleAction.GRANTED, 1L, Instant.now());
        when(users.findById("admin")).thenReturn(Optional.of(user("admin", true, false)));
        when(users.findById("target")).thenReturn(Optional.of(user("target", true, false)));
        when(roleEvents.findByTargetUserId(org.mockito.ArgumentMatchers.eq("target"),
                any(Pageable.class))).thenReturn(new PageImpl<>(List.of(event)));

        var history = service("admin").roleHistory("admin", "target", 0);

        assertEquals(List.of(new PlatformRoleAuditView("event", "target", "admin",
                PlatformRoleAction.GRANTED, 1L, event.occurredAt())), history.getContent());
        verify(roleEvents).findByTargetUserId(org.mockito.ArgumentMatchers.eq("target"),
                org.mockito.ArgumentMatchers.eq(PageRequest.of(0, 20,
                        org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC,
                                "revision"))));
    }
}
