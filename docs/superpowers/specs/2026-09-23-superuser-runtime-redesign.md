# Superuser Runtime Redesign

## 1. Status

Approved implementation contract.

This document captures the confirmed redesign for superuser support in Syncthing Android. It replaces the prior modern-root architecture rather than incrementally correcting it.

For this work, this document supersedes:

- `docs/superpowers/specs/2026-08-25-modern-superuser-access-design.md`
- `docs/superpowers/specs/2026-09-01-unified-syncthing-runtime-design.md`
- implementation-specific lifecycle and RootService plans derived from those designs

The old documents remain useful as failure history and test-case inspiration. They are not architectural authority for the replacement.

Terminology is defined in `/CONTEXT.md`.

## 2. Objective

Restore the simple product model proven by the earlier root prototype:

> **Run Syncthing as Superuser**

while replacing its scattered root branches with a small, typed, testable execution boundary.

The redesign must:

1. preserve one Syncthing Installation across Normal Mode and Superuser Mode;
2. keep normal-mode behavior as close to current `main` as possible;
3. fail closed when Superuser Mode is selected but root capability is unavailable;
4. never signal a process whose ownership is not proven;
5. keep root-specific transport isolated from application business logic;
6. preserve backup/import format and existing user workflows;
7. avoid introducing a new lifecycle architecture in the same change set;
8. be deliverable as a linear stack of small, independently green PRs suitable for upstream review.

## 3. Scope

This work changes the Android wrapper only.

It does not change Syncthing core.

The feature must support the repository's existing Android compatibility floor, including minSdk 23. Root support must not introduce a higher Android floor.

## 4. Non-goals

This redesign does not:

- introduce a privileged Android `RootService`;
- introduce AIDL for root execution;
- add arbitrary root shell execution to application callers;
- add arbitrary privileged filesystem APIs;
- add separate root and normal Syncthing installations;
- persist a full lifecycle or transition journal;
- redesign `SyncthingService` around a new actor, queue, or executor;
- add a watchdog solely to kill root Syncthing after application death;
- silently fall back from Superuser Mode to Normal Mode;
- add compatibility for unreleased buggy root-export archive shapes;
- store physical qualification artifacts in the repository;
- modernize unrelated normal-mode lifecycle behavior merely because this feature touches it.

A later PR may improve the broader lifecycle architecture after this stack lands.

## 5. Target architecture

The application-facing shape is:

```text
SyncthingService
      |
      v
DefaultSyncthingRuntime
      |
      v
PrivilegeBackend
   /             \
AppUidBackend   RootBackend
```

### 5.1 SyncthingService

`SyncthingService` remains the lifecycle authority in this initial redesign.

It continues to own:

- `INIT / STARTING / ACTIVE / DISABLED / ERROR`;
- Run Conditions;
- REST readiness;
- notification behavior;
- restart policy;
- foreground-service ownership;
- existing lifecycle integration points.

This is deliberate scope control. Lifecycle ownership may move later, but not as part of this root rewrite.

### 5.2 DefaultSyncthingRuntime

`DefaultSyncthingRuntime` owns mode-neutral execution mechanics and semantic operations that must work under either selected backend.

Its responsibilities include:

- launching an approved Syncthing invocation;
- exact execution inspection and termination;
- orphan/recovery handling;
- semantic Managed State operations;
- semantic folder operations;
- Run Script dispatch;
- root-independent result/error vocabulary.

It must not expose `isRoot`, `runAsRoot`, `su`, libsu types, or generic shell execution to callers.

### 5.3 PrivilegeBackend

`PrivilegeBackend` is mode-neutral from the runtime's perspective.

The production implementations are:

- `AppUidBackend`
- `RootBackend`

Above this boundary, callers do not branch on root.

The selected backend is immutable for a selected-mode session. Changing Execution Mode disposes the old runtime/backend and creates a new one.

## 6. Root technology boundary

Use **libsu core 6.0.0 only**.

Allowed dependency:

- libsu `core`

Disallowed for this design:

- libsu `service`
- libsu `nio`
- `RootService`
- AIDL root IPC

All libsu references belong inside the RootBackend transport package.

Production code must not use libsu's global/static main-shell APIs as a general execution surface. RootBackend owns explicit shell instances.

### 6.1 Root activation

