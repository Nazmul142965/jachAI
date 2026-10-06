package com.jachai.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jachai.auth.repository.UserRepository;
import com.jachai.organization.repository.MembershipRepository;
import com.jachai.organization.repository.OrganizationRepository;
import com.jachai.shared.error.ApiException;
import com.mongodb.client.MongoDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

class DemoAccountServiceTests {
    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final DemoAccountService service = new DemoAccountService(mongo,
            mock(UserRepository.class), mock(OrganizationRepository.class),
            mock(MembershipRepository.class), mock(AuthService.class));

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
}
