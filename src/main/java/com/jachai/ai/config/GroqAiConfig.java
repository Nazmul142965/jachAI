package com.jachai.ai.config;

import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;

@Configuration
public class GroqAiConfig {
    @Bean
    @ConditionalOnProperty(name = "app.ai.tutor-provider", havingValue = "groq")
    @Conditional(GroqKeyConfigured.class)
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

    static final class GroqKeyConfigured implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String key = context.getEnvironment().getProperty("app.ai.groq.api-key", "");
            return !key.isBlank();
        }
    }
}
