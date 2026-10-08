# Agent 03 — AI and personal practice

Create a new worktree/branch from current `main`; read `ARCHITECTURE.md`, `README.md`, `HANDOFF.md`, and existing AI/practice code before edits. Own only Java packages `ai` and `practice` and their tests. Do not edit frontend, assessment/question, auth, billing or shared config without coordinating. Preserve dirty work and never reset/force.

Existing Gemini/Groq/Ollama routing, Mongo-backed personal quota, starter catalog and AI-generated questions exist; inspect them rather than recreate. Make the journey dependable: 20 free generations and 20 answers per UTC day, a platform-wide provider-cost cap, user-only question/answer history, stated retention policy, retry-safe answers, refunds after failed generation, validated AI output, meaningful provider error categories, and usage visible through existing contracts. Never copy tenant-private questions or answer keys into personal practice. AI must never decide official assessment scores. No automatic cross-provider fallback for private assessment drafts without the user's explicit approval; that would send content to another external service.

If a live service or account is needed, tell the owner exactly what is needed and where it belongs, without requesting that a secret be pasted into chat. Continue independent work while waiting.

Add focused service tests and safe opt-in Mongo/Redis tests. Exercise a live provider only when a private key is configured; record latency/error behavior honestly. No personal paid checkout or unlimited promise until price, terms and limits are approved. Commit meaningful slices, run one broader suite at completion, update `AI_PRACTICE_DELIVERY.md`, and report hashes and live-provider gates.
