# Privileged Managed State and Backup/Import Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the closed Syncthing Managed State usable through the selected backend, including root-owned state, while preserving the established backup/import and HTTPS-certificate workflows.

**Architecture:** Keep `SyncthingService` as lifecycle authority and keep root transport behind `PrivilegeBackend`. Add a closed Managed State vocabulary plus semantic state-transfer and HTTPS-certificate capabilities; Normal Mode implements them with app-UID filesystem access, while `RootBackend` performs the same operations through one bounded operation-scoped helper shell. Backup/import uses fresh app-owned staging and shared archive code, never extracts directly into live Managed State.

**Tech Stack:** Java, Android minSdk 23, libsu core 6.0.0, Zip4j, JUnit/JVM tests.

**Spec:** `docs/superpowers/specs/2026-09-23-superuser-runtime-redesign.md` sections 13-17, 27, 29, 31, 33 (logical PR4); tracker tickets `.scratch/superuser-runtime-redesign/issues/07-operate-on-managed-state-through-selected-backend.md` and `08-preserve-backup-import-semantics-in-superuser-mode.md`.

## Global Constraints

- This is logical PR4 of the approved design and the next actual stacked PR after `codex/rootbackend-transport-execution-and-recovery`; keep the production Superuser UI hidden.
- Preserve the Android compatibility floor, including minSdk 23.
- Root transport uses libsu **core 6.0.0 only**. Do not add libsu service/nio, RootService, AIDL, global/static main-shell routing, or another privileged process.
- `SyncthingService` remains lifecycle authority. Do not add a general lifecycle actor, queue, executor, or persistent transition journal.
- Superuser behavior fails closed. A failed privileged state operation must never fall back to app-UID access.
- Managed State is exactly: `config.xml`, `cert.pem`, `key.pem`, `https-cert.pem`, `https-key.pem`, and `index-v2/`. Verify the current repository still uses this set before implementation; do not expand it.
- Never recursively mutate the whole application data directory, user synchronization folders, or unrelated app data. Apply no-follow/symlink-safe handling where required.
- Root-created Managed State may remain root-owned across ordinary root stops. Ownership/context repair runs only as an explicit semantic operation.
- Privileged callers receive semantic operations only. Do not expose arbitrary root command execution, arbitrary privileged path access, or generic root file read/write/delete APIs.
- Root helper shells are operation-scoped. A compound state operation uses at most one helper shell and closes it when the operation ends.
- Stopped-state mutation must continue using the existing narrow stop -> mutate -> optional restart ownership. It may not overlap an active bundled Syncthing invocation.
- Backup/import keeps the established pre-root external archive shape, password/encryption behavior, and user workflow. Do not add compatibility for the discarded unreleased root-export shape.
- Imported root-mode state is never authoritative for device-local Execution Mode. The current device-local mode must survive import.
- Tests travel with the behavior in this PR; ordinary tests require no real `su`.
- Preserve the existing untracked project/spec/ADR/IDE files. Implementation work remains uncommitted and unpushed for human review; the project close-out workflow owns commit/push.

## Corrective Plan Amendments

These amendments refine Tasks 1-5 and take precedence over any conflicting implementation detail below. They do not expand PR4 into later folder, script, tuning, UI, or mode-switching work.

1. **Keep staging creation out of `PrivilegeBackend`.** The backend exposes only selected-execution behavior, including `managedStateTransfer()` and `httpsCertificateStorage()`. Do not add `newManagedStateStaging()` or identical staging pass-through methods to both backends. Create fresh import staging through `DefaultSyncthingRuntime`, a shared package-private factory, or another narrow mode-neutral seam.
2. **Preserve byte-exact `ConfigStorage`.** `ConfigStorage.load()` returns `byte[]`; root reads must preserve all bytes, including trailing newlines. Use a fixed encoded transport or validated operation-owned staging, with command construction and decoding confined to `LibsuRootShell`. Do not add a generic privileged path/read API. `ConfigXml` remains mode-neutral and must not import, catch, inspect, or branch on root-specific failures; adapters may preserve those failures as causes of its existing checked storage failure.
3. **Prove root-to-app staging handoff.** Ownership and SELinux context changes are permitted only under the current validated operation-owned staging tree, never on live Managed State during ordinary export. Before `snapshotForExport()` succeeds, validate the staging root and copied descendants have the expected app UID/GID, established context, and actual app access for reading and cleanup. Treat handoff/context failures as fatal; clean only the current proven operation-owned tree.
4. **Make `repairAppAccess()` retry-safe.** Keep repair limited to `config.xml`, `cert.pem`, `key.pem`, `https-cert.pem`, `https-key.pem`, and `index-v2/`. Do not skip context repair after a previous attempt changed ownership. A partial ownership change followed by context/traversal failure must be recoverable on a later call and from a new helper/backend instance. Report success only after app access is verified. Test partial ownership success, context failure, retry success, and untouched unrelated state.
5. **Validate archives and preference payloads before live mutation.** Validate all ZIP entries before extraction: allow only approved top-level members, `sharedpreferences.dat`, and descendants of `index-v2/`; reject absolute/traversing/escaping paths, duplicate singleton entries, type collisions, and representable symlink/special-file entries. Extract only into fresh app-owned staging. Validate required `config.xml`, `cert.pem`, `key.pem` and deserialize/validate any present `sharedpreferences.dat` before shutdown or installation. Missing optional HTTPS files, `index-v2`, and `sharedpreferences.dat` retain their existing semantics.
6. **Make archive export transactional.** Write to a temporary archive beside the destination, reopen and validate the completed entry set and encryption mode, and replace the destination only after validation. If writing or validation fails, clean the temporary archive and preserve the prior valid backup.

