package com.jachai.practice.service;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class PracticeCatalog {
    public record Option(String id, String text) { }
    public record Question(String id, String topic, String prompt, List<Option> options,
                           String correctOptionId, String explanation) {
        public QuestionView publicView() { return new QuestionView(id, topic, prompt, options); }
    }
    public record QuestionView(String id, String topic, String prompt, List<Option> options) { }

    // Application-owned starter content; tenant question banks are never reused for personal practice.
    private static final List<Question> QUESTIONS = List.of(
            new Question("java-record", "Java", "Which statement about a Java record is correct?",
                    List.of(new Option("a", "It cannot implement interfaces"),
                            new Option("b", "Its components generate accessors and a canonical constructor"),
                            new Option("c", "Its components are mutable by default")), "b",
                    "Records generate component accessors, a canonical constructor, equals, hashCode and toString."),
            new Question("java-optional", "Java", "What does Optional.orElseGet accept?",
                    List.of(new Option("a", "A Supplier evaluated only when empty"),
                            new Option("b", "A value always computed eagerly"),
                            new Option("c", "A Runnable")), "a",
                    "orElseGet takes a Supplier and invokes it only when the Optional is empty."),
            new Question("spring-transaction", "Spring", "What is the default rollback behavior of @Transactional?",
                    List.of(new Option("a", "Rollback for every checked exception"),
                            new Option("b", "Rollback for RuntimeException and Error"),
                            new Option("c", "Never roll back automatically")), "b",
                    "By default Spring rolls back on unchecked exceptions and Error, not checked exceptions."),
            new Question("http-idempotent", "Web", "Which HTTP method is defined to be idempotent?",
                    List.of(new Option("a", "POST"), new Option("b", "PATCH"),
                            new Option("c", "PUT")), "c",
                    "Repeating a PUT request has the same intended effect as making it once."),
            new Question("mongo-index", "MongoDB", "What does a unique index enforce?",
                    List.of(new Option("a", "No two indexed entries have the same indexed value"),
                            new Option("b", "Documents are always returned sorted"),
                            new Option("c", "Documents never expire")), "a",
                    "A unique index rejects duplicate indexed key values."));

    public List<QuestionView> questions() { return QUESTIONS.stream().map(Question::publicView).toList(); }
    public Question find(String id) {
        return QUESTIONS.stream().filter(question -> question.id().equals(id)).findFirst().orElse(null);
    }
}
