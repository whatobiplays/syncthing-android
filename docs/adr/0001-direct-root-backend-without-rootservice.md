# Use a direct root backend without RootService

The superuser runtime uses libsu `core` with explicit, narrowly owned shell instances behind a typed RootBackend rather than a UID-0 Android RootService/AIDL architecture. The previous RootService approach made privilege transport, Android service lifetime, lifecycle admission, Binder failure, and root process ownership one coupled problem; the replacement keeps Android lifecycle authority in `SyncthingService` and treats root as an execution capability. Application callers receive only semantic operations, never a generic root shell or privileged filesystem interface.

## Considered options

- **Direct libsu core backend**: selected because it keeps the privilege boundary small and avoids a second Android lifecycle authority.
- **libsu RootService/AIDL**: rejected because the prior implementation accumulated substantial binding, timeout, recovery, and lifecycle complexity around capabilities that do not require a privileged Android component.
- **Scattered `su` branches**: rejected because privilege decisions leak through unrelated application code and are difficult to test or constrain.

## Consequences

Long-running root Syncthing is not coupled to Android service death, so the application must prove exact process ownership and recover it explicitly. Root helper shells are operation-scoped, while each root Syncthing launch uses a dedicated audited raw libsu launch task that ends in `exec`.

Automatic sync-completion Run Scripts are a narrow, explicitly opted-in exception to the
no-background-prompt policy. `SyncthingService` owns durable, default-off consent, suspension,
and lifecycle admission. A configured event may request one bounded libsu-core root helper only
after Run Script and runtime-operation admission. `RootBackend` verifies UID 0 and performs the
semantic folder script operation in that helper session. Authorization and script execution are
asynchronous; stale callbacks cannot change the selected backend. This exception does not expose
a shell to application callers or add a persistent helper, global cached shell, RootService/AIDL,
undocumented manager-specific acquisition, or generic privileged commands.
