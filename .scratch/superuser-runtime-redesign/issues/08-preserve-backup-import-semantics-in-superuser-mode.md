# 08: Preserve backup and import semantics in Superuser Mode

Triage: enhancement

Stack: PR4

**What to build:** Make export and import operate correctly against root-owned Managed State without changing the established external backup format, import workflow, or device-local Execution Mode.

**Blocked by:** 07: Operate on Managed State through the selected backend.

**Status:** ready-for-agent

- [ ] Normal Mode and Superuser Mode produce the same established pre-root archive shape.
- [ ] Export snapshots Managed State into fresh app-readable private staging before shared archive creation.
- [ ] Import extracts to fresh app-owned staging and installs only approved Managed State members while Syncthing is stopped.
- [ ] The archived root preference is ignored and the current device-local Execution Mode is preserved.
- [ ] Valid legacy archives that omit index-v2 preserve existing import semantics without retaining stale local database state.
- [ ] No compatibility path is added for the discarded buggy root-export shape.
