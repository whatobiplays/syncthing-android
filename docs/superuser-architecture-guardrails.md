# Superuser Mode architecture guardrails

Syncthing Android has one Installation and selects one execution backend for each bundled
Syncthing command. `SyncthingService` owns execution-mode transitions. The root backend uses
libsu core 6.0.0 through `LibsuRootShell` and `LibsuRootShellFactory`; callers receive semantic
state, folder, execution, and authorization operations rather than a general root shell.

## Source checks

`scripts/check_superuser_architecture.py` scans every production Java and Kotlin file under
`app/src/main`, including files in newly added packages, AIDL files, and relevant Gradle dependency
declarations. It rejects libsu imports outside the two transport files, non-core libsu artifacts,
RootService/AIDL, global libsu shell routing, generic privileged entrypoints, direct bundled-binary
access and process creation outside the approved backend and normal-UID helpers, hard-coded
bundled-binary process invocations even inside those helpers, process-name signals in execution
arguments, and execution-mode writes outside the transition commit operation. The generic
privileged-interface rule includes Kotlin functions whose visibility defaults to public, while
leaving semantic operations outside its generic-name pattern available. The interface check
compares direct `RootShell` operation names and parameter types, including overloads and Java
default methods with bodies. Raw terminal-script
calls are confined to the one root launch call and its session-owned transport delegate. References
to the mode preference are restricted to the explicit selection, display, and transition owners.
The import path permits exactly one device-local mode-preference restoration call inside
`restoreLocalExecutionModePreference()`; an additional call elsewhere in the service fails.
The checker also requires archive validation inside a `try` whose `catch (Exception)` ends with
a top-level `return false` before shutdown and Managed State installation in the production import
method. A conditional return inside the catch does not satisfy this check.

The scanner ignores test sources, documentation, comments, and quoted examples that are not
execution arguments. It inspects quoted process arguments and root transport script literals so
that a string containing `pkill` or `killall` is not silently discarded at an execution site. It
also follows simple earlier local assignments of preference keys and command strings into guarded
calls, including semicolon-free Kotlin assignments. This is a bounded lexical check: it does not
resolve fields, arbitrary alias chains,
control-flow-dependent reassignments, reflection, generated sources, or dynamic command
construction. A passing scan does not establish general dataflow correctness. Its
positive and negative fixtures live in `scripts/test_superuser_architecture.py`. Run both with
`make test`; normal debug pull requests run them and the debug JVM suite in the shared build
workflow. Release builds and signing do not run the new debug-only step.

These checks identify source-level violations. They do not prove that a live process is owned or
that asynchronous operations are correctly ordered. The JVM tests of `ExecutionOwnershipManager`,
`RootBackend`, `RuntimeSeamTest`, service transitions, and Run Script admission cover those
behaviors with deterministic fakes and no real `su` requirement.

## Archive and device-local state

`SyncthingBackupArchiveTest` exercises the established plain and password-encrypted archive
entries. A malformed preference payload makes the production extractor throw and leaves live
configuration and identity files byte-for-byte unchanged. In `SyncthingService.importConfig()`,
extraction is inside a `try` whose exception handler returns before the shutdown and installation
calls; the source guardrail enforces that shape as well as call order. The JVM suite does not
instantiate the complete Android Service with a malformed archive. The combined tests prove the
extractor's failure behavior and live-file isolation at its production seam, while complete
service execution and device-level import behavior remain unverified. `LocalExecutionModeImportTest` covers true, false, and
absent device-local mode against archived values and verifies that root-script consent is omitted
from exported preferences.

No demonstrably unused root dependency or scaffolding was found in the PR #8 baseline. Only
`libsu:core` is declared; no RootService, AIDL, libsu service, or libsu nio dependency exists.
Physical root-manager and SELinux qualification remains separate from ordinary CI.
