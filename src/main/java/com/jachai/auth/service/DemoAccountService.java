package com.jachai.auth.service;

import com.jachai.auth.dto.AuthResponse;
import com.jachai.auth.model.User;
import com.jachai.auth.repository.UserRepository;
import com.jachai.organization.model.Membership;
import com.jachai.organization.model.Organization;
import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.model.OrganizationType;
import com.jachai.organization.repository.MembershipRepository;
import com.jachai.organization.repository.OrganizationRepository;
import com.jachai.shared.error.ApiException;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Disposable local demo identities; no passwords or public credentials are stored. */
@Service
@Profile("demo & !prod")
@Slf4j
public class DemoAccountService {
    public record DemoAccount(String key, String label, String role, String workspace) { }

    private static final String ACADEMY_ID = "demo-org-academy";
    private static final String TALENT_ID = "demo-org-talent";
    private static final List<DemoAccount> ACCOUNTS = List.of(
            new DemoAccount("academy-admin", "Academy owner", "ORG_ADMIN", "Academy"),
            new DemoAccount("instructor", "Instructor", "INSTRUCTOR", "Academy"),
            new DemoAccount("learner", "Learner", "LEARNER", "Academy"),
            new DemoAccount("talent-admin", "Talent owner", "ORG_ADMIN", "Talent"),
            new DemoAccount("recruiter", "Recruiter", "RECRUITER", "Talent"),
            new DemoAccount("candidate", "Candidate", "CANDIDATE", "Talent"));

    private final MongoTemplate mongo;
    private final UserRepository users;
    private final OrganizationRepository organizations;
    private final MembershipRepository memberships;
    private final AuthService auth;
    private final DemoFixtureService fixtures;

    public DemoAccountService(MongoTemplate mongo, UserRepository users,
                              OrganizationRepository organizations, MembershipRepository memberships,
                              AuthService auth, DemoFixtureService fixtures) {
        this.mongo = mongo;
        this.users = users;
        this.organizations = organizations;
        this.memberships = memberships;
        this.auth = auth;
        this.fixtures = fixtures;
    }

    public List<DemoAccount> accounts() {
        requireDemoDatabase();
        return ACCOUNTS;
    }

    public synchronized AuthResponse login(String key) {
        requireDemoDatabase();
        DemoAccount account = ACCOUNTS.stream().filter(value -> value.key().equals(key)).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "DEMO_ROLE_NOT_FOUND", "Demo role not found"));
        Instant now = Instant.now();
        for (DemoAccount entry : ACCOUNTS) {
            String id = userId(entry);
            insertIfMissing(users.findById(id).isPresent(),
                    new User(id, entry.key() + "@demo.jachai.invalid", entry.label(), null,
                            true, false, 0, now));
        }
        insertIfMissing(organizations.findById(ACADEMY_ID).isPresent(),
                new Organization(ACADEMY_ID, "Demo Academy", OrganizationType.ACADEMY,
                        userId(ACCOUNTS.get(0)), now));
        insertIfMissing(organizations.findById(TALENT_ID).isPresent(),
                new Organization(TALENT_ID, "Demo Talent", OrganizationType.TALENT,
                        userId(ACCOUNTS.get(3)), now));
        for (DemoAccount entry : ACCOUNTS) {
            String id = "demo-member-" + entry.key();
            insertIfMissing(memberships.findById(id).isPresent(),
                    new Membership(id, userId(entry), organizationId(entry),
                            OrganizationRole.valueOf(entry.role()), true, now));
        }
        User user = users.findById(userId(account)).orElseThrow(() ->
                new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DEMO_ACCOUNT_UNAVAILABLE", "Demo account unavailable"));
        if (!user.emailVerified() || user.disabled() || !user.email().endsWith("@demo.jachai.invalid")) {
            throw new ApiException(HttpStatus.CONFLICT, "DEMO_ACCOUNT_CHANGED", "Demo account changed; use a fresh demo database");
        }
        try {
            fixtures.seed();
        } catch (RuntimeException exception) {
            // Disposable example data must never lock users out of their demo accounts.
            log.warn("Demo example setup incomplete: {}", exception.getClass().getSimpleName());
        }
        return auth.issueSession(user);
    }

    private void requireDemoDatabase() {
        if (!mongo.getDb().getName().startsWith("jachai_demo_")) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DEMO_DATABASE_REQUIRED",
                    "Demo login requires a dedicated jachai_demo_* database");
        }
    }

    private void insertIfMissing(boolean present, Object document) {
        if (!present) {
            try { mongo.insert(document); }
            catch (DuplicateKeyException ignored) { /* Another local request inserted it first. */ }
        }
    }

    private static String userId(DemoAccount account) { return "demo-account-" + account.key(); }
    private static String organizationId(DemoAccount account) {
        return account.workspace().equals("Academy") ? ACADEMY_ID : TALENT_ID;
    }
}
