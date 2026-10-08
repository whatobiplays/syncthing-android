# 14: Disable Superuser Mode and repair state access

Triage: enhancement

Stack: PR6

**What to build:** Make the root-to-normal transition truthful even when root-owned Managed State cannot immediately be repaired. A live root Syncthing must always be proven exited first; state repair may be bypassed only through the explicit confirmed Disable anyway path, followed by an explicit repair action.

**Blocked by:** 13: Enable Superuser Mode from Settings.

**Status:** ready-for-agent

- [ ] Disabling cannot commit Normal Mode until any live root Owned Execution has proven exit.
- [ ] A mode transition closes admission to old-backend mutations and waits for their bounded completion or cancellation before state repair or mode commit; state repair never overlaps an earlier Managed State mutation.
- [ ] In-flight Run Script work follows the bounded teardown contract in ticket 10 before the mode commits; if old-mode work cannot reach a safe terminal state, the transition remains failed in the old mode.
- [ ] Normal disable repairs Managed State and verifies normal access before restarting according to Run Conditions.
- [ ] If repair is unavailable because root cannot be acquired, the user can cancel or explicitly choose Disable anyway.
- [ ] Disable anyway bypasses only state ownership repair/access; it still requires proven Syncthing exit and safe completion of admitted old-mode work, does not attempt an unsafe normal launch, and exposes STATE_ACCESS_REPAIR_REQUIRED.
- [ ] Repair Syncthing state access may request root while Normal Mode remains selected and never silently re-enables Superuser Mode.
- [ ] Transition crash recovery derives state from the committed mode, execution evidence, and actual state access without a persistent transition journal.
