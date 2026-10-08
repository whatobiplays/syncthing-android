# 05: Launch and stop an Owned Execution as superuser

Triage: enhancement

Stack: PR3

**What to build:** Run the closed Syncthing command vocabulary as UID 0 through RootBackend while preserving the same environment, ownership, exclusivity, and shutdown semantics used by Normal Mode. Keep the production root feature hidden.

**Blocked by:** 04: Add bounded RootBackend activation.

**Status:** ready-for-agent

- [ ] Each root Syncthing invocation uses a dedicated explicit root shell and the approved raw launch primitive ending in exec.
- [ ] The selected structured environment is shared with Normal Mode except for private root execution metadata.
- [ ] Root execution establishes and verifies the same strong Owned Execution evidence as the normal backend.
- [ ] At most one bundled Syncthing invocation can be owned at once, including one-shot commands.
- [ ] Root stop uses exact ownership and the shared bounded shutdown semantics.
- [ ] No generic arbitrary root command surface is exposed to callers.
