package com.nutomic.syncthingandroid.runtime;

import android.os.Process;

import java.io.IOException;

/** Sends Linux process signals through Android's API-1 process transport. */
final class AndroidProcessSignalTransport implements ProcessSignalTransport {
    @Override
    public void sendSignal(int pid, int signal) throws IOException {
        try {
            Process.sendSignal(pid, signal);
        } catch (IllegalArgumentException | SecurityException e) {
            throw new IOException("Android rejected process signal", e);
        }
    }
}
