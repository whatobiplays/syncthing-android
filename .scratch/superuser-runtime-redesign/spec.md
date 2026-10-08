# Superuser Runtime Redesign

Status: approved

The authoritative design is the confirmed **Superuser Runtime Redesign** in the repository documentation. This local tracker slices that design into agent-sized tracer bullets while preserving the agreed seven-PR review stack.

## Delivery rules

- Implement as a true linear seven-PR stack.
- Every PR must remain independently green and reviewable.
- Tests travel with the behavior they verify.
- The production superuser feature remains hidden until PR6.
- PR6 is functionally complete; PR7 is cleanup and architectural hardening only.
- Physical qualification is performed only in the developer's local clone on personally controlled devices; qualification evidence remains uncommitted.
- Normal Mode behavior is preserved except where the approved specification explicitly changes safety semantics.
- Superuser Mode is fail-closed and never silently becomes Normal Mode.
- No process is signaled without exact Owned Execution proof.
- Root transport uses libsu core only, with no RootService/AIDL privileged runtime.

## Ticket-to-PR map

- PR1: 01
- PR2: 02-03
- PR3: 04-06
- PR4: 07-08
- PR5: 09-11
- PR6: 12-15
- PR7: 16