Root acquisition is asynchronous and bounded.

The activation timeout is initially **60 seconds**.

Activation must distinguish at least:

- root denied;
- root unavailable;
- activation timeout;
- transport/shell failure;
- UID verification failure.

No activation wait may block the Android main/service thread.

A late activation result whose originating request is no longer admissible is discarded. If a shell was acquired after the request became obsolete, close it immediately and do not mutate lifecycle state.

### 6.2 Prompt policy

A root prompt is allowed only for an explicit root-capable action:

- enabling Superuser Mode;
- an actual attempt to start a configured Superuser Mode runtime;
- an explicit stopped-state privileged operation;
- recovery of a previously proven root execution;
- State Access Repair;
- explicitly enabling/applying the inotify expert feature.

Passive rendering must never prompt, including:

- onboarding;
- settings screen construction;
- folder/device list rendering;
- app/service startup while Run Conditions say Syncthing should remain stopped.

Configured Superuser Mode alone is not sufficient reason to acquire root.

### 6.3 Helper shell lifetime

Root helper shells are **operation-scoped**.

There may be at most one helper shell for a privileged operation, and it may be reused within that operation. Close it when the operation finishes.

Do not keep a general-purpose UID-0 helper process alive merely because root Syncthing is active.

### 6.4 Root launch primitive

A normal libsu `Job` is not the Syncthing launch primitive because libsu appends end-of-job framing that assumes the process remains a shell.

Each root Syncthing invocation receives a dedicated explicit root `Shell` and exactly one audited raw `execTask` launch operation.

That launch task:

1. verifies the shell is UID 0;
2. installs the structured environment and output redirection;
3. writes the execution identity evidence required by the launch protocol;
4. finally `exec`s the approved bundled Syncthing invocation.

The launch shell is never reused for helper work.

Do not enable mount-master behavior by default. Ordinary libsu root-shell namespace semantics are the baseline unless later physical qualification proves a concrete visibility requirement.

## 7. Closed Syncthing command vocabulary

Every invocation of the bundled Syncthing binary routes through the selected backend.

The runtime exposes typed operations, not arbitrary executable/argv selection.

The supported command set includes the existing wrapper uses, such as:

- `serve --no-browser`;
- `device-id`;
- `generate`;
- database reset;
- delta-index reset.

The implementation may represent these as a closed enum/value object.

At most **one bundled Syncthing invocation** may be owned at a time. A one-shot Syncthing command cannot overlap an active `serve` invocation.

Run Script shell processes are not Syncthing invocations and are governed separately.

## 8. Environment parity

Build one structured environment map above the backend boundary and use it in both modes.

Preserve existing normal-mode environment behavior, including the current values derived for:

- `HOME`;
- `STHOMEDIR`;
- `STTRACE`;
- `STMONITORED`;
- `STNOUPGRADE`;
- `STVERSIONEXTRA`;
- `SQLITE_TMPDIR`;
- fallback gateway information;
- proxy/Tor variables;
- `GOGC`;
- custom user environment variables.

Root execution adds only private runtime metadata needed for execution ownership.

RootBackend owns the single audited shell encoder needed to express this structured data safely.

## 9. Exact execution ownership

Both Normal Mode and Superuser Mode use the same ownership standard.

An Owned Execution is identified by durable evidence including:

- PID;
- Linux process start time;
- Linux boot ID;
- expected bundled executable;
- random run token.

The exact storage schema is versioned and app-owned.

Runtime metadata is stored outside Managed State and must remain application-readable even when Syncthing state is root-owned.

### 9.1 Universal signaling invariant

> **No verified execution identity -> no process signal.**

This applies to both normal and root execution.

Process-name-based killing is removed.

Candidate process discovery may be used only to determine whether it is safe to proceed or whether recovery must fail closed. Candidate discovery must never authorize signaling by itself.

### 9.2 Normal launch recording

Normal mode continues to use the normal process-launch mechanism rather than introducing a shell wrapper solely for identity bookkeeping.

The execution record may therefore be finalized immediately after process creation once PID/start-time evidence is available.

The small crash window between process creation and durable identity recording is accepted. If recovery later finds a candidate process but lacks sufficient identity evidence, it is an Ambiguous Execution and must not be signaled.

### 9.3 Root launch recording

