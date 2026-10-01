package com.jachai.assessment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jachai.assessment.model.AssessmentVersion;
import com.jachai.assessment.repository.AssessmentVersionRepository;
import com.jachai.shared.error.ApiException;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Only published, immutable snapshots are cached. Authorization happens before this lookup. */
@Service
public class PublishedVersionStore {
    private static final Duration TTL = Duration.ofHours(6);
    private final AssessmentVersionRepository versions;
    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final ObjectMapper json;
    private final boolean cacheEnabled;

    public PublishedVersionStore(AssessmentVersionRepository versions,
                                 ObjectProvider<StringRedisTemplate> redisProvider,
                                 ObjectMapper json,
                                 @Value("${app.cache.published-versions.enabled:false}") boolean cacheEnabled) {
        this.versions = versions;
        this.redisProvider = redisProvider;
        this.json = json;
        this.cacheEnabled = cacheEnabled;
    }

    public AssessmentVersion get(String organizationId, String versionId) {
        StringRedisTemplate redis = cacheEnabled ? redisProvider.getIfAvailable() : null;
        String key = "jachai:assessment-version:" + organizationId + ":" + versionId;
        if (redis != null) {
            try {
                String cached = redis.opsForValue().get(key);
                if (cached != null) {
                    AssessmentVersion value = json.readValue(cached, AssessmentVersion.class);
                    if (organizationId.equals(value.organizationId()) && versionId.equals(value.id())) {
                        return value;
                    }
                }
            } catch (RuntimeException | JsonProcessingException ignored) {
                // Redis is an optimization; MongoDB remains the source of truth.
            }
        }
        AssessmentVersion value = versions.findByIdAndOrganizationId(versionId, organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Assessment version not found"));
        if (redis != null) {
            try {
                redis.opsForValue().set(key, json.writeValueAsString(value), TTL);
            } catch (RuntimeException | JsonProcessingException ignored) {
                // A cache outage must not interrupt an assessment.
            }
        }
        return value;
    }
}
