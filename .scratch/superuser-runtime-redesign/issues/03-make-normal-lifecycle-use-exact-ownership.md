# 03: Make Normal Mode lifecycle depend on exact ownership

Triage: enhancement

Stack: PR2

**What to build:** Make Normal Mode startup, shutdown, and recovery safe against PID reuse and stale processes by allowing signals only against a verified Owned Execution. Replace unbounded startup readiness with a terminal success/failure outcome while preserving existing service lifecycle meaning.

**Blocked by:** 02: Record and verify Normal Mode Owned Executions.

**Status:** ready-for-agent

- [ ] Process-name-based Syncthing termination is absent from the refactored lifecycle path.
- [ ] Startup reaches ACTIVE only after execution verification plus REST/config readiness.
- [ ] Startup readiness is bounded to the approved 60-second policy and timeout terminates only the exact Owned Execution.
- [ ] Shutdown follows bounded REST shutdown, then SIGINT, then SIGKILL escalation with ownership verification before signaling.
- [ ] Ambiguous candidate processes are never signaled and prevent unsafe replacement launch.
- [ ] Normal Mode lifecycle regression tests remain green.
