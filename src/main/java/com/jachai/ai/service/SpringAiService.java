package com.jachai.ai.service;

import com.jachai.shared.error.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;

@Service
@Slf4j
public class SpringAiService implements AiService {
    /** After this many failures in a row a provider is skipped for {@link #COOLDOWN}. */
    private static final int FAILURES_BEFORE_SKIP = 3;
    private static final Duration COOLDOWN = Duration.ofMinutes(2);
    private static final ExecutorService CALLS = Executors.newVirtualThreadPerTaskExecutor();

    private final ObjectProvider<GoogleGenAiChatModel> gemini;
    private final ObjectProvider<OllamaChatModel> ollama;
    private final ObjectProvider<OpenAiChatModel> groq;
    private final Environment environment;
    private final Map<String, Health> health = new ConcurrentHashMap<>();

    public SpringAiService(ObjectProvider<GoogleGenAiChatModel> gemini, ObjectProvider<OllamaChatModel> ollama,
                           ObjectProvider<OpenAiChatModel> groq, Environment environment) {
        this.gemini = gemini;
        this.ollama = ollama;
        this.groq = groq;
        this.environment = environment;
    }

    private static final class Health {
        final AtomicInteger failures = new AtomicInteger();
        volatile Instant skipUntil = Instant.EPOCH;
    }

    /**
     * Asks the provider chosen for this use case. When a second provider is available and the first one
     * fails or is too slow, the same request goes to the second one, so a Gemini outage or a rate limit
     * does not turn into an error for the user. The caller still sees one call, so a quota is used once.
     */
    @Override
    public String generate(AiUseCase useCase, String systemInstruction, String userInput) {
        boolean draft = useCase == AiUseCase.QUESTION_DRAFT;
        String primary = draft
                ? environment.getProperty("spring.ai.model.chat", "none")
                : environment.getProperty("app.ai.tutor-provider", "none");
        if (modelFor(primary, useCase) == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_NOT_CONFIGURED",
                    "AI is not turned on for this site yet.");
        }
        String fallback = fallbackFor(primary, environment.getProperty(
                draft ? "app.ai.question-fallback" : "app.ai.tutor-fallback", "auto"));
        List<String> chain = new ArrayList<>(List.of(primary));
        if (fallback != null) chain.add(fallback);

        ApiException lastFailure = null;
        for (int index = 0; index < chain.size(); index++) {
            String provider = chain.get(index);
            boolean hasNext = index < chain.size() - 1;
            if (hasNext && isSkipped(provider)) {
                log.warn("AI provider {} skipped after repeated failures; using {}", provider, chain.get(index + 1));
                continue;
            }
            try {
                String response = attempt(provider, useCase, systemInstruction, userInput, hasNext);
                recordSuccess(provider);
                if (index > 0) {
                    log.warn("AI request served by fallback provider {} (primary {} failed)", provider, primary);
                }
                return response;
            } catch (ApiException failure) {
                recordFailure(provider);
                lastFailure = failure;
                if (hasNext) {
                    log.warn("AI provider {} failed ({}); trying {}", provider, failure.code(), chain.get(index + 1));
                }
            }
        }
        throw lastFailure != null ? lastFailure : new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "AI_UNAVAILABLE", publicMessage("AI_UNAVAILABLE"));
    }

    private String attempt(String provider, AiUseCase useCase, String system, String user, boolean timed) {
        try {
            ChatModel model = modelFor(provider, useCase);
            if (model == null) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_NOT_CONFIGURED",
                        "AI is not turned on for this site yet.");
            }
            String response = timed ? callWithDeadline(model, useCase, system, user) : call(model, useCase, system, user);
            int maxLength = useCase == AiUseCase.TUTOR || useCase == AiUseCase.ASSISTANT ? 3000 : 12000;
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

    private String call(ChatModel model, AiUseCase useCase, String system, String user) {
        var prompt = ChatClient.builder(model).build().prompt().system(system).user(user);
        if (model instanceof OpenAiChatModel && useCase == AiUseCase.QUESTION_DRAFT) {
            // The Groq model is configured with a short limit for tutoring; a drafted question needs more room.
            prompt = prompt.options(OpenAiChatOptions.builder().maxCompletionTokens(3000).build());
        }
        return prompt.call().content();
    }

    /** Gives up on a slow first provider so the second one still has time to answer. */
    private String callWithDeadline(ChatModel model, AiUseCase useCase, String system, String user) {
        long seconds = Math.max(5, environment.getProperty("app.ai.primary-timeout-seconds", Long.class, 25L));
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> call(model, useCase, system, user), CALLS);
        try {
            return future.get(seconds, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_PROVIDER_UNAVAILABLE",
                    publicMessage("AI_PROVIDER_UNAVAILABLE"));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE", publicMessage("AI_UNAVAILABLE"));
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(failed.getCause());
        }
    }

    private ChatModel modelFor(String provider, AiUseCase useCase) {
        try {
            return switch (provider) {
                case "google-genai" -> gemini.getIfAvailable();
                case "ollama" -> ollama.getIfAvailable();
                case "groq" -> groq.getIfAvailable();
                default -> null;
            };
        } catch (RuntimeException exception) {
            String code = classify(exception);
            log.warn("AI provider unavailable: provider={}, useCase={}, category={}, cause={}",
                    provider, useCase, code, exception.getClass().getSimpleName());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, code, publicMessage(code));
        }
    }

    /** "none" turns it off; "auto" picks the other hosted provider if it is set up; a name picks that one. */
    private String fallbackFor(String primary, String setting) {
        String wanted = setting == null ? "auto" : setting.trim().toLowerCase();
        if (wanted.isEmpty() || wanted.equals("none")) return null;
        List<String> candidates = wanted.equals("auto") ? List.of("google-genai", "groq") : List.of(wanted);
        for (String candidate : candidates) {
            if (candidate.equals(primary)) continue;
            try {
                if (modelFor(candidate, AiUseCase.QUESTION_DRAFT) != null) return candidate;
            } catch (ApiException unavailable) {
                // not usable as a fallback; try the next one
            }
        }
        return null;
    }

    private boolean isSkipped(String provider) {
        Health state = health.get(provider);
        return state != null && state.failures.get() >= FAILURES_BEFORE_SKIP
                && Instant.now().isBefore(state.skipUntil);
    }

    private void recordFailure(String provider) {
        Health state = health.computeIfAbsent(provider, key -> new Health());
        if (state.failures.incrementAndGet() >= FAILURES_BEFORE_SKIP) {
            state.skipUntil = Instant.now().plus(COOLDOWN);
        }
    }

    private void recordSuccess(String provider) {
        Health state = health.get(provider);
        if (state != null) state.failures.set(0);
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
