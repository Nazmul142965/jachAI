# Personal Resume Checker

The first delivery is an account-owned resume checker, not a complete recruitment
ATS. It uses the existing Spring Boot app and plain HTML/CSS/ES-module UI. It
does not call an external parser or payment service. Basic checks are local;
the separate content-review action calls the existing configured AI providers
only after explicit user permission.

## Delivered flow

Personal → Resume checker → upload PDF/DOCX, optional job description → background
processing → report → fix the resume yourself → upload an improved version.
Choose a history report as a baseline or press Upload an improved version. The
comparison shows readiness change and resolved/new rule codes, not a job-score
delta when the job description may differ. Reports with different rule versions
or unsupported language scores cannot be compared.

Each report has extracted text, findings with evidence/action/deduction, score
breakdown, matched/missing technical skill mentions, and limitations. History is
paged, reports can be reopened or deleted, and processing can be resumed from
history after a page refresh. No original-file download or storage is provided.

## Runtime

- Java 21 and the application's existing MongoDB/auth/rate-limit configuration.
- PDFBox 3.0.8 reads PDF text; the JDK's bounded ZIP and hardened XML parser read
  DOCX body paragraphs/tables. This avoids installing a general-purpose document
  parser when only PDF and DOCX are accepted.
- Text PDFs and DOCX work without Tesseract. To read scanned PDFs locally, install
  Tesseract with English language data, set `RESUME_OCR_ENABLED=true` and optionally
  `RESUME_OCR_COMMAND` to its executable path, then restart the application.
  The Docker runtime installs English Tesseract and enables OCR by default.
- A single background worker accepts at most two queued uploads. A busy server
  returns 503; the UI permits retry. This deliberately bounds memory/concurrency
  on the current small host. Increase capacity only after measuring memory/CPU.
- Jobs are not durable across restarts. Reports still processing after ten minutes
  are presented as interrupted/failed, and the owner can re-upload. Do not describe
  the current queue as a durable Redis worker.

## API

| Method and path | Purpose |
| --- | --- |
| `POST /api/v1/resume/scans` | Multipart `file`, UUID `requestId`, optional `jobDescription`; returns 202 and a scan view |
| `GET /api/v1/resume/scans?page=0` | Owner's summaries, 10 per page |
| `GET /api/v1/resume/scans/{id}` | Owner's status, report or readable failure |
| `DELETE /api/v1/resume/scans/{id}` | Delete the owner's report/text; 204 |
| `GET /api/v1/resume/scans/{id}/review` | Read the owner's saved AI content review/status; never calls AI |
| `POST /api/v1/resume/scans/{id}/review` | JSON `{"consent":true}`; review actual extracted text and JD using configured AI |

## Evidence-based content review

The report separates **Basic document checks** (the deterministic score below)
from **Review the actual resume content** (AI advice). The latter sends the actual
extracted resume text and stored job description as JSON through `AiService` with
`AiUseCase.RESUME_REVIEW`. It uses `AI_PROVIDER` and `AI_QUESTION_FALLBACK`, already
used for question drafting. No new API key is required when that provider works.
If AI is disabled or unavailable, it returns an honest failure rather than canned
advice. No external call is made just by uploading a resume or opening a report.

The user must check the disclosure and explicitly request review. The controller
and service both reject missing/false consent before provider access. Requests
are limited to five attempts per account and 100 per deployment in a rolling
24-hour rate window, including failed attempts; the existing rate-limit backend
enforces these policies. Shared demos share their allowance and reviews. Each
provider attempt has the existing primary timeout, including the last fallback.

Findings contain a title, severity, exact source quote, explanation and concrete
action. The server validates field sizes, allowed values and every quote against
the chosen source (resume or JD), rejecting fabricated quotes and duplicates.
This validates provenance, not the truth of the AI interpretation. The advice is
still labelled fallible. AI never assigns or modifies the readiness/keyword score.

Completed reviews are cached against immutable scan IDs. New uploads get a new
review; concurrent calls cannot duplicate the same active review. Saved reviews
are account-scoped in `resume_reviews`, expire with the source scan and are removed
when it is deleted. Interrupted reviews can be retried after three minutes. Inputs
over 40,000 extracted characters are refused rather than silently truncated.

