# 16: Lock down the architecture and remove residual scaffolding

Triage: enhancement

Stack: PR7

**What to build:** Harden the completed feature against architectural regression and remove only residual scaffolding that is genuinely obsolete. This ticket must not contain functionality required to make Superuser Mode work; PR6 is already functionally complete.

**Blocked by:** 14: Disable Superuser Mode and repair state access; 15: Finish root-specific settings and failure UX.

**Status:** ready-for-agent

- [ ] Structural checks enforce that only root transport imports libsu and that RootService/AIDL/libsu service/nio are absent.
- [ ] Structural checks reject generic root command/path APIs, multiple production writers for the mode preference, process-name Syncthing killing, and bundled Syncthing invocation paths that bypass the runtime/backend boundary.
- [ ] Backup/import regression checks prove archived root mode cannot change the device-local Execution Mode.
- [ ] Residual dead code or obsolete dependency remnants discovered during the stack are removed without inventing a legacy-root migration that does not exist on upstream main.
- [ ] Final cross-cutting integration tests and documentation are consistent with the approved domain vocabulary and ADRs.
- [ ] The completed seven-PR stack remains independently reviewable and green.
