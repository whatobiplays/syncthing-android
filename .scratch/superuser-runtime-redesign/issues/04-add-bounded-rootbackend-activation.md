# 04: Add bounded RootBackend activation

Triage: enhancement

Stack: PR3

**What to build:** Add the hidden Superuser Mode transport boundary using libsu core only, with bounded explicit activation and no privileged Android service. Root acquisition must be safe to invoke from explicit operations without blocking lifecycle-owned state or creating surprise passive prompts.

**Blocked by:** 03: Make Normal Mode lifecycle depend on exact ownership.

**Status:** ready-for-agent

- [ ] Root transport uses libsu core 6.0.0 only; RootService, AIDL, libsu service, and libsu nio are absent.
- [ ] Activation verifies UID 0 and distinguishes denial, unavailability, timeout, transport failure, and verification failure.
- [ ] Root activation is bounded to 60 seconds and never blocks the Android main/service thread.
- [ ] Obsolete late activation results are discarded and any late-acquired shell is closed without mutating lifecycle state.
- [ ] Helper shells are operation-scoped rather than runtime-scoped.
- [ ] Passive UI/onboarding behavior still has no path that acquires root.
