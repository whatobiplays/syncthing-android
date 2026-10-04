package com.nutomic.syncthingandroid.runtime;

import android.system.ErrnoException;
import android.system.Os;

/** Sends Linux process signals through Android's errno-reporting syscall API. */
final class AndroidProcessSignalTransport implements ProcessSignalTransport {
    @Override
    public ExecutionOwnershipManager.SignalResult sendSignal(int pid, int signal) {
        try {
            Os.kill(pid, signal);
            return ExecutionOwnershipManager.SignalResult.SIGNALED;
        } catch (ErrnoException | IllegalArgumentException | SecurityException e) {
            return ExecutionOwnershipManager.SignalResult.SIGNAL_FAILED;
        }
    }
}
