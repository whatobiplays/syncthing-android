# 06: Recover root executions across Android process death

Triage: enhancement

Stack: PR3

**What to build:** Make a root Syncthing process that outlives the Android app recover safely on the next launch. The app must verify and terminate its prior Owned Execution rather than adopt it, preserve useful logs across the orphan window, and fail closed when root or identity proof is insufficient.

**Blocked by:** 05: Launch and stop an Owned Execution as superuser.

**Status:** ready-for-agent

- [ ] A verified surviving root execution is terminated, proven exited, and followed by a fresh start only when Run Conditions require it.
- [ ] Missing/corrupt evidence plus a candidate process produces Ambiguous Execution behavior and never authorizes a signal.
- [ ] Lost root authorization during recovery leaves the process untouched and prevents replacement launch.
- [ ] Boot-ID mismatch or proven process absence clears stale identity safely.
- [ ] Root output survives app death through an app-owned per-run spool and is reconciled into existing logging on recovery/exit.
- [ ] PR3 root launch, stop, force-stop, and recovery smoke scenarios are ready for local device qualification.
