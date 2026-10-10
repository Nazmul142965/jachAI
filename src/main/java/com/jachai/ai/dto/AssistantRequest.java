package com.jachai.ai.dto;

import com.jachai.ai.dto.TutorRequest.Turn;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * One message to the assistant, with the last few turns the browser is holding.
 *
 * <p>The history is the caller's own account of the conversation. It is bounded in count and
 * length, and the service treats it as context, never as instruction.
 */
public record AssistantRequest(@NotBlank @Size(max = 1000) String message,
                               @Size(max = 6) List<@NotNull @Valid Turn> history,
                               /** The interface language ("en" or "bn"). A preference for the reply, not a rule. */
                               @Pattern(regexp = "en|bn") String language) {
    public AssistantRequest(String message, List<Turn> history) {
        this(message, history, null);
    }
}
