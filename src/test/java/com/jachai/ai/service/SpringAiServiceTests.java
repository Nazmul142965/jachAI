package com.jachai.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
        var environment = new MockEnvironment().withProperty("app.ai.tutor-provider", "groq")
                .withProperty("app.ai.tutor-fallback", "none");
        var service = new SpringAiService(gemini, ollama, groq, environment);

        ApiException error = assertThrows(ApiException.class,
                () -> service.generate(AiUseCase.TUTOR, "system", "question"));

        assertEquals("AI_NOT_CONFIGURED", error.code());
        verifyNoInteractions(gemini, ollama);
    }

    @Test
    void disabledDraftProviderDoesNotFallBackToTutorProvider() {
        var environment = new MockEnvironment().withProperty("spring.ai.model.chat", "none")
                .withProperty("app.ai.tutor-provider", "groq")
                .withProperty("app.ai.tutor-fallback", "none");
        var service = new SpringAiService(gemini, ollama, groq, environment);

        assertEquals("AI_NOT_CONFIGURED", assertThrows(ApiException.class,
                () -> service.generate(AiUseCase.QUESTION_DRAFT, "system", "brief")).code());
        verifyNoInteractions(gemini, ollama, groq);
    }

    @Test
    void providerInitializationFailureIsReportedAsUnavailable() {
        var environment = new MockEnvironment().withProperty("app.ai.tutor-provider", "groq")
                .withProperty("app.ai.tutor-fallback", "none");
        when(groq.getIfAvailable()).thenThrow(new IllegalStateException("provider initialization failed"));

        ApiException error = assertThrows(ApiException.class,
                () -> new SpringAiService(gemini, ollama, groq, environment)
                        .generate(AiUseCase.TUTOR, "system", "question"));

        assertEquals("AI_UNAVAILABLE", error.code());
        verifyNoInteractions(gemini, ollama);
    }

    @Test
    void provider429IsNotReportedAsPersonalPracticeQuota() {
        var environment = new MockEnvironment().withProperty("spring.ai.model.chat", "google-genai")
                .withProperty("app.ai.question-fallback", "none");
        when(gemini.getIfAvailable()).thenThrow(
                new com.google.genai.errors.ClientException(429, "RESOURCE_EXHAUSTED", "private provider detail"));
        ApiException error = assertThrows(ApiException.class,
                () -> new SpringAiService(gemini, ollama, groq, environment)
                        .generate(AiUseCase.QUESTION_DRAFT, "system", "question"));
        assertEquals("AI_PROVIDER_RATE_LIMIT", error.code());
        org.junit.jupiter.api.Assertions.assertFalse(error.getMessage().contains("private provider detail"));
    }

    @Test
    void providerAuthErrorKeepsProviderDetailPrivate() {
        var environment = new MockEnvironment().withProperty("app.ai.tutor-provider", "groq")
                .withProperty("app.ai.tutor-fallback", "none");
        when(groq.getIfAvailable()).thenThrow(org.springframework.web.client.HttpClientErrorException.create(
                org.springframework.http.HttpStatus.UNAUTHORIZED, "Unauthorized",
                org.springframework.http.HttpHeaders.EMPTY, "private key detail".getBytes(), null));
        ApiException error = assertThrows(ApiException.class,
                () -> new SpringAiService(gemini, ollama, groq, environment)
                        .generate(AiUseCase.TUTOR, "system", "question"));
        assertEquals("AI_PROVIDER_AUTH", error.code());
        org.junit.jupiter.api.Assertions.assertFalse(error.getMessage().contains("private key detail"));
    }

    @Test
    void aFailingFirstProviderIsReplacedByTheSecondAndTheCallerStillGetsOneAnswer() {
        var environment = new MockEnvironment().withProperty("spring.ai.model.chat", "google-genai");
        var geminiModel = org.mockito.Mockito.mock(GoogleGenAiChatModel.class);
        var groqModel = org.mockito.Mockito.mock(OpenAiChatModel.class);
        when(gemini.getIfAvailable()).thenReturn(geminiModel);
        when(groq.getIfAvailable()).thenReturn(groqModel);
        when(geminiModel.call(org.mockito.ArgumentMatchers.any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenThrow(new com.google.genai.errors.ClientException(429, "RESOURCE_EXHAUSTED", "busy"));
        when(groqModel.call(org.mockito.ArgumentMatchers.any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(new org.springframework.ai.chat.model.ChatResponse(java.util.List.of(
                        new org.springframework.ai.chat.model.Generation(
                                new org.springframework.ai.chat.messages.AssistantMessage("from groq")))));

        String answer = new SpringAiService(gemini, ollama, groq, environment)
                .generate(AiUseCase.QUESTION_DRAFT, "system", "brief");

        assertEquals("from groq", answer);
    }

    @Test
    void whenBothProvidersFailTheUserGetsTheLastProvidersError() {
        var environment = new MockEnvironment().withProperty("spring.ai.model.chat", "google-genai");
        var geminiModel = org.mockito.Mockito.mock(GoogleGenAiChatModel.class);
        var groqModel = org.mockito.Mockito.mock(OpenAiChatModel.class);
        when(gemini.getIfAvailable()).thenReturn(geminiModel);
        when(groq.getIfAvailable()).thenReturn(groqModel);
        when(geminiModel.call(org.mockito.ArgumentMatchers.any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenThrow(new com.google.genai.errors.ClientException(429, "RESOURCE_EXHAUSTED", "busy"));
        when(groqModel.call(org.mockito.ArgumentMatchers.any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenThrow(org.springframework.web.client.HttpClientErrorException.create(
                        org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "slow down",
                        org.springframework.http.HttpHeaders.EMPTY, new byte[0], null));

        ApiException error = assertThrows(ApiException.class,
                () -> new SpringAiService(gemini, ollama, groq, environment)
                        .generate(AiUseCase.QUESTION_DRAFT, "system", "brief"));

        assertEquals("AI_PROVIDER_RATE_LIMIT", error.code());
    }

    @Test
    void withNoSecondProviderConfiguredTheFirstErrorIsReportedAsBefore() {
        var environment = new MockEnvironment().withProperty("spring.ai.model.chat", "google-genai");
        var geminiModel = org.mockito.Mockito.mock(GoogleGenAiChatModel.class);
        when(gemini.getIfAvailable()).thenReturn(geminiModel);
        when(geminiModel.call(org.mockito.ArgumentMatchers.any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenThrow(new com.google.genai.errors.ClientException(429, "RESOURCE_EXHAUSTED", "busy"));

        ApiException error = assertThrows(ApiException.class,
                () -> new SpringAiService(gemini, ollama, groq, environment)
                        .generate(AiUseCase.QUESTION_DRAFT, "system", "brief"));

        assertEquals("AI_PROVIDER_RATE_LIMIT", error.code());
    }
}
