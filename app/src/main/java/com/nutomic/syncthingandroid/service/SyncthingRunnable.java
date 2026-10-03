package com.nutomic.syncthingandroid.service;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.RouteInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.MulticastLock;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import com.google.common.base.Charsets;
import com.google.common.io.Files;
import com.nutomic.syncthingandroid.R;
import com.nutomic.syncthingandroid.SyncthingApp;
import com.nutomic.syncthingandroid.runtime.DefaultSyncthingRuntime;
import com.nutomic.syncthingandroid.runtime.ExecutionAdmissionException;
import com.nutomic.syncthingandroid.runtime.ExecutionIdentity;
import com.nutomic.syncthingandroid.runtime.ExecutionOwnershipManager;
import com.nutomic.syncthingandroid.runtime.ExecutionRecoveryException;
import com.nutomic.syncthingandroid.runtime.ExecutableNotFoundException;
import com.nutomic.syncthingandroid.runtime.OwnedExecutionShutdown;
import com.nutomic.syncthingandroid.runtime.SyncthingCommand;
import com.nutomic.syncthingandroid.runtime.SyncthingEnvironment;
import com.nutomic.syncthingandroid.runtime.SyncthingExecution;
import com.nutomic.syncthingandroid.util.FileUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import javax.inject.Inject;

import static com.nutomic.syncthingandroid.service.SyncthingService.EXTRA_STOP_AFTER_CRASHED_NATIVE;

/**
 * Runs the syncthing binary from command line, and prints its output to logcat.
 *
 * @see <a href="http://docs.syncthing.net/users/syncthing.html">Command Line Docs</a>
 */
public class SyncthingRunnable implements Runnable {

    private static final String TAG = "SyncthingRunnable";
    private static final String TAG_NATIVE = "SyncthingNativeCode";
    private static final String TAG_NICE = "SyncthingRunnableIoNice";

    private Boolean ENABLE_VERBOSE_LOG = false;
    private static final int LOG_FILE_MAX_LINES = 200000;
    private static final int LOG_FILE_BUFFER_SIZE = 1024 * 1024;

    private final Context mContext;
    private final SyncthingCommand mCommand;
    private final boolean mWaitForAdmission;
    private final File mSyncthingLogFile;
    private final DefaultSyncthingRuntime.OwnedExecutionRecoveryHandler mRecoveryHandler;
    private final LifecycleListener mLifecycleListener;
    private final DefaultSyncthingRuntime.LifecycleLaunchCheck mLifecycleLaunchCheck;

    /** Immutable lifecycle result produced by the background execution worker. */
    static final class LifecycleOutcome {
        enum Type {
            EXECUTION_STARTED,
            IDENTITY_UNAVAILABLE,
            EXECUTION_EXITED,
            RECOVERY_BLOCKED,
            GUI_PORT_UNAVAILABLE,
            WORKER_FINISHED
        }

        private final Type type;
        private final ExecutionIdentity identity;
        private final int exitCode;
        private final boolean executionCreated;
        private final boolean exitObserved;
        private final ExecutionOwnershipManager.RecoveryAssessment recoveryAssessment;

        private LifecycleOutcome(
                Type type,
                ExecutionIdentity identity,
                int exitCode,
                boolean executionCreated,
                boolean exitObserved,
                ExecutionOwnershipManager.RecoveryAssessment recoveryAssessment
        ) {
            this.type = type;
            this.identity = identity;
            this.exitCode = exitCode;
            this.executionCreated = executionCreated;
            this.exitObserved = exitObserved;
            this.recoveryAssessment = recoveryAssessment;
        }

        static LifecycleOutcome started(ExecutionIdentity identity) {
            return new LifecycleOutcome(
                    identity == null ? Type.IDENTITY_UNAVAILABLE : Type.EXECUTION_STARTED,
                    identity,
                    -1,
                    true,
                    false,
                    null
            );
        }

        static LifecycleOutcome exited(ExecutionIdentity identity, int exitCode) {
            return new LifecycleOutcome(
                    Type.EXECUTION_EXITED, identity, exitCode, true, true, null
            );
        }

