# AI Practice Delivery

Branch: `ai/practice-reliability` — branched from `main` at `bba1b94`.
Worktree: `E:\Advance Java\jachAI-practice-ai`.

## Scope

This slice owns only the Java packages `com.jachai.ai` and `com.jachai.practice`
and their tests. Frontend templates, the assessment/question domain, auth,
billing, and shared configuration were not edited.

## What already existed (inspected, not recreated)

| Feature | Location | Notes |
|---|---|---|
| Provider routing (Gemini/Groq/Ollama) | `SpringAiService` selects one provider per use case from `AI_PROVIDER` / `AI_TUTOR_PROVIDER`. No fallback between providers. | `GroqAiConfig` fails startup if `groq` is selected without `GROQ_API_KEY`. |
| Mongo-backed personal quota | `PracticeUsage` document with atomic `findAndModify` reservation. | User bucket (`userId:day`) + platform global bucket (`personal-ai:global:day`). |
| Starter catalog | `PracticeCatalog.QUESTIONS` — 5 app-owned questions, no tenant data. | Built-in questions are shared fallback examples; personal questions are user-scoped. |
| AI-generated personal questions | `PersonalPracticeGenerationService` | Validates JSON before storage; refunds both buckets on failure. |
| Answer history | `PracticeService.submit` | Retry-safe via `userId:requestId` as answer ID. |
| Provider error categories | `SpringAiService.classify` maps to `AI_PROVIDER_RATE_LIMIT`, `AI_PROVIDER_AUTH`, `AI_PROVIDER_BILLING`, `AI_PROVIDER_CONFIGURATION`, `AI_PROVIDER_UNAVAILABLE`, `AI_NOT_CONFIGURED`, `AI_UNAVAILABLE`. | Provider response text is never leaked to the client. |
| Usage API | `GET /api/v1/practice/usage` | Returns `Usage` record with allowances. |

## Changes in this slice

### 1. Stated retention policy in the usage contract

The `PracticeService.Usage` record now returns `answerRetentionDays` (90) and
`generationRetentionDays` (30) so the retention policy is visible to callers:

```java
public record Usage(String utcDay, int used, int limit, int remaining,
                    int generated, int generationLimit, int generationRemaining,
                    int answerRetentionDays, int generationRetentionDays) { }
```

MongoDB TTL indexes on `PracticeAnswer.expiresAt` (90 days) and
`PersonalPracticeQuestion.expiresAt` (30 days) already enforce this at the
storage layer.

### 2. Shared limits via `PracticeLimits`

`PracticeLimits` centralizes all daily allowances and retention durations.
Both `PracticeService` and `PersonalPracticeGenerationService` reference it,
removing the previously hardcoded `20` in the `Usage` record's generation limit:

| Constant | Value | Used by |
|---|---|---|
| `DAILY_FREE_ANSWERS` | 20 | Answer quota in `PracticeService` |
| `DAILY_FREE_GENERATIONS` | 20 | Generation quota in `PersonalPracticeGenerationService` |
| `ANSWER_RETENTION_DAYS` | 90 | TTL on `PracticeAnswer` |
| `GENERATION_RETENTION_DAYS` | 30 | TTL on `PersonalPracticeQuestion` |

The `Usage` record's generation bucket TTL was corrected from 60 days to 2 days
(matching the generation service) so stale generation counters don't linger.

### 3. Focused service tests (unit, mock-based)

### 3. Retry-safety and ambiguous-outcome fixes (correction slice)

**`PracticeService.submit` reorder** — the prior-answer lookup
(`answers.findById(answerId)`) now runs *before* `catalog.find()`. This lets
a valid replay succeed even after a generated question has expired via TTL.
Reuse with a different `questionId` or `optionId` still returns 409 `CONFLICT`.

**Ambiguous insert outcome handling (both services)** — on any insert failure
(`DuplicateKeyException`, `RuntimeException`, or `MongoSocketException` from a
timeout after the write may have committed):