The dedicated root launch path writes/establishes execution evidence as part of the pre-`exec` protocol and the app verifies the resulting live process before considering launch successful.

### 9.4 Record cleanup

Remove an execution record only after proven process exit and only when the record's run token still matches the execution being finalized.

If cleanup fails, leave the record. Recovery treats it as stale evidence on next startup.

Never let an older completion delete a newer execution's record.

## 10. Recovery

### 10.1 Valid matching record

If the record matches a live process exactly, recovery may inspect or terminate only that verified process.

A previously live root execution is not adopted as the new runtime generation. Recovery terminates the verified orphan, proves exit, performs any required bounded cleanup, then starts a fresh execution if Run Conditions require it.

### 10.2 Process no longer exists

If the recorded process is gone, clear stale evidence and continue.

### 10.3 Boot mismatch

A boot-ID mismatch proves the recorded process cannot still be the same execution. Clear stale evidence and continue.

### 10.4 Missing, corrupt, or nonmatching evidence

If there is no exact bundled Syncthing candidate, recovery may continue.

If an exact bundled Syncthing candidate exists but ownership cannot be proven, classify it as an **Ambiguous Execution**, remain stopped, and never signal it.

### 10.5 Root authorization lost during orphan recovery

If the execution record proves an old root Syncthing is alive but the app can no longer acquire root and REST shutdown is unavailable:

- do not signal it;
- do not start another Syncthing;
- fail closed with a recoverable error;
- allow the user to restore root permission and Retry, or reboot.

After reboot, boot-ID validation proves the prior process cannot still exist.

### 10.6 Migration from old root implementations

A legacy root Syncthing process created by an older implementation but lacking the new ownership evidence is never killed automatically.

If such a candidate is present, fail closed and instruct the user to reboot before the new runtime establishes ownership.

## 11. Startup readiness

The service's existing semantic meaning of `ACTIVE` is preserved.

Startup succeeds only after:

1. process launch succeeds;
2. exact execution identity verifies;
3. the configured REST/Web GUI endpoint becomes reachable;
4. REST configuration initialization completes successfully.

Only then may `STARTING -> ACTIVE`.

### 11.1 Startup timeout

The existing unbounded REST readiness poll becomes bounded for both modes.

Initial timeout: **60 seconds**.

On timeout:

1. cancel the readiness poller;
2. terminate only the exact Owned Execution;
3. report typed `STARTUP_TIMEOUT`;
4. leave the service stopped/error;
5. never fall back to another Execution Mode.

## 12. Shutdown

Graceful shutdown follows one shared strategy:

```text
REST shutdown
    ->
bounded wait
    ->
SIGINT
    ->
bounded wait
    ->
SIGKILL
```

Every signal requires exact ownership verification immediately before signaling.

Timeout durations are named policy constants and may be tuned from qualification evidence. The ordering and bounded nature of escalation are architectural; exact durations are not.

For Normal Mode, backend-specific transport may use the safest equivalent process-control mechanism while preserving the same ownership and bounded-exit requirements.

## 13. Lifecycle integration and concurrency

This stack deliberately does **not** introduce a new general-purpose lifecycle executor or actor.

### 13.1 Existing lifecycle model

Preserve the current service threading/lifecycle structure as far as practical.

Add narrowly scoped background work only for operations that may block, including:

- root activation;
- process-exit waits;
- privileged state/file work;
- ownership repair;
- state snapshot/install.

Background workers return immutable results to the service thread.

### 13.2 State ownership

Lifecycle-owned fields, including current service state, REST objects, and execution references, are mutated on the service/main thread.

Workers do not directly mutate lifecycle state.

### 13.3 Multi-step mutating operations

Operations requiring strict stop-then-mutate-then-restart sequencing use a narrow continuation-based transition:

```text
request
  -> mark transition busy
  -> async bounded stop
  -> proven exit
  -> perform exact requested mutation
  -> optional restart
  -> clear busy
```

Only one such mutating transition is admitted at a time.

Other mutating commands are disabled/rejected as busy rather than queued in a new general lifecycle scheduler.

### 13.4 Stale callback hygiene

This redesign intentionally does not add generation-token fencing to the existing service model.

Compensating requirements:

