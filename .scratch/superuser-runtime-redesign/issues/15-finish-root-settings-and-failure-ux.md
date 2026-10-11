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
- [ ] PR #8 exception: automatic Superuser Mode Run Script authorization is separately opted in, device-local, default off, and never restored by backup/import. Successful Superuser Mode disable, including Disable anyway, resets it to off.
- [ ] With consent off or suspended, a root-selected script reports a typed failure without `su` acquisition or app-UID fallback. Only an admitted configured sync-completion event may make one bounded root authorization attempt on a worker; no unrelated callback gains this exception.
- [ ] Root Run Script authorization and execution are asynchronous and do not delay the sync-completion broadcast, REST event handling, or service thread. Normal Mode behavior remains unchanged.
- [ ] Run Script and runtime-operation admission are reserved before scheduling; operation identity and mode generation reject stale work. Concurrent root-script callbacks return a typed busy result without an unbounded queue. Admission remains owned until privileged work has truly settled.
- [ ] Policy transitions are serialized and durably committed before `su`: `OFF`, `ARMED`, `ATTEMPT_IN_PROGRESS`, or `SUSPENDED(reason)`. Failed writes, unknown states, interrupted work, denial, loss, and authorization timeout fail closed. Late workers cannot rearm after opt-out, suspension, destruction, or mode change.
- [ ] Settings explains unrestricted superuser script execution and possible background prompts without promising a root manager will show them. It shows suspension reason and a Resume/Retry control that arms future events only, never replays failed scripts.
- [ ] Ordinary nonzero script exit does not suspend authorization, stop Syncthing, change Run Conditions, or cause implicit mode fallback. No global cached shell, persistent helper, RootService/AIDL, undocumented manager option, or generic privileged command interface is added.
- [ ] Together with ticket 14, this leaves PR6 functionally complete and ready for the full local physical qualification matrix.
