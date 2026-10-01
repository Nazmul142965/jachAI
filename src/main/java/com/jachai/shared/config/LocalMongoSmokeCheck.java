package com.jachai.shared.config;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import java.util.UUID;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("local & mongo-smoke")
public class LocalMongoSmokeCheck implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(LocalMongoSmokeCheck.class);
    private static final String COLLECTION = "foundation_smoke_checks";
    private final MongoTemplate mongoTemplate;

    public LocalMongoSmokeCheck(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        MongoCollection<Document> collection = mongoTemplate.getCollection(COLLECTION);
        String id = UUID.randomUUID().toString();
        try {
            collection.insertOne(new Document("_id", id).append("value", "phase1"));
            Document readBack = collection.find(Filters.eq("_id", id)).first();
            if (readBack == null || !"phase1".equals(readBack.getString("value"))) {
                throw new IllegalStateException("MongoDB save/read check failed");
            }
            log.info("MongoDB save/read check passed");
        } finally {
            collection.deleteOne(Filters.eq("_id", id));
        }
    }
}