- cancel/disable the old Web GUI poller before replacement;
- shut down the old EventProcessor;
- detach/clear old RestApi state;
- prove the old process exited;
- clear execution-specific references;
- do not allow two readiness pollers or execution-specific callback sources to coexist.

Existing service-state checks remain part of callback rejection.

### 13.5 onDestroy

Correctness must not depend on `Service.onDestroy()`.

Orderly user/run-condition/mode transitions perform explicit bounded shutdown while the service is still active.

`onDestroy()` performs best-effort cleanup only and must not block the main thread waiting indefinitely for root or process exit.

Abrupt app death is handled by durable execution recovery.

## 14. Execution Mode semantics

`PREF_USE_ROOT` remains the durable selected Execution Mode.

It has one production writer: the mode-transition operation behind the service/runtime boundary.

Settings UI never writes it directly.

### 14.1 Enabling Superuser Mode

Required ordering:

1. request and verify root capability while the current healthy normal core may remain running;
2. if root preparation fails, leave the preference off and do not disrupt normal execution;
3. once root capability is proven, stop the existing normal execution if necessary;
4. prove old execution exit;
5. commit `PREF_USE_ROOT=true`;
6. launch root Syncthing if Run Conditions require it.

The commit point means “root execution policy is now selected and safe to attempt,” not “Syncthing has reached ACTIVE.”

If root launch later fails, keep Superuser Mode selected and fail closed. Do not roll back to Normal Mode automatically.

Enabling while Run Conditions say Syncthing should remain stopped is valid: verify root, commit the mode transition, and remain stopped.

### 14.2 Disabling Superuser Mode

The current root Syncthing execution must be proven stopped before the mode can change.

Required ordering:

1. stop the verified root execution if present;
2. prove exit;
3. attempt Managed State ownership/context repair;
4. verify whether Normal Mode can access Managed State;
5. if repair/access succeeds, commit `PREF_USE_ROOT=false`;
6. restart in Normal Mode if Run Conditions require it.

If repair cannot run because root access has been revoked, the user may explicitly choose **Disable anyway** after a warning.

In that exceptional path:

- `PREF_USE_ROOT=false` commits;
- do not attempt a normal Syncthing launch against inaccessible state;
- expose `STATE_ACCESS_REPAIR_REQUIRED`;
- show an explicit State Access Repair action.

The forced disable exception applies to state repair only. It does **not** allow committing Normal Mode while a previously owned root Syncthing process is still alive.

### 14.3 State Access Repair

State Access Repair is a Privileged Maintenance Operation.

It may request root while Normal Mode remains selected.

On success:

- repair only Managed State;
- verify app access;
- keep `PREF_USE_ROOT=false`;
- allow normal startup when Run Conditions permit.

No other ordinary normal-mode feature silently escalates to root merely because access failed.

### 14.4 Crash recovery during transitions

Do not persist a transition journal.

Recover from committed facts:

- `PREF_USE_ROOT` is the durable Execution Mode commit point;
- execution records are the durable process-ownership evidence;
- Managed State accessibility is checked from the actual filesystem state.

Enable crash before preference commit -> Normal Mode remains selected.

Enable crash after preference commit -> Superuser Mode remains selected.

Disable crash before preference commit -> Superuser Mode remains selected.

Disable crash after preference commit -> Normal Mode remains selected; if state is inaccessible, derive `STATE_ACCESS_REPAIR_REQUIRED`.

Transition steps must therefore be idempotent.

## 15. Managed State

The runtime owns the closed Managed State manifest.

Baseline contents are:

- `config.xml`;
- `cert.pem`;
- `key.pem`;
- `https-cert.pem`;
- `https-key.pem`;
- `index-v2/`.

The implementation must verify the current repository/Syncthing version still uses this closed set before coding, but it must not broaden repair into recursive whole-app ownership changes.

Managed State operations:

- use no-follow semantics where appropriate;
- tolerate missing optional members where existing workflows do;
- never include user synchronization folders;
- never recursively chown the entire application data directory.

### 15.1 Ownership while root mode is configured

Root-created Managed State may remain root-owned across ordinary root stops.

Do not repair ownership after every root stop.

Stopped Superuser Mode state operations use RootBackend.

Repair occurs when required for an explicit root-to-normal transition or State Access Repair.

## 16. Semantic state API

