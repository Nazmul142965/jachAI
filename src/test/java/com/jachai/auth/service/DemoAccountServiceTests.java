package com.jachai.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
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

class DemoAccountServiceTests {
    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final UserRepository users = mock(UserRepository.class);
    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final MembershipRepository memberships = mock(MembershipRepository.class);
    private final AuthService auth = mock(AuthService.class);
    private final DemoFixtureService fixtures = mock(DemoFixtureService.class);
    private final DemoAccountService service = new DemoAccountService(mongo,
            users, organizations, memberships, auth, fixtures, false);

    @Test
    void refusesToExposeRolesAgainstNormalDatabase() {
        MongoDatabase database = mock(MongoDatabase.class);
        when(mongo.getDb()).thenReturn(database);
        when(database.getName()).thenReturn("jachai_dev");
        ApiException error = assertThrows(ApiException.class, service::accounts);
        assertEquals("DEMO_DATABASE_REQUIRED", error.code());
    }

    @Test
    void dedicatedDemoDatabaseListsAllSixRoles() {
        MongoDatabase database = mock(MongoDatabase.class);
        when(mongo.getDb()).thenReturn(database);
        when(database.getName()).thenReturn("jachai_demo_local");
        assertEquals(6, service.accounts().size());
        assertEquals("ORG_ADMIN", service.accounts().get(0).role());
    }

    @Test
    void explicitlyAllowsSharedProductionDatabase() {
        DemoAccountService shared = new DemoAccountService(mongo,
                users, organizations, memberships, auth, fixtures, true);
        MongoDatabase database = mock(MongoDatabase.class);
        when(mongo.getDb()).thenReturn(database);
        when(database.getName()).thenReturn("jachai_prod");
        assertEquals(6, shared.accounts().size());
    }

    @Test
    void brokenExampleSeedDoesNotLockOutDemoAccount() {
        MongoDatabase database = mock(MongoDatabase.class);
        when(mongo.getDb()).thenReturn(database);
        when(database.getName()).thenReturn("jachai_demo_local");
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
