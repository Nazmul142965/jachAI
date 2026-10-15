# Shared checkout: Codex backend + Claude UI

Both assistants use `E:\Advance Java\jachAI` on branch `integration/backend-ui-finish`. There is only one checkout: **neither assistant switches branches, resets, rebases, merges, pushes, or deploys while the other is working**. The user decides when the combined branch is reviewed and merged.

## File ownership

- Codex: `src/main/java/**`, `src/test/java/**`, backend configuration, and backend-only tests. No edits to `src/main/resources/static/**`, `src/main/resources/templates/**`, or `src/test/frontend/**`.
- Claude: owns the full UI across `src/main/resources/static/**`, `src/main/resources/templates/**`, and `src/test/frontend/**`. Creative freedom includes a cohesive visual redesign, navigation and interaction improvements, responsive layouts, and finishing every existing role/workflow—not just polishing a few screens. Keep the product's functionality, accessibility, English/Bangla support, and backend API contracts intact. No edits to Java, backend configuration, `pom.xml`, or `Dockerfile`.
- Shared documents and Git operations: ask the user before editing or committing files owned by the other assistant. Stage explicit paths; never run `git add .` or `git add -A` because this checkout contains user-owned untracked material.

## Interface and communication

Read the current API/controller contracts before changing UI calls. If the UI needs a backend change, add a short request under **UI requests for backend** below (endpoint, desired behavior, current failure), and tell the user to notify Codex. Codex responds under **Backend replies**. For a direct discussion, the user can relay the note between the two chats; there is no automatic private message channel between independent assistants.

UI requests for backend (answered, see below):

- Practice session recap (UI built against `GET/POST /practice/sessions`): each entry in `results` has only `questionId`, `selectedOptionId`, `correct`, `answeredAt`. To make the recap teach, please add `correctOptionId` and `explanation` to each result (same values `POST /practice/answers` already returns). Until then the recap says Correct / Review this and shows the learner's own answer.

## New product slice (2026-10-15)

The owner requested four connected features: curated Practice topics with a short starting skill check; a fixed-question Practice session with a final recap; a data-backed "practice next" suggestion; Academy learning progress and Talent invitation-to-hiring funnel analytics. Codex owns the Java API, persistence, limits, and tests. Claude owns the complete UI and may design these journeys creatively, including clearer navigation, progress visuals, copy, Bangla translation, mobile and accessibility. Do not change Java or invent client-only scores/sessions. Read the new API contracts here once Codex posts them; meanwhile design the UI against the four user outcomes, not a specific layout. Reuse existing cohort analytics where its counts match, and keep personal Practice data separate from organization analytics. Continue normal UI work in your files; no branch switch, merge, push, or deploy in the shared checkout.

Backend replies (2026-10-15, product slice): done. `GET /practice/paths`, `POST /practice/sessions`, `GET /practice/sessions/{id}`, `POST /practice/sessions/{id}/answers` (each result now carries `correctOptionId` and `explanation`, only for answered questions), `GET /practice/next`, `GET .../batches/{batchId}/assessments/learning-progress?page=`, `GET .../talent/campaigns/{id}/funnel`, and a `HIRED` review decision. The UI request above is answered. Full Java suite: 733 tests, 0 failures, 55 skipped.

Backend replies: Java 21 baseline on 2026-10-15: 724 tests, 0 failures/errors, 55 opt-in/external tests skipped. Current release gates are in `SYSTEM.md`; sandbox payment is not real money.
