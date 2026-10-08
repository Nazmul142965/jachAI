# Google sign-in behavior

Google Identity Services verifies the credential on the server before JachAI creates a session.

- A new Google account with a verified Gmail or Workspace domain email creates a verified JachAI account immediately; no separate registration or password is required.
- An existing verified JachAI account with the same authoritative Google email is linked automatically on the first Google sign-in and receives a session immediately.
- A later Google sign-in uses the stored Google subject, not only an email string.
- Disabled or unverified JachAI accounts cannot be bypassed through Google.
- Non-authoritative third-party email domains still cannot create an unverified shortcut account.
- If a Google subject is already linked to a different account, the server rejects the conflict rather than merging accounts.

This keeps the convenient one-click flow while requiring Google's signed, email-verified identity and preserving tenant and account restrictions.
