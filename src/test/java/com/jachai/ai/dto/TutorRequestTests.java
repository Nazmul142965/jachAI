package com.jachai.ai.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class TutorRequestTests {
    @Test
    void questionOnlyJsonRemainsAccepted() throws Exception {
        TutorRequest request = new ObjectMapper().readValue(
                "{\"question\":\"Explain volatile\"}", TutorRequest.class);

        assertEquals("Explain volatile", request.question());
        assertTrue(request.history() == null || request.history().isEmpty());
    }

    @Test
    void historyHasBoundedNestedValidation() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var valid = new TutorRequest("Explain volatile", List.of(
                    new TutorRequest.Turn(TutorRequest.Role.LEARNER, "What is visibility?")));
            assertTrue(validator.validate(valid).isEmpty());

            var tooMany = new TutorRequest("Explain volatile", Collections.nCopies(7,
                    new TutorRequest.Turn(TutorRequest.Role.LEARNER, "Earlier question")));
            assertFalse(validator.validate(tooMany).isEmpty());

            var invalidTurn = new TutorRequest("Explain volatile", List.of(
                    new TutorRequest.Turn(null, " ")));
            assertFalse(validator.validate(invalidTurn).isEmpty());
        }
    }
}