Add deterministic coverage for base64 command failure and malformed/truncated output, app-UID read/write repair and typed deletion failures, exact certificate rollback and snapshot failure, root-transport failure without app-UID fallback, operation-tree cleanup/confinement, optional HTTPS/preferences absence, malformed preferences before install, local `use_root` true/false/absent against opposite archived values, and `FileMutationBarrier`/`PostMutationStartupGate` ownership and cancellation cases. Keep real-root/SELinux/device qualification explicitly separate from JVM evidence.

## Review Focus

1. **Staging path/symlink escape:** a crafted or stale staging tree must never cause a root operation to touch anything outside the internally generated app-private transfer directory. Pin this in Tasks 1-2.
2. **Partial privileged mutation:** root timeout/transport failure during snapshot, install, certificate replace/reset, or repair must fail deterministically without silently switching backend or broadening cleanup. Pin this in Tasks 2 and 4.
3. **Legacy archive without `index-v2`:** import must remove stale local database state rather than retaining the previous installation's index. Pin this in Task 3.
4. **Missing optional Managed State members:** export/import/certificate workflows must preserve existing semantics when optional HTTPS certificate material or database state is absent. Pin this in Tasks 1, 3, and 4.
5. **Lifecycle cancellation/conflict:** rejected, interrupted, or failed file mutation must still release the existing ownership/barrier correctly and must not restart from partially installed state. Pin this in Tasks 3-4.

---

### Task 1: Define the closed Managed State boundary and Normal Mode implementation

**Files:**
- Create: `app/src/main/java/com/nutomic/syncthingandroid/runtime/ManagedStateMember.java`
- Create: `app/src/main/java/com/nutomic/syncthingandroid/runtime/ManagedStateStaging.java`
- Create: `app/src/main/java/com/nutomic/syncthingandroid/runtime/ManagedStateTransfer.java`
- Create: `app/src/main/java/com/nutomic/syncthingandroid/runtime/ManagedStateFailure.java`
- Create: `app/src/main/java/com/nutomic/syncthingandroid/runtime/ManagedStateException.java`
- Create: `app/src/main/java/com/nutomic/syncthingandroid/runtime/HttpsCertificateState.java`
- Create: `app/src/main/java/com/nutomic/syncthingandroid/runtime/HttpsCertificateStorage.java`
- Create: `app/src/main/java/com/nutomic/syncthingandroid/runtime/AppUidManagedStateTransfer.java`
- Create: `app/src/main/java/com/nutomic/syncthingandroid/runtime/AppUidHttpsCertificateStorage.java`
- Modify: `app/src/main/java/com/nutomic/syncthingandroid/runtime/PrivilegeBackend.java`
- Modify: `app/src/main/java/com/nutomic/syncthingandroid/runtime/AppUidBackend.java`
- Modify: `app/src/main/java/com/nutomic/syncthingandroid/runtime/DefaultSyncthingRuntime.java`
- Modify if needed to expose the fixed database member name without duplicating literals: `app/src/main/java/com/nutomic/syncthingandroid/service/Constants.java`
- Test: `app/src/test/java/com/nutomic/syncthingandroid/runtime/AppUidManagedStateTransferTest.java`
- Test: `app/src/test/java/com/nutomic/syncthingandroid/runtime/AppUidHttpsCertificateStorageTest.java`
- Test: `app/src/test/java/com/nutomic/syncthingandroid/runtime/RuntimeSeamTest.java`