Privileged state access is exposed as semantic operations only, for example:

- load/save Syncthing configuration;
- export Managed State snapshot;
- install imported snapshot;
- replace/reset HTTPS certificate material;
- reset database/index state;
- generate/read selected Syncthing metadata.

Do not expose generic root file open/read/write/delete APIs to arbitrary application callers.

## 17. Backup and import

The externally visible archive contract remains the established **pre-root** backup format.

Requirements:

- Normal and Superuser modes produce the same archive shape;
- `PREF_USE_ROOT` is device-local and is never restored from an archive;
- do not support unreleased buggy root-export layouts;
- preserve existing import workflow semantics;
- preserve valid legacy archives that omit `index-v2`;
- such an import must not accidentally retain stale local database state.

### 17.1 Transfer mechanism

Use fresh app-private staging for each operation.

Export:

1. stop Syncthing where required;
2. selected backend snapshots Managed State into fresh app-readable private staging;
3. shared archive code creates the established external format;
4. cleanup staging transactionally;
5. restart according to Run Conditions.

Import:

1. shared archive code validates/extracts into fresh app-owned private staging;
2. stop Syncthing;
3. selected backend validates and installs only approved Managed State members;
4. preserve device-local Execution Mode;
5. cleanup staging;
6. restart according to Run Conditions.

Staging identifiers are internally generated opaque operation IDs, not caller-controlled privileged paths.

## 18. Configuration access and passive UI

Preserve current ConfigRouter semantics behind the runtime boundary.

### 18.1 Active Syncthing

Use REST as current behavior does.

Successful REST configuration reads/changes may refresh the in-memory Configuration Projection.

### 18.2 Stopped Normal Mode

Use semantic AppUidBackend operations against live authoritative configuration.

### 18.3 Stopped Superuser Mode

If an already legitimate privileged operation has usable root capability, semantic state operations may access live authoritative configuration through RootBackend.

Passive list rendering does not request root.

If a valid in-memory Configuration Projection exists, passive UI may display it.

The projection:

- is memory-only;
- is non-authoritative;
- is never written back as an offline configuration;
- is never sufficient authority for a privileged folder path operation.

If the app process restarts, the projection is lost by design.

If root-configured Syncthing is stopped, no projection exists, and reading authoritative config would require a new prompt, show a configuration-unavailable state. Do not fabricate an empty folder/device list.

### 18.4 Editing from projected data

Projected data is display-only.

Before entering an add/edit flow that requires authoritative configuration, treat the user action as an explicit privileged operation, acquire root, and reload authoritative state.

If acquisition fails, do not enter editable state.

## 19. Onboarding

Onboarding must not infer installation existence solely from whether `config.xml` is app-readable.

Introduce an app-owned Installation Marker.

Set the marker after a valid initial generation or import.

### 19.1 Existing-install migration

When the marker is absent:

1. if app-readable configuration is parseable, mark the installation present;
2. if device-local evidence indicates an existing root-configured installation but state is not app-readable, provisionally mark it present without acquiring root;
3. otherwise preserve normal first-run onboarding.

Authoritative validation of a provisionally recognized root installation happens only at the next legitimate runtime activation.

Passive onboarding never invokes `su`.

Once established, the marker represents installation lineage rather than current config readability. Do not automatically clear it merely because state is temporarily inaccessible or corrupt.

## 20. Run Conditions while root state is inaccessible

Global Run Conditions still decide whether Syncthing should run.

Per-folder/per-device custom Run Condition mutations that require stopped-state authoritative config must not trigger a surprise root prompt.

If Superuser Mode is stopped and config cannot be read without activation:

- defer those config mutations;
- apply them at the next legitimate root activation or active REST session against authoritative configuration;
- do not write them into the Configuration Projection.

## 21. Folder capabilities

Application callers pass semantic identifiers, not arbitrary privileged paths.

Existing-folder operations accept a folder ID/reference and resolve its authoritative configured path inside the runtime.

New-folder validation is a separate typed candidate-folder operation because the path is not yet in authoritative config.

### 21.1 Writeability probe

Do not create a persistent fixed probe such as `.stwritetest`.

Normal backend:

- prefer unnamed `O_TMPFILE` where directly supported;
- otherwise use non-mutating permission/access inference.

Root backend:

