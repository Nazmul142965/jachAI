# Shared checkout: Codex backend + Claude UI

Both assistants use `E:\Advance Java\jachAI` on branch `integration/backend-ui-finish`. There is only one checkout: **neither assistant switches branches, resets, rebases, merges, pushes, or deploys while the other is working**. The user decides when the combined branch is reviewed and merged.

## File ownership

- Codex: `src/main/java/**`, `src/test/java/**`, backend configuration, and backend-only tests. No edits to `src/main/resources/static/**`, `src/main/resources/templates/**`, or `src/test/frontend/**`.
- Claude: owns the full UI across `src/main/resources/static/**`, `src/main/resources/templates/**`, and `src/test/frontend/**`. Creative freedom includes a cohesive visual redesign, navigation and interaction improvements, responsive layouts, and finishing every existing role/workflow—not just polishing a few screens. Keep the product's functionality, accessibility, English/Bangla support, and backend API contracts intact. No edits to Java, backend configuration, `pom.xml`, or `Dockerfile`.
- Shared documents and Git operations: ask the user before editing or committing files owned by the other assistant. Stage explicit paths; never run `git add .` or `git add -A` because this checkout contains user-owned untracked material.

## Interface and communication

Read the current API/controller contracts before changing UI calls. If the UI needs a backend change, add a short request under **UI requests for backend** below (endpoint, desired behavior, current failure), and tell the user to notify Codex. Codex responds under **Backend replies**. For a direct discussion, the user can relay the note between the two chats; there is no automatic private message channel between independent assistants.

UI requests for backend: none yet.

Backend replies: Java 21 baseline on 2026-10-15: 724 tests, 0 failures/errors, 55 opt-in/external tests skipped. Current release gates are in `SYSTEM.md`; sandbox payment is not real money.
