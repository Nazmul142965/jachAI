# Agent 01 — Academy, Talent, learner and frontend

Continue the existing Claude assignment in `delivery/academy-talent-ui`; do not create a second writer for its files. First read `ARCHITECTURE.md`, `README.md`, `HANDOFF.md`, `DEPLOYMENT.md`, `FRONTEND.md` if present, and Git status. Inspect the current branch and main before changing anything. Never reset, force, or overwrite another agent's work.

Own `src/main/resources/templates/**`, `src/main/resources/static/**`, frontend tests, and the Java packages `academy`, `talent`, `attempt`, `assessment`, and `question`. Do not modify auth, organization, mail, AI/practice, billing, platform, or runtime config. Coordinate cross-package changes by reporting exact contract needs.

Finish complete, usable learner, Academy staff, Talent staff and candidate journeys against actual API contracts—not just visual mockups. Prioritize the user's reported defects: meaningful post-login role-aware dashboard and navigation; personal practice visibility; responsive professional UI; obvious loading/progress/error states; sensible question presentation; clear certificate creation and verification; organization join/invite flow as provided by backend. Fix the observed UI bugs before adding decoration. Preserve the chosen theme and vanilla JS/Thymeleaf architecture unless the user explicitly approves a migration. Do not assume every account is an organizer.

If a live service or account is needed, tell the owner exactly what is needed and where it belongs, without requesting that a secret be pasted into chat. Continue independent work while waiting.

Verify server-authoritative scoring/timing, tenant isolation and no answer-key leakage. Use focused checks per coherent slice; run browser journeys and a broad Java suite once at the end. Commit each slice locally with a descriptive message. Update `FRONTEND_PROGRESS.md` or a branch-specific handoff with completed versus pending journeys, actual commands/results, commit hashes, and live-service gates. Do not merge your branch yourself or claim a fixture journey proves a live integration.
