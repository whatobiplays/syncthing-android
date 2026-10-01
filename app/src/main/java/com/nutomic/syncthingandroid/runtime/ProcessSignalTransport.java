package com.nutomic.syncthingandroid.runtime;

import java.io.IOException;

/** Sends a Linux signal after the ownership manager has verified the target identity. */
interface ProcessSignalTransport {
    void sendSignal(int pid, int signal) throws IOException;
}
