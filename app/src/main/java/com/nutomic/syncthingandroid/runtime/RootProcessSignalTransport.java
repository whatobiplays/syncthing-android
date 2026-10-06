package com.nutomic.syncthingandroid.runtime;

import java.io.IOException;
import java.util.Objects;

/**
 * Sends a signal through a one-shot root shell after the ownership manager verified the target.
 *
 * <p>The transport is deliberately unable to decide ownership. It transports exactly the PID and
 * signal it is given, and reports a typed signal failure instead of guessing when the helper shell
 * cannot be acquired or the kernel rejects the request.</p>
 */
final class RootProcessSignalTransport implements ProcessSignalTransport {
    private final RootShellProvider shells;

    RootProcessSignalTransport(RootShellProvider shells) {
        this.shells = Objects.requireNonNull(shells);
    }

    @Override
    public ExecutionOwnershipManager.SignalResult sendSignal(int pid, int signal) {
        if (pid <= 0 || signal <= 0) {
            return ExecutionOwnershipManager.SignalResult.SIGNAL_FAILED;
        }
        try (RootShell shell = shells.acquireHelperShell()) {
            return shell.sendSignal(pid, signal)
                    ? ExecutionOwnershipManager.SignalResult.SIGNALED
                    : ExecutionOwnershipManager.SignalResult.SIGNAL_FAILED;
        } catch (IOException | RuntimeException e) {
            return ExecutionOwnershipManager.SignalResult.SIGNAL_FAILED;
        }
    }
}
