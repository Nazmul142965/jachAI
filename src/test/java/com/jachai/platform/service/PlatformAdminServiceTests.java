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
import com.jachai.platform.dto.PlatformUserView;
import com.jachai.shared.error.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class PlatformAdminServiceTests {
    @Mock UserRepository users;
    @Mock OrganizationRepository organizations;

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

        PlatformAdminService service = new PlatformAdminService(users, organizations, "other, admin ");
        var page = service.organizations("admin", 0);

        assertEquals(List.of(new PlatformOrganizationView("org", "Academy", OrganizationType.ACADEMY,
                "owner", organization.createdAt())), page.getContent());
        verify(organizations).findAll(any(Pageable.class));
    }

    @Test
    void notConfiguredUserCannotReadOtherTenants() {
        PlatformAdminService service = new PlatformAdminService(users, organizations, "admin");

        assertEquals("PLATFORM_ADMIN_REQUIRED", assertThrows(ApiException.class,
                () -> service.organizations("ordinary-user", 0)).code());
        assertEquals("PLATFORM_ADMIN_REQUIRED", assertThrows(ApiException.class,
                () -> service.user("ordinary-user", "target")).code());
        verify(organizations, never()).findAll(any(Pageable.class));
        verify(users, never()).findById("target");
    }

    @Test
    void disabledOrUnverifiedConfiguredUserCannotReadPlatformData() {
        PlatformAdminService service = new PlatformAdminService(users, organizations, "admin");
        when(users.findById("admin")).thenReturn(Optional.of(user("admin", false, false)),
                Optional.of(user("admin", true, true)));

        assertFalse(service.isPlatformAdmin("admin"));
        assertFalse(service.isPlatformAdmin("admin"));
        verify(organizations, never()).findAll(any(Pageable.class));
    }

    @Test
    void emptyConfigurationGrantsNoOne() {
        PlatformAdminService service = new PlatformAdminService(users, organizations, " ");

        assertFalse(service.isPlatformAdmin("admin"));
        verify(users, never()).findById("admin");
    }

    @Test
    void userLookupExcludesPasswordAndGoogleIdentity() {
        when(users.findById("admin")).thenReturn(Optional.of(user("admin", true, false)));
        when(users.findById("target")).thenReturn(Optional.of(user("target", true, false)));

        PlatformUserView view = new PlatformAdminService(users, organizations, "admin")
                .user("admin", "target");

        assertEquals("target", view.id());
        assertEquals("target@example.com", view.email());
        assertEquals(6, PlatformUserView.class.getRecordComponents().length);
        assertTrue(List.of(PlatformUserView.class.getRecordComponents()).stream()
                .noneMatch(component -> component.getName().contains("password")
                        || component.getName().contains("google")));
    }
}