- use typed non-mutating root-side permission/mount/access checks through the audited shell transport;
- do not introduce a native privileged helper solely for this probe in this stack.

If physical qualification proves the root-side inference insufficient, a narrow native probe may be proposed separately.

### 21.2 Conflict discovery

Conflict discovery is a typed configured-folder capability.

It executes under the selected backend identity and is cancellation/teardown aware.

Do not expose generic privileged `find` to callers.

## 22. Run Script

Preserve the existing sync-completion feature.

The public semantic operation is conceptually:

`runFolderScripts(folderId, event)`

The runtime:

- resolves the configured folder;
- locates the approved script directory;
- enumerates allowed `.sh` files;
- uses the folder root as the expected working context;
- invokes `/system/bin/sh` with structured arguments;
- preserves existing event semantics such as `sync_complete`.

Normal Mode scripts run app UID.

Superuser Mode scripts run UID 0.

Settings must warn that scripts execute with unrestricted superuser privileges when Superuser Mode is enabled.

Scripts may run while the main Syncthing `serve` process remains active.

Root scripts use an operation-scoped helper shell. Do not add durable orphan recovery for arbitrary user scripts in this stack.

Orderly teardown stops admitting new script operations and performs bounded cleanup/waiting for current script work. Arbitrary child processes deliberately launched by user scripts are outside Syncthing process ownership.

## 23. Optional system tuning

Global inotify tuning is **not part of Superuser Mode semantics**.

Expose it separately under Experimental, default off, conceptually:

> Increase system inotify watch limit

When the user explicitly enables it:

1. acquire root;
2. apply the configured watch limit;
3. commit the setting only after successful application.

The existing legacy target of 131072 may be used unless implementation evidence justifies another value.

On later Normal Mode starts:

- if a verified root helper already exists for another legitimate reason, the tuning may be reapplied;
- otherwise skip it non-fatally;
- never prompt solely to reapply this optional tuning.

Failure to tune inotify must never block Syncthing startup.

## 24. I/O priority

Preserve the existing `ionice` optimization only for the exact Owned Execution PID.

No process-name scanning.

Failure is non-fatal.

## 25. Logging

Normal Mode may retain its current direct pipe-based capture.

Superuser Mode must not depend on an app-owned pipe remaining alive after force-stop.

For each root run:

1. app pre-creates an app-owned per-run spool;
2. root Syncthing redirects stdout/stderr to that spool before `exec`;
3. while the app is alive, runtime drains/tails into the existing user-visible `syncthing.log`;
4. after recovery or proven exit, reconcile remaining output;
5. trim using the existing log-retention policy;
6. remove the spool when safe.

One-shot Syncthing commands use operation-scoped output capture and do not automatically pollute `syncthing.log`.

Temporary spool growth while a root execution outlives the Android app is accepted. Do not add a privileged log supervisor solely to enforce a hard cap during that orphan window.

## 26. Root capability loss

Do not poll continuously for root revocation.

A verified root Syncthing process may continue running after the root manager revokes future authorization.

Subsequent helper operations or new process generations must reacquire root and fail with a typed error if unavailable.

Loss of an idle helper shell does not kill a healthy Syncthing core. The next privileged operation gets one bounded reacquisition attempt.

## 27. Error model

Keep ordinary service lifecycle state plus a typed failure cause.

At minimum support stable causes equivalent to:

- `ROOT_DENIED`;
- `ROOT_UNAVAILABLE`;
- `ROOT_ACTIVATION_TIMEOUT`;
- `ROOT_TRANSPORT_FAILED`;
- `UID_VERIFICATION_FAILED`;
- `AMBIGUOUS_EXECUTION`;
- `EXECUTION_VERIFICATION_FAILED`;
- `STARTUP_TIMEOUT`;
- `STATE_ACCESS_REPAIR_REQUIRED`;
- `STATE_REPAIR_FAILED`;
- `STATE_ACCESS_FAILED`;
- `STATE_TRANSFER_FAILED`;
- `FOLDER_ACCESS_FAILED`;
- `SCRIPT_FAILED`;
- `BUSY`.

Do not create a parallel persisted root lifecycle state machine.

## 28. Settings UX

### Behavior

Add:

> **Run Syncthing as Superuser**

Default: off.