**Interfaces:**
- `ManagedStateMember` is the single closed vocabulary for the six approved members and records whether each member is a file or directory. It has no caller-supplied path constructor.
- `ManagedStateStaging` represents one fresh internally generated app-private transfer directory, exposes its directory only for shared archive code, validates its provenance before backend installation, and owns cleanup of only that generated directory.
- `ManagedStateStaging snapshotForExport() throws ManagedStateException` returns a fresh app-readable staging area containing only existing approved members.
- `void installImportedState(ManagedStateStaging staging) throws ManagedStateException` replaces only approved Managed State. A staging area without `index-v2` removes any stale live `index-v2`.
- `void repairAppAccess() throws ManagedStateException` repairs only approved Managed State; the app-UID implementation is a bounded verify/no-op as appropriate.
- `ManagedStateFailure` is root-independent and includes the stable semantic causes needed by this slice: `STATE_ACCESS_FAILED`, `STATE_TRANSFER_FAILED`, and `STATE_REPAIR_FAILED`; `ManagedStateException` carries one of those causes. Root acquisition/UID/transport failures retain their existing `RootFailure` vocabulary rather than being collapsed into a filesystem error.
- `HttpsCertificateState snapshot() throws IOException`, `replace(byte[] certPem, byte[] keyPem)`, `reset()`, and `restore(HttpsCertificateState state)` model the certificate pair without leaking live `File` handles.
- `PrivilegeBackend` exposes `managedStateTransfer()` and `httpsCertificateStorage()`; `DefaultSyncthingRuntime` delegates these capabilities without branching on execution mode.
- Existing `ConfigStorage` remains the semantic configuration interface; Normal Mode behavior must remain equivalent.

- [ ] **Step 1: Write failing tests for the closed manifest and staging provenance**

Assert that the manifest contains exactly `config.xml`, `cert.pem`, `key.pem`, `https-cert.pem`, `https-key.pem`, and `index-v2`; staging uses unique generated directories under one dedicated app-private base; forged/out-of-base staging and symlink escapes are rejected; cleanup cannot delete outside its owned generated directory.

- [ ] **Step 2: Run the focused tests and verify they fail**

Run: `./gradlew --no-daemon :app:testDebugUnitTest --tests 'com.nutomic.syncthingandroid.runtime.AppUidManagedStateTransferTest'`

Expected: FAIL because the Managed State/staging types do not exist yet.

- [ ] **Step 3: Implement the closed vocabulary, staging type, and app-UID transfer**

Use the fixed manifest only. Snapshot copies existing approved members into a fresh staging directory. Install validates provenance first, replaces only approved members, and explicitly removes live `index-v2` when the imported staging omits it. Preserve existing tolerance for optional HTTPS certificate/database members.

- [ ] **Step 4: Write and implement certificate-state tests**

Cover all four prior-state combinations (both files present, both absent, cert-only, key-only), atomic replacement, reset, restore after failure, private-key owner permissions, and failure without destructive “unread means absent” behavior.

Run: `./gradlew --no-daemon :app:testDebugUnitTest --tests 'com.nutomic.syncthingandroid.runtime.AppUidHttpsCertificateStorageTest'`

Expected: PASS.

- [ ] **Step 5: Extend the backend/runtime seam and prove Normal Mode delegation**

Update `PrivilegeBackend`, `AppUidBackend`, and `DefaultSyncthingRuntime`. Extend `RuntimeSeamTest` so the new state capabilities flow through the selected backend and no root-specific branch appears above backend selection.

Run: `./gradlew --no-daemon :app:testDebugUnitTest --tests 'com.nutomic.syncthingandroid.runtime.RuntimeSeamTest' --tests 'com.nutomic.syncthingandroid.runtime.AppUidManagedStateTransferTest' --tests 'com.nutomic.syncthingandroid.runtime.AppUidHttpsCertificateStorageTest'`

Expected: PASS.

- [ ] **Step 6: Review checkpoint**

Review the Task 1 diff only. Do not stage, commit, or push.

---

### Task 2: Implement Managed State through the bounded RootBackend transport

