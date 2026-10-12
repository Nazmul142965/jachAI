package com.jachai.shared.config;

import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.mongo.MongoClientSettingsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Keeps a couple of database connections open and replaces any that have sat idle for a while.
 *
 * Hosted databases and the network in front of them quietly drop idle connections. The next request then
 * pays for a fresh TLS handshake, and on a small free host that was the multi-second pause people saw on
 * the first sign-in after a quiet period. Pruning idle connections before they go stale, and always keeping
 * a minimum open, avoids handing a request a dead one.
 */
@Configuration
public class MongoPoolConfig {
    @Bean
    MongoClientSettingsBuilderCustomizer keepConnectionsFresh() {
        return builder -> builder.applyToConnectionPoolSettings(pool -> pool
                .minSize(2)
                .maxConnectionIdleTime(2, TimeUnit.MINUTES));
    }
}
