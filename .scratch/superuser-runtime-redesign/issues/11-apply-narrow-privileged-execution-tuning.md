# 11: Apply execution tuning through narrow privileged capabilities

Triage: enhancement

Stack: PR5

**What to build:** Preserve useful privileged tuning without letting it define Superuser Mode or reintroduce process-name targeting. I/O priority targets only the exact Owned Execution, and the optional inotify watch-limit capability remains explicit and non-fatal.

**Blocked by:** 08: Preserve backup and import semantics in Superuser Mode.

**Status:** ready-for-agent

- [ ] I/O priority is applied only to the exact verified Syncthing PID and failure never blocks startup.
- [ ] No process-name scan is used for I/O priority.
- [ ] Inotify tuning is exposed as a distinct typed privileged capability rather than implicit Superuser Mode behavior.
- [ ] Applying the watch limit can report success/failure independently of Syncthing execution.
- [ ] No normal startup path prompts for root solely to perform optional tuning.
