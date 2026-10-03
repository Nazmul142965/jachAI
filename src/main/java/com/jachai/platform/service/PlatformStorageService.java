package com.jachai.platform.service;

import com.jachai.platform.dto.PlatformStorageView;
import com.jachai.shared.error.ApiException;
import java.time.Clock;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class PlatformStorageService {
    private final PlatformAdminService platform;
    private final MongoTemplate mongo;
    private final long warningThresholdBytes;
    private final Clock clock;

    @Autowired
    public PlatformStorageService(PlatformAdminService platform, MongoTemplate mongo,
                                  @Value("${app.platform.storage-warning-bytes:400000000}") long warningThresholdBytes) {
        this(platform, mongo, warningThresholdBytes, Clock.systemUTC());
    }

    PlatformStorageService(PlatformAdminService platform, MongoTemplate mongo,
                           long warningThresholdBytes, Clock clock) {
        if (warningThresholdBytes < 1) throw new IllegalArgumentException("Storage warning threshold must be positive");
        this.platform = platform;
        this.mongo = mongo;
        this.warningThresholdBytes = warningThresholdBytes;
        this.clock = clock;
    }

    public PlatformStorageView usage(String actorUserId) {
        if (!platform.isPlatformAdmin(actorUserId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PLATFORM_ADMIN_REQUIRED",
                    "Platform administrator access required");
        }
        try {
            Document stats = mongo.executeCommand(new Document("dbStats", 1).append("scale", 1));
            long dataBytes = nonNegativeBytes(stats, "dataSize");
            long indexBytes = nonNegativeBytes(stats, "indexSize");
            long estimate = Math.addExact(dataBytes, indexBytes);
            return new PlatformStorageView(clock.instant(), dataBytes, indexBytes, estimate,
                    warningThresholdBytes, estimate >= warningThresholdBytes);
        } catch (RuntimeException exception) {
            // Driver errors may include connection details; never pass them to an API caller.
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_METRICS_UNAVAILABLE",
                    "Database storage metrics are unavailable");
        }
    }

    private long nonNegativeBytes(Document stats, String key) {
        Object value = stats == null ? null : stats.get(key);
        if (!(value instanceof Number number) || number.doubleValue() < 0
                || !Double.isFinite(number.doubleValue()) || number.doubleValue() > Long.MAX_VALUE) {
            throw new IllegalStateException("Invalid database storage metric");
        }
        return number.longValue();
    }
}
