package com.jachai.resume.service;

import com.jachai.auth.repository.UserRepository;
import com.jachai.resume.model.ResumeScan;
import com.jachai.shared.error.ApiException;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ResumeScanService {
    private final MongoTemplate mongo;
    private final UserRepository users;
    private final ResumeExtractor extractor;
    private final ResumeChecker checker;
    // At most three 5 MB files are held transiently. Files are never retained in the database.
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(2), task -> {
                Thread thread = new Thread(task, "resume-scan"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    public ResumeScanService(MongoTemplate mongo, UserRepository users, ResumeExtractor extractor, ResumeChecker checker) {
        this.mongo = mongo; this.users = users; this.extractor = extractor; this.checker = checker;
    }

    public record View(String id, String fileName, String status, ResumeChecker.Report report,
                       String error, Instant createdAt, Instant expiresAt) { }
    public record Summary(String id, String fileName, String status, Integer readinessScore,
                          Integer jobMatchScore, Instant createdAt) { }

    public View start(String userId, UUID requestId, byte[] bytes, String fileName, String jd) {
        requireAccount(userId);
        if (bytes.length == 0 || bytes.length > ResumeExtractor.MAX_BYTES)
            throw new ApiException(HttpStatus.BAD_REQUEST, "RESUME_UPLOAD_SIZE", "Upload a PDF or DOCX no larger than 5 MB.");
        jd = jd == null ? "" : jd.strip();
        if (jd.length() > 12_000) throw new ApiException(HttpStatus.BAD_REQUEST, "RESUME_JOB_TOO_LONG", "Job description must be at most 12000 characters.");
        String name = fileName == null ? "resume" : fileName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "");
        if (name.length() > 120) name = name.substring(name.length() - 120);
        String id = UUID.nameUUIDFromBytes((userId + ":resume:" + requestId).getBytes(StandardCharsets.UTF_8)).toString();
        String fingerprint = fingerprint(bytes, jd);
        ResumeScan existing = mongo.findOne(owned(userId, id), ResumeScan.class);
        if (existing != null) return replay(existing, fingerprint);
        Instant now = Instant.now();
        ResumeScan scan = new ResumeScan(id, userId, fingerprint, name, jd, "PROCESSING", null, null,
                now, now.plus(Duration.ofDays(30)));
        try { mongo.insert(scan); }
        catch (DuplicateKeyException e) {
            ResumeScan raced = mongo.findOne(owned(userId, id), ResumeScan.class);
            if (raced == null) throw new ApiException(HttpStatus.CONFLICT, "RESUME_REQUEST_EXPIRED", "Use a new scan request and try again.");
            return replay(raced, fingerprint);
        }
        try { worker.execute(() -> process(scan, bytes)); }
        catch (RejectedExecutionException e) {
            mongo.remove(owned(userId, id), ResumeScan.class);
            Arrays.fill(bytes, (byte) 0);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "RESUME_BUSY", "The resume checker is busy. Try again shortly.");
        }
        return view(scan);
    }

    private View replay(ResumeScan scan, String fingerprint) {
        if (!scan.fingerprint().equals(fingerprint))
            throw new ApiException(HttpStatus.CONFLICT, "RESUME_REQUEST_REUSED", "This scan request belongs to another upload. Start a new scan.");
        return view(scan);
    }

    private void process(ResumeScan scan, byte[] bytes) {
        Update update;
        try {
            var report = checker.check(extractor.extract(bytes, scan.fileName()), scan.jobDescription());
            update = new Update().set("status", "COMPLETED").set("report", report).unset("error");
        } catch (Exception e) {
            String message = e instanceof ApiException ? e.getMessage()
                    : "The resume could not be checked. Try uploading a simpler PDF or DOCX.";
            update = new Update().set("status", "FAILED").set("error", message);
        } finally { Arrays.fill(bytes, (byte) 0); }
        // An in-flight scan must never recreate a report the owner deleted.
        mongo.updateFirst(owned(scan.userId(), scan.id()).addCriteria(Criteria.where("status").is("PROCESSING")),
                update, ResumeScan.class);
    }

    public View get(String userId, String id) {
        requireAccount(userId);
        ResumeScan scan = mongo.findOne(owned(userId, id), ResumeScan.class);
        if (scan == null) throw missing();
        return view(scan);
    }

    public Page<Summary> history(String userId, int page) {
        requireAccount(userId);
        Query query = Query.query(Criteria.where("userId").is(userId).and("expiresAt").gt(Instant.now()));
        long count = mongo.count(query, ResumeScan.class);
        var pageable = PageRequest.of(page, 10, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("_id")));
        query.with(pageable);
        query.fields().exclude("jobDescription").exclude("report.extractedText").exclude("report.issues")
                .exclude("report.breakdown").exclude("report.warnings").exclude("report.matchedSkills").exclude("report.missingSkills");
        var rows = mongo.find(query, ResumeScan.class).stream().map(scan -> new Summary(scan.id(), scan.fileName(),
                effectiveStatus(scan), scan.report() == null ? null : scan.report().readinessScore(),
                scan.report() == null ? null : scan.report().jobMatchScore(), scan.createdAt())).toList();
        return new PageImpl<>(rows, pageable, count);
    }

    public void delete(String userId, String id) {
        requireAccount(userId);
        if (mongo.remove(owned(userId, id), ResumeScan.class).getDeletedCount() == 0) throw missing();
        mongo.remove(owned(userId, id), com.jachai.resume.model.ResumeReview.class);
    }

    public record ReviewInput(String text, String jobDescription, Instant expiresAt) { }

    public ReviewInput reviewInput(String userId, String id) {
        requireAccount(userId);
        ResumeScan scan = mongo.findOne(owned(userId, id), ResumeScan.class);
        if (scan == null) throw missing();
        if (!"COMPLETED".equals(scan.status()) || scan.report() == null)
            throw new ApiException(HttpStatus.CONFLICT, "RESUME_NOT_READY", "Wait for the resume scan to finish before requesting a content review.");
        if (scan.report().extractedText().length() > 40000)
            throw new ApiException(HttpStatus.BAD_REQUEST, "RESUME_REVIEW_TOO_LONG", "For a detailed review, upload a resume with at most 40000 extracted characters.");
        return new ReviewInput(scan.report().extractedText(), scan.jobDescription(), scan.expiresAt());
    }

    private View view(ResumeScan scan) {
        String status = effectiveStatus(scan);
        return new View(scan.id(), scan.fileName(), status, scan.report(),
                status.equals("FAILED") && scan.error() == null
                        ? "Processing was interrupted or took too long. Upload the resume again." : scan.error(),
                scan.createdAt(), scan.expiresAt());
    }

    private String effectiveStatus(ResumeScan scan) {
        return scan.status().equals("PROCESSING") && scan.createdAt().plusSeconds(600).isBefore(Instant.now())
                ? "FAILED" : scan.status();
    }

    private Query owned(String userId, String id) {
        return Query.query(Criteria.where("_id").is(id).and("userId").is(userId).and("expiresAt").gt(Instant.now()));
    }

    private void requireAccount(String userId) {
        var user = users.findById(userId).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "ACCOUNT_REQUIRED", "Sign in to check a resume."));
        if (user.disabled() || !user.emailVerified())
            throw new ApiException(HttpStatus.FORBIDDEN, "RESUME_ACCOUNT_UNAVAILABLE", "Use an active, verified account to check a resume.");
    }

    private ApiException missing() {
        return new ApiException(HttpStatus.NOT_FOUND, "RESUME_NOT_FOUND", "This resume report was not found or has expired.");
    }

    private String fingerprint(byte[] bytes, String jd) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(bytes); digest.update((byte) 0); digest.update(jd.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    @PreDestroy
    public void close() { worker.shutdownNow(); }
}
