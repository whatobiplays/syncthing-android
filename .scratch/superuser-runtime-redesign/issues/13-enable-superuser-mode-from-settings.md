# 13: Enable Superuser Mode from Settings

Triage: enhancement

Stack: PR6

**What to build:** Make the Behavior setting safely switch an existing Syncthing Installation from Normal Mode to Superuser Mode. Root capability is proven before disrupting a healthy normal process, the execution-policy preference has one production writer, and later root startup failure never silently rolls back to Normal Mode.

**Blocked by:** 12: Make passive root-configured UI independent of root authorization.

**Status:** ready-for-agent

- [ ] The Run Syncthing as Superuser switch defaults off and reflects the last committed Execution Mode while transitions are pending.
- [ ] Root denial/unavailability before commit leaves Normal Mode configured and does not unnecessarily stop a healthy normal execution.
- [ ] After root preparation succeeds, any old Owned Execution is stopped and proven exited before Superuser Mode commits.
- [ ] The durable mode preference is written by one transition authority rather than directly by Settings UI.
- [ ] Post-commit root startup failure leaves Superuser Mode selected and fails closed.
- [ ] Enabling while Run Conditions say Syncthing should stay stopped commits safely without forcing a launch.
