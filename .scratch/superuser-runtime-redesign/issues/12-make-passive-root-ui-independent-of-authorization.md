# 12: Make passive root-configured UI independent of root authorization

Triage: enhancement

Stack: PR6

**What to build:** Keep onboarding, folder/device presentation, and stopped-root configuration views useful without acquiring root merely to render them. Distinguish installation existence from config readability and use only non-authoritative in-memory projection data for passive display.

**Blocked by:** 09: Perform configured-folder access through the selected backend; 10: Run folder completion scripts under the selected identity; 11: Apply execution tuning through narrow privileged capabilities.

**Status:** ready-for-agent

- [ ] An app-owned Installation Marker distinguishes an existing Syncthing Installation from temporary config unreadability.
- [ ] Existing installs migrate to the marker without a passive root prompt.
- [ ] Passive root-configured folder/device rendering never invokes root.
- [ ] A memory-only Configuration Projection may support display, but never becomes write authority or durable config storage.
- [ ] After app restart with no projection and inaccessible root state, the UI shows configuration unavailable rather than an empty configuration.
- [ ] Explicit editing requires authoritative state; stopped-state custom Run Condition mutations that cannot be applied safely are deferred to a legitimate authoritative session.
