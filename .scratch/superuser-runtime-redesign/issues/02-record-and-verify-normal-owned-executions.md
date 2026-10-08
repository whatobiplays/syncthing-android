# 02: Record and verify Normal Mode Owned Executions

Triage: enhancement

Stack: PR2

**What to build:** Give every Normal Mode Syncthing invocation durable ownership evidence so the app can later distinguish an Owned Execution from an Ambiguous Execution without relying on process names. Preserve the accepted small crash window between process creation and durable identity recording.

**Blocked by:** 01: Extract the mode-neutral Normal Mode runtime seam.

**Status:** ready-for-agent

- [ ] Normal Mode executions record PID, process start time, boot ID, expected bundled executable, and a random run token.
- [ ] Identity metadata is versioned, app-owned, and stored outside Managed State.
- [ ] Record cleanup happens only after proven exit and only when the current run token still matches.
- [ ] Recovery logic can classify exact, stale, missing, corrupt, and ambiguous identity evidence deterministically.
- [ ] Focused tests prove the Owned Execution and Ambiguous Execution contracts.
