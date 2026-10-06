package com.nutomic.syncthingandroid.runtime;

import java.util.Arrays;

/**
 * The closed set of bundled Syncthing invocations supported by the application.
 *
 * <p>The command arguments live with the command vocabulary so callers cannot construct an
 * arbitrary executable invocation through the runtime seam.</p>
 */
public enum SyncthingCommand {
    SERVE("serve", "--no-browser"),
    DEVICE_ID("device-id"),
    GENERATE("generate"),
    RESET_DATABASE("debug", "reset-database"),
    RESET_DELTAS("serve", "--debug-reset-delta-idxs");

    private final String[] arguments;

    SyncthingCommand(String... arguments) {
        this.arguments = arguments;
    }

    String[] argv(String binaryPath) {
        String[] command = new String[arguments.length + 1];
        command[0] = binaryPath;
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        return Arrays.copyOf(command, command.length);
    }

    /**
     * Resolves a persisted command name onto this closed vocabulary.
     *
     * <p>Reconciliation reads the name a previous run recorded and must never guess: a name that
     * is missing, blank, or unknown to this enum has no defined output policy.</p>
     *
     * @return the matching command, or {@code null} when the name is not part of this vocabulary
     */
    static SyncthingCommand fromPersistedName(String name) {
        if (name == null) {
            return null;
        }
        for (SyncthingCommand command : values()) {
            if (command.name().equals(name)) {
                return command;
            }
        }
        return null;
    }

    /**
     * Reports whether invocations of this command stream long-running output into the shared
     * Syncthing log.
     *
     * <p>Serve-style runs keep writing after the application stops reading, so their leftover
     * output is reconciled into the shared log. One-shot commands answer a single request and keep
     * their output operation-scoped. The policy lives with the closed command vocabulary so the
     * live launch path and the leftover-spool reconciliation can never disagree about which runs
     * own shared-log output.</p>
     */
    boolean writesServeLog() {
        switch (this) {
            case SERVE:
            case RESET_DELTAS:
                return true;
            default:
                return false;
        }
    }
}
