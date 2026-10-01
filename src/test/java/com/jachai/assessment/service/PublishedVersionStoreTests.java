package com.jachai.assessment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jachai.assessment.model.AssessmentVersion;
import com.jachai.assessment.model.ResultReleasePolicy;
import com.jachai.assessment.repository.AssessmentVersionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class PublishedVersionStoreTests {
    @Mock AssessmentVersionRepository versions;
    @Mock ObjectProvider<StringRedisTemplate> provider;
    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> values;

    private AssessmentVersion snapshot() {
        return new AssessmentVersion("a:1", "org-a", "a", 1, "Java", 30, 60,
                ResultReleasePolicy.IMMEDIATE, List.of(), Instant.now());
    }

    private PublishedVersionStore store(boolean enabled) {
        return new PublishedVersionStore(versions, provider, new ObjectMapper().findAndRegisterModules(), enabled);
    }

    @Test
    void cacheHitAvoidsMongoButStillUsesTenantScopedKey() throws Exception {
        AssessmentVersion version = snapshot();
        when(provider.getIfAvailable()).thenReturn(redis);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("jachai:assessment-version:org-a:a:1"))
                .thenReturn(new ObjectMapper().findAndRegisterModules().writeValueAsString(version));

        assertEquals(version.id(), store(true).get("org-a", "a:1").id());

        verify(versions, never()).findByIdAndOrganizationId(any(), any());
    }

    @Test
    void cacheFailureFallsBackToMongo() {
        AssessmentVersion version = snapshot();
        when(provider.getIfAvailable()).thenReturn(redis);
        when(redis.opsForValue()).thenThrow(new IllegalStateException("Redis unavailable"));
        when(versions.findByIdAndOrganizationId("a:1", "org-a")).thenReturn(Optional.of(version));

        assertEquals(version.id(), store(true).get("org-a", "a:1").id());
        verify(versions).findByIdAndOrganizationId("a:1", "org-a");
    }

    @Test
    void disabledCacheNeverTouchesRedis() {
        when(versions.findByIdAndOrganizationId("a:1", "org-a"))
                .thenReturn(Optional.of(snapshot()));

        store(false).get("org-a", "a:1");

        verify(provider, never()).getIfAvailable();
    }

    @Test
    void cacheMissWritesSnapshotWithTtl() {
        when(provider.getIfAvailable()).thenReturn(redis);
        when(redis.opsForValue()).thenReturn(values);
        when(versions.findByIdAndOrganizationId("a:1", "org-a"))
                .thenReturn(Optional.of(snapshot()));

        store(true).get("org-a", "a:1");

        verify(values).set(eq("jachai:assessment-version:org-a:a:1"), any(), eq(Duration.ofHours(6)));
    }
}