The switch reflects the last committed Execution Mode while a transition is pending. It is disabled/pending during the transaction rather than optimistically flipping.

If root authorization fails while enabling, leave it off.

If disabling cannot repair state because root capability is unavailable, warn with a choice equivalent to:

- Cancel
- Disable anyway

If Disable anyway is selected, commit Normal Mode and surface State Access Repair.

Show **Repair Syncthing state access** only when repair is actually required.

### Experimental

Add:

> **Increase system inotify watch limit**

Default: off.

Run Script warning copy must make root execution consequences explicit when Superuser Mode is selected.

## 29. Normal-mode preservation contract

Except where this specification explicitly changes behavior, the first stack preserves current normal-mode semantics for:

- Run Conditions;
- Syncthing argv/environment;
- REST/config routing;
- service restart/exit policy;
- logging;
- folder/device visibility and stopped-state editing;
- backup/import format;
- Run Script behavior;
- foreground-service ownership;
- Android compatibility floor.

The refactor should be an extraction around current behavior, not a simultaneous normal-lifecycle redesign.

## 30. Structural constraints

Add automated/static checks sufficient to prevent architectural regression.

At minimum:

- only RootBackend/root transport code imports libsu;
- no libsu service/nio dependency;
- no RootService/AIDL privileged runtime;
- no production generic root command API;
- no production arbitrary privileged-path API;
- one production writer for `PREF_USE_ROOT`;
- mode-neutral callers do not branch on root after backend selection;
- every bundled Syncthing invocation routes through the runtime/backend boundary;
- no process-name-based Syncthing kill path;
- backup import does not restore archived root mode;
- global/static libsu main-shell APIs are not used as production runtime routing.

## 31. Testability

Backend/process/state collaborators must be injectable behind test seams.

Deterministic tests must be able to simulate:

- root grant/denial/unavailability/timeout;
- process launch and exit;
- exact identity verification;
- stale and ambiguous execution recovery;
- state repair;
- snapshot/install;
- folder operations;
- script operations;
- helper-shell loss;
- stale readiness callbacks;
- mode-transition ordering;
- forced-disable repair-required state.

Real `su` is not required for ordinary JVM tests.

Tests for a behavior travel with the PR that introduces that behavior rather than being deferred to the end.

## 32. Physical qualification

Physical qualification is performed only in the developer's local clone on personally controlled rooted devices.

Qualification artifacts remain local and uncommitted.

No upstream reviewer is expected to possess or commit the local qualification harness/evidence.

Minimum final qualification matrix:

1. normal-mode startup/stop/restart regression;
2. enable root -> grant -> verified UID-0 Syncthing;
3. deny root -> setting remains off;
4. configured root + revoked grant -> startup fails closed;
5. re-grant + Retry succeeds;
6. force-stop app while root Syncthing runs -> next start verifies, terminates, and starts fresh;
7. root-to-normal transition repairs Managed State;
8. forced root disable with unavailable repair -> repair-required state;
9. explicit State Access Repair restores normal operation;
10. import/export in Superuser Mode preserves archive format and device-local mode;
11. restricted-folder write/access and conflict discovery;
12. root Run Script executes as UID 0;
13. optional inotify tuning does not create surprise normal-start prompts;
14. startup-readiness timeout terminates only the exact Owned Execution;
15. ambiguous/missing ownership evidence is never signaled;
16. normal-mode baseline remains behaviorally equivalent.

Milestone qualification:

- after PR3: root launch/stop/recovery smoke tests;
- after PR4/PR5: state ownership, restricted folders, import/export, scripts/helpers;
- after PR6: full production UI and final matrix;
- PR7: regression/hardening verification.

Before PR6, temporary instrumentation/debug wiring may exist only as an uncommitted local qualification patch.

## 33. Reviewable PR stack

Implementation is a **true linear stack**. Each PR targets the previous PR branch until lower layers merge.

Every PR must:

- compile;
- pass its relevant automated tests;
- leave a coherent intermediate architecture;
- describe its dependency;
- state its narrow review scope;
- state what is intentionally deferred to later PRs.

The new root feature remains hidden from users until PR6.

### PR1 - Runtime seam / normal-mode extraction

Introduce the mode-neutral runtime/backend seam around existing normal behavior:

