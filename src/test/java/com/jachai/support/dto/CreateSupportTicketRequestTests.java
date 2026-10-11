package com.jachai.support.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.jachai.support.type.TicketCategory;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

class CreateSupportTicketRequestTests {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void aCompleteRequestValidates() {
        var request = new CreateSupportTicketRequest(TicketCategory.TECHNICAL, "Help", "Something broke", null);
        // @NotBlank on the enum made the validator itself throw, so every ticket was a 500.
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void aMissingCategoryIsAValidationError() {
        var request = new CreateSupportTicketRequest(null, "Help", "Something broke", null);
        assertThat(validator.validate(request)).hasSize(1);
    }
}
