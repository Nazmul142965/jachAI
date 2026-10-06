# JachAI — account and credential checklist (owner only)

This is **your** setup list, not an agent assignment. Never paste passwords, API keys, SMTP App Passwords, Atlas URIs, or JWT secrets into chat, source, screenshots, logs, or commits. Put them in IntelliJ's Run/Debug Configuration → Environment variables (table editor) or a private shell/secret manager. Restart the app after changing them. Keep `Store as project file` unchecked. Do not send the values to an agent; tell the agent only **configured** or **not yet**.

## Needed for local end-to-end checks

- **MongoDB Atlas:** an application database user with access to your dev cluster and Network Access allowing your current public IP. Set `MONGODB_URI` to the Atlas connection string **without a database path**. The local Spring profile uses database `jachai_dev` by default; set `LOCAL_MONGODB_DATABASE` only if you intentionally want another dev database. Keep a **separate disposable test database** whose name starts `jachai_it_`; put its full URI, including that database name, in `MONGODB_TEST_URI` only for integration tests. Never point it at `jachai_dev` or production.
- **JWT:** set `JWT_SECRET` to a stable cryptographically random secret of at least 32 bytes. Changing it logs users out. Do not reuse the sample value from chat.
- **Gmail SMTP:** since you created a Gmail App Password, set `MAIL_HOST=smtp.gmail.com`, `MAIL_PORT=587`, `MAIL_USERNAME` to your Gmail address, `MAIL_FROM` to the **same** address, and `MAIL_PASSWORD` to the App Password (not your ordinary Gmail password or an AI API key). Optional but recommended: stable, separate 32+ byte `MAIL_JOB_ENCRYPTION_KEY`. Verify delivery by checking your inbox and the `email_jobs` status; a queued link alone is not delivery.
- **Google login:** create a Google Cloud **OAuth Web application client**; add `http://localhost:8080` to Authorized JavaScript origins. Set `GOOGLE_CLIENT_ID` to the Web client ID. The current login uses an ID token; it does not need a Google client secret for this flow. This is separate from Gmail SMTP.
- **Gemini and Groq:** set `GEMINI_API_KEY` and `GROQ_API_KEY` from their respective developer dashboards. Set `AI_PROVIDER` and `AI_TUTOR_PROVIDER` to the provider names supported by the app (`google-genai`, `groq`, or `ollama`) for the actions you want to test. Check the current model names and free-tier limits in the provider dashboard before live use. Ollama is optional unless you specifically want offline/local model testing.
- **Redis:** for cache/revocation and production-like checks, run Redis locally or obtain a cloud endpoint. Set `REDIS_HOST` and `REDIS_PORT`; add `REDIS_USERNAME`, `REDIS_PASSWORD`, `REDIS_SSL_ENABLED=true` if the provider requires them. For local mode, these advanced features may remain disabled until Redis is available.

## Needed later for sandbox payment and deployment

- **bKash sandbox merchant:** obtain sandbox `app key`, `app secret`, `username`, and `password`; set `BKASH_SANDBOX_APP_KEY`, `BKASH_SANDBOX_APP_SECRET`, `BKASH_SANDBOX_USERNAME`, `BKASH_SANDBOX_PASSWORD`. These are **not configured yet** per your last answer. Sandbox-only; no real-money credentials.
- **SSLCOMMERZ sandbox merchant:** obtain sandbox store ID/password; set `SSLCOMMERZ_SANDBOX_STORE_ID` and `SSLCOMMERZ_SANDBOX_STORE_PASSWORD`. Also **not configured yet**. Sandbox-only.
- **Docker Desktop or deployment host:** required for the container/deployment gate, not for ordinary IntelliJ local testing.
- **Public HTTPS origin:** only when deploying; set `APP_PUBLIC_BASE_URL` to the actual HTTPS site origin so email links and callbacks target the right host. A domain is not required for local Gmail-based SMTP testing.

## Safe handoff to agents

Tell an agent only which of MongoDB test URI, SMTP, Google, AI, Redis, and sandbox merchants are configured in that agent's own private environment. Never share raw values. If a service is unavailable, the agent should complete code/offline tests and mark the live gate pending rather than claiming it passed.
