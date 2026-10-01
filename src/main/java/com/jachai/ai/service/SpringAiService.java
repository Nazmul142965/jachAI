package com.jachai.ai.service;

import com.jachai.shared.error.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
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
        ChatModel model = switch (provider) {
            case "google-genai" -> gemini.getIfAvailable();
            case "ollama" -> ollama.getIfAvailable();
            case "groq" -> groq.getIfAvailable();
            default -> null;
        };
        if (model == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE",
                    "AI is not configured for this environment");
        }
        try {
            String response = ChatClient.builder(model).build().prompt()
                    .system(systemInstruction).user(userInput).call().content();
            if (response == null || response.isBlank() || response.length() > 12000) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "AI_INVALID_OUTPUT", "AI returned unusable content");
            }
            return response;
        } catch (ApiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE",
                    "AI provider is temporarily unavailable");
        }
    }
}
