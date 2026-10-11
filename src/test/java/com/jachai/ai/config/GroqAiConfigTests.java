package com.jachai.ai.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class GroqAiConfigTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(GroqAiConfig.class)
            .withPropertyValues("app.ai.groq.base-url=https://api.groq.com/openai", "app.ai.groq.model=test-model");

    @Test
    void groqIsBuiltWhenAKeyExistsEvenIfItIsNotTheTutor() {
        runner.withPropertyValues("app.ai.tutor-provider=none", "app.ai.groq.api-key=test-key")
                .run(context -> assertThat(context).hasSingleBean(OpenAiChatModel.class));
    }

    @Test
    void groqIsNotBuiltWithoutAKeyWhenItIsNotTheTutor() {
        runner.withPropertyValues("app.ai.tutor-provider=none")
                .run(context -> assertThat(context).doesNotHaveBean(OpenAiChatModel.class));
    }

    @Test
    void groqAsTutorWithoutAKeyStillFailsStartup() {
        runner.withPropertyValues("app.ai.tutor-provider=groq")
                .run(context -> assertThat(context).hasFailed());
    }
}
