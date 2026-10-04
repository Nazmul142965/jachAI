package com.jachai.shared.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
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

    @RestController
    static class ValidationController {
        @PostMapping("/validation-probe")
        void validate(@Valid @RequestBody Request request) { }

        @GetMapping("/api/v1/pagination-probe")
        void page(@RequestParam @Min(0) @Max(500) int page) { }
    }

    record Request(@NotBlank String name) { }
}