        static LifecycleOutcome recoveryBlocked(
                ExecutionOwnershipManager.RecoveryAssessment assessment
        ) {
            return new LifecycleOutcome(
                    Type.RECOVERY_BLOCKED, null, -1, false, false, assessment
            );
        }

        static LifecycleOutcome guiPortUnavailable() {
            return new LifecycleOutcome(
                    Type.GUI_PORT_UNAVAILABLE, null, -1, false, false, null
            );
        }

        static LifecycleOutcome workerFinished(
                ExecutionIdentity identity,
                int exitCode,
                boolean executionCreated,
                boolean exitObserved
        ) {
            return new LifecycleOutcome(
                    Type.WORKER_FINISHED,
                    identity,
                    exitCode,
                    executionCreated,
                    exitObserved,
                    null
            );
        }

        Type type() {
            return type;
        }

        ExecutionIdentity identity() {
            return identity;
        }

        int exitCode() {
            return exitCode;
        }

        boolean executionCreated() {
            return executionCreated;
        }

        boolean exitObserved() {
            return exitObserved;
        }

        boolean provesNoExecutionExit() {
            return type == Type.WORKER_FINISHED && !executionCreated;
        }

        ExecutionOwnershipManager.RecoveryAssessment recoveryAssessment() {
            return recoveryAssessment;
        }
    }

    @FunctionalInterface
    interface LifecycleListener {
        void onOutcome(LifecycleOutcome outcome);
    }

    @Inject
    SharedPreferences mPreferences;

    @Inject
    NotificationHandler mNotificationHandler;

    @Inject
    DefaultSyncthingRuntime mRuntime;

    /**
     * Constructs instance for a one-shot bundled Syncthing command.
     *
     * <p>A one-shot command rejects immediately when another bundled invocation owns runtime
     * admission.</p>
     *
     * @param command Which type of Syncthing command to execute.
     */
    public SyncthingRunnable(Context context, SyncthingCommand command) {
        this(context, command, false, null, null, null);
    }

    /** Creates a one-shot command that can stop a previous execution after proving ownership. */
    static SyncthingRunnable forOneShotWithRecovery(
            Context context,
            SyncthingCommand command,
            DefaultSyncthingRuntime.OwnedExecutionRecoveryHandler recoveryHandler
    ) {
        return new SyncthingRunnable(context, command, false, recoveryHandler, null, null);
    }

    /**
     * Creates the service-owned lifecycle runnable.
     *
     * <p>Unlike a one-shot runnable, the service lifecycle runnable waits for a still-active
     * bundled invocation to exit and release runtime admission before it launches Syncthing.</p>
     *
     * @param command Which service lifecycle command to execute.
     */
    static SyncthingRunnable forServiceLifecycle(Context context, SyncthingCommand command) {
        return new SyncthingRunnable(context, command, true, null, null, null);
    }

    /** Creates a service lifecycle worker with immutable outcome and exact recovery callbacks. */
    static SyncthingRunnable forServiceLifecycle(
            Context context,
            SyncthingCommand command,
            DefaultSyncthingRuntime.OwnedExecutionRecoveryHandler recoveryHandler,
            LifecycleListener lifecycleListener
    ) {
        return new SyncthingRunnable(
                context, command, true, recoveryHandler, lifecycleListener, null
        );
    }

    /** Creates a lifecycle worker with a final check after exact prior-owner recovery. */
    static SyncthingRunnable forServiceLifecycle(
            Context context,
            SyncthingCommand command,
            DefaultSyncthingRuntime.OwnedExecutionRecoveryHandler recoveryHandler,
            LifecycleListener lifecycleListener,
            DefaultSyncthingRuntime.LifecycleLaunchCheck launchCheck
    ) {
        return new SyncthingRunnable(
                context, command, true, recoveryHandler, lifecycleListener, launchCheck
        );
    }

