package com.jachai.platform.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jachai.shared.error.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

@ExtendWith(MockitoExtension.class)
class PlatformStorageServiceTests {
    private static final Instant NOW = Instant.parse("2026-10-03T10:00:00Z");

    @Mock PlatformAdminService platform;
    @Mock MongoTemplate mongo;

    private PlatformStorageService service(long threshold) {
        return new PlatformStorageService(platform, mongo, threshold, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void ordinaryUserCannotExecuteDatabaseStatsCommand() {
        assertEquals("PLATFORM_ADMIN_REQUIRED", assertThrows(ApiException.class,
                () -> service(400).usage("ordinary")).code());
        verifyNoInteractions(mongo);
    }

    @Test
    void estimatesDataAndIndexesAndFlagsThreshold() {
        when(platform.isPlatformAdmin("admin")).thenReturn(true);
        when(mongo.executeCommand(any(Document.class))).thenReturn(new Document("dataSize", 250L)
                .append("indexSize", 150L));

        var view = service(400).usage("admin");

        assertEquals(NOW, view.generatedAt());
        assertEquals(250L, view.dataBytes());
        assertEquals(150L, view.indexBytes());
        assertEquals(400L, view.estimatedLogicalBytes());
        assertEquals(400L, view.warningThresholdBytes());
        assertTrue(view.warning());
    }

    @Test
    void remainsClearBelowThreshold() {
        when(platform.isPlatformAdmin("admin")).thenReturn(true);
        when(mongo.executeCommand(any(Document.class))).thenReturn(new Document("dataSize", 100L)
                .append("indexSize", 50L));

        assertFalse(service(400).usage("admin").warning());
    }

    @Test
    void invalidOrUnavailableStatsHaveSanitizedError() {
        when(platform.isPlatformAdmin("admin")).thenReturn(true);
        when(mongo.executeCommand(any(Document.class))).thenReturn(new Document("dataSize", -1)
                .append("indexSize", 50));
        assertEquals("STORAGE_METRICS_UNAVAILABLE", assertThrows(ApiException.class,
                () -> service(400).usage("admin")).code());

        when(mongo.executeCommand(any(Document.class))).thenThrow(new IllegalStateException("secret host"));
        ApiException error = assertThrows(ApiException.class, () -> service(400).usage("admin"));
        assertEquals("Database storage metrics are unavailable", error.getMessage());
    }

    @Test
    void rejectsInvalidThreshold() {
        assertThrows(IllegalArgumentException.class, () -> service(0));
    }
}