**Files:**
- Modify: `app/src/main/java/com/nutomic/syncthingandroid/runtime/RootShell.java`
- Modify: `app/src/main/java/com/nutomic/syncthingandroid/runtime/LibsuRootShell.java`
- Modify: `app/src/main/java/com/nutomic/syncthingandroid/runtime/RootBackend.java`
- Modify: `app/src/test/java/com/nutomic/syncthingandroid/runtime/FakeRootTransport.java`
- Modify: `app/src/test/java/com/nutomic/syncthingandroid/runtime/LibsuRootShellTest.java`
- Modify: `app/src/test/java/com/nutomic/syncthingandroid/runtime/RootBackendTest.java`

**Interfaces:**
- `RootBackend.configStorage()`, `managedStateTransfer()`, and `httpsCertificateStorage()` implement the semantic capabilities instead of returning `PRIVILEGED_STATE_NOT_IMPLEMENTED`.
- `RootShell` gains only fixed semantic helpers required by those capabilities. No method accepts arbitrary command text, executable selection, or an unconstrained privileged filesystem path.
- Root state operations use `RootBackend`'s existing bounded helper-session acquisition. One compound snapshot/install/certificate/repair operation uses one helper shell.
- Transport command encoding and parsing remain inside `LibsuRootShell`; libsu types remain confined there.
- Semantic state failures use the root-independent `ManagedStateFailure` vocabulary from Task 1; root acquisition/UID/transport failures keep the existing `RootFailure` cause. Neither path may silently fall back to app-UID behavior.

- [ ] **Step 1: Add failing root-backend tests for semantic state access**

Cover root configuration load/save, export snapshot, import install, certificate snapshot/replace/reset/restore, explicit access repair, missing optional members, and one-helper-session-per-compound-operation. Assert root transport failure/timeout closes the helper and propagates a typed failure without app-UID fallback.

- [ ] **Step 2: Add failing transport tests for confinement and no-follow behavior**

In `LibsuRootShellTest`/fake transport, prove every operation is limited to the six fixed Managed State members and the validated staging root. Exercise malicious symlink/path cases and verify no command can be repurposed to act on a user sync folder or unrelated app-private path.

- [ ] **Step 3: Run the focused root tests and verify they fail**

Run: `./gradlew --no-daemon :app:testDebugUnitTest --tests 'com.nutomic.syncthingandroid.runtime.RootBackendTest' --tests 'com.nutomic.syncthingandroid.runtime.LibsuRootShellTest'`

Expected: FAIL on the currently unimplemented privileged-state surface.

- [ ] **Step 4: Implement the fixed root state transport**

Implement only the semantic operations from Task 1. Keep all shell text internal and safely encoded. Snapshot copies live Managed State into the already-created validated staging directory and leaves the live state root-owned. Install replaces only approved members. Repair restores application UID/GID and appropriate SELinux context only for Managed State. Directory handling for `index-v2` must not follow symlinks outside that member.

- [ ] **Step 5: Replace the root fail-closed placeholder for state only**

Wire `RootBackend.configStorage()`, `managedStateTransfer()`, and `httpsCertificateStorage()`. Leave folder access and Run Script methods as later-slice fail-closed placeholders.

- [ ] **Step 6: Run the root/runtime focused suite**

Run: `./gradlew --no-daemon :app:testDebugUnitTest --tests 'com.nutomic.syncthingandroid.runtime.RootBackendTest' --tests 'com.nutomic.syncthingandroid.runtime.LibsuRootShellTest' --tests 'com.nutomic.syncthingandroid.runtime.RuntimeSeamTest'`

Expected: PASS.

- [ ] **Step 7: Review checkpoint**

Confirm no production libsu import moved outside the existing root transport and no generic privileged command/path API was introduced. Do not stage, commit, or push.

---

### Task 3: Move backup/import onto fresh staging and the selected backend

**Files:**
- Create: `app/src/main/java/com/nutomic/syncthingandroid/service/SyncthingBackupArchive.java`
- Modify: `app/src/main/java/com/nutomic/syncthingandroid/service/SyncthingService.java`
- Modify: `app/src/main/java/com/nutomic/syncthingandroid/service/Constants.java`
- Test: `app/src/test/java/com/nutomic/syncthingandroid/service/SyncthingBackupArchiveTest.java`
- Test: `app/src/test/java/com/nutomic/syncthingandroid/service/FileMutationBarrierTest.java`
- Test: `app/src/test/java/com/nutomic/syncthingandroid/service/PostMutationStartupGateTest.java`
- Test as needed for extracted pure helpers: `app/src/test/java/com/nutomic/syncthingandroid/service/SyncthingServiceBackupPolicyTest.java`

