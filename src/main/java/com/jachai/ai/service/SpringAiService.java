package com.jachai.ai.service;

import com.jachai.shared.error.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;

@Service
@Slf4j
@RequiredArgsConstructor
public class SpringAiService implements AiService {
    private final ObjectProvider<GoogleGenAiChatModel> gemini;
    private final ObjectProvider<OllamaChatModel> ollama;
    private final ObjectProvider<OpenAiChatModel> groq;
    private final Environment environment;

    @Override
    public String generate(AiUseCase useCase, String systemInstruction, String userInput) {
        String provider = useCase == AiUseCase.QUESTION_DRAFT
                ? environment.getProperty("spring.ai.model.chat", "none")
                : environment.getProperty("app.ai.tutor-provider", "none");
        try {
            ChatModel model = switch (provider) {
                case "google-genai" -> gemini.getIfAvailable();
                case "ollama" -> ollama.getIfAvailable();
                case "groq" -> groq.getIfAvailable();
                default -> null;
            };
            if (model == null) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_NOT_CONFIGURED",
                        "AI is not configured for this environment");
            }
            String response = ChatClient.builder(model).build().prompt()
                    .system(systemInstruction).user(userInput).call().content();
            int maxLength = useCase == AiUseCase.TUTOR ? 3000 : 12000;
            if (response == null || response.isBlank() || response.length() > maxLength) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "AI_INVALID_OUTPUT", "AI returned unusable content");
            }
            return response;
        } catch (ApiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            String code = classify(exception);
            log.warn("AI request failed: provider={}, useCase={}, category={}, cause={}",
                    provider, useCase, code, exception.getClass().getSimpleName());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, code, publicMessage(code));
        }
    }

    private static String classify(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            int status = 0;
            if (cause instanceof com.google.genai.errors.ApiException google) status = google.code();
            else if (cause instanceof RestClientResponseException http) status = http.getStatusCode().value();
            if (status == 429) return "AI_PROVIDER_RATE_LIMIT";
            if (status == 402) return "AI_PROVIDER_BILLING";
            if (status == 401 || status == 403) return "AI_PROVIDER_AUTH";
            if (status == 400 || status == 404) return "AI_PROVIDER_CONFIGURATION";
            if (status == 408 || status >= 500) return "AI_PROVIDER_UNAVAILABLE";
            if (cause instanceof ResourceAccessException
                    || cause instanceof com.google.genai.errors.GenAiIOException) return "AI_PROVIDER_UNAVAILABLE";
        }
        return "AI_UNAVAILABLE";
    }

    private static String publicMessage(String code) {
        return switch (code) {
            case "AI_PROVIDER_RATE_LIMIT" -> "AI provider is limiting requests; personal practice allowance was not used";
            case "AI_PROVIDER_BILLING" -> "AI provider account billing needs operator attention";
            case "AI_PROVIDER_AUTH" -> "AI provider credentials or access need operator attention";
            case "AI_PROVIDER_CONFIGURATION" -> "AI provider model or request configuration needs operator attention";
            case "AI_PROVIDER_UNAVAILABLE" -> "AI provider timed out or is temporarily unavailable";
            default -> "AI provider could not complete the request";
        };
    }
}
