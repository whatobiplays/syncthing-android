package com.nutomic.syncthingandroid.runtime;

import java.io.IOException;
import java.util.List;

/** Reads Linux process identity and discovers executable candidates without authorizing signals. */
interface ExecutionInspector {
    String currentBootId() throws IOException;

    ExecutionIdentity inspect(int pid) throws IOException;

    List<ExecutionIdentity> findBundledCandidates(String executablePath) throws IOException;

    ExecutionIdentity findLaunchedProcess(String executablePath, String runToken) throws IOException;
}