1. Re-read by the deterministic ID (`userId:requestId` for answers,
   `pg-<UUID>` for questions).
2. If the record **exists** → the write likely succeeded; do **not** refund
   quota. Return the persisted result.
3. If the record **does not exist** → definitely not persisted; refund the
   reservation bucket(s).
4. If the re-read **itself fails** → fail closed without refunding (avoids
   silently undercounting usage). The original failure propagates with the
   read failure suppressed.

`PersonalPracticeGenerationService` applies the same pattern to its question
insert with both user and global bucket refunds. AI/parse failures (before
any insert) still refund both buckets unconditionally.

#### `PracticeServiceTests` — 12 tests (9 new)

| Test | Verifies |
|---|---|
| `publicQuestionsExcludeAnswerKeysAndExplanations` | Starter questions never expose `correctOptionId` or `explanation` |
| `correctAnswerIsStoredOnlyAfterSubmission` | Answer is persisted only after quota reservation succeeds |
| `replayWithDifferentChoiceDoesNotConsumeQuota` | Replay with a different choice fails with `CONFLICT` |
| `sameRequestIdReturnsSameResultWithoutConsumingQuota` | Retry-safe: identical `requestId` returns the same `Result` and never touches Mongo |
| `quotaExhaustedThrows429WithoutSavingAnswer` | 21st answer on a UTC day throws `PRACTICE_LIMIT_REACHED` (HTTP 429) |
| `unexpectedInsertFailureRefundsQuotaBucket` | If `answers.insert` throws and re-read finds nothing, the bucket is refunded |
| `replayWorksAfterGeneratedQuestionExpires` | Replay succeeds after TTL expiry; `catalog.find` never called |
| `insertErrorDoesNotRefundWhenAnswerWasPersisted` | Insert error but re-read finds answer → no refund, returns result |
| `insertErrorFailsClosedWhenReReadCannotEstablishOutcome` | Insert + re-read both fail → fail closed, no refund |
| `duplicateKeyFromConcurrentRequestRefundsQuotaAndReplays` | Concurrent `DuplicateKeyException` loser refunds quota and replays |
| `usageReportsStatedRetentionAndCurrentAllowance` | `Usage` includes `answerRetentionDays=90` and `generationRetentionDays=30` |
| `usageWithNoBucketReportsFullAllowanceAndRetention` | No bucket → full allowance reported |

#### `PersonalPracticeGenerationServiceTests` — 8 tests (5 new)

| Test | Verifies |
|---|---|
| `generatedQuestionIsStoredPrivatelyAndReturnedWithoutAnswerKey` | Generated question belongs only to the requesting user |
| `invalidModelOutputDoesNotSaveQuestionAndRefundsBothBuckets` | Bad JSON → no save, both user and global buckets refunded |
| `globalCapacityIsNotReportedAsPersonalLimit` | Global cap exhaustion → `PRACTICE_AI_PLATFORM_CAPACITY` (not personal limit) |
| `providerAuthErrorRefundsBothBucketsAndPropagatesCategory` | `AI_PROVIDER_AUTH` from provider → both buckets refunded |
| `globalCapExhaustionRefundsPersonalBucketAndDoesNotCallAi` | Global cap hit → personal bucket refunded, AI never invoked |
| `invalidTopicIsRejectedBeforeAnyQuotaOrAiCall` | Topic too long → `INVALID_PRACTICE_BRIEF` before any Mongo/AI call |
| `insertErrorDoesNotRefundWhenQuestionWasPersisted` | Insert throws but re-read finds the question; buckets are NOT refunded |
| `insertErrorFailsClosedWhenReReadCannotEstablishOutcome` | Insert throws and re-read also throws; no refund (fail closed), original error propagates with suppressed read failure |

#### `PracticeControllerTests` — 2 tests (1 new)