    /** Raised when a prior owner exited but a replacement launch precondition remains unmet. */
    static final class GuiPortUnavailableException extends RuntimeException {
        GuiPortUnavailableException() {
            super("The Web GUI port remains occupied after execution recovery");
        }
    }

    private SyncthingRunnable(
            Context context,
            SyncthingCommand command,
            boolean waitForAdmission,
            DefaultSyncthingRuntime.OwnedExecutionRecoveryHandler recoveryHandler,
            LifecycleListener lifecycleListener,
            DefaultSyncthingRuntime.LifecycleLaunchCheck launchCheck
    ) {
        ((SyncthingApp) context.getApplicationContext()).component().inject(this);
        ENABLE_VERBOSE_LOG = AppPrefs.getPrefVerboseLog(mPreferences);
        mContext = context;
        mCommand = command;
        mWaitForAdmission = waitForAdmission;
        mRecoveryHandler = recoveryHandler;
        mLifecycleListener = lifecycleListener;
        mLifecycleLaunchCheck = launchCheck;
        mSyncthingLogFile = Constants.getSyncthingLogFile(mContext);
    }

    @Override
    public void run() {
        try {
            run(false);
        } catch (ExecutableNotFoundException e) {
            throw new RuntimeException(e.getMessage());
        }
    }

