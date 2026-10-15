package com.jachai.practice.service;

import com.jachai.practice.model.PersonalPracticeQuestion;
import com.jachai.practice.repository.PersonalPracticeQuestionRepository;
import java.util.List;
import java.util.ArrayList;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PracticeCatalog {
    private final PersonalPracticeQuestionRepository personalQuestions;
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
            new Question("java-stream", "Java", "Which Stream operation starts processing elements?",
                    List.of(new Option("a", "map"), new Option("b", "filter"),
                            new Option("c", "collect")), "c",
                    "collect is a terminal operation; map and filter are intermediate."),
            new Question("spring-transaction", "Spring", "What is the default rollback behavior of @Transactional?",
                    List.of(new Option("a", "Rollback for every checked exception"),
                            new Option("b", "Rollback for RuntimeException and Error"),
                            new Option("c", "Never roll back automatically")), "b",
                    "By default Spring rolls back on unchecked exceptions and Error, not checked exceptions."),
            new Question("spring-component", "Spring", "What does @Component do?",
                    List.of(new Option("a", "Marks a class for component scanning"),
                            new Option("b", "Starts a database transaction"),
                            new Option("c", "Makes every method public")), "a",
                    "Component scanning can discover a class marked with @Component as a bean."),
            new Question("spring-injection", "Spring", "Why use constructor injection?",
                    List.of(new Option("a", "Dependencies become explicit and available at construction"),
                            new Option("b", "It avoids creating an object"),
                            new Option("c", "It makes fields globally shared")), "a",
                    "The constructor states the required dependencies before the object is used."),
            new Question("http-idempotent", "Web", "Which HTTP method is defined to be idempotent?",
                    List.of(new Option("a", "POST"), new Option("b", "PATCH"),
                            new Option("c", "PUT")), "c",
                    "Repeating a PUT request has the same intended effect as making it once."),
            new Question("http-not-found", "Web", "What does HTTP 404 mean?",
                    List.of(new Option("a", "The request was accepted for later processing"),
                            new Option("b", "The requested resource was not found"),
                            new Option("c", "The server has no network connection")), "b",
                    "404 says the server did not find the requested resource."),
            new Question("http-created", "Web", "Which status best describes a newly created resource?",
                    List.of(new Option("a", "201 Created"), new Option("b", "204 No Content"),
                            new Option("c", "304 Not Modified")), "a",
                    "201 Created reports that the request created a resource."),
            new Question("mongo-index", "MongoDB", "What does a unique index enforce?",
                    List.of(new Option("a", "No two indexed entries have the same indexed value"),
                            new Option("b", "Documents are always returned sorted"),
                            new Option("c", "Documents never expire")), "a",
                    "A unique index rejects duplicate indexed key values."),
            new Question("mongo-ttl", "MongoDB", "What is a TTL index used for?",
                    List.of(new Option("a", "Automatically removing documents after a time"),
                            new Option("b", "Encrypting every field"),
                            new Option("c", "Joining two collections")), "a",
                    "A TTL index lets MongoDB expire documents after the indexed time."),
            new Question("mongo-id", "MongoDB", "Which field uniquely identifies a MongoDB document?",
                    List.of(new Option("a", "createdAt"), new Option("b", "_id"),
                            new Option("c", "collection")), "b",
                    "The _id field is the document's unique identifier."));

    public record PathView(String id, String title, int questionCount) { }

    public List<PathView> paths() {
        return java.util.stream.Stream.concat(
                java.util.stream.Stream.of(new PathView("baseline", "Starting skill check", 4)),
                topicPaths().stream()).toList();
    }

    private List<PathView> topicPaths() {
        return List.of("Java", "Spring", "Web", "MongoDB").stream()
                .map(topic -> new PathView(topic.toLowerCase(java.util.Locale.ROOT), topic,
                        (int) QUESTIONS.stream().filter(question -> question.topic().equals(topic)).count()))
                .toList();
    }

    public List<Question> starterQuestions(String pathId) {
        if ("baseline".equals(pathId)) {
            return topicPaths().stream().map(path -> QUESTIONS.stream()
                    .filter(question -> question.topic().equals(path.title())).findFirst().orElseThrow()).toList();
        }
        return topicPaths().stream().filter(path -> path.id().equals(pathId)).findFirst()
                .map(path -> QUESTIONS.stream().filter(question -> question.topic().equals(path.title())).toList())
                .orElse(List.of());
    }

    public List<QuestionView> questions(String userId) {
        List<QuestionView> visible = new ArrayList<>();
        personalQuestions.findByUserId(userId, PageRequest.of(0, 100, Sort.by(Sort.Direction.DESC, "createdAt")))
                .forEach(question -> {
                    if (question.expiresAt().isAfter(java.time.Instant.now()))
                        visible.add(fromPersonal(question).publicView());
                });
        visible.addAll(QUESTIONS.stream().map(Question::publicView).toList());
        return List.copyOf(visible);
    }
    public Question find(String userId, String id) {
        Question builtIn = QUESTIONS.stream().filter(question -> question.id().equals(id)).findFirst().orElse(null);
        if (builtIn != null) return builtIn;
        return personalQuestions.findByIdAndUserId(id, userId)
                .filter(question -> question.expiresAt().isAfter(java.time.Instant.now()))
                .map(PracticeCatalog::fromPersonal).orElse(null);
    }

    private static Question fromPersonal(PersonalPracticeQuestion question) {
        return new Question(question.id(), question.topic(), question.prompt(), question.options(),
                question.correctOptionId(), question.explanation());
    }
}
