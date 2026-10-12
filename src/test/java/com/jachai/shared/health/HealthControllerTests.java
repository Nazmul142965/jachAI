package com.jachai.shared.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.core.MongoTemplate;

class HealthControllerTests {
    @SuppressWarnings("unchecked")
    private final ObjectProvider<MongoTemplate> provider = mock(ObjectProvider.class);
    private final MongoTemplate template = mock(MongoTemplate.class);
    private final MongoDatabase database = mock(MongoDatabase.class);

    @Test
    void answersUpAndKeepsTheDatabaseConnectionWarm() {
        when(provider.getIfAvailable()).thenReturn(template);
        when(template.getDb()).thenReturn(database);

        assertEquals("UP", new HealthController(provider).health().status());

        verify(database).runCommand(any(Document.class));
    }

    @Test
    void aFailingDatabaseNeverMakesTheHealthCheckFail() {
        when(provider.getIfAvailable()).thenReturn(template);
        when(template.getDb()).thenThrow(new IllegalStateException("database down"));

        assertEquals("UP", new HealthController(provider).health().status());
    }

    @Test
    void manyCallersDoNotHammerTheDatabase() {
        when(provider.getIfAvailable()).thenReturn(template);
        when(template.getDb()).thenReturn(database);
        var controller = new HealthController(provider);

        for (int i = 0; i < 20; i++) controller.health();

        verify(database, times(1)).runCommand(any(Document.class));
    }
}
