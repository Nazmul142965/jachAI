package com.jachai.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
            users, organizations, memberships, auth, fixtures);

    @Test
    void anyDatabaseListsAllSixRoles() {
        assertEquals(6, service.accounts().size());
        assertEquals("ORG_ADMIN", service.accounts().get(0).role());
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