JWT subject supplies ownership. Disabled/unverified accounts are rejected. Verified
demo accounts are allowed; their reports are shared by everyone using that demo
identity, so the UI advises sample resumes only. Cross-owner, missing and expired records all return 404.
An upload request ID is owner-scoped and idempotent for the same file bytes and
job description; reuse for changed content returns 409. The rate-limit policy is
10 start requests/hour, including retries (it is not a paid quota).

## Score contract: resume-rules-1

Readiness is a 0–100 heuristic, with every deduction exposed:

| Check | Points |
| --- | ---: |
| Recognizable email | 10 |
| Likely phone number | 5 |
| Experience or projects heading | 25 |
| Education heading | 10 |
| Skills heading | 10 |
| Four-digit year present; no reversed year range detected | 15 |
| 150–1500 extracted words | 5 |
| No duplicate long text lines | 5 |
| At most three replacement/box characters | 15 |

These are our transparent guidelines, not requirements enforced by every employer.
Headings are English; unsupported predominantly non-Latin resumes get no score,
not a low score. Tables and more than two PDF pages produce review suggestions,
with zero deduction. Overlapping jobs and career gaps are not declared errors.
No scoring uses name, gender, photo, age or protected characteristics.

Job match is separate: recognized skills mentioned in the resume / recognized
skills mentioned in the JD, equally weighted. It is null without a JD or recognized
keywords. The fixed technical dictionary does not cover all occupations. It does
not distinguish required/preferred or negated mentions, validate experience, or
measure seniority/semantic relevance. This limitation appears in every JD report.
Do not market it as an employer's ATS score or an automated hiring decision.

## Data and processing limits

- Max upload 5 MB, request 6 MB, PDF 1–10 pages, JD 12,000 characters, extracted
  text 80,000 characters. DOCX max 200 ZIP entries, 2 MB per expanded entry and
  15 MB total expanded content; external XML entities/DTDs and deep XML are rejected.
- File extension and actual signature/format are checked, not just browser MIME.
  Password-protected/restricted and malformed documents fail without scoring.
- Scanned PDF pages use local OCR with a 15-second process timeout per page and
  an 8-million-pixel render bound. Unreadable content fails instead of getting a
  misleading low score. OCR results require the user's text-preview review.
- Original upload bytes are transient and cleared after the worker finishes.
  OCR temporary images/text are removed in a finally block. Report/text/JD are
  stored privately in `resume_scans`, with a 30-day TTL index and an explicit expiry
  check on reads/deletes. In-flight completion uses an update, never an upsert, so
  deleting a report cannot recreate it. MongoDB backup retention remains governed
  by the deployment's backup policy.
- DOCX headers/footers, drawings and text in images are not extracted. Mixed PDF
  pages with both text and images can contain text not captured by the OCR trigger.
  PDF column order and visual layout are not certified. No external links are fetched.
- Parsing is bounded but runs in the app process; hostile-document stress testing,
  process isolation and malware scanning are further hardening work before opening
  the service to unrestricted high-volume uploads.

## Verification and remaining work

Run `./mvnw test` with Java 21. `ResumeCheckerTests`, `ResumeExtractorTests` and
`ResumeScanServiceTests` cover deterministic scoring, skill boundaries, language
limits, real text PDF/DOCX extraction, XML/ZIP rejection, account/owner scoping,
request reuse and safe background completion. Application tests cover auth and
pagination validation. These use mocked MongoDB, not a live database.

Run `node src/test/frontend/run-journeys.js --resume-only --screens` for real-browser
fixture checks: polling, scores, escaped document text, version comparison, deletion,
sign-out cleanup, Bangla labels and 320/375px layouts in both themes. Evidence is
under ignored `target/frontend-evidence/`. Fixtures do not prove extraction or live
MongoDB integration. Run the normal frontend journeys to check existing screens.

Before release: verify the complete flow against a disposable local MongoDB,
run real Tesseract on a scanned resume in the built Docker image, and benchmark an
anonymized representative resume set with human-labelled expected findings.
No external customer database is needed or authorized for these checks.

Later phases: richer skills taxonomy and required/preferred handling; layout and
reading-order checks; explicit work-history parsing; user-approved AI rewrites;
template/export; and permissioned Talent integration. AI critique is now delivered
as described above; rewrite and third-party ATS integration are not implemented.
The former support/feedback/practice/platform UI gaps are documented in
[Frontend API coverage](FRONTEND_API_COVERAGE.md).
