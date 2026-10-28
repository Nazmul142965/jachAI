# Backend features connected to the existing frontend

This pass checks the previously identified gaps against the current Java controllers
and workspace JS. It does not claim every backend endpoint needs its own screen:
provider callbacks, SMTP delivery, caching and other internal operations remain
backend concerns. The optional React Practice pilot is not the main workspace UI.

| Area | Added interaction | API |
| --- | --- | --- |
| Customer Support | Open ticket and description, paged public replies, send reply, reopen resolved ticket | `GET /support/tickets/{id}`, `GET/POST .../replies`, `POST .../reopen` |
| Instructor feedback | Load saved draft/publication automatically from attempt review or explicitly; confirm before replacing unsaved text; lock published content | `GET .../academy/attempts/{id}/feedback` |
| Guided Practice | Continue saved session with server-owned answers; no new session created by resume | `GET /practice/sessions/{id}` |
| Platform payments | Date/provider filters, totals/provider/week breakdown, clear sandbox-only wording | `GET /platform/operations/sandbox-payments` |
| Platform audit recovery | Inspect one page, see recoverable/ambiguous cases, confirm recovery, refresh report, page through remaining records | `GET/POST /platform/organizations/{id}/audit-recovery?page=...` |

Paths are relative to `/api/v1`; tenant paths include the selected organization.
UI visibility never replaces server authorization. The existing token refresh,
error, list-loading, confirmation, theme and translation helpers are reused.
No new frontend dependencies or framework migration were introduced.

Support replies always request `publicReply: true`; internal operator notes are
not fetched by the customer screen. Only RESOLVED tickets offer reopen, matching
the service's transition rules. CLOSED tickets cannot be replied to.

Feedback loading checks current organization, attempt and edit token before
painting. Unsaved text is protected from reload; publishing requires a saved
draft. Practice stores only a session ID plus account ID in sessionStorage, not
answers or tokens. The server checks the session owner and expiry. The marker
survives reload in that tab; this is not cross-device session discovery.

Audit recovery is explicitly page-scoped. The UI only enables it after a current
preview finds recoverable entries, refuses stale selection, asks for confirmation,
and refreshes the preview after recovery. Ambiguous records remain for manual
review. It does not claim a whole organization was checked from one page.

Connecting the payment panel exposed inconsistent provider filtering: totals and
weekly buckets previously included every provider while the provider breakdown
was filtered. All three now use the same selected provider. Report ranges are
bounded to 366 days to prevent unbounded weekly database queries. Date inputs are
local time, serialized to UTC for the API; weekly date labels come from the server.

## Verification

- `node src/test/frontend/run-journeys.js --missing-flows-only --screens`: real
  browser with fixtures; support conversation/reopen/reply, saved practice resume,
  draft load/unsaved edits, payment filters, audit preview/confirmation/recovery/paging.
- `node src/test/frontend/run-journeys.js --resume-only --screens`: resume report
  UI and consent/evidence rendering. AI results are fixtures, not real provider calls.
- `node src/test/frontend/run-journeys.js`: existing role journeys and mobile checks.
- Java tests cover actual provider-filter calculations, bounded date ranges,
  resume review consent/source validation and API authentication.

Screenshots and JSON evidence are in ignored `target/frontend-evidence/`.
No real customer database was used. Live provider/MongoDB end-to-end verification,
OCR-image deployment checks and representative resume evaluation remain separate
release checks. AI review does not certify employer ATS compatibility.
