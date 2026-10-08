# Payment, invitation, messaging and demo audit

## Implemented

- Sandbox checkout opens in a separate browser tab. The signed-in JachAI tab keeps its in-memory session, so cancelling a gateway flow no longer logs the customer out.
- `CREATED` and `CHECKOUT_READY` payment orders can be cancelled by the organization admin. Gateway cancel/failure callbacks also mark only unfinished orders as `CANCELLED`. Verified or applied orders cannot be cancelled, reconciled, or used to grant entitlement.
- Billing history exposes **Cancel checkout** for unfinished orders and explains that a cancelled order grants nothing.
- Member invitations distinguish queued SMTP delivery from local-console delivery. In local mode the invitation link is printed in the application log; real delivery requires `MAIL_HOST`, `MAIL_USERNAME`, `MAIL_PASSWORD`, and `MAIL_FROM`.
- Quota copy now distinguishes the read-only check from the audited repair operation.
- Demo role controls are reloaded after logout when the same-deployment demo endpoint is enabled.

## Existing boundaries

- Academy direct messaging remains intentionally limited to instructor/learner participants. Owners and Talent roles do not receive a misleading Messages action because the backend does not authorize those conversations.
- The Google `g_state` warning is emitted by Tomcat when it ignores a malformed Google Identity Services cookie. It is not a JachAI session failure, and the application must not weaken global cookie parsing to hide it. The cookie belongs to Google's GIS integration and cannot be safely cleared by the JachAI origin.
- A queued email is not proof of mailbox delivery. Production must use a verified SMTP sender and operators should inspect `email_jobs` for `SENT` or `FAILED`.

## Verification

`mvnw.cmd -DskipTests compile` and `node --check src/main/resources/static/app.js` pass after these changes. Live gateway success and SMTP mailbox delivery still require the configured sandbox credentials and public callback URL.
