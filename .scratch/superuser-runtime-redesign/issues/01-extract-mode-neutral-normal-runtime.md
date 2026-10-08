# 01: Extract the mode-neutral Normal Mode runtime seam

Triage: enhancement

Stack: PR1

**What to build:** Preserve today's Normal Mode user behavior while routing Syncthing execution and the existing mode-sensitive state, folder, environment, and Run Script operations through one mode-neutral runtime/backend seam. This is the prefactor that later tickets extend with Superuser Mode; it must not expose root behavior or change existing Normal Mode workflows.

**Blocked by:** None (can start immediately).

**Status:** ready-for-agent

- [ ] Normal Mode startup, one-shot Syncthing commands, state/config access, folder access, environment construction, and Run Script behavior still work through the new mode-neutral runtime/backend boundary.
- [ ] Existing Run Conditions, REST/config routing, logging, foreground-service behavior, backup/import workflow, and stopped-state editing remain behaviorally equivalent.
- [ ] Callers above backend selection do not need a root-specific branch or root-specific API.
- [ ] The slice is independently buildable and its focused tests pass.
