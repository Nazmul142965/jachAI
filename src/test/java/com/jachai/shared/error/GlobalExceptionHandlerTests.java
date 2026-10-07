package com.jachai.shared.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;

import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import com.jachai.shared.web.PageBoundsInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerTests {
    @Test
    void invalidInputHasStableErrorShape() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new ValidationController())
                .setControllerAdvice(new GlobalExceptionHandler()).build();

        mvc.perform(post("/validation-probe").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.name").exists());
    }

    @Test
    void oversizedPageIsRejectedBeforeTheHandlerRuns() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new ValidationController())
                .addInterceptors(new PageBoundsInterceptor())
                .setControllerAdvice(new GlobalExceptionHandler()).build();

        mvc.perform(get("/api/v1/pagination-probe?page=501"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGE"));
        mvc.perform(get("/api/v1/pagination-probe?page=500"))
                .andExpect(status().isOk());
    }

    @Test
    void invalidEnumNamesFieldAndSafeChoicesWithoutEchoingRejectedValue() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new ValidationController())
                .setControllerAdvice(new GlobalExceptionHandler()).build();

        mvc.perform(post("/validation-probe").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ok\",\"policy\":\"SECRET_TOKEN\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.policy").value("Choose one of: IMMEDIATE, MANUAL"))
                .andExpect(content().string(not(containsString("SECRET_TOKEN"))));
    }

    @Test
    void invalidParameterNamesFieldWithoutEchoingInput() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new ValidationController())
                .setControllerAdvice(new GlobalExceptionHandler()).build();

        mvc.perform(get("/method-validation-probe?page=-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.page").value("Invalid value"));
    }

    @Test
    void constraintViolationNamesOnlyTheField() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var violations = factory.getValidator().validate(new Request("", Policy.MANUAL));
            var response = new GlobalExceptionHandler()
                    .invalidParameter(new ConstraintViolationException(violations));

            org.junit.jupiter.api.Assertions.assertEquals("Invalid value", response.getBody().details().get("name"));
        }
    }

    @RestController
    static class ValidationController {
        @PostMapping("/validation-probe")
        void validate(@Valid @RequestBody Request request) { }

        @GetMapping("/api/v1/pagination-probe")
        void page(@RequestParam @Min(0) @Max(500) int page) { }

        @GetMapping("/method-validation-probe")
        void methodPage(@RequestParam @Min(0) int page) { }
    }

    enum Policy { IMMEDIATE, MANUAL }

    record Request(@NotBlank String name, Policy policy) { }
}