**Interfaces:**
- `SyncthingBackupArchive` owns the established Zip4j archive encoding/decoding and SharedPreferences payload placement; it operates on `ManagedStateStaging`, never live `filesDir`.
- Export sequence: existing file-mutation stop ownership -> selected backend `snapshotForExport()` -> write `sharedpreferences.dat` into staging -> create the established archive -> clean staging -> existing restart policy.
- Import sequence: open/validate archive -> create fresh app-owned import staging -> extract there -> validate required `config.xml`, `cert.pem`, and `key.pem` -> stop Syncthing -> import SharedPreferences from staging while preserving device-local execution mode -> selected backend `installImportedState(staging)` -> existing database/folder cleanup policy -> cleanup -> restart according to the existing post-mutation gate.
- The archive entry names and encryption/password behavior remain unchanged.
- `sharedpreferences.dat` remains an archive adjunct, not part of Managed State.

- [ ] **Step 1: Write archive-contract tests before moving service code**

Assert Normal and root snapshots produce the same top-level pre-root archive shape; password/no-password archives remain readable under existing behavior; required config/device identity files are enforced; unknown archive content can never be installed into live app state.

- [ ] **Step 2: Pin legacy absent-index behavior**

Add a regression test importing a valid archive with no `index-v2` over a staging/live state that already has an index. Assert installation removes the stale index instead of retaining it.

- [ ] **Step 3: Pin device-local mode preservation**

Define the device-local preference key once as `Constants.PREF_USE_ROOT = "use_root"` without adding any production writer or UI. Before the existing SharedPreferences clear/import, capture both `contains(PREF_USE_ROOT)` and its current boolean value; ignore any archived `use_root`; after applying imported preferences, restore the prior local value only when it was previously present. A missing local key stays missing. Tests must cover local true, local false, local absent, and an archive containing the opposite value.

- [ ] **Step 4: Run the new policy/archive tests and verify they fail**

Run: `./gradlew --no-daemon :app:testDebugUnitTest --tests 'com.nutomic.syncthingandroid.service.SyncthingBackupArchiveTest' --tests 'com.nutomic.syncthingandroid.service.SyncthingServiceBackupPolicyTest'`

Expected: FAIL because archive/staging policy is still embedded in `SyncthingService`.

- [ ] **Step 5: Extract shared archive logic and refactor `exportConfig()`**

Do not create `sharedpreferences.dat` in live Managed State. Use the selected backend snapshot, add preferences in staging, build the same target ZIP, and always clean only the operation-owned staging directory.

- [ ] **Step 6: Refactor `importConfig()` to extract before shutdown and install after proven stop**

Extraction and archive validation happen in fresh app-owned staging before the live mutation. After `shutdownForFileMutation()` succeeds, install through the selected backend. A failed/partial install follows the existing import failure path and must not auto-start Syncthing from partially installed state.

- [ ] **Step 7: Preserve existing lifecycle ownership semantics**

Extend the existing barrier/post-mutation tests for rejection, interrupted waiter, shutdown failure, and successful import restart. Do not replace `FileMutationBarrier` or `PostMutationStartupGate`.

Run: `./gradlew --no-daemon :app:testDebugUnitTest --tests 'com.nutomic.syncthingandroid.service.SyncthingBackupArchiveTest' --tests 'com.nutomic.syncthingandroid.service.SyncthingServiceBackupPolicyTest' --tests 'com.nutomic.syncthingandroid.service.FileMutationBarrierTest' --tests 'com.nutomic.syncthingandroid.service.PostMutationStartupGateTest'`

Expected: PASS.

- [ ] **Step 8: Review checkpoint**

Compare an exported archive against the established names/shape and confirm no compatibility code for the discarded root-export format exists. Do not stage, commit, or push.

---

### Task 4: Route HTTPS certificate mutation through selected-backend Managed State

**Files:**
- Modify: `app/src/main/java/com/nutomic/syncthingandroid/service/SyncthingService.java`
- Modify only if state-machine policy needs a pure seam: `app/src/main/java/com/nutomic/syncthingandroid/service/CertificateVerificationState.java`
- Test: `app/src/test/java/com/nutomic/syncthingandroid/service/CertificateVerificationStateTest.java`
- Test: `app/src/test/java/com/nutomic/syncthingandroid/service/SyncthingServiceCertificatePolicyTest.java`
- Reuse/extend: `app/src/test/java/com/nutomic/syncthingandroid/runtime/AppUidHttpsCertificateStorageTest.java`
- Reuse/extend: `app/src/test/java/com/nutomic/syncthingandroid/runtime/RootBackendTest.java`

