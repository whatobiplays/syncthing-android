# 09: Perform configured-folder access through the selected backend

Triage: enhancement

Stack: PR5

**What to build:** Make restricted-folder checks and conflict discovery work under the selected identity without exposing arbitrary privileged paths. Existing configured folders are addressed semantically; candidate paths for new folders use a separate validation capability.

**Blocked by:** 08: Preserve backup and import semantics in Superuser Mode.

**Status:** ready-for-agent

- [ ] Existing-folder privileged operations resolve authoritative paths from folder identity rather than caller-supplied root paths.
- [ ] New-folder validation is a separate typed candidate-folder operation.
- [ ] Writeability checks leave no fixed .stwritetest artifact and introduce no general privileged filesystem API.
- [ ] Conflict discovery runs under the selected backend and cannot be repurposed as generic root find execution.
- [ ] Folder access failures are typed and cancellation/teardown aware.
