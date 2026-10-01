package com.jachai.ai.config;

import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GroqAiConfig {
    @Bean
    @ConditionalOnProperty(name = "app.ai.tutor-provider", havingValue = "groq")
    OpenAiChatModel groqTutorModel(@Value("${app.ai.groq.api-key:}") String apiKey,
                                  @Value("${app.ai.groq.base-url}") String baseUrl,
                                  @Value("${app.ai.groq.model}") String model) {
        if (apiKey.isBlank()) {
            throw new IllegalStateException("GROQ_API_KEY is required when AI_TUTOR_PROVIDER=groq");
        }
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey).build();
        return OpenAiChatModel.builder().openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(model).maxCompletionTokens(600).build())
                .build();
    }
}