    /**
     * Runs the configured bundled Syncthing command.
     *
     * @throws ExecutionAdmissionException when this one-shot command runs while another bundled
     *                                     invocation is still active
     */
    public String run(boolean returnStdOut) throws ExecutableNotFoundException {
        Boolean sendStopToService = false;
        Boolean restartSyncthingNative = false;
        int exitCode = -1;
        String capturedStdOut = "";

        // Trim Syncthing log.
        trimSyncthingLogFile();

        MulticastLock multicastLock = null;
        SyncthingExecution execution = null;
        boolean executionExitObserved = false;
        boolean aborted = false;
        try {
            // Android 11 blocks local discovery if we did not acquire MulticastLock.
            WifiManager wifi = (WifiManager) mContext.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            multicastLock = wifi.createMulticastLock("multicastLock");
            multicastLock.setReferenceCounted(true);
            multicastLock.acquire();

            /**
             * Setup and run a new syncthing instance
             */
            SyncthingEnvironment targetEnv = buildEnvironment();
            execution = startExecution(targetEnv);
            if (mWaitForAdmission) {
                publishLifecycleOutcome(LifecycleOutcome.started(execution.identity()));
            }

            Thread lInfo = null;
            Thread lWarn = null;
            if (returnStdOut) {
                BufferedReader br = null;
                try {
                    br = new BufferedReader(new InputStreamReader(execution.stdout(), Charsets.UTF_8));
                    String line;
                    while ((line = br.readLine()) != null) {
                        Log.i(TAG_NATIVE, line);
                        capturedStdOut = capturedStdOut + line + "\n";
                    }
                } catch (IOException e) {
                    Log.w(TAG, "Failed to read Syncthing's command line output", e);
                } finally {
                    if (br != null)
                        br.close();
                }
            } else {
                lInfo = log(execution.stdout(), Log.INFO);
                lWarn = log(execution.stderr(), Log.WARN);
            }

            exitCode = execution.await();
            executionExitObserved = true;
            LogV("Syncthing exited with code " + exitCode);
            if (lInfo != null) {
                lInfo.join();
            }
            if (lWarn != null) {
                lWarn.join();
            }

            switch (exitCode) {
                case 0:
                case 137:
                    Log.i(TAG, "Syncthing was shut down normally via API or SIGKILL. Exit code = " + exitCode);
                    break;
                case 1:
                    Log.w(TAG, "exit reason = exitError. Another Syncthing instance may be already running.");
                    mNotificationHandler.showCrashedNotification(R.string.notification_crash_title, Integer.toString(exitCode));
                    sendStopToService = true;
                    break;
                case 2:
                    // This should not happen as STNOUPGRADE is set.
                    Log.w(TAG, "exit reason = exitNoUpgradeAvailable. Another Syncthing instance may be already running.");
                    mNotificationHandler.showCrashedNotification(R.string.notification_crash_title, Integer.toString(exitCode));
                    sendStopToService = true;
                    break;
                case 3:
                    // Restart was requested via Rest API call.
                    Log.i(TAG, "exit reason = exitRestarting. Restarting syncthing.");
                    restartSyncthingNative = true;
                    break;
                case 9:
                    // Native was force killed.
                    Log.w(TAG, "exit reason = exitForceKill.");
                    mNotificationHandler.showCrashedNotification(R.string.notification_crash_title, Integer.toString(exitCode));
                    sendStopToService = true;
                    break;
                case 64:
                    Log.w(TAG, "exit reason = exitInvalidCommandLine.");
                    mNotificationHandler.showCrashedNotification(R.string.notification_crash_title, Integer.toString(exitCode));
                    sendStopToService = true;
                    break;
                default:
                    Log.w(TAG, "Syncthing exited unexpectedly. Exit code = " + exitCode);
                    mNotificationHandler.showCrashedNotification(R.string.notification_crash_title, Integer.toString(exitCode));
                    sendStopToService = true;
            }
        } catch (ExecutableNotFoundException e) {
            Log.e(TAG, "CRITICAL - Syncthing core binary is missing in APK package location " + e.getMessage());
            throw e;
        } catch (GuiPortUnavailableException e) {
            if (!mWaitForAdmission) throw e;
            publishLifecycleOutcome(LifecycleOutcome.guiPortUnavailable());
            return capturedStdOut;
        } catch (ExecutionRecoveryException e) {
            if (!mWaitForAdmission) throw e;
            publishLifecycleOutcome(LifecycleOutcome.recoveryBlocked(e.assessment()));
            return capturedStdOut;
        } catch (IOException | InterruptedException e) {
            aborted = true;
            Log.e(TAG, "Failed to execute syncthing binary or read output", e);
        } finally {
            if (multicastLock != null) {
                multicastLock.release();
                multicastLock = null;
            }
            if (execution != null) {
                if (!executionExitObserved) {
                    boolean interrupted = Thread.interrupted();
                    if (aborted && !mWaitForAdmission) {
                        try {
                            stopAbortedOneShot(
                                    execution.identity(),
                                    mRuntime,
                                    OwnedExecutionShutdown.processWaiter()
                            );
                        } catch (InterruptedException e) {
                            interrupted = true;
                        }
                    }
                    while (!executionExitObserved) {
                        try {
                            exitCode = execution.await();
                            executionExitObserved = true;
                        } catch (InterruptedException e) {
                            interrupted = true;
                        }
                    }
                    if (interrupted) Thread.currentThread().interrupt();
                }
                if (mWaitForAdmission && executionExitObserved) {
                    publishLifecycleOutcome(
                            LifecycleOutcome.exited(execution.identity(), exitCode)
                    );
                }
            }
            if (mWaitForAdmission && execution == null) {
                publishLifecycleOutcome(
                        LifecycleOutcome.workerFinished(null, exitCode, false, false)
                );
            }
        }

        if (!mWaitForAdmission && executionExitObserved) {
            execution.requireIdentityForSuccessfulOneShotResult();
        }

        // Restart syncthing if it exited unexpectedly while running on a separate thread.
        if (!returnStdOut && restartSyncthingNative) {
            mContext.startService(new Intent(mContext, SyncthingService.class)
                    .setAction(SyncthingService.ACTION_RESTART));
        }

        // Notify {@link SyncthingService} that service state State.ACTIVE is no longer valid.
        if (!returnStdOut && sendStopToService) {
            Intent intent = new Intent(mContext, SyncthingService.class);
            intent.setAction(SyncthingService.ACTION_STOP);
            intent.putExtra(EXTRA_STOP_AFTER_CRASHED_NATIVE, true);
            mContext.startService(intent);
        }

        // Return captured command line output.
        return capturedStdOut;
    }

