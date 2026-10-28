package com.jachai.resume;

import com.jachai.auth.model.User;
import com.jachai.auth.repository.UserRepository;
import com.jachai.resume.model.ResumeScan;
import com.jachai.resume.service.*;
import com.jachai.shared.error.ApiException;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResumeScanServiceTests {
    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final UserRepository users = mock(UserRepository.class);
    private final ResumeExtractor extractor = mock(ResumeExtractor.class);
    private final ResumeChecker checker = new ResumeChecker();
    private final ResumeScanService service = new ResumeScanService(mongo, users, extractor, checker);

    @BeforeEach void account() {
        when(users.findById("owner")).thenReturn(Optional.of(new User("owner", "a@example.com", "Amina", "hash", true, false, 0, Instant.now())));
    }
    @AfterEach void close() { service.close(); }

    @Test void readsAndDeletesAlwaysIncludeOwnerAndExpiry() {
        assertThatThrownBy(() -> service.get("owner", "other-person-report")).isInstanceOf(ApiException.class)
                .hasMessageContaining("not found");
        var query = ArgumentCaptor.forClass(Query.class);
        verify(mongo).findOne(query.capture(), eq(ResumeScan.class));
        assertThat(query.getValue().getQueryObject()).containsEntry("userId", "owner").containsEntry("_id", "other-person-report").containsKey("expiresAt");
    }

    @Test void requiresAnActiveVerifiedAccountBeforeReadingFiles() {
        when(users.findById("owner")).thenReturn(Optional.of(new User("owner", "a@example.com", "Amina", "hash", false, false, 0, Instant.now())));
        assertThatThrownBy(() -> service.start("owner", UUID.randomUUID(), new byte[]{1}, "a.pdf", ""))
                .isInstanceOf(ApiException.class).hasMessageContaining("verified");
        verifyNoInteractions(mongo, extractor);
    }

    @Test void verifiedDemoCandidateCanStartAResumeCheck() {
        String userId = "demo-account-candidate";
        when(users.findById(userId)).thenReturn(Optional.of(new User(userId, "demo@example.com", "Demo", "hash", true, false, 0, Instant.now())));
        when(extractor.extract(any(), anyString())).thenReturn(new ResumeExtractor.Extracted("Demo candidate\nEducation\nSkills\nProjects\nJava 2024", 1, false, false, List.of()));
        var scan = service.start(userId, UUID.randomUUID(), new byte[]{1}, "sample.pdf", "Java");
        assertThat(scan.status()).isEqualTo("PROCESSING");
        var stored = ArgumentCaptor.forClass(ResumeScan.class);
        verify(mongo).insert(stored.capture());
        assertThat(stored.getValue().userId()).isEqualTo(userId);
        verify(mongo, timeout(3000)).updateFirst(any(Query.class), any(Update.class), eq(ResumeScan.class));
    }

    @Test void interruptedScansHaveAnHonestFailureInsteadOfPollingForever() {
        when(mongo.findOne(any(Query.class), eq(ResumeScan.class))).thenReturn(new ResumeScan("s", "owner", "hash", "a.pdf", "",
                "PROCESSING", null, null, Instant.now().minusSeconds(601), Instant.now().plusSeconds(86400)));
        var view = service.get("owner", "s");
        assertThat(view.status()).isEqualTo("FAILED");
        assertThat(view.error()).contains("interrupted");
    }

    @Test void backgroundCompletionOnlyUpdatesExistingProcessingReportAndClearsFileBytes() throws Exception {
        when(extractor.extract(any(), anyString())).thenReturn(new ResumeExtractor.Extracted("Amina Developer\nEducation\nSkills\nProjects\nJava 2024", 1, false, false, List.of()));
        byte[] bytes = {1, 2, 3};
        var accepted = service.start("owner", UUID.randomUUID(), bytes, "C:\\private\\resume.pdf", "Java");
        assertThat(accepted.status()).isEqualTo("PROCESSING");
        assertThat(accepted.fileName()).isEqualTo("resume.pdf");
        var query = ArgumentCaptor.forClass(Query.class);
        verify(mongo, timeout(3000)).updateFirst(query.capture(), any(Update.class), eq(ResumeScan.class));
        assertThat(query.getValue().getQueryObject()).containsEntry("userId", "owner").containsEntry("status", "PROCESSING").containsEntry("_id", accepted.id());
        assertThat(bytes).containsOnly((byte) 0);
        verify(mongo, never()).save(any());
    }

    @Test void requestIdCannotBeReusedForDifferentContent() {
        when(mongo.findOne(any(Query.class), eq(ResumeScan.class))).thenReturn(new ResumeScan("s", "owner", "different-hash", "a.pdf", "",
                "COMPLETED", null, null, Instant.now(), Instant.now().plusSeconds(86400)));
        assertThatThrownBy(() -> service.start("owner", UUID.randomUUID(), new byte[]{1}, "a.pdf", ""))
                .isInstanceOf(ApiException.class).hasMessageContaining("another upload");
        verifyNoInteractions(extractor);
    }
}
