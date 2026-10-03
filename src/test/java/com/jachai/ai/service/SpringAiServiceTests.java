package com.jachai.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;

import com.jachai.shared.error.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

@ExtendWith(MockitoExtension.class)
class SpringAiServiceTests {
    @Mock ObjectProvider<GoogleGenAiChatModel> gemini;
    @Mock ObjectProvider<OllamaChatModel> ollama;
    @Mock ObjectProvider<OpenAiChatModel> groq;

    @Test
    void selectedGroqWithoutAKeyReportsUnavailableInsteadOfInvokingAnotherProvider() {
        var environment = new MockEnvironment().withProperty("app.ai.tutor-provider", "groq");
        var service = new SpringAiService(gemini, ollama, groq, environment);

        ApiException error = assertThrows(ApiException.class,
                () -> service.generate(AiUseCase.TUTOR, "system", "question"));

        assertEquals("AI_UNAVAILABLE", error.code());
        verifyNoInteractions(gemini, ollama);
    }

    @Test
    void disabledDraftProviderDoesNotFallBackToTutorProvider() {
        var environment = new MockEnvironment().withProperty("spring.ai.model.chat", "none")
                .withProperty("app.ai.tutor-provider", "groq");
        var service = new SpringAiService(gemini, ollama, groq, environment);

        assertEquals("AI_UNAVAILABLE", assertThrows(ApiException.class,
                () -> service.generate(AiUseCase.QUESTION_DRAFT, "system", "brief")).code());
        verifyNoInteractions(gemini, ollama, groq);
    }
}
