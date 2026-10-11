package com.nutomic.syncthingandroid.service;

import com.nutomic.syncthingandroid.runtime.ManagedStateException;
import com.nutomic.syncthingandroid.runtime.RootFailure;
import com.nutomic.syncthingandroid.runtime.SyncthingCommand;

import java.util.function.Consumer;

/** Replaces transition I/O and dispatch in JVM tests while the service retains lifecycle authority. */
interface ExecutionModeTransitionTestSeam {
    boolean post(Runnable callback);

    boolean executeWorker(String name, Runnable work);

    boolean durableMode();

    boolean runtimeMode();

    RootFailure authorizeRoot();

    boolean closeScriptAdmission(long timeoutMillis) throws InterruptedException;

    boolean closeBackendAdmission(long timeoutMillis) throws InterruptedException;

    void reopenBackendAdmissionWhenDrained();

    void openBackendAdmission();

    void shutdown(Consumer<Boolean> completion);

    ManagedStateException repairState(boolean explicitAuthorization);

    boolean applicationStateAccessible();

    boolean commitMode(boolean useRoot);

    void selectMode(boolean useRoot);

    void launchStartup(SyncthingCommand command);

    void startStartupConfigurationLoad(
            StartupConfigurationLoad.Source source,
            StartupConfigurationLoad.Callback callback
    );

    void scheduleStartupConfigurationTimeout(Runnable timeout, long delayMillis);
}
