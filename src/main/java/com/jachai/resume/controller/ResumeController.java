package com.jachai.resume.controller;

import com.jachai.resume.service.ResumeScanService;
import com.jachai.shared.error.ApiException;
import com.jachai.shared.security.RateLimitPolicy;
import com.jachai.shared.security.RateLimitService;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/v1/resume/scans")
public class ResumeController {
    private final ResumeScanService scans;
    private final RateLimitService limits;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ResumeScanService.View start(@AuthenticationPrincipal Jwt jwt,
            @RequestParam UUID requestId, @RequestParam MultipartFile file,
            @RequestParam(defaultValue = "") @Size(max = 12000) String jobDescription) throws IOException {
        limits.consume(RateLimitPolicy.RESUME_SCAN_ACTOR, jwt.getSubject());
        if (file.getSize() > com.jachai.resume.service.ResumeExtractor.MAX_BYTES)
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "RESUME_UPLOAD_SIZE", "Upload a PDF or DOCX no larger than 5 MB.");
        return scans.start(jwt.getSubject(), requestId, file.getBytes(), file.getOriginalFilename(), jobDescription);
    }

    @GetMapping
    public Page<ResumeScanService.Summary> history(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") @Min(0) @Max(500) int page) {
        return scans.history(jwt.getSubject(), page);
    }

    @GetMapping("/{id}")
    public ResumeScanService.View get(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return scans.get(jwt.getSubject(), id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        scans.delete(jwt.getSubject(), id);
    }
}