- `DefaultSyncthingRuntime`;
- `PrivilegeBackend`;
- `AppUidBackend`;
- typed execution/state/folder result boundaries needed by later PRs.

Preserve normal behavior.

No root feature is exposed.

### PR2 - Exact execution ownership

Introduce shared durable execution identity and exact termination/recovery semantics for Normal Mode first.

Remove process-name-based Syncthing signaling from the refactored path.

Cover ambiguous execution behavior and exact shutdown ownership.

No root feature is exposed.

### PR3 - Root transport and root process execution

Add:

- libsu core only;
- RootBackend;
- bounded 60-second activation;
- UID-0 verification;
- operation-scoped helper shells;
- dedicated raw launch task;
- root execution ownership/recovery;
- root log spool.

Keep production root UI hidden.

Perform local root launch/stop/recovery qualification.

### PR4 - Privileged Syncthing state operations

Add:

- closed Managed State manifest;
- semantic root state access;
- ownership/context repair;
- app-private transfer staging;
- import/export integration;
- certificate/database state operations.

Keep production root UI hidden.

### PR5 - Privileged folder/runtime helpers

Add:

- authoritative folder resolution;
- candidate-folder validation;
- conflict discovery;
- Run Script under selected identity;
- exact-PID `ionice`;
- separate inotify expert capability.

Keep production root UI hidden.

### PR6 - Mode switching, UI, onboarding, and failure UX

Make the feature production-visible.

Add:

- single-writer mode transition operation;
- Behavior superuser switch;
- forced-disable warning;
- State Access Repair action;
- Experimental inotify toggle;
- Installation Marker migration;
- passive root-mode Configuration Projection behavior;
- deferred custom Run Condition mutation behavior;
- user-visible typed failure handling.

At the end of PR6 the feature is functionally complete and can pass the full physical qualification matrix.

### PR7 - Cleanup and architectural hardening

No functionality required for the feature's basic operation belongs here.

Use this PR for:

- residual obsolete/dead-code cleanup found during the stack;
- dependency cleanup if any obsolete root remnants exist;
- structural architecture tests;
- final cross-cutting integration assertions;
- final documentation consistency.

Upstream `main` currently has no legacy root feature to delete. Do not invent a deletion migration. If obsolete root remnants are discovered while rebasing from the old experimental branch, remove them here.

## 34. ADRs

The architectural trade-offs most likely to be misunderstood later are recorded separately:

- `docs/adr/0001-direct-root-backend-without-rootservice.md`
- `docs/adr/0002-exact-process-ownership-before-signaling.md`
- `docs/adr/0003-superuser-mode-is-fail-closed-over-shared-state.md`

## 35. Acceptance invariants

The stack is complete only when all of the following are true:

1. There is one Syncthing Installation across both modes.
2. Superuser Mode never silently falls back to Normal Mode.
3. Root transport uses libsu core only and no RootService/AIDL.
4. Application callers have no generic privileged shell/filesystem surface.
5. `SyncthingService` remains lifecycle authority for this stack.
6. Mode-neutral runtime callers do not branch on root after backend selection.
7. Every bundled Syncthing invocation goes through the selected backend.
8. At most one bundled Syncthing invocation is owned at a time.
9. No process is signaled without exact ownership proof.
10. Process-name-based Syncthing termination is absent.
11. Root force-stop recovery works from durable identity evidence.
12. Ambiguous executions fail closed.
13. Startup reaches ACTIVE only after REST/config readiness.
14. Startup readiness is bounded.
15. Root prompts occur only from approved explicit triggers.
16. Passive onboarding/settings/folder/device rendering never prompts.
17. Managed State repair is closed and never recursively repairs arbitrary app data.
18. Normal Mode never opportunistically escalates to root except explicit Privileged Maintenance Operations.
19. Backup/import external format remains the established pre-root contract.
20. Root preference is not restored from backup.
21. Configuration Projection is in-memory and non-authoritative.
22. Run Scripts use selected execution identity and warn when root-enabled.
23. Inotify tuning is separate from Superuser Mode and non-fatal.
24. Normal-mode behavior is preserved except for explicitly specified safety corrections.
25. Every stacked PR is independently green.
26. PR6 is functionally complete; PR7 is cleanup/hardening only.
27. Physical qualification evidence remains local and uncommitted.
