package com.jachai.shared.health;

import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The cheap "is it up" check that uptime monitors call every few minutes.
 *
 * It always answers UP, so a database blip can never make a platform health check restart the app. But it also
 * touches the database now and then. On a small free host the connection to the database goes cold when nobody
 * has used the app for a while, and the next person to sign in paid for reconnecting. A monitor that calls this
 * keeps that connection warm, which is what makes the first sign-in after a quiet period fast.
 */
@RestController
@RequestMapping("/api/v1/health")
@Slf4j
public class HealthController {
    /** No more often than this, however many callers there are. */
    private static final long WARM_EVERY_MILLIS = 30_000;

    private final ObjectProvider<MongoTemplate> mongo;
    private final AtomicLong lastWarm = new AtomicLong();

    public HealthController(ObjectProvider<MongoTemplate> mongo) {
        this.mongo = mongo;
    }

    @GetMapping
    public HealthResponse health() {
        warmDatabase();
        return new HealthResponse("UP");
    }

    private void warmDatabase() {
        long now = System.currentTimeMillis();
        long last = lastWarm.get();
        if (now - last < WARM_EVERY_MILLIS || !lastWarm.compareAndSet(last, now)) return;
        try {
            MongoTemplate template = mongo.getIfAvailable();
            if (template != null) template.getDb().runCommand(new Document("ping", 1));
        } catch (RuntimeException exception) {
            log.warn("Database warm-up ping failed: {}", exception.getClass().getSimpleName());
        }
    }

    public record HealthResponse(String status) { }
}
