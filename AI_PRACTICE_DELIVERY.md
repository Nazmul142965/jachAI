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

#### `PracticeServiceTests` — 8 tests (3 new)

| Test | Verifies |
|---|---|
| `publicQuestionsExcludeAnswerKeysAndExplanations` | Starter questions never expose `correctOptionId` or `explanation` |
| `correctAnswerIsStoredOnlyAfterSubmission` | Answer is persisted only after quota reservation succeeds |
| `replayWithDifferentChoiceDoesNotConsumeQuota` | Replay with a different choice fails with `CONFLICT` |
| `sameRequestIdReturnsSameResultWithoutConsumingQuota` | Retry-safe: identical `requestId` returns the same `Result` and never touches Mongo |
| `quotaExhaustedThrows429WithoutSavingAnswer` | 21st answer on a UTC day throws `PRACTICE_LIMIT_REACHED` (HTTP 429) |
| `unexpectedInsertFailureRefundsQuotaBucket` | If `answers.insert` throws, the usage bucket is decremented |
| `usageReportsStatedRetentionAndCurrentAllowance` | `Usage` includes `answerRetentionDays=90` and `generationRetentionDays=30` |
| `usageWithNoBucketReportsFullAllowanceAndRetention` | No bucket → full allowance reported |

#### `PersonalPracticeGenerationServiceTests` — 6 tests (3 new)

| Test | Verifies |
|---|---|
| `generatedQuestionIsStoredPrivatelyAndReturnedWithoutAnswerKey` | Generated question belongs only to the requesting user |
| `invalidModelOutputDoesNotSaveQuestionAndRefundsBothBuckets` | Bad JSON → no save, both user and global buckets refunded |
| `globalCapacityIsNotReportedAsPersonalLimit` | Global cap exhaustion → `PRACTICE_AI_PLATFORM_CAPACITY` (not personal limit) |
| `providerAuthErrorRefundsBothBucketsAndPropagatesCategory` | `AI_PROVIDER_AUTH` from provider → both buckets refunded |
| `globalCapExhaustionRefundsPersonalBucketAndDoesNotCallAi` | Global cap hit → personal bucket refunded, AI never invoked |
| `invalidTopicIsRejectedBeforeAnyQuotaOrAiCall` | Topic too long → `INVALID_PRACTICE_BRIEF` before any Mongo/AI call |

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
| `generationFailureUnreservesBothBuckets` | AI failure → user bucket refunded to 0; no question saved |

## Live-provider gates

- **No live provider is exercised in this slice.** All AI calls are mocked in tests.
- To run the Mongo integration tests, set `MONGODB_TEST_URI` to a dedicated
  `jachai_it_` database before `mvn test`.
- To exercise a live AI provider locally, set `AI_PROVIDER=google-genai`
  (or `ollama`) and the provider's key in your environment. **Do not paste
  secrets into chat.**
- A single `POST /api/v1/practice/questions/generate` with a real key is the
  minimal live check. Review the `Gemini` / `Groq` troubleshooting docs linked
  in `PRODUCT_UI_HANDOFF.md` before relying on a specific model.

## What remains

- Live MongoDB integration tests require `MONGODB_TEST_URI` (opt-in).
- Live provider verification (Gemini/Groq/Ollama) is unverified.
- No personal paid checkout or unlimited generation is advertised or implemented.
