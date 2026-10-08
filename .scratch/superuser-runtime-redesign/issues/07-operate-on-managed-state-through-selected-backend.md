# 07: Operate on Managed State through the selected backend

Triage: enhancement

Stack: PR4

**What to build:** Let semantic configuration, certificate, and database state operations work under either selected backend while keeping Managed State a closed, bounded set. Root-created state may remain root-owned during Superuser Mode, but ownership repair must never broaden into recursive whole-app mutation.

**Blocked by:** 06: Recover root executions across Android process death.

**Status:** ready-for-agent

- [ ] The Managed State contract is closed to the approved Syncthing configuration, key/certificate, and index/database state.
- [ ] Stopped-state operations use the selected backend and cannot overlap an active Syncthing invocation.
- [ ] Ownership/context repair affects only Managed State and never user synchronization folders or unrelated app data.
- [ ] Missing optional state members are handled according to existing workflow semantics.
- [ ] Root-created Managed State may remain root-owned across ordinary root stops.
- [ ] State operation and repair failure modes are deterministic and covered by tests.