**Interfaces:**
- Service-level certificate workflow keeps the existing asynchronous stop/restart/verify/rollback state machine.
- File mutation itself uses `mSyncthingRuntime.httpsCertificateStorage()`; `SyncthingService` no longer renames/writes live HTTPS certificate files directly.
- Rollback restores the exact captured presence/content state. A snapshot failure aborts before mutation and can never be interpreted as “certificate absent.”
- A root-backed certificate mutation uses root only for the explicit stopped-state action and never creates a passive prompt path.

- [ ] **Step 1: Write failing service-policy tests**

Cover replacement success, reset success, no-run pending-start result, startup verification failure with rollback, explicit stop during verification, snapshot failure before mutation, mutation failure with rollback, and lifecycle conflict/busy rejection.

- [ ] **Step 2: Run the certificate tests and verify the new selected-backend expectations fail**

Run: `./gradlew --no-daemon :app:testDebugUnitTest --tests 'com.nutomic.syncthingandroid.service.CertificateVerificationStateTest' --tests 'com.nutomic.syncthingandroid.service.SyncthingServiceCertificatePolicyTest'`

Expected: FAIL while `SyncthingService` still operates on direct `File` handles.

- [ ] **Step 3: Replace direct certificate file helpers with the semantic storage**

Keep `FileMutationBarrier`, verification watchdog, state listener, STOP suppression, and restart policy unchanged. Remove now-dead direct backup/restore/write helpers only when no other caller needs them.

- [ ] **Step 4: Run certificate plus root state tests**

Run: `./gradlew --no-daemon :app:testDebugUnitTest --tests 'com.nutomic.syncthingandroid.service.CertificateVerificationStateTest' --tests 'com.nutomic.syncthingandroid.service.SyncthingServiceCertificatePolicyTest' --tests 'com.nutomic.syncthingandroid.runtime.AppUidHttpsCertificateStorageTest' --tests 'com.nutomic.syncthingandroid.runtime.RootBackendTest'`

Expected: PASS.

- [ ] **Step 5: Review checkpoint**

Confirm certificate operations use the selected backend, remain explicit stopped-state mutations, and do not modify the feature/UI/mode-switching surface. Do not stage, commit, or push.

---

### Task 5: Cross-slice regression and architecture verification

**Files:**
- Modify focused tests only where evidence is missing from Tasks 1-4.
- Do not add PR5 folder/script/tuning behavior, PR6 UI/mode-switching/onboarding behavior, or PR7 general structural cleanup.

**Interfaces:**
- No new production interface is expected in this task.

- [ ] **Step 1: Run focused state/backup/certificate tests**

Run:
`./gradlew --no-daemon :app:testDebugUnitTest --tests 'com.nutomic.syncthingandroid.runtime.*ManagedState*' --tests 'com.nutomic.syncthingandroid.runtime.RootBackendTest' --tests 'com.nutomic.syncthingandroid.runtime.RuntimeSeamTest' --tests 'com.nutomic.syncthingandroid.service.SyncthingBackupArchiveTest' --tests 'com.nutomic.syncthingandroid.service.SyncthingServiceBackupPolicyTest' --tests 'com.nutomic.syncthingandroid.service.*Certificate*' --tests 'com.nutomic.syncthingandroid.service.FileMutationBarrierTest' --tests 'com.nutomic.syncthingandroid.service.PostMutationStartupGateTest'`

Expected: PASS.

- [ ] **Step 2: Run the repository's full automated validation**

Run: `make test`

Expected: PASS. If the repository's current validation entry point has changed, use the repo-declared equivalent and report the exact command/result rather than changing build configuration to make validation pass.

- [ ] **Step 3: Check the architectural boundaries**

Verify from the final diff:
- only the root transport package/class talks to libsu;
- no RootService/AIDL/libsu service/nio/global-shell route appeared;
- no generic root command or arbitrary privileged-path API appeared;
- root folder access, Run Script, inotify, mode switching, UI, onboarding, and state-repair UX remain deferred;
- live Managed State is never recursively repaired beyond the six-member contract;
- backup import cannot restore an archived root mode;
- root state failures never select app-UID behavior;
- tests are in this PR with the behavior they verify.

- [ ] **Step 4: Leave the implementation ready for human review**

Report changed files, focused/full validation results, any limitations, and any physical qualification still NOT RUN. Leave all implementation changes uncommitted and unpushed.