    /**
     * Opens this runnable's bundled Syncthing invocation through the runtime seam.
     *
     * <p>One-shot runnables keep the immediate admission rejection contract. The service lifecycle
     * runnable waits for the active invocation to release admission, which must not happen on the
     * main thread.</p>
     */
    private SyncthingExecution startExecution(SyncthingEnvironment targetEnv)
            throws IOException, ExecutableNotFoundException, InterruptedException {
        if (mWaitForAdmission) {
            return mRuntime.startServiceLifecycle(
                    mCommand, targetEnv, mRecoveryHandler, mLifecycleLaunchCheck
            );
        }
        return mRuntime.start(mCommand, targetEnv, mRecoveryHandler);
    }

    static OwnedExecutionShutdown.Outcome stopAbortedOneShot(
            ExecutionIdentity identity,
            OwnedExecutionShutdown.ExecutionControl control,
            OwnedExecutionShutdown.Waiter waiter
    ) throws InterruptedException {
        if (identity == null) return null;
        return OwnedExecutionShutdown.stop(identity, () -> null, control, waiter);
    }

    private void publishLifecycleOutcome(LifecycleOutcome outcome) {
        if (mLifecycleListener != null) mLifecycleListener.onOutcome(outcome);
    }

    private Map<String, String> customEnvironmentVariables(SharedPreferences sp) {
        Map<String, String> environment = new HashMap<>();
        String customEnvironment = sp.getString(Constants.PREF_ENVIRONMENT_VARIABLES, null);
        if (TextUtils.isEmpty(customEnvironment)) {
            return environment;
        }

        for (String e : customEnvironment.split(" ")) {
            String[] e2 = e.split("=", 2);
            LogV("Setting env var: [" + e2[0] + "]=[" + e2[1] + "]");
            environment.put(e2[0], e2[1]);
        }
        return environment;
    }

    /**
     * Logs the outputs of a stream to logcat and mNativeLog.
     *
     * @param is       The stream to log.
     * @param priority The priority level.
     * @param saveLog  True if the log should be stored to {@link #mSyncthingLogFile}.
     */
    private Thread log(final InputStream is, final int priority) {
        Thread t = new Thread(() -> {
            BufferedReader br = null;
            try {
                br = new BufferedReader(new InputStreamReader(is, Charsets.UTF_8));
                String line;
                while ((line = br.readLine()) != null) {
                    /*
                    if (ENABLE_VERBOSE_LOG) {
                        String lineWithoutTimestamp = line.replaceFirst("\\d{4}/\\d{2}/\\d{2} \\d{2}:\\d{2}:\\d{2} ?", "");
                        Log.println(priority, TAG_NATIVE, lineWithoutTimestamp);
                    }
                    */
                    // Always output SynchtingNative's output to "syncthing.log".
                    Files.append(line + "\n", mSyncthingLogFile, Charsets.UTF_8);
                }
            } catch (IOException e) {
                Log.w(TAG, "Failed to read Syncthing's command line output", e);
            }
            if (br != null) {
                try {
                    br.close();
                } catch (IOException e) {
                    Log.w(TAG, "log: Failed to close bufferedReader", e);
                }
            }
        });
        t.start();
        return t;
    }

    // If the nth last newline is found within this buffer, then the offset of that newline within
    // the buffer is returned. Otherwise, the negative of (nth - newlines consumed) is returned for
    // use with the new search.
    private static int findNthLastNewline(byte[] data, int size, int nth) {
        if (nth <= 0) {
            throw new IllegalArgumentException("nth must be positive: " + nth);
        }

        for (int i = size - 1; i >= 0; i--) {
            if (data[i] == '\n') {
                nth--;

                if (nth == 0) {
                    return i;
                }
            }
        }

        return -nth;
    }

