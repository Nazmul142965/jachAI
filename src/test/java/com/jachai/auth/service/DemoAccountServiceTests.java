package com.jachai.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jachai.auth.dto.AuthResponse;
import com.jachai.auth.model.User;
import com.jachai.auth.repository.UserRepository;
import com.jachai.organization.model.Membership;
import com.jachai.organization.model.Organization;
import com.jachai.organization.repository.MembershipRepository;
import com.jachai.organization.repository.OrganizationRepository;
import com.jachai.shared.error.ApiException;
import com.mongodb.client.MongoDatabase;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.core.env.Environment;

class DemoAccountServiceTests {
    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final UserRepository users = mock(UserRepository.class);
    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final MembershipRepository memberships = mock(MembershipRepository.class);
    private final AuthService auth = mock(AuthService.class);
    private final DemoFixtureService fixtures = mock(DemoFixtureService.class);
    private final Environment environment = mock(Environment.class);
    private final DemoAccountService service = new DemoAccountService(mongo,
            users, organizations, memberships, auth, fixtures, environment);

    @Test
    void anyDatabaseListsAllSixRoles() {
        assertEquals(6, service.accounts().size());
        assertEquals("ORG_ADMIN", service.accounts().get(0).role());
        assertEquals("DEMO_ROLE_NOT_FOUND", assertThrows(ApiException.class,
                () -> service.login("platform-admin")).code());
    }

    @Test
    void isolatedDemoListsPlatformAdministratorWithoutWorkspaceMembership() {
        MongoDatabase database = mock(MongoDatabase.class);
        when(environment.matchesProfiles("demo")).thenReturn(true);
        when(mongo.getDb()).thenReturn(database);
        when(database.getName()).thenReturn("jachai_demo_faculty");
        assertEquals(7, service.accounts().size());
        assertEquals("PLATFORM_ADMIN", service.accounts().get(6).role());

        User admin = new User("demo-account-platform-admin", "platform-admin@demo.jachai.invalid",
                "Platform administrator", null, true, false, 0, Instant.now());
        when(users.findById(anyString())).thenReturn(Optional.of(admin));
        when(organizations.findById(anyString())).thenReturn(Optional.of(mock(Organization.class)));
        when(memberships.findById(anyString())).thenReturn(Optional.of(mock(Membership.class)));
        AuthResponse response = mock(AuthResponse.class);
        when(auth.issueSession(admin)).thenReturn(response);
        assertSame(response, service.login("platform-admin"));
        verify(memberships, never()).findById("demo-member-platform-admin");
    }

    @Test
    void demoProfileOnNormalDatabaseDoesNotExposePlatformAdministrator() {
        MongoDatabase database = mock(MongoDatabase.class);
        when(environment.matchesProfiles("demo")).thenReturn(true);
        when(mongo.getDb()).thenReturn(database);
        when(database.getName()).thenReturn("jachai_prod");
        assertEquals(6, service.accounts().size());
        assertEquals("DEMO_ROLE_NOT_FOUND", assertThrows(ApiException.class,
                () -> service.login("platform-admin")).code());
    }

    @Test
    void productionProfileNeverExposesDemoPlatformAdministrator() {
        when(environment.matchesProfiles("demo")).thenReturn(true);
        when(environment.matchesProfiles("prod")).thenReturn(true);
        assertEquals(6, service.accounts().size());
        assertEquals("DEMO_ROLE_NOT_FOUND", assertThrows(ApiException.class,
                () -> service.login("platform-admin")).code());
    }

    @Test
    void brokenExampleSeedDoesNotLockOutDemoAccount() {
        User learner = new User("demo-account-learner", "learner@demo.jachai.invalid", "Learner",
                null, true, false, 0, Instant.now());
        when(users.findById(anyString())).thenReturn(Optional.of(learner));
        when(organizations.findById(anyString())).thenReturn(Optional.of(mock(Organization.class)));
        when(memberships.findById(anyString())).thenReturn(Optional.of(mock(Membership.class)));
        org.mockito.Mockito.doThrow(new IllegalStateException("fixture quota exhausted")).when(fixtures).seed();
        AuthResponse response = mock(AuthResponse.class);
        when(auth.issueSession(learner)).thenReturn(response);

        assertSame(response, service.login("learner"));
    }
}
