# 15: Finish root-specific settings and failure UX

Triage: enhancement

Stack: PR6

**What to build:** Complete the production-facing root experience around the core mode transition: optional inotify tuning, root-aware Run Script warning, deterministic failure/retry presentation, and prompt policy that never surprises users during passive or unrelated work.

**Blocked by:** 13: Enable Superuser Mode from Settings.

**Status:** ready-for-agent

- [ ] The Experimental inotify setting defaults off and commits only after explicit root acquisition and successful application.
- [ ] Future startup never prompts solely to reapply optional inotify tuning.
- [ ] Run Script settings clearly warn that scripts execute with unrestricted superuser privileges when Superuser Mode is selected.
- [ ] Root denial, unavailable, activation timeout, transport failure, recovery failure, startup timeout, and Retry flows are presented consistently.
- [ ] Passive screens and unrelated background callbacks cannot acquire root or initiate a prompt.
- [ ] Configured Superuser Mode startup/retry and verified orphan recovery may acquire root asynchronously only as continuations of those approved triggers; acquisition remains bounded, never blocks the service thread, and fails closed with a typed result when unavailable.
- [ ] A sync-completion Run Script callback may use an operation-scoped helper only when root authorization is already available; if authorization is absent or revoked, it reports script failure without opening a new prompt or claiming success.
- [ ] Together with ticket 14, this leaves PR6 functionally complete and ready for the full local physical qualification matrix.
