# 10: Run folder completion scripts under the selected identity

Triage: enhancement

Stack: PR5

**What to build:** Preserve the existing sync-completion Run Script feature while making its execution identity match the selected Execution Mode. Root scripts must remain a narrowly defined folder/event capability rather than becoming an arbitrary root shell API.

**Blocked by:** 08: Preserve backup and import semantics in Superuser Mode.

**Status:** ready-for-agent

- [ ] Existing sync_complete behavior remains unchanged for Normal Mode.
- [ ] Superuser Mode runs approved folder scripts as UID 0 through an operation-scoped helper shell.
- [ ] Script execution resolves the authoritative configured folder and allowed script set from semantic inputs.
- [ ] Scripts may run while Syncthing serve remains active.
- [ ] Orderly teardown stops admitting new script work and performs bounded handling of current script work.
- [ ] Arbitrary child processes deliberately spawned by user scripts are not treated as Syncthing Owned Executions.