    /**
     * Only keep last {@link #LOG_FILE_MAX_LINES} lines in log file, to avoid bloat.
     */
    private void trimSyncthingLogFile() {
        if (!mSyncthingLogFile.exists()) {
            return;
        }

        try (RandomAccessFile input = new RandomAccessFile(mSyncthingLogFile, "r")) {
            // Find the offset of the (n + 1)th newline with constant memory. The last n lines is
            // everything after that point. This will read in block-aligned chunks if
            // LOG_FILE_BUFFER_SIZE is a multiple of the filesystem block size.
            byte[] buf = new byte[LOG_FILE_BUFFER_SIZE];
            long length = input.length();
            long chunks = Math.ceilDiv(length, buf.length);
            int newlinesRemaining = LOG_FILE_MAX_LINES + 1;
            long truncationOffset = -1;

            for (long chunk = chunks - 1; chunk >= 0; chunk--) {
                long offset = buf.length * chunk;
                input.seek(offset);

                // Last chunk can be smaller than the whole buffer.
                int n = (int) Math.min(length - offset, buf.length);
                input.readFully(buf, 0, n);

                int ret = findNthLastNewline(buf, n, newlinesRemaining);
                if (ret >= 0) {
                    truncationOffset = offset + ret + 1;
                    break;
                } else {
                    newlinesRemaining = -ret;
                }
            }

            if (truncationOffset < 0) {
                // The file already contains fewer than maximum lines.
                return;
            }

            input.seek(truncationOffset);

            File tempFile = new File(mContext.getFilesDir().toString(), "syncthing.log.tmp");
            long remain = length - truncationOffset;

            try (FileOutputStream output = new FileOutputStream(tempFile)) {
                while (remain > 0) {
                    int n = (int) Math.min(remain, buf.length);

                    input.readFully(buf, 0, n);
                    output.write(buf, 0, n);

                    remain -= n;
                }
            }

            tempFile.renameTo(mSyncthingLogFile);
        } catch (IOException e) {
            Log.w(TAG, "Failed to trim log file", e);
        }
    }

    private SyncthingEnvironment buildEnvironment() {
        SyncthingEnvironment.Builder builder = SyncthingEnvironment.builder()
                // Set home directory to data folder for web GUI folder picker.
                .home(FileUtils.getSyncthingTildeAbsolutePath())
                // Set config, key and database directory.
                .syncthingHome(mContext.getFilesDir().toString())
                .trace(TextUtils.join(" ",
                        mPreferences.getStringSet(Constants.PREF_DEBUG_FACILITIES_ENABLED, new HashSet<>())))
                .monitored()
                .noUpgrade()
                .versionExtra(mContext.getString(R.string.app_name))
                // Database tuning against slowness.
                .sqliteTemporaryDirectory(mContext.getCacheDir().getAbsolutePath());

        // Workaround SyncthingNativeCode denied to read gatewayIP by Android 14+ restriction.
        builder.fallbackGatewayIpv4(getGatewayIpV4(mContext));

        if (mPreferences.getBoolean(Constants.PREF_USE_TOR, false)) {
            builder.torProxy();
        } else {
            builder.socksProxy(mPreferences.getString(Constants.PREF_SOCKS_PROXY_ADDRESS, ""));
            builder.httpProxy(mPreferences.getString(Constants.PREF_HTTP_PROXY_ADDRESS, ""));
        }

        // Optimize memory usage for older devices.
        int gogc = 100;         // GO default
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            gogc = 75;
        }
        LogV("Setting env var: [GOGC]=[" + Integer.toString(gogc) + "]");
        return builder
                .gogc(gogc)
                .customVariables(customEnvironmentVariables(mPreferences))
                .build();
    }

    private void LogV(String logMessage) {
        if (ENABLE_VERBOSE_LOG) {
            Log.v(TAG, logMessage);
        }
    }

    public static String getGatewayIpV4(final Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network activeNetwork = cm.getActiveNetwork();
        if (activeNetwork == null) return null;

        LinkProperties props = cm.getLinkProperties(activeNetwork);
        if (props == null) return null;

        for (RouteInfo route : props.getRoutes()) {
            InetAddress gateway = route.getGateway();
            if (route.isDefaultRoute() && gateway instanceof Inet4Address) {
                return gateway.getHostAddress();
            }
        }
        return null;
    }
}