| Test | Verifies |
|---|---|
| `historyReturnsStablePageWithoutInternalUserOrDocumentIds` | Answer history excludes `userId`, `answerId`, `expiresAt` |
| `usageExposesStatedRetentionPolicyAndAllowances` | Usage endpoint serializes `answerRetentionDays` and `generationRetentionDays` |

#### `SpringAiServiceTests` — 5 tests (unchanged, already passing)

Covers provider routing without fallback, `AI_NOT_CONFIGURED`, `AI_UNAVAILABLE`,
`AI_PROVIDER_RATE_LIMIT` (429), `AI_PROVIDER_AUTH` (401), and private-provider-detail.

### 4. Opt-in Mongo integration tests (safe, skipped without MongoDB)

`PracticeIntegrationTests` extends `BaseMongoIntegrationTest` and is **skipped**
(`@BeforeAll` calls `assumeTrue(false)` when `MONGODB_TEST_URI` is not set).
When MongoDB is available, the tests verify:

| Test | Verifies |
|---|---|
| `answerSubmissionPersistsAndReplayIsIdempotent` | Real Mongo: answer is persisted; replay with same `requestId` creates no duplicate; only one `PracticeAnswer` document exists |
| `dailyAnswerQuotaEnforcedAtomicallyByMongo` | 20 answers succeed; 21st throws `PRACTICE_LIMIT_REACHED`; bucket `used` is 20 |
| `generatedQuestionIsAccessibleOnlyToOwner` | Intruder cannot submit against another user's generated question (`INVALID_PRACTICE_ANSWER`); intruder has 0 answers |
| `usageReadsRealMongoBucketState` | Usage reflects the actual Mongo bucket after submit |
| `generationReservesQuotaAtomicallyAndPersistsQuestion` | Real Mongo: both user and global buckets incremented to 1; question saved with correct `userId` |
| `generationFailureUnreservesBothBuckets` | AI failure → both user and global buckets refunded to 0; no question saved |

## Verification

Four commits on `ai/practice-reliability` (branched from `main` at `bba1b94`):

| Hash | Summary |
|---|---|
| `9e624cf` | ai/practice: state retention policy, fix hardcoded generation limit, add unit tests |
| `1c04f83` | test: add opt-in Mongo integration tests for practice workflow |
| `5603a28` | fix: resolve replay-after-expiry and ambiguous-insert-outcome defects |
| `103496b` | fix: separate DuplicateKeyException refund from fail-closed; global bucket isolation |
| `8a5ae19` | fix: compare replay fields instead of whole Result record (Mongo truncates Instant to ms) |
| `67163e8` | fix: remove unsafe @BeforeEach global bucket deletion; track per-test |

### Broader suite (all packages, Java 17)

```
[INFO] Tests run: 584, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### Local Mongo run

Set `MONGODB_TEST_URI=mongodb://localhost:27017/jachai_it_practice` and run:

```
mvn test -Dtest="PracticeIntegrationTests"
```

Result against local MongoDB:

```
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 5.343 s
BUILD SUCCESS
```

Integration tests are **not** skipped when `MONGODB_TEST_URI` is set. The global
bucket (`personal-ai:global:<day>`) is no longer deleted in `@BeforeEach`; each
test that touches it now explicitly deletes any stale document and registers the
ID with `trackDocumentId` for `@AfterEach` cleanup, preserving isolation without
mutating a shared platform resource.

### Live-provider gate (unverified)

No live AI provider call was made. To verify, run the app with
`AI_PROVIDER=google-genai` and `GEMINI_API_KEY` in the environment (shell or
IntelliJ run config), then:

```
POST /api/v1/practice/questions/generate
{"topic":"Java","difficulty":"BEGINNER","brief":""}
```

Record latency and any provider error category honestly. No claims are made
without a real call.

## What remains

- Live MongoDB integration tests require `MONGODB_TEST_URI` (opt-in).
- Live provider verification (Gemini/Groq/Ollama) is unverified.
- No personal paid checkout or unlimited generation is advertised or implemented.
