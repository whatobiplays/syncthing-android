package com.nutomic.syncthingandroid.service;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

import com.nutomic.syncthingandroid.R;
import com.nutomic.syncthingandroid.SyncthingApp;
import com.nutomic.syncthingandroid.http.PollWebGuiAvailableTask;
import com.nutomic.syncthingandroid.model.Device;
import com.nutomic.syncthingandroid.model.Folder;
import com.nutomic.syncthingandroid.runtime.DefaultSyncthingRuntime;
import com.nutomic.syncthingandroid.runtime.ExecutionAdmissionException;
import com.nutomic.syncthingandroid.runtime.ExecutionIdentity;
import com.nutomic.syncthingandroid.runtime.ExecutionOwnershipManager;
import com.nutomic.syncthingandroid.runtime.ExecutionRecoveryException;
import com.nutomic.syncthingandroid.runtime.OwnedExecutionShutdown;
import com.nutomic.syncthingandroid.runtime.SyncthingCommand;
import com.nutomic.syncthingandroid.util.ConfigRouter;
import com.nutomic.syncthingandroid.util.ConfigXml;
import com.nutomic.syncthingandroid.util.FileUtils;
import com.nutomic.syncthingandroid.util.PermissionUtil;
import com.nutomic.syncthingandroid.util.Util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.CompressionLevel;
import net.lingala.zip4j.model.enums.CompressionMethod;
import net.lingala.zip4j.model.enums.EncryptionMethod;
import net.lingala.zip4j.model.enums.AesKeyStrength;

/**
 * Holds the native syncthing instance and provides an API to access it.
 */
public class SyncthingService extends Service {

    private static final String TAG = "SyncthingService";

    private Boolean ENABLE_VERBOSE_LOG = false;

    /**
     * Intent action to perform a Syncthing restart.
     */
    public static final String ACTION_RESTART =
            ".SyncthingService.RESTART";

    /**
     * Intent action to perform a Syncthing stop.
     */
    public static final String ACTION_STOP =
            ".SyncthingService.STOP";

    /**
     * Intent action to reset Syncthing's database.
     */
    public static final String ACTION_RESET_DATABASE =
            ".SyncthingService.RESET_DATABASE";

    /**
     * Intent action to reset Syncthing's delta indexes.
     */
    public static final String ACTION_RESET_DELTAS =
            ".SyncthingService.RESET_DELTAS";

    public static final String ACTION_REFRESH_NETWORK_INFO =
            ".SyncthingService.REFRESH_NETWORK_INFO";

    /**
     * Intent action to permanently ignore a device connection request.
     */
    public static final String ACTION_IGNORE_DEVICE =
            ".SyncthingService.IGNORE_DEVICE";

    /**
     * Intent action to permanently ignore a folder share request.
     */
    public static final String ACTION_IGNORE_FOLDER =
            ".SyncthingService.IGNORE_FOLDER";

    /**
     * Intent action to override folder changes.
     */
    public static final String ACTION_OVERRIDE_CHANGES =
            ".SyncthingService.OVERRIDE_CHANGES";

    /**
     * Intent action to revert local folder changes.
     */
    public static final String ACTION_REVERT_LOCAL_CHANGES =
            ".SyncthingService.REVERT_LOCAL_CHANGES";


    /**
     * Extra used together with ACTION_IGNORE_DEVICE, ACTION_IGNORE_FOLDER.
     */
    public static final String EXTRA_NOTIFICATION_ID =
            ".SyncthingService.EXTRA_NOTIFICATION_ID";

    /**
     * Extra used together with ACTION_IGNORE_DEVICE
     */
    public static final String EXTRA_DEVICE_ID =
            ".SyncthingService.EXTRA_DEVICE_ID";

    /**
     * Extra used together with ACTION_IGNORE_DEVICE
     */
    public static final String EXTRA_DEVICE_ADDRESS =
            ".SyncthingService.EXTRA_DEVICE_ADDRESS";

    /**
     * Extra used together with ACTION_IGNORE_DEVICE
     */
    public static final String EXTRA_DEVICE_NAME =
            ".SyncthingService.EXTRA_DEVICE_NAME";

    /**
     * Extra used together with ACTION_IGNORE_FOLDER
     */
    public static final String EXTRA_FOLDER_ID =
            ".SyncthingService.EXTRA_FOLDER_ID";

    /**
     * Extra used together with ACTION_IGNORE_FOLDER
     */
    public static final String EXTRA_FOLDER_LABEL =
            ".SyncthingService.EXTRA_FOLDER_LABEL";

    /**
     * Extra used together with ACTION_STOP.
     */
    public static final String EXTRA_STOP_AFTER_CRASHED_NATIVE =
            ".SyncthingService.EXTRA_STOP_AFTER_CRASHED_NATIVE";

    public interface OnServiceStateChangeListener {
        void onServiceStateChange(State currentState);
    }

    /**
     * Outcome of {@link #replaceHttpsCertificate} / {@link #resetHttpsCertificate}.
     */
    public enum HttpsCertReplaceResult {
        /** The new certificate was applied and Syncthing came back online with it. */
        SUCCESS,
        /** The files were written but Syncthing is not currently meant to run; applies on next start. */
        SUCCESS_PENDING_START,
        /** The change failed and the previous certificate was restored. */
        FAILED
    }

    public interface OnHttpsCertReplaceResultListener {
        void onResult(HttpsCertReplaceResult result, @Nullable String errorDetail);
    }

    /**
     * Indicates the current state of SyncthingService and of Syncthing itself.
     */
    public enum State {
        /**
         * Service is initializing, Syncthing was not started yet.
         */
        INIT,
        /**
         * Syncthing binary is starting.
         */
        STARTING,
        /**
         * Syncthing binary is running,
         * Rest API is available,
         * RestApi class read the config and is fully initialized.
         */
        ACTIVE,
        /**
         * Syncthing binary is shutting down.
         */
        DISABLED,
        /**
         * There is some problem that prevents Syncthing from running.
         */
        ERROR,
    }

    /**
     * Initialize the service with State.DISABLED as {@link RunConditionMonitor} will
     * send an update if we should run the binary after it got instantiated in
     * {@link #onStartCommand}.
     */
    private State mCurrentState = State.DISABLED;
    private ConfigRouter mConfigRouter;
    private ConfigXml mConfig;
    private Thread mSyncthingRunnableThread = null;
    private Handler mHandler;

    private final HashSet<OnServiceStateChangeListener> mOnServiceStateChangeListeners = new HashSet<>();
    private final SyncthingServiceBinder mBinder = new SyncthingServiceBinder(this);

    private @Nullable
    PollWebGuiAvailableTask mPollWebGuiAvailableTask = null;

    private @Nullable
    RestApi mRestApi = null;

    private @Nullable
    EventProcessor mEventProcessor = null;

    private @Nullable
    RunConditionMonitor mRunConditionMonitor = null;

    private @Nullable
    SyncthingRunnable mSyncthingRunnable = null;

    @Inject
    NotificationHandler mNotificationHandler;

    @Inject
    SharedPreferences mPreferences;

    @Inject
    DefaultSyncthingRuntime mRuntime;

    @Nullable
    private StartupReadiness mStartupReadiness;

    @Nullable
    private ExecutionIdentity mOwnedExecution;

    @Nullable
    private RestApi mRecoveryRestApi;

    @Nullable
    private RestApi mShutdownRestApi;

    @Nullable
    private Runnable mAfterShutdown;
    @Nullable private FileMutationBarrier mFileMutationBarrier;
    @Nullable private CertificateVerificationStopHandler mCertificateVerificationStopHandler;
    private final ShutdownStartIntent mShutdownStartIntent = new ShutdownStartIntent();
    private final PostMutationStartupGate mPostMutationStartupGate =
            new PostMutationStartupGate();

    private boolean mShutdownInProgress;
    private boolean mShutdownExitProven;
    private boolean mShutdownWorkerStarted;
    private boolean mShutdownRecoveryCheckStarted;
    private boolean mLastExecutionExitProven;
    private boolean mDestroying;
    private boolean mStopAfterDeltaResetWhenNotRequired;
    private SyncthingCommand mStartupCommand;

    private interface CertificateVerificationStopHandler {
        void onExplicitStop(boolean crashedNativeStop);
    }

    /**
     * Object that must be locked upon accessing mCurrentState
     */
    private final Object mStateLock = new Object();

    /**
     * Stores the result of the last should run decision received by OnShouldRunChangedListener.
     */
    private boolean mLastDeterminedShouldRun = false;

    /**
     * True if the user granted the storage permission.
     */
    private boolean mStoragePermissionGranted = false;

    /**
     * Starts the native binary.
     */
    @Override
    public void onCreate() {
        super.onCreate();
        ((SyncthingApp) getApplication()).component().inject(this);
        ENABLE_VERBOSE_LOG = AppPrefs.getPrefVerboseLog(mPreferences);
        LogV("onCreate");
        mConfigRouter = new ConfigRouter(SyncthingService.this);
        mHandler = new Handler();

        /**
         * If runtime permissions are revoked, android kills and restarts the service.
         * We need to recheck if we still have the storage permission.
         */
        mStoragePermissionGranted = PermissionUtil.haveStoragePermission(this);

        if (mNotificationHandler != null) {
            mNotificationHandler.setAppShutdownInProgress(false);
        }
    }

    /**
     * Handles intent actions, e.g. {@link #ACTION_RESTART}
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand");
        if (!mStoragePermissionGranted) {
            Log.e(TAG, "User revoked storage permission. Stopping service.");
            if (mNotificationHandler != null) {
                mNotificationHandler.showStoragePermissionRevokedNotification();
            }
            stopSelf();
            return START_NOT_STICKY;
        }

        // Run condition monitor is enabled.
        if (mRunConditionMonitor == null) {
            /**
             * Instantiate the run condition monitor on first onStartCommand and
             * enable callback on run condition change affecting the final decision to
             * run/terminate syncthing. After initial run conditions are collected
             * the first decision is sent to {@link onShouldRunDecisionChanged}.
             */
            mRunConditionMonitor = new RunConditionMonitor(SyncthingService.this,
                this::onShouldRunDecisionChanged,
                this::applyCustomRunConditions
            );
        }
        mNotificationHandler.updatePersistentNotification(this);

        if (intent == null) {
            return START_STICKY;
        }

        if (ACTION_RESTART.equals(intent.getAction()) && mCurrentState == State.ACTIVE) {
            shutdown(State.INIT, () -> {
                if (mLastDeterminedShouldRun) {
                    launchStartupTask(SyncthingCommand.SERVE);
                } else {
                    onServiceStateChange(State.DISABLED);
                }
            });
        } else if (ACTION_STOP.equals(intent.getAction())) {
            boolean crashedNativeStop =
                    intent.getBooleanExtra(EXTRA_STOP_AFTER_CRASHED_NATIVE, false);
            if (mCertificateVerificationStopHandler != null) {
                mCertificateVerificationStopHandler.onExplicitStop(crashedNativeStop);
            }
            if (mFileMutationBarrier != null) {
                mFileMutationBarrier.suppressAutomaticStartup();
            }
            mPostMutationStartupGate.suppressAutomaticStartup(mShutdownStartIntent);
            if (crashedNativeStop) {
                /**
                 * We were requested to stop the service because the syncthing native binary crashed.
                 * Changing mCurrentState prevents the "defer until syncthing is started" routine we normally
                 * use for clean shutdown to take place. Instead, we will immediately shutdown the crashed
                 * instance forcefully.
                 */
                mCurrentState = State.ERROR;
                shutdown(State.DISABLED);
            } else {
                // Graceful shutdown.
                if (mCurrentState == State.STARTING ||
                        mCurrentState == State.ACTIVE) {
                    shutdown(State.DISABLED);
                }
            }
        } else if (ACTION_RESET_DATABASE.equals(intent.getAction())) {
            // External resets cannot overlap a stopped-state file mutation or import handoff.
            boolean accepted = SyncthingResetPolicy.runExternalResetIfUnowned(
                    mFileMutationBarrier != null,
                    mPostMutationStartupGate.ownsStartup(),
                    () -> {
                        Log.i(TAG, "Invoking reset of database");
                        requestResetDatabase(SyncthingResetPolicy.relaunchAfterReset(
                                () -> mLastDeterminedShouldRun,
                                () -> launchStartupTask(SyncthingCommand.SERVE)
                        ));
                    }
            );
            if (!accepted) {
                Log.w(TAG, "Ignoring external database reset while a file mutation owns lifecycle admission");
            }
        } else if (ACTION_RESET_DELTAS.equals(intent.getAction())) {
            // Check ownership before setting the delta-reset follow-up state or launching work.
            boolean accepted = SyncthingResetPolicy.runExternalResetIfUnowned(
                    mFileMutationBarrier != null,
                    mPostMutationStartupGate.ownsStartup(),
                    () -> {
                        Log.i(TAG, "Invoking reset of delta indexes");
                        mStopAfterDeltaResetWhenNotRequired = true;
                        Runnable resetDeltas = () -> launchStartupTask(SyncthingCommand.RESET_DELTAS);
                        if (mCurrentState != State.DISABLED || mSyncthingRunnable != null) {
                            shutdown(State.DISABLED, resetDeltas);
                        } else {
                            resetDeltas.run();
                        }
                    }
            );
            if (!accepted) {
                Log.w(TAG, "Ignoring external delta reset while a file mutation owns lifecycle admission");
            }
        } else if (ACTION_REFRESH_NETWORK_INFO.equals(intent.getAction())) {
            if (mRunConditionMonitor != null) {
                mRunConditionMonitor.updateShouldRunDecision();
            }
        } else if (ACTION_IGNORE_DEVICE.equals(intent.getAction())) {
            mConfigRouter.ignoreDevice(
                    mRestApi,
                    intent.getStringExtra(EXTRA_DEVICE_ID),
                    intent.getStringExtra(EXTRA_DEVICE_NAME),
                    intent.getStringExtra(EXTRA_DEVICE_ADDRESS)
            );
            mNotificationHandler.cancelConsentNotification(intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0));
        } else if (ACTION_IGNORE_FOLDER.equals(intent.getAction())) {
            mConfigRouter.ignoreFolder(
                    mRestApi,
                    intent.getStringExtra(EXTRA_DEVICE_ID),
                    intent.getStringExtra(EXTRA_FOLDER_ID),
                    intent.getStringExtra(EXTRA_FOLDER_LABEL)
            );
            mNotificationHandler.cancelConsentNotification(intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0));
        } else if (ACTION_OVERRIDE_CHANGES.equals(intent.getAction()) && mCurrentState == State.ACTIVE) {
            mRestApi.overrideChanges(intent.getStringExtra(EXTRA_FOLDER_ID));
        } else if (ACTION_REVERT_LOCAL_CHANGES.equals(intent.getAction()) && mCurrentState == State.ACTIVE) {
            mRestApi.revertLocalChanges(intent.getStringExtra(EXTRA_FOLDER_ID));
        } else {
            afterFreshServiceInstanceStart();
        }
        return START_STICKY;
    }

    /**
     * Event handler ot catch a fresh service startup right after the run condition evaluation took place
     * and SyncthingNative may be starting in the background meanwhilst or non-present.
     */
    private void afterFreshServiceInstanceStart() {
        LogV("afterFreshServiceInstanceStart: Service started from scratch, SyncthingNative is going to STATE_" + mCurrentState + " meanwhilst ...");
        if (mCurrentState == State.DISABLED) {
            // Read and parse the config from disk.
            ConfigXml configXml = new ConfigXml(this);
            try {
                configXml.loadConfig();
            } catch (ConfigXml.OpenConfigException e) {
                mNotificationHandler.showCrashedNotification(R.string.config_read_failed, "afterFreshServiceInstanceStart:OpenConfigException");
                synchronized (mStateLock) {
                    onServiceStateChange(State.ERROR);
                }
                stopSelf();
                return;
            }
        }
    }

    /**
     * After run conditions monitored by {@link RunConditionMonitor} changed and
     * it had an influence on the decision to run/terminate syncthing, this
     * function is called to notify this class to run/terminate the syncthing binary.
     * {@link #onServiceStateChange} is called while applying the decision change.
     */
    private void onShouldRunDecisionChanged(boolean newShouldRunDecision) {
        if (newShouldRunDecision != mLastDeterminedShouldRun) {
            Log.i(TAG, "shouldRun decision changed to " + newShouldRunDecision + " according to configured run conditions.");
            mLastDeterminedShouldRun = newShouldRunDecision;

            // React to the shouldRun condition change.
            if (newShouldRunDecision) {
                boolean lifecycleBlocksStartup = mShutdownInProgress
                        || mFileMutationBarrier != null
                        || mPostMutationStartupGate.ownsStartup()
                        || ((mCurrentState == State.DISABLED || mCurrentState == State.INIT)
                        && (mOwnedExecution != null
                        || mSyncthingRunnable != null
                        || mSyncthingRunnableThread != null));
                mShutdownStartIntent.onRunConditionChanged(true, lifecycleBlocksStartup);
                if (lifecycleBlocksStartup) {
                    if (!mShutdownInProgress
                            && mFileMutationBarrier == null
                            && !mPostMutationStartupGate.ownsStartup()) {
                        shutdown(State.DISABLED, null, true);
                    }
                    return;
                }

                // Start syncthing.
                switch (mCurrentState) {
                    case DISABLED:
                    case INIT:
                        launchStartupTask(SyncthingCommand.SERVE);
                        break;
                    case STARTING:
                    case ACTIVE:
                    case ERROR:
                        break;
                    default:
                        break;
                }
            } else {
                mShutdownStartIntent.onRunConditionChanged(false, false);
                // Stop syncthing.
                if (mCurrentState == State.DISABLED) {
                    return;
                }
                shutdown(State.DISABLED);
            }
        }
    }

    /**
     * After sync preconditions changed, we need to inform {@link RestApi} to pause or
     * unpause devices and folders as defined in per-object sync preferences.
     */
    private void applyCustomRunConditions(RunConditionMonitor runConditionMonitor) {
        synchronized (mStateLock) {
            if (mRestApi != null && mCurrentState == State.ACTIVE) {
                // Forward event because syncthing is running.
                mRestApi.applyCustomRunConditions(runConditionMonitor);
                return;
            }
        }

        Boolean configChanged = false;
        ConfigXml configXml;

        // Read and parse the config from disk.
        configXml = new ConfigXml(this);
        try {
            configXml.loadConfig();
        } catch (ConfigXml.OpenConfigException e) {
            mNotificationHandler.showCrashedNotification(R.string.config_read_failed, "applyCustomRunConditions:OpenConfigException");
            synchronized (mStateLock) {
                onServiceStateChange(State.ERROR);
            }
            stopSelf();
            return;
        }

        // Check if the folders are available from config.
        List<Folder> folders = configXml.getFolders();
        if (folders != null) {
            for (Folder folder : folders) {
                // LogV("applyCustomRunConditions: Processing config of folder(" + folder.label + ")");
                Boolean folderCustomSyncConditionsEnabled = mPreferences.getBoolean(
                    Constants.DYN_PREF_OBJECT_CUSTOM_SYNC_CONDITIONS(Constants.PREF_OBJECT_PREFIX_FOLDER + folder.id), false
                );
                if (folderCustomSyncConditionsEnabled) {
                    Boolean syncConditionsMet = runConditionMonitor.checkObjectSyncConditions(
                        Constants.PREF_OBJECT_PREFIX_FOLDER + folder.id
                    );
                    LogV("applyCustomRunConditions: f(" + folder.label + ")=" + (syncConditionsMet ? "1" : "0"));
                    if (folder.paused != !syncConditionsMet) {
                        configXml.setFolderPause(folder.id, !syncConditionsMet);
                        Log.d(TAG, "applyCustomRunConditions: f(" + folder.label + ")=" + (syncConditionsMet ? ">1" : ">0"));
                        configChanged = true;
                    }
                }
            }
        } else {
            Log.d(TAG, "applyCustomRunConditions: folders == null");
            return;
        }

        // Check if the devices are available from config.
        List<Device> devices = configXml.getDevices(false);
        if (devices != null) {
            for (Device device : devices) {
                // LogV("applyCustomRunConditions: Processing config of device(" + device.name + ")");
                Boolean deviceCustomSyncConditionsEnabled = mPreferences.getBoolean(
                    Constants.DYN_PREF_OBJECT_CUSTOM_SYNC_CONDITIONS(Constants.PREF_OBJECT_PREFIX_DEVICE + device.deviceID), false
                );
                if (deviceCustomSyncConditionsEnabled) {
                    Boolean syncConditionsMet = runConditionMonitor.checkObjectSyncConditions(
                        Constants.PREF_OBJECT_PREFIX_DEVICE + device.deviceID
                    );
                    LogV("applyCustomRunConditions: d(" + device.name + ")=" + (syncConditionsMet ? "1" : "0"));
                    if (device.paused != !syncConditionsMet) {
                        configXml.setDevicePause(device.deviceID, !syncConditionsMet);
                        Log.d(TAG, "applyCustomRunConditions: d(" + device.name + ")=" + (syncConditionsMet ? ">1" : ">0"));
                        configChanged = true;
                    }
                }
            }
        } else {
            Log.d(TAG, "applyCustomRunConditions: devices == null");
            return;
        }

        if (configChanged) {
            LogV("applyCustomRunConditions: Saving changed config ...");
            configXml.saveChanges();
        } else {
            LogV("applyCustomRunConditions: No action was necessary.");
        }
    }

    private static final class OwnershipVerification {
        private final ExecutionIdentity identity;
        private final ExecutionOwnershipManager.Observation observation;

        private OwnershipVerification(
                ExecutionIdentity identity,
                ExecutionOwnershipManager.Observation observation
        ) {
            this.identity = identity;
            this.observation = observation;
        }
    }

    @FunctionalInterface
    private interface OwnershipVerificationCallback {
        void onComplete(OwnershipVerification verification);
    }

    /** Prepares a bounded startup whose deadline includes REST configuration initialization. */
    private void launchStartupTask(SyncthingCommand command) {
        synchronized (mStateLock) {
            if (mCurrentState != State.DISABLED && mCurrentState != State.INIT) {
                Log.e(TAG, "launchStartupTask: Wrong state " + mCurrentState + " detected. Cancelling.");
                return;
            }
        }

        if (mSyncthingRunnable != null || mSyncthingRunnableThread != null) {
            Log.e(TAG, "launchStartupTask: Syncthing binary lifecycle violated");
            return;
        }
        if (mFileMutationBarrier != null || mPostMutationStartupGate.ownsStartup()) {
            Log.d(TAG, "launchStartupTask deferred until the stopped-state file mutation completes");
            return;
        }

        mConfig = new ConfigXml(this);
        try {
            mConfig.loadConfig();
        } catch (ConfigXml.OpenConfigException e) {
            mNotificationHandler.showCrashedNotification(
                    R.string.config_read_failed,
                    "launchStartupTask:OpenConfigException"
            );
            synchronized (mStateLock) {
                onServiceStateChange(State.ERROR);
            }
            stopSelf();
            return;
        }

        Integer webGuiTcpPort = mConfig.getWebGuiBindPort();

        onServiceStateChange(State.STARTING);
        mStartupCommand = command;
        mLastExecutionExitProven = false;
        mShutdownExitProven = false;
        mShutdownWorkerStarted = false;
        mShutdownRecoveryCheckStarted = false;
        mRecoveryRestApi = createRecoveryRestApi(mConfig);
        mStartupReadiness = new StartupReadiness((delayMillis, task) -> {
            mHandler.postDelayed(task, delayMillis);
            return () -> mHandler.removeCallbacks(task);
        }, this::onStartupDeadlineExceeded);

        RestApi recoveryApi = mRecoveryRestApi;
        DefaultSyncthingRuntime.OwnedExecutionRecoveryHandler recoveryHandler =
                identity -> stopOwnedExecution(identity, recoveryApi);
        DefaultSyncthingRuntime.LifecycleLaunchCheck portCheck = () -> {
            if (Util.isTcpPortListening(webGuiTcpPort)) {
                throw new SyncthingRunnable.GuiPortUnavailableException();
            }
        };
        mSyncthingRunnable = SyncthingRunnable.forServiceLifecycle(
                this,
                command,
                recoveryHandler,
                outcome -> mHandler.post(() -> onLifecycleOutcome(outcome)),
                portCheck
        );

        Thread.UncaughtExceptionHandler exceptionHandler = (thread, error) -> {
            Log.e(TAG, "mSyncthingRunnableThread failed", error);
            mHandler.post(() -> {
                if (error instanceof ExecutionAdmissionException) {
                    handleSyncthingLaunchFailure();
                } else {
                    handleUnexpectedLaunchFailure(error);
                }
            });
        };
        mSyncthingRunnableThread = new Thread(mSyncthingRunnable, "Syncthing lifecycle");
        mSyncthingRunnableThread.setUncaughtExceptionHandler(exceptionHandler);
        mSyncthingRunnableThread.start();
    }

    private RestApi createRecoveryRestApi(ConfigXml config) {
        return new RestApi(this, config.getWebGuiUrl(), config.getApiKey(), () -> { }, () -> { });
    }

    private RestApi createCurrentRestApi() {
        RestApi[] reference = new RestApi[1];
        reference[0] = new RestApi(
                this,
                mConfig.getWebGuiUrl(),
                mConfig.getApiKey(),
                () -> mHandler.post(() -> onApiAvailable(reference[0])),
                () -> mHandler.post(() -> {
                    if (mRestApi == reference[0]) {
                        onServiceStateChange(mCurrentState);
                    }
                })
        );
        return reference[0];
    }

    private boolean stopOwnedExecution(
            ExecutionIdentity identity,
            @Nullable RestApi recoveryApi
    ) throws InterruptedException {
        OwnedExecutionShutdown.Outcome outcome = OwnedExecutionShutdown.stop(
                identity,
                () -> {
                    if (recoveryApi != null) recoveryApi.shutdown();
                },
                mRuntime,
                OwnedExecutionShutdown.processWaiter()
        );
        if (outcome != OwnedExecutionShutdown.Outcome.EXITED) return false;
        try {
            mRuntime.clearAfterExit(identity);
        } catch (IOException e) {
            Log.e(TAG, "Could not clear the recovered Syncthing execution record", e);
        }
        return true;
    }

    private void verifyOwnershipAsync(
            ExecutionIdentity identity,
            OwnershipVerificationCallback callback
    ) {
        Thread verificationThread = new Thread(() -> {
            ExecutionOwnershipManager.Observation observation = mRuntime.observe(identity);
            if (observation == ExecutionOwnershipManager.Observation.EXITED) {
                try {
                    mRuntime.clearAfterExit(identity);
                } catch (IOException e) {
                    Log.e(TAG, "Could not clear the exited Syncthing execution record", e);
                }
            }
            OwnershipVerification result = new OwnershipVerification(identity, observation);
            mHandler.post(() -> callback.onComplete(result));
        }, "Syncthing ownership verification");
        verificationThread.setDaemon(true);
        verificationThread.start();
    }

    private void onLifecycleOutcome(SyncthingRunnable.LifecycleOutcome outcome) {
        switch (outcome.type()) {
            case EXECUTION_STARTED:
                mOwnedExecution = outcome.identity();
                mLastExecutionExitProven = false;
                if (mShutdownInProgress || mDestroying || mCurrentState != State.STARTING) {
                    if (outcome.identity() != null) {
                        startShutdownWorker(outcome.identity(), mShutdownRestApi);
                    } else {
                        mSyncthingRunnableThread.interrupt();
                    }
                } else {
                    verifyOwnershipAsync(outcome.identity(), this::onInitialOwnershipVerified);
                }
                break;
            case IDENTITY_UNAVAILABLE:
                mOwnedExecution = null;
                mShutdownStartIntent.clear();
                failFileMutationBarrier();
                if (mCurrentState == State.STARTING) {
                    failStartup("Syncthing started without durable exact ownership evidence");
                }
                break;
            case EXECUTION_EXITED:
                mLastExecutionExitProven = outcome.exitObserved();
                if (sameExecution(mOwnedExecution, outcome.identity())) {
                    mOwnedExecution = null;
                }
                if (mCurrentState == State.STARTING) {
                    failStartup("Syncthing exited before startup completed");
                }
                if (mShutdownInProgress && outcome.exitObserved()) {
                    mShutdownExitProven = true;
                }
                clearWorkerHandlesWhenStopped(mSyncthingRunnableThread);
                if (mShutdownInProgress && outcome.exitObserved()) checkShutdownRecovery();
                break;
            case RECOVERY_BLOCKED:
                handleRecoveryBlocked(outcome.recoveryAssessment());
                break;
            case GUI_PORT_UNAVAILABLE:
                mShutdownStartIntent.clear();
                if (mStartupReadiness != null) {
                    mStartupReadiness.cancel();
                    mStartupReadiness = null;
                }
                mRecoveryRestApi = null;
                mNotificationHandler.showCrashedNotification(
                        R.string.webui_tcp_port_unavailable,
                        Integer.toString(mConfig.getWebGuiBindPort())
                );
                synchronized (mStateLock) {
                    if (mCurrentState == State.STARTING) onServiceStateChange(State.DISABLED);
                }
                clearWorkerHandlesWhenStopped(mSyncthingRunnableThread);
                break;
            case WORKER_FINISHED:
                if (outcome.provesNoExecutionExit()) {
                    if (mCurrentState == State.STARTING && !mDestroying) {
                        failStartup("Syncthing startup worker finished before creating an execution");
                    }
                    if (mShutdownInProgress) {
                        mShutdownExitProven = true;
                        mLastExecutionExitProven = true;
                        checkShutdownRecovery();
                    }
                }
                clearWorkerHandlesWhenStopped(mSyncthingRunnableThread);
                break;
            default:
                throw new IllegalStateException("Unhandled lifecycle outcome: " + outcome.type());
        }
    }

    private void onInitialOwnershipVerified(OwnershipVerification verification) {
        if (!sameExecution(mOwnedExecution, verification.identity)) return;
        if (verification.observation != ExecutionOwnershipManager.Observation.OWNED) {
            if (mCurrentState == State.STARTING) {
                failStartup("Syncthing ownership could not be verified after launch");
            } else if (verification.observation == ExecutionOwnershipManager.Observation.EXITED
                    && mShutdownInProgress) {
                mShutdownExitProven = true;
                checkShutdownRecovery();
            }
            return;
        }

        if (mShutdownInProgress || mDestroying || mCurrentState != State.STARTING) {
            if (!mShutdownInProgress) shutdown(State.DISABLED, null, true);
            else startShutdownWorker(verification.identity, mShutdownRestApi);
            return;
        }

        if (mStartupReadiness == null
                || mStartupReadiness.state() != StartupReadiness.State.PENDING) {
            if (mCurrentState == State.STARTING) {
                failStartup("Syncthing ownership verification arrived after startup closed");
            }
            return;
        }
        mStartupReadiness.markOwnershipVerified();

        mRecoveryRestApi = null;
        mRestApi = createCurrentRestApi();
        RestApi expectedApi = mRestApi;
        Log.i(TAG, "Web GUI will be available at " + mConfig.getWebGuiUrl());
        mPollWebGuiAvailableTask = new PollWebGuiAvailableTask(
                this,
                mConfig.getWebGuiUrl(),
                mConfig.getApiKey(),
                result -> mHandler.post(() -> onWebGuiAvailable(expectedApi))
        );
    }

    private void onWebGuiAvailable(RestApi expectedApi) {
        if (mRestApi != expectedApi || mCurrentState != State.STARTING
                || mStartupReadiness == null || mOwnedExecution == null) {
            return;
        }
        ExecutionIdentity identity = mOwnedExecution;
        verifyOwnershipAsync(identity, verification -> {
            if (mRestApi != expectedApi || mCurrentState != State.STARTING
                    || !sameExecution(mOwnedExecution, verification.identity)) {
                return;
            }
            if (verification.observation != ExecutionOwnershipManager.Observation.OWNED) {
                failStartup("Syncthing ownership was lost before endpoint readiness");
                return;
            }
            mStartupReadiness.markEndpointReady();
            expectedApi.readConfigFromRestApi();
        });
    }

    /** Called after REST version, config, and system-status initialization all complete. */
    private void onApiAvailable(RestApi expectedApi) {
        if (expectedApi == null || mRestApi != expectedApi || mCurrentState != State.STARTING
                || mStartupReadiness == null || mOwnedExecution == null) {
            return;
        }
        ExecutionIdentity identity = mOwnedExecution;
        verifyOwnershipAsync(identity, verification -> {
            if (mRestApi != expectedApi || mCurrentState != State.STARTING
                    || !sameExecution(mOwnedExecution, verification.identity)) {
                return;
            }
            if (verification.observation != ExecutionOwnershipManager.Observation.OWNED) {
                failStartup("Syncthing ownership was lost after REST configuration initialization");
                return;
            }
            if (!mStartupReadiness.markConfigurationInitialized()) return;
            mStartupReadiness = null;
            synchronized (mStateLock) {
                onServiceStateChange(State.ACTIVE);
            }
            if (mEventProcessor == null) {
                mEventProcessor = new EventProcessor(SyncthingService.this, expectedApi);
                mEventProcessor.start();
            }
            if (mStartupCommand == SyncthingCommand.RESET_DELTAS
                    && mStopAfterDeltaResetWhenNotRequired) {
                mStopAfterDeltaResetWhenNotRequired = false;
                if (!mLastDeterminedShouldRun) shutdown(State.DISABLED);
            }
        });
    }

    private void onStartupDeadlineExceeded() {
        if (mCurrentState == State.STARTING) {
            failStartup("Syncthing startup did not complete within 60 seconds");
        }
    }

    private void failStartup(String reason) {
        mShutdownStartIntent.clear();
        Log.e(TAG, reason);
        if (mNotificationHandler != null) {
            mNotificationHandler.showCrashedNotification(R.string.config_read_failed, reason);
        }
        if (mStartupReadiness != null) mStartupReadiness.cancel();
        shutdown(State.ERROR, null, true);
    }

    private void handleRecoveryBlocked(
            ExecutionOwnershipManager.RecoveryAssessment assessment
    ) {
        mShutdownStartIntent.clear();
        if (mStartupReadiness != null) mStartupReadiness.cancel();
        cancelStartupRequests();
        mRestApi = null;
        if (assessment != null && assessment.ownedExecution() != null) {
            mOwnedExecution = assessment.ownedExecution();
        } else {
            mRecoveryRestApi = null;
        }
        synchronized (mStateLock) {
            onServiceStateChange(State.ERROR);
        }
        if (mNotificationHandler != null) {
            String evidence = assessment == null
                    ? "unknown recovery evidence"
                    : assessment.classification().toString();
            mNotificationHandler.showCrashedNotification(
                    R.string.notification_crash_title,
                    "Syncthing recovery is blocked: " + evidence
            );
        }
        clearWorkerHandlesWhenStopped(mSyncthingRunnableThread);
    }

    private void handleUnexpectedLaunchFailure(Throwable error) {
        mShutdownStartIntent.clear();
        if (mStartupReadiness != null) mStartupReadiness.cancel();
        cancelStartupRequests();
        synchronized (mStateLock) {
            if (mCurrentState == State.STARTING) onServiceStateChange(State.ERROR);
        }
        if (mNotificationHandler != null) {
            mNotificationHandler.showCrashedNotification(
                    R.string.executable_not_found,
                    error.getMessage() == null ? Constants.FILENAME_SYNCTHING_BINARY : error.getMessage()
            );
        }
        clearWorkerHandlesWhenStopped(mSyncthingRunnableThread);
    }

    private void cancelStartupRequests() {
        if (mPollWebGuiAvailableTask != null) {
            mPollWebGuiAvailableTask.cancelRequestsAndCallback();
            mPollWebGuiAvailableTask = null;
        }
        if (mEventProcessor != null) {
            mEventProcessor.stop();
            mEventProcessor = null;
        }
    }

    private void startShutdownWorker(ExecutionIdentity identity, @Nullable RestApi restApi) {
        if (!mShutdownInProgress || mShutdownWorkerStarted || identity == null) return;
        mShutdownWorkerStarted = true;
        try {
            Thread worker = new Thread(() -> {
                OwnedExecutionShutdown.Outcome outcome;
                try {
                    outcome = OwnedExecutionShutdown.stop(
                            identity,
                            () -> {
                                if (restApi != null) restApi.shutdown();
                            },
                            mRuntime,
                            OwnedExecutionShutdown.processWaiter()
                    );
                    if (outcome == OwnedExecutionShutdown.Outcome.EXITED) {
                        try {
                            mRuntime.clearAfterExit(identity);
                        } catch (IOException e) {
                            Log.e(TAG, "Could not clear the exited Syncthing execution record", e);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    outcome = OwnedExecutionShutdown.Outcome.EXIT_NOT_PROVEN;
                } catch (RuntimeException e) {
                    Log.e(TAG, "Owned Syncthing shutdown failed", e);
                    outcome = OwnedExecutionShutdown.Outcome.EXIT_NOT_PROVEN;
                }
                OwnedExecutionShutdown.Outcome result = outcome;
                mHandler.post(() -> onShutdownOutcome(identity, result));
            }, "Syncthing bounded shutdown");
            worker.setDaemon(true);
            worker.start();
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not start owned Syncthing shutdown worker", e);
            onShutdownOutcome(identity, OwnedExecutionShutdown.Outcome.EXIT_NOT_PROVEN);
        }
    }

    private static boolean sameExecution(
            @Nullable ExecutionIdentity first,
            @Nullable ExecutionIdentity second
    ) {
        return first != null && second != null
                && first.pid() == second.pid()
                && first.processStartTimeTicks() == second.processStartTimeTicks()
                && first.bootId().equals(second.bootId())
                && first.executablePath().equals(second.executablePath())
                && first.runToken().equals(second.runToken());
    }

    /**
     * Leaves STARTING when the runnable could not create an admitted execution.
     *
     * <p>The service owns the lifecycle transition. The runnable reports the failure through its
     * uncaught-exception path and does not mutate service state itself.</p>
     */
    private void handleSyncthingLaunchFailure() {
        mShutdownStartIntent.clear();
        State terminalState;
        synchronized (mStateLock) {
            terminalState = SyncthingLaunchFailurePolicy.terminalState(mCurrentState);
            if (terminalState != mCurrentState) {
                onServiceStateChange(terminalState);
            }
        }
        if (mStartupReadiness != null) {
            mStartupReadiness.cancel();
            mStartupReadiness = null;
        }
        cancelStartupRequests();
        // Admission rejection does not transfer ownership of the invocation that blocked launch.
        clearWorkerHandlesWhenStopped(mSyncthingRunnableThread);
    }

    /**
     * Requests the existing database reset operation after any service-owned Syncthing execution
     * has been shut down. Admission rejection is handled here so one-shot failures cannot detach
     * or terminate the invocation that currently owns runtime admission.
     */
    private void requestResetDatabase(@Nullable Runnable afterReset) {
        requestResetDatabase(afterReset, null);
    }

    private void requestResetDatabase(
            @Nullable Runnable afterReset,
            @Nullable Runnable onFailure
    ) {
        RestApi recoveryApi = createRecoveryRestApiFromDisk();
        DefaultSyncthingRuntime.OwnedExecutionRecoveryHandler recoveryHandler =
                identity -> stopOwnedExecution(identity, recoveryApi);
        Runnable reset = () -> {
            Thread resetWorker = new Thread(() -> {
                try {
                    SyncthingRunnable.forOneShotWithRecovery(
                            this,
                            SyncthingCommand.RESET_DATABASE,
                            recoveryHandler
                    ).run();
                    if (afterReset != null) {
                        mHandler.post(SyncthingResetPolicy.afterResetUnlessDestroying(
                                () -> mDestroying,
                                afterReset
                        ));
                    }
                } catch (ExecutionAdmissionException e) {
                    Log.e(TAG, "Database reset rejected because another invocation owns admission", e);
                    dispatchResetFailure(onFailure);
                } catch (RuntimeException e) {
                    Log.e(TAG, "Database reset could not recover an exact execution", e);
                    if (mNotificationHandler != null) {
                        mHandler.post(() -> mNotificationHandler.showCrashedNotification(
                                R.string.notification_crash_title,
                                "Database reset was blocked by Syncthing execution recovery"
                        ));
                    }
                    dispatchResetFailure(onFailure);
                }
            }, "Syncthing database reset");
            resetWorker.setDaemon(true);
            try {
                resetWorker.start();
            } catch (RuntimeException e) {
                Log.e(TAG, "Database reset worker could not start", e);
                dispatchResetFailure(onFailure);
            }
        };

        if (SyncthingResetPolicy.shouldWaitForShutdownComplete(
                mCurrentState,
                mSyncthingRunnable != null
        )) {
            try {
                shutdown(State.DISABLED, reset);
            } catch (RuntimeException e) {
                Log.e(TAG, "Could not begin shutdown for database reset", e);
                dispatchResetFailure(onFailure);
            }
        } else {
            reset.run();
        }
    }

    private void dispatchResetFailure(@Nullable Runnable onFailure) {
        if (onFailure == null) return;
        mHandler.post(SyncthingResetPolicy.afterResetUnlessDestroying(
                () -> mDestroying,
                onFailure
        ));
    }

    @Nullable
    private RestApi createRecoveryRestApiFromDisk() {
        ConfigXml config = mConfig;
        if (config == null) {
            config = new ConfigXml(this);
            try {
                config.loadConfig();
            } catch (ConfigXml.OpenConfigException e) {
                Log.w(TAG, "Cannot load REST settings for execution recovery", e);
                return null;
            }
        }
        return createRecoveryRestApi(config);
    }

    @Override
    public SyncthingServiceBinder onBind(Intent intent) {
        return mBinder;
    }

    /**
     * Stops the native binary.
     * Shuts down RunConditionMonitor instance.
     */
    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        mDestroying = true;
        mPostMutationStartupGate.cancel(mShutdownStartIntent);
        failFileMutationBarrier();
        if (mRunConditionMonitor != null) {
            /**
             * Shut down the OnShouldRunChangedListener so we won't get interrupted by run
             * condition events that occur during shutdown.
             */
            mRunConditionMonitor.shutdown();
        }
        if (mNotificationHandler != null) {
            mNotificationHandler.setAppShutdownInProgress(true);
        }
        if (!mStoragePermissionGranted) {
            // If the storage permission got revoked, we did not start the binary and
            // are in State.INIT requiring an immediate shutdown of this service class.
            Log.i(TAG, "Shutting down syncthing binary due to missing storage permission.");
        }
        shutdown(State.DISABLED, null, true);
        super.onDestroy();
    }

    private void shutdown(State newState) {
        shutdown(newState, null);
    }

    private void shutdown(State newState, @Nullable Runnable afterShutdown) {
        shutdown(newState, afterShutdown, false);
    }

    /** Runs shutdown orchestration asynchronously; process waits never run on the service thread. */
    private void shutdown(
            State newState,
            @Nullable Runnable afterShutdown,
            boolean duringStartup
    ) {
        shutdown(newState, afterShutdown, duringStartup, false);
    }

    @FunctionalInterface
    private interface StoppedFileMutation {
        void run(Runnable releaseOwnership, boolean automaticStartupAllowed);
    }

    /** Reserves the shared mutation owner before shutdown and stopped-state file work begin. */
    private void shutdownForFileMutation(
            StoppedFileMutation afterMutation,
            Runnable onRejected,
            Runnable onShutdownFailure,
            Runnable onLifecycleConflict
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            if (!mHandler.post(() -> shutdownForFileMutation(
                    afterMutation, onRejected, onShutdownFailure, onLifecycleConflict
            ))) {
                onShutdownFailure.run();
            }
            return;
        }

        if (FileMutationBarrier.rejectIfShutdownContinuationPending(
                mShutdownInProgress, mAfterShutdown != null, onLifecycleConflict
        )) return;
        if (mPostMutationStartupGate.rejectNewMutation(onRejected)) return;

        FileMutationBarrier owner = FileMutationBarrier.reserveAsyncOwner(
                mFileMutationBarrier,
                onRejected,
                onShutdownFailure,
                onLifecycleConflict
        );
        if (owner == null) return;
        try {
            beginReservedAsyncMutation(
                    owner,
                    () -> mFileMutationBarrier = owner,
                    () -> {
                        owner.setAsyncMutation(() -> {
                            boolean startupSuppressed = owner.clearDeferredStartIfSuppressed(
                                    mShutdownStartIntent
                            );
                            afterMutation.run(
                                    () -> releaseAsyncFileMutation(owner), !startupSuppressed
                            );
                        });
                        shutdown(State.DISABLED, null);
                    }
            );
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not begin shutdown for HTTPS certificate mutation", e);
            if (mFileMutationBarrier == owner) mFileMutationBarrier = null;
            owner.stopFailed();
        }
    }

    private void releaseAsyncFileMutation(FileMutationBarrier owner) {
        if (mFileMutationBarrier == owner) mFileMutationBarrier = null;
    }

    /** Publishes certificate mutation ownership before beginning its asynchronous shutdown. */
    static void beginReservedAsyncMutation(FileMutationBarrier owner, Runnable publishOwner,
                                           Runnable beginShutdown) {
        if (!owner.isAsyncOwner()) throw new IllegalArgumentException("Expected async owner");
        publishOwner.run();
        beginShutdown.run();
    }

    private void shutdown(
            State newState,
            @Nullable Runnable afterShutdown,
            boolean duringStartup,
            boolean mutationBeforeDeferredCompletion
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mHandler.post(() -> shutdown(
                    newState, afterShutdown, duringStartup, mutationBeforeDeferredCompletion
            ));
            return;
        }
        if (mCurrentState == State.STARTING && !duringStartup) {
            Log.w(TAG, "Deferring shutdown until State.STARTING was left");
            mHandler.postDelayed(() -> {
                shutdown(newState, afterShutdown, false, mutationBeforeDeferredCompletion);
            }, 1000);
            return;
        }

        synchronized (mStateLock) {
            onServiceStateChange(newState);
        }

        if (mStartupReadiness != null) {
            mStartupReadiness.cancel();
            mStartupReadiness = null;
        }
        cancelStartupRequests();

        if (mNotificationHandler != null) {
            mNotificationHandler.cancelRestartNotification();
        }

        RestApi restApi = mRestApi != null ? mRestApi : mRecoveryRestApi;
        mRestApi = null;
        mRecoveryRestApi = null;

        if (mShutdownInProgress) {
            mAfterShutdown = mutationBeforeDeferredCompletion
                    ? FileMutationBarrier.mutationBeforeDeferredCompletion(
                            afterShutdown, mAfterShutdown
                    )
                    : appendCompletion(mAfterShutdown, afterShutdown);
            if (mShutdownRestApi == null) mShutdownRestApi = restApi;
            return;
        }

        mShutdownInProgress = true;
        mShutdownExitProven = mLastExecutionExitProven;
        mShutdownWorkerStarted = false;
        mShutdownRecoveryCheckStarted = false;
        mShutdownRestApi = restApi;
        mAfterShutdown = afterShutdown;

        if (mOwnedExecution != null) {
            startShutdownWorker(mOwnedExecution, restApi);
        } else if (mSyncthingRunnableThread != null) {
            if (duringStartup || mDestroying) mSyncthingRunnableThread.interrupt();
            if (mShutdownExitProven && !mSyncthingRunnableThread.isAlive()) {
                checkShutdownRecovery();
            }
        } else {
            mShutdownExitProven = true;
            checkShutdownRecovery();
        }
    }

    private void onShutdownOutcome(
            ExecutionIdentity identity,
            OwnedExecutionShutdown.Outcome outcome
    ) {
        if (!mShutdownInProgress) return;
        mShutdownWorkerStarted = false;
        if (outcome == OwnedExecutionShutdown.Outcome.EXITED) {
            mShutdownExitProven = true;
            mLastExecutionExitProven = true;
            if (sameExecution(mOwnedExecution, identity)) mOwnedExecution = null;
            clearWorkerHandlesWhenStopped(mSyncthingRunnableThread);
            checkShutdownRecovery();
            return;
        }

        Log.e(TAG, "Could not prove the owned Syncthing execution exited: " + outcome);
        mShutdownStartIntent.clear();
        mShutdownInProgress = false;
        mAfterShutdown = null;
        mShutdownRestApi = null;
        failFileMutationBarrier();
        synchronized (mStateLock) {
            if (mCurrentState != State.ERROR) onServiceStateChange(State.ERROR);
        }
    }

    /** Reclassifies after exit so no callback can mutate state while another candidate remains. */
    private void checkShutdownRecovery() {
        if (!mShutdownInProgress || !mShutdownExitProven || mShutdownRecoveryCheckStarted) return;
        Thread lifecycleThread = mSyncthingRunnableThread;
        boolean started = LifecycleShutdownBarrier.runWhenReady(
                mShutdownExitProven,
                lifecycleThread,
                () -> clearWorkerHandlesIfStopped(lifecycleThread),
                this::startShutdownRecoveryCheck
        );
        if (!started) {
            mHandler.postDelayed(this::checkShutdownRecovery, 25);
        }
    }

    private void startShutdownRecoveryCheck() {
        mShutdownRecoveryCheckStarted = true;
        RestApi recoveryApi = mShutdownRestApi;
        try {
            Thread recoveryCheck = new Thread(() -> {
                boolean launchPermitted = false;
                ExecutionOwnershipManager.RecoveryAssessment assessment = null;
                try {
                    assessment = mRuntime.recoverExecutions();
                    if (assessment.classification()
                            == ExecutionOwnershipManager.Classification.OWNED_EXECUTION) {
                        ExecutionIdentity identity = assessment.ownedExecution();
                        OwnedExecutionShutdown.Outcome outcome = OwnedExecutionShutdown.stop(
                                identity,
                                () -> {
                                    if (recoveryApi != null) recoveryApi.shutdown();
                                },
                                mRuntime,
                                OwnedExecutionShutdown.processWaiter()
                        );
                        if (outcome == OwnedExecutionShutdown.Outcome.EXITED) {
                            try {
                                mRuntime.clearAfterExit(identity);
                            } catch (IOException e) {
                                Log.e(TAG, "Could not clear recovered execution record", e);
                            }
                            assessment = mRuntime.recoverExecutions();
                        }
                    }
                    launchPermitted = assessment != null && assessment.mayLaunch();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Log.w(TAG, "Post-shutdown recovery check was interrupted", e);
                } catch (RuntimeException e) {
                    Log.e(TAG, "Could not classify executions after shutdown", e);
                }
                boolean permitted = launchPermitted;
                ExecutionOwnershipManager.RecoveryAssessment result = assessment;
                mHandler.post(() -> onShutdownRecoveryChecked(permitted, result));
            }, "Syncthing post-shutdown recovery check");
            recoveryCheck.setDaemon(true);
            recoveryCheck.start();
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not start post-shutdown recovery check", e);
            mShutdownRecoveryCheckStarted = false;
            onShutdownRecoveryChecked(false, null);
        }
    }

    private void onShutdownRecoveryChecked(
            boolean launchPermitted,
            @Nullable ExecutionOwnershipManager.RecoveryAssessment assessment
    ) {
        if (!mShutdownInProgress) return;
        if (!launchPermitted) {
            mShutdownStartIntent.clear();
            Log.e(TAG, "Shutdown completed but recovery remains blocked: "
                    + (assessment == null ? "unknown" : assessment.classification()));
            mShutdownInProgress = false;
            mShutdownRecoveryCheckStarted = false;
            mAfterShutdown = null;
            mShutdownRestApi = null;
            failFileMutationBarrier();
            synchronized (mStateLock) {
                if (mCurrentState != State.ERROR) onServiceStateChange(State.ERROR);
            }
            return;
        }

        Runnable completion = mAfterShutdown;
        mAfterShutdown = null;
        mShutdownRestApi = null;
        mShutdownInProgress = false;
        mShutdownRecoveryCheckStarted = false;
        mOwnedExecution = null;
        if (mDestroying) {
            failFileMutationBarrier();
            return;
        }
        Log.d(TAG, "Finished the owned Syncthing lifecycle execution.");
        if (mFileMutationBarrier != null) {
            FileMutationBarrier barrier = mFileMutationBarrier;
            // A continuation added while shutdown was pending is opaque and may claim runtime
            // admission. Fail the mutation before releasing its waiter, then honor that work.
            if (FileMutationBarrier.rejectMutationForDeferredCompletion(barrier, completion)) {
                mFileMutationBarrier = null;
                runShutdownCompletion(completion);
                return;
            }
            if (barrier.isAsyncOwner()) {
                Runnable asyncCompletion = barrier.completeAsyncOwner();
                if (asyncCompletion != null) {
                    try {
                        runShutdownCompletion(asyncCompletion);
                    } catch (RuntimeException e) {
                        Log.e(TAG, "Asynchronous file mutation could not complete", e);
                        failFileMutationBarrier();
                    }
                }
            } else {
                barrier.stopCompleted(completion);
                if (barrier.isAbandoned()) completeAbandonedFileMutation(barrier);
            }
            return;
        }
        runShutdownCompletion(completion);
    }

    private void clearWorkerHandlesWhenStopped(@Nullable Thread lifecycleThread) {
        if (lifecycleThread == null) return;
        if (lifecycleThread.isAlive()) {
            mHandler.postDelayed(() -> clearWorkerHandlesWhenStopped(lifecycleThread), 25);
            return;
        }
        clearWorkerHandlesIfStopped(lifecycleThread);
        if (mShutdownInProgress && mShutdownExitProven) checkShutdownRecovery();
    }

    private void clearWorkerHandlesIfStopped(@Nullable Thread lifecycleThread) {
        if (lifecycleThread == null || lifecycleThread.isAlive()) return;
        if (mSyncthingRunnableThread == lifecycleThread) {
            mSyncthingRunnableThread = null;
            mSyncthingRunnable = null;
        }
    }

    private void runShutdownCompletion(@Nullable Runnable completion) {
        boolean executionExitProven = mShutdownExitProven;
        if (completion != null) {
            completion.run();
        }
        if (mPostMutationStartupGate.consumeDeferredStart(
                mShutdownStartIntent,
                mLastDeterminedShouldRun,
                executionExitProven,
                true,
                mCurrentState == State.DISABLED
                        && mOwnedExecution == null
                        && mSyncthingRunnable == null
                        && mSyncthingRunnableThread == null,
                mDestroying
        )) {
            launchStartupTask(SyncthingCommand.SERVE);
        }
    }

    private void failFileMutationBarrier() {
        if (mFileMutationBarrier == null) return;
        FileMutationBarrier barrier = mFileMutationBarrier;
        mFileMutationBarrier = null;
        barrier.stopFailed();
    }

    @Nullable
    private static Runnable appendCompletion(
            @Nullable Runnable first,
            @Nullable Runnable second
    ) {
        if (first == null) return second;
        if (second == null) return first;
        return () -> {
            first.run();
            second.run();
        };
    }

    /** Waits off the service thread before backup/import code touches files used by Syncthing. */
    private boolean shutdownForFileMutation() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.e(TAG, "File mutation was requested on the service thread");
            return false;
        }
        FileMutationBarrier barrier = new FileMutationBarrier();
        boolean posted = mHandler.post(() -> {
            if (mDestroying || mFileMutationBarrier != null) {
                barrier.stopFailed();
                return;
            }
            if (mPostMutationStartupGate.rejectNewMutation(barrier::stopFailed)) return;
            if (FileMutationBarrier.rejectIfShutdownContinuationPending(
                    mShutdownInProgress,
                    mAfterShutdown != null,
                    barrier::stopFailed
            )) {
                Log.w(TAG, "Rejecting file mutation while a shutdown continuation is pending");
                return;
            }
            boolean lifecycleHandlePresent = mSyncthingRunnableThread != null
                    || mSyncthingRunnable != null;
            if (FileMutationBarrier.rejectIfNoSafeShutdownPath(
                    lifecycleHandlePresent,
                    mOwnedExecution != null,
                    mLastExecutionExitProven,
                    barrier::stopFailed
            )) return;
            try {
                mFileMutationBarrier = barrier;
                if (mRestApi == null && mRecoveryRestApi == null) {
                    mRecoveryRestApi = createRecoveryRestApiFromDisk();
                }
                shutdown(State.DISABLED, null, true);
            } catch (RuntimeException e) {
                Log.e(TAG, "Could not begin shutdown for file mutation", e);
                if (mFileMutationBarrier == barrier) mFileMutationBarrier = null;
                barrier.stopFailed();
            }
        });
        if (!posted) barrier.stopFailed();
        boolean safeToMutate = barrier.awaitSafeToMutate();
        if (!safeToMutate) {
            mHandler.post(() -> releaseFailedFileMutationWait(barrier));
        }
        return safeToMutate;
    }

    private void releaseFailedFileMutationWait(FileMutationBarrier barrier) {
        if (mFileMutationBarrier != barrier) return;
        barrier.abandon();
        if (mShutdownInProgress) return;
        completeAbandonedFileMutation(barrier);
    }

    /** Finishes shutdown for an interrupted file caller without permitting its file writes. */
    private void completeAbandonedFileMutation(FileMutationBarrier barrier) {
        if (mFileMutationBarrier != barrier) return;
        boolean destroying = mDestroying;
        Runnable deferred = finishAbandonedFileMutation(
                barrier, mShutdownStartIntent, mLastDeterminedShouldRun, destroying
        );
        mFileMutationBarrier = null;
        if (destroying) return;
        runShutdownCompletion(deferred);
    }

    /**
     * Applies the stopped-state policy for an interrupted file mutation. When this mutation still
     * permits an automatic start, its Run Conditions start is deferred exactly once; explicit STOP
     * suppression clears any deferred start instead. Returns the shutdown continuation that must run
     * after the barrier is released, or {@code null} while the service is being destroyed.
     */
    static @Nullable Runnable finishAbandonedFileMutation(
            FileMutationBarrier barrier,
            ShutdownStartIntent startIntent,
            boolean shouldRunNow,
            boolean destroying
    ) {
        if (barrier.shouldAutomaticallyStartAfterMutation(shouldRunNow)) {
            startIntent.onRunConditionChanged(true, true);
        }
        barrier.clearDeferredStartIfSuppressed(startIntent);
        Runnable deferred = barrier.takeAfterMutation();
        return destroying ? null : deferred;
    }

    /** Releases a file-mutation barrier and runs any deferred completion on the service thread. */
    private void finishFileMutation(
            @Nullable Runnable afterMutation,
            boolean evaluateRunConditions
    ) {
        finishFileMutation(afterMutation, evaluateRunConditions, false);
    }

    private void finishFileMutation(
            @Nullable Runnable afterMutation,
            boolean evaluateRunConditions,
            boolean operationOwnsStartup
    ) {
        mHandler.post(() -> {
            FileMutationBarrier barrier = mFileMutationBarrier;
            if (barrier == null) return;
            boolean startupSuppressed = barrier.clearDeferredStartIfSuppressed(
                    mShutdownStartIntent
            );
            mFileMutationBarrier = null;
            if (mDestroying) return;

            if (operationOwnsStartup) {
                mPostMutationStartupGate.beginOperation(!startupSuppressed);
            }

            Runnable deferred = barrier.takeAfterMutation();
            runShutdownCompletion(deferred);
            if (afterMutation != null) {
                try {
                    afterMutation.run();
                } catch (RuntimeException e) {
                    Log.e(TAG, "Post-mutation lifecycle continuation failed", e);
                    if (operationOwnsStartup) failImportLifecycle();
                }
            }
            if (deferred == null && afterMutation == null && evaluateRunConditions
                    && mCurrentState == State.DISABLED
                    && barrier.shouldAutomaticallyStartAfterMutation(
                            mLastDeterminedShouldRun
                    )) {
                launchStartupTask(SyncthingCommand.SERVE);
            }
        });
    }

    private void completeImportLifecycle() {
        mPostMutationStartupGate.completeOperation(
                mShutdownStartIntent,
                mLastDeterminedShouldRun,
                () -> launchStartupTask(SyncthingCommand.SERVE)
        );
    }

    private void failImportLifecycle() {
        mPostMutationStartupGate.cancel(mShutdownStartIntent);
    }

    public @Nullable
    RestApi getApi() {
        return mRestApi;
    }

    /**
     * Force re-evaluating run conditions immediately e.g. after
     * preferences were modified by {@link ../activities/SettingsActivity#onStop}.
     */
    public void evaluateRunConditions() {
        if (mRunConditionMonitor == null) {
            return;
        }
        Log.d(TAG, "Forced re-evaluating run conditions ...");
        mRunConditionMonitor.updateShouldRunDecision();
    }

    /**
     * Register a listener for the syncthing API state changing.
     * The listener is called immediately with the current state, and again whenever the state
     * changes. The call is always from the GUI thread.
     *
     * @see #unregisterOnServiceStateChangeListener
     */
    public void registerOnServiceStateChangeListener(OnServiceStateChangeListener listener) {
        /**
         * Initially send the current state to the new subscriber to make sure it doesn't stay
         * in undefined state forever until the state next change occurs.
         */
        listener.onServiceStateChange(mCurrentState);
        mOnServiceStateChangeListeners.add(listener);
    }

    /**
     * Unregisters a previously registered listener.
     *
     * @see #registerOnServiceStateChangeListener
     */
    public void unregisterOnServiceStateChangeListener(OnServiceStateChangeListener listener) {
        mOnServiceStateChangeListeners.remove(listener);
    }

    /**
     * Called to notify listeners of an API change.
     */
    private void onServiceStateChange(State newState) {
        if (newState == mCurrentState) {
            Log.d(TAG, "onServiceStateChange: Called with unchanged state " + newState);
            return;
        }
        Log.i(TAG, "onServiceStateChange: from " + mCurrentState + " to " + newState);
        mCurrentState = newState;
        mHandler.post(() -> {
            mNotificationHandler.updatePersistentNotification(this);
            Iterator<OnServiceStateChangeListener> it = mOnServiceStateChangeListeners.iterator();
            while (it.hasNext()) {
                OnServiceStateChangeListener listener = it.next();
                if (listener != null) {
                    listener.onServiceStateChange(mCurrentState);
                } else {
                    it.remove();
                }
            }
        });
    }

    public State getCurrentState() {
        return mCurrentState;
    }

    public NotificationHandler getNotificationHandler() {
        return mNotificationHandler;
    }

    public String getRunDecisionExplanation() {
        if (mRunConditionMonitor != null) {
            return mRunConditionMonitor.getRunDecisionExplanation();
        }

        // mRunConditionMonitor == null
        return getResources().getString(R.string.reason_run_condition_monitor_not_instantiated);
    }

    /**
     * Get backup zip file.
     * Default: /storage/emulated0/backups/syncthing/config.zip
     */
    private final File getBackupZipFile() {
        String defaultPath = "backups/syncthing/config.zip";
        String relPathToZip = mPreferences.getString(Constants.PREF_BACKUP_REL_PATH_TO_ZIP, defaultPath);
        // NOTE: somehow we get empty string from the prefs, which crashes the app, use default when that happens
        // TODO: figure out where the empty string is coming from and fix that
        if (relPathToZip.isEmpty()) {
            relPathToZip = defaultPath;
        }
        return new File(Environment.getExternalStorageDirectory(), relPathToZip);
    }

    /**
     * Exports the local config and keys to {@link Constants#EXPORT_PATH}.
     *
     * Test with Android Virtual Device using emulator.
     * cls & adb shell su 0 "ls -a -l -R /data/data/${applicationId}/files; echo === SDCARD ===; ls -a -l -R /storage/emulated/0/backups/syncthing"
     *
     */
    public boolean exportConfig() {
        Boolean failSuccess = true;
        Log.d(TAG, "exportConfig BEGIN");

        if (!shutdownForFileMutation()) return false;
        try {

        // Create export dir if non-existant.
            File targetZip = getBackupZipFile();
            targetZip.getParentFile().mkdirs();

            // Export SharedPreferences.
            File sharedPreferencesFile = null;
            FileOutputStream fileOutputStream = null;
            ObjectOutputStream objectOutputStream = null;
            try {
                sharedPreferencesFile = Constants.getSharedPrefsFile(this);
                fileOutputStream = new FileOutputStream(sharedPreferencesFile);
                if (!sharedPreferencesFile.exists()) {
                    sharedPreferencesFile.createNewFile();
                }
                objectOutputStream = new ObjectOutputStream(fileOutputStream);
                objectOutputStream.writeObject(mPreferences.getAll());
                objectOutputStream.flush();
                fileOutputStream.flush();
            } catch (IOException e) {
                Log.e(TAG, "exportConfig: Failed to export SharedPreferences #1", e);
                failSuccess = false;
            } finally {
                try {
                    if (objectOutputStream != null) {
                        objectOutputStream.close();
                    }
                    if (fileOutputStream != null) {
                        fileOutputStream.close();
                    }
                } catch (IOException e) {
                    Log.e(TAG, "exportConfig: Failed to export SharedPreferences #2", e);
                }
            }

            // Make a list of files to backup.
            List<File> includePaths = Arrays.asList(
                Constants.getConfigFile(this),

                Constants.getPrivateKeyFile(this),
                Constants.getPublicKeyFile(this),

                Constants.getHttpsCertFile(this),
                Constants.getHttpsKeyFile(this),

                Constants.getSharedPrefsFile(this),

                Constants.getIndexDbFolder(this)
            );

            // If user set one, apply a password and encrypt the zip file.
            String zipEncryptionPassword = mPreferences.getString(Constants.PREF_BACKUP_PASSWORD, "");

            // Compress files to zip file.
            try {
                // Delete existing ZIP file to ensure we create a fresh archive instead of appending
                if (targetZip.exists()) {
                    targetZip.delete();
                }
            
                ZipParameters parameters = new ZipParameters();
                parameters.setCompressionMethod(CompressionMethod.DEFLATE);
                parameters.setCompressionLevel(CompressionLevel.NORMAL);

                ZipFile zipFile;
                if (zipEncryptionPassword.isEmpty()) {
                    zipFile = new ZipFile(targetZip);
                    parameters.setEncryptFiles(false);
                } else {
                    zipFile = new ZipFile(targetZip, zipEncryptionPassword.toCharArray());
                    parameters.setEncryptFiles(true);
                    parameters.setEncryptionMethod(EncryptionMethod.AES);
                    parameters.setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256);
                }

                // Add files.
                for (File includePath : includePaths) {
                    if (includePath.exists()) {
                        if (includePath.isFile()) {
                            zipFile.addFile(includePath, parameters);
                        } else if (includePath.isDirectory()) {
                            zipFile.addFolder(includePath, parameters);
                        }
                    }
                }

                if (sharedPreferencesFile != null && sharedPreferencesFile.exists()) {
                    sharedPreferencesFile.delete();
                }
            } catch (Exception e) {
                Log.w(TAG, "exportConfig: Failed to export config, " + e.getMessage());
                failSuccess = false;
            }
            Log.d(TAG, "exportConfig END");
            return failSuccess;
        } finally {
            finishFileMutation(null, true);
        }
    }

    /**
     * Imports config and keys from {@link Constants#EXPORT_PATH}.
     *
     * Test with Android Virtual Device using emulator.
     * cls & adb shell su 0 "ls -a -l -R /data/data/${applicationId}/files; echo === SDCARD ===; ls -a -l -R /storage/emulated/0/backups/syncthing"
     *
     * @return True if the import was successful, false otherwise (eg if files aren't found).
     */
    public boolean importConfig() {
        ZipFile zipFile = null;
        Log.d(TAG, "importConfig PRECHECK");

        // Check if ZIP exists.
        File zipFilePath = getBackupZipFile();
        if (!zipFilePath.exists()) {
            Log.e(TAG, "importConfig: ZIP file is missing. Please check if it is present at '" + zipFilePath.getAbsolutePath() + "' as specified in the settings screen.");
            return false;
        }

        // Open ZIP file.
        try {
            // If user set one, get password to decrypt the zip file.
            String zipEncryptionPassword = mPreferences.getString(Constants.PREF_BACKUP_PASSWORD, "");
            if (zipEncryptionPassword.isEmpty()) {
                zipFile = new ZipFile(zipFilePath);
            } else {
                zipFile = new ZipFile(zipFilePath, zipEncryptionPassword.toCharArray());
                if (!zipFile.isEncrypted()) {
                    Log.e(TAG, "importConfig: ZIP file is not encrypted, but password was specified in settings screen. Try to specify an empty password temporarily.");
                    return false;
                }
            }

            // Check if ZIP archive contains required files.
            List<String> checkFiles = Arrays.asList(
                Constants.CONFIG_FILE,

                Constants.PRIVATE_KEY_FILE,
                Constants.PUBLIC_KEY_FILE
            );
            for (final String checkFile : checkFiles) {
                if (zipFile.getFileHeader(checkFile) == null) {
                    Log.e(TAG, "importConfig: Required file not found inside zip [" + checkFile + "]");
                    return false;
                }
            }

            // Test if supplied encryption password is correct.
            String cacheDir = this.getCacheDir().getAbsolutePath();
            zipFile.extractFile(Constants.PUBLIC_KEY_FILE, cacheDir);
            new File(cacheDir, Constants.PUBLIC_KEY_FILE).delete();
        } catch (ZipException e) {
            Log.e(TAG, "importConfig: Failed to open zip, " + e.getMessage());
            return false;
        }

        // Shutdown SyncthingNative.
        Boolean failSuccess = true;
        Log.d(TAG, "importConfig BEGIN");
        if (!shutdownForFileMutation()) return false;
        Runnable afterImport = null;
        boolean resetRequested = false;
        try {

        // Remove database folder if it exists.
            File databasePath = Constants.getIndexDbFolder(this);
            if (databasePath.exists()) {
                Log.d(TAG, "importConfig: Clearing index database");
                try {
                    FileUtils.deleteDirectoryRecursively(databasePath);
                } catch (IOException e) {
                    Log.e(TAG, "Failed to delete directory '" + databasePath.getAbsolutePath() + "'" + e);
                }
            }

            // Decompress zip file.
            try {
                zipFile.extractAll(this.getFilesDir().getAbsolutePath());
            } catch (ZipException e) {
                Log.e(TAG, "importConfig: Failed to extract zip, " + e.getMessage());
                failSuccess = false;
            }

            // Check if necessary files are present after extraction.
            List<File> checkPaths = Arrays.asList(
                Constants.getConfigFile(this),

                Constants.getPrivateKeyFile(this),
                Constants.getPublicKeyFile(this),

                Constants.getHttpsCertFile(this),
                Constants.getHttpsKeyFile(this),

                Constants.getSharedPrefsFile(this)
            );
            for (final File checkPath : checkPaths) {
                if (!checkPath.exists()) {
                    Log.e(TAG, "importConfig: Missing file after extraction [" + checkPath.getName() + "]");
                    failSuccess = false;
                }
            }
        
            // Import shared preferences.
            File sharedPreferencesFile = Constants.getSharedPrefsFile(this);
            if (sharedPreferencesFile.exists()) {
                Log.d(TAG, "importConfig: Importing shared preferences");
                failSuccess = failSuccess && importConfigSharedPrefs(sharedPreferencesFile);
                sharedPreferencesFile.delete();
            }

            try {
                resetRequested = cleanupImportedFolderDatabases();
            } catch (Exception e) {
                Log.e(TAG, "importConfig: Failed to cleanup invalid folder databases", e);
            }

            if (resetRequested) {
                afterImport = () -> requestResetDatabase(
                        this::completeImportLifecycle,
                        this::failImportLifecycle
                );
            } else {
                afterImport = this::completeImportLifecycle;
            }
            return failSuccess;
        } finally {
            finishFileMutation(importCompletionOrFailure(
                    afterImport, failSuccess, this::failImportLifecycle
            ), false, true);
        }
    }

    /** Failed imports release lifecycle ownership without starting from partial managed state. */
    static Runnable importCompletionOrFailure(@Nullable Runnable completion, boolean succeeded,
                                              Runnable failure) {
        return succeeded && completion != null ? completion : failure;
    }

    /**
     * Backstop timeout for {@link #verifyRestartAndRollback} in case the state machine never reaches
     * a terminal state (e.g. the binary crashed via a path that doesn't transition to ERROR).
     */
    private static final long HTTPS_CERT_VERIFY_TIMEOUT_MS = 30000;
    private static final String FILE_MUTATION_IN_PROGRESS_MESSAGE =
            "Another file mutation is already in progress.";
    private static final String CERT_MUTATION_SHUTDOWN_FAILURE_MESSAGE =
            "Could not safely stop Syncthing before changing its HTTPS certificate.";
    private static final String CERT_MUTATION_LIFECYCLE_PENDING_MESSAGE =
            "Cannot change the HTTPS certificate while a Syncthing lifecycle operation is pending.";

    /**
     * Replaces the Web GUI HTTPS certificate and key with the supplied PEM bytes, then restarts
     * Syncthing so the new files take effect. If the restart fails to bring the Web GUI back online,
     * the previous certificate and key are restored automatically.
     *
     * The bytes are expected to already be validated (see {@link com.nutomic.syncthingandroid.util.CertificateValidator}).
     * The whole start/stop lifecycle is marshalled onto the main thread because the binary
     * orchestration fields are only safe to touch there.
     */
    public void replaceHttpsCertificate(byte[] certPem, byte[] keyPem,
                                        OnHttpsCertReplaceResultListener listener) {
        mHandler.post(() -> doReplaceHttpsCertificate(certPem, keyPem, listener));
    }

    /**
     * Deletes the user-supplied HTTPS certificate/key so Syncthing regenerates a fresh self-signed
     * certificate at the next start, then restarts (with rollback on failure).
     */
    public void resetHttpsCertificate(OnHttpsCertReplaceResultListener listener) {
        mHandler.post(() -> doResetHttpsCertificate(listener));
    }

    private void doReplaceHttpsCertificate(byte[] certPem, byte[] keyPem,
                                           OnHttpsCertReplaceResultListener listener) {
        if (FileMutationBarrier.rejectConcurrentMutation(
                mFileMutationBarrier,
                () -> listener.onResult(
                        HttpsCertReplaceResult.FAILED, FILE_MUTATION_IN_PROGRESS_MESSAGE
                )
        )) return;
        if (mCurrentState == State.STARTING) {
            mHandler.postDelayed(() -> doReplaceHttpsCertificate(certPem, keyPem, listener), 1000);
            return;
        }

        final File certFile = Constants.getHttpsCertFile(this);
        final File keyFile = Constants.getHttpsKeyFile(this);
        StoppedFileMutation replaceFiles = (releaseOwnership, automaticStartupAllowed) -> {
            final File certBak = backupFile(certFile);
            final File keyBak = backupFile(keyFile);

            try {
                writeBytesAtomic(certFile, certPem);
                writeBytesAtomic(keyFile, keyPem);
                restrictToOwner(keyFile);
            } catch (IOException e) {
                Log.e(TAG, "doReplaceHttpsCertificate: Failed to write new cert/key", e);
                restoreFile(certBak, certFile);
                restoreFile(keyBak, keyFile);
                if (releaseOwnership != null) releaseOwnership.run();
                if (automaticStartupAllowed && mLastDeterminedShouldRun) {
                    launchStartupTask(SyncthingCommand.SERVE);
                }
                listener.onResult(HttpsCertReplaceResult.FAILED, e.getMessage());
                return;
            }

            if (releaseOwnership != null) releaseOwnership.run();
            applyCertChangeWithVerify(
                    certFile, keyFile, certBak, keyBak, listener, automaticStartupAllowed
            );
        };

        if (hasServiceExecution()) {
            shutdownForFileMutation(
                    replaceFiles,
                    () -> listener.onResult(
                            HttpsCertReplaceResult.FAILED, FILE_MUTATION_IN_PROGRESS_MESSAGE
                    ),
                    () -> listener.onResult(
                            HttpsCertReplaceResult.FAILED, CERT_MUTATION_SHUTDOWN_FAILURE_MESSAGE
                    ),
                    () -> listener.onResult(
                            HttpsCertReplaceResult.FAILED, CERT_MUTATION_LIFECYCLE_PENDING_MESSAGE
                    )
            );
        } else {
            replaceFiles.run(null, true);
        }
    }

    private void doResetHttpsCertificate(OnHttpsCertReplaceResultListener listener) {
        if (FileMutationBarrier.rejectConcurrentMutation(
                mFileMutationBarrier,
                () -> listener.onResult(
                        HttpsCertReplaceResult.FAILED, FILE_MUTATION_IN_PROGRESS_MESSAGE
                )
        )) return;
        if (mCurrentState == State.STARTING) {
            mHandler.postDelayed(() -> doResetHttpsCertificate(listener), 1000);
            return;
        }

        final File certFile = Constants.getHttpsCertFile(this);
        final File keyFile = Constants.getHttpsKeyFile(this);

        StoppedFileMutation resetFiles = (releaseOwnership, automaticStartupAllowed) -> {
            final File certBak = backupFile(certFile);
            final File keyBak = backupFile(keyFile);
            // Removing the files makes syncthing generate a fresh self-signed certificate at startup.
            deleteQuietly(certFile);
            deleteQuietly(keyFile);
            if (releaseOwnership != null) releaseOwnership.run();
            applyCertChangeWithVerify(
                    certFile, keyFile, certBak, keyBak, listener, automaticStartupAllowed
            );
        };

        if (hasServiceExecution()) {
            shutdownForFileMutation(
                    resetFiles,
                    () -> listener.onResult(
                            HttpsCertReplaceResult.FAILED, FILE_MUTATION_IN_PROGRESS_MESSAGE
                    ),
                    () -> listener.onResult(
                            HttpsCertReplaceResult.FAILED, CERT_MUTATION_SHUTDOWN_FAILURE_MESSAGE
                    ),
                    () -> listener.onResult(
                            HttpsCertReplaceResult.FAILED, CERT_MUTATION_LIFECYCLE_PENDING_MESSAGE
                    )
            );
        } else {
            resetFiles.run(null, true);
        }
    }

    private boolean hasServiceExecution() {
        return mShutdownInProgress
                || mFileMutationBarrier != null
                || mCurrentState != State.DISABLED
                || mOwnedExecution != null
                || mSyncthingRunnable != null
                || mSyncthingRunnableThread != null;
    }

    private void applyCertChangeWithVerify(File certFile, File keyFile,
                                           @Nullable File certBak, @Nullable File keyBak,
                                           OnHttpsCertReplaceResultListener listener,
                                           boolean automaticStartupAllowed) {
        if (automaticStartupAllowed && mLastDeterminedShouldRun) {
            verifyRestartAndRollback(certFile, keyFile, certBak, keyBak, listener);
        } else {
            // Not currently meant to run; the new files will take effect on next start.
            deleteQuietly(certBak);
            deleteQuietly(keyBak);
            listener.onResult(HttpsCertReplaceResult.SUCCESS_PENDING_START, null);
        }
    }

    /**
     * Restarts the binary to verify the new certificate. Reaching ACTIVE succeeds; a genuine
     * startup failure, an abnormal STARTING&rarr;DISABLED transition, or a watchdog timeout restores
     * the backed-up cert/key. An explicit stop before success keeps the new files and reports
     * {@link HttpsCertReplaceResult#SUCCESS_PENDING_START}; a stop during failure recovery also
     * suppresses its automatic relaunch.
     */
    private void verifyRestartAndRollback(File certFile, File keyFile,
                                          @Nullable File certBak, @Nullable File keyBak,
                                          OnHttpsCertReplaceResultListener listener) {
        final CertificateVerificationState verification = new CertificateVerificationState();
        verification.beginVerification();
        final OnServiceStateChangeListener[] verifyListener = new OnServiceStateChangeListener[1];
        final Runnable[] watchdog = new Runnable[1];
        final CertificateVerificationStopHandler[] stopHandler =
                new CertificateVerificationStopHandler[1];

        final Runnable clearStopHandler = () -> {
            if (mCertificateVerificationStopHandler == stopHandler[0]) {
                mCertificateVerificationStopHandler = null;
            }
        };

        final Runnable finishSuccess = () -> {
            clearStopHandler.run();
            deleteQuietly(certBak);
            deleteQuietly(keyBak);
            listener.onResult(HttpsCertReplaceResult.SUCCESS, null);
        };
        final Runnable finishPendingStart = () -> {
            verification.completePendingStart(
                    () -> {
                        deleteQuietly(certBak);
                        deleteQuietly(keyBak);
                    },
                    () -> {
                        clearStopHandler.run();
                        listener.onResult(HttpsCertReplaceResult.SUCCESS_PENDING_START, null);
                    }
            );
        };
        final Runnable finishFailure = () -> {
            Runnable restoreAndRelaunch = verification.completeFailureRecovery(
                    () -> {
                        restoreFile(certBak, certFile);
                        restoreFile(keyBak, keyFile);
                    },
                    () -> mLastDeterminedShouldRun,
                    () -> launchStartupTask(SyncthingCommand.SERVE),
                    () -> onServiceStateChange(State.DISABLED),
                    () -> {
                        clearStopHandler.run();
                        listener.onResult(HttpsCertReplaceResult.FAILED,
                                "Syncthing did not come online with the new certificate.");
                    }
            );
            if (hasServiceExecution()) {
                shutdown(State.INIT, restoreAndRelaunch, true);
            } else {
                restoreAndRelaunch.run();
            }
        };

        watchdog[0] = () -> {
            CertificateVerificationState.Outcome outcome =
                    verification.onVerificationResult(mCurrentState == State.ACTIVE);
            scheduleCertificateVerificationResolution(
                    outcome, verifyListener[0], watchdog[0], finishSuccess,
                    finishPendingStart, finishFailure
            );
        };

        verifyListener[0] = (state) -> {
            if (state == State.STARTING) {
                verification.onStarting();
                return;
            }
            final boolean success = (state == State.ACTIVE);
            final boolean failure = (state == State.ERROR)
                    || (verification.sawStarting() && state == State.DISABLED);
            if (!success && !failure) {
                return;
            }
            CertificateVerificationState.Outcome outcome =
                    verification.onVerificationResult(success);
            scheduleCertificateVerificationResolution(
                    outcome, verifyListener[0], watchdog[0], finishSuccess,
                    finishPendingStart, finishFailure
            );
        };

        stopHandler[0] = (crashedNativeStop) -> {
            boolean verificationAlreadyActive = mCurrentState == State.ACTIVE;
            boolean preserveFailureResolution = crashedNativeStop
                    || mCurrentState == State.ERROR
                    || (verification.sawStarting() && mCurrentState == State.DISABLED);
            CertificateVerificationState.Outcome outcome = verification.onExplicitStop(
                    verificationAlreadyActive, preserveFailureResolution
            );
            scheduleCertificateVerificationResolution(
                    outcome, verifyListener[0], watchdog[0], finishSuccess,
                    finishPendingStart, finishFailure
            );
        };
        mCertificateVerificationStopHandler = stopHandler[0];

        // registerOnServiceStateChangeListener replays the current state (DISABLED) synchronously;
        // that is ignored because sawStarting is still false.
        registerOnServiceStateChangeListener(verifyListener[0]);
        mHandler.postDelayed(watchdog[0], HTTPS_CERT_VERIFY_TIMEOUT_MS);
        launchStartupTask(SyncthingCommand.SERVE);
    }

    private void scheduleCertificateVerificationResolution(
            CertificateVerificationState.Outcome outcome,
            OnServiceStateChangeListener verifyListener,
            Runnable watchdog,
            Runnable finishSuccess,
            Runnable finishPendingStart,
            Runnable finishFailure) {
        if (outcome == CertificateVerificationState.Outcome.IGNORED) {
            return;
        }
        mHandler.removeCallbacks(watchdog);
        // Defer unregister + lifecycle work out of onServiceStateChange's listener iteration.
        mHandler.post(() -> {
            unregisterOnServiceStateChangeListener(verifyListener);
            CertificateVerificationState.dispatch(
                    outcome, finishSuccess, finishPendingStart, finishFailure
            );
        });
    }

    @Nullable
    private File backupFile(File file) {
        if (!file.exists()) {
            return null;
        }
        File bak = new File(file.getParentFile(), file.getName() + ".bak");
        deleteQuietly(bak);
        if (file.renameTo(bak)) {
            return bak;
        }
        Log.w(TAG, "backupFile: Failed to back up " + file.getName());
        return null;
    }

    private void restoreFile(@Nullable File bak, File target) {
        if (bak == null || !bak.exists()) {
            return;
        }
        deleteQuietly(target);
        if (!bak.renameTo(target)) {
            Log.w(TAG, "restoreFile: Failed to restore " + target.getName());
        }
    }

    private void deleteQuietly(@Nullable File file) {
        if (file != null && file.exists() && !file.delete()) {
            Log.w(TAG, "deleteQuietly: Failed to delete " + file.getName());
        }
    }

    private void writeBytesAtomic(File target, byte[] data) throws IOException {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(data);
            fos.flush();
            fos.getFD().sync();
        }
        if (!tmp.renameTo(target)) {
            deleteQuietly(tmp);
            throw new IOException("Failed to rename " + tmp.getName() + " to " + target.getName());
        }
    }

    private void restrictToOwner(File file) {
        // Mirror syncthing core, which writes the HTTPS key with 0600 permissions.
        file.setReadable(false, false);
        file.setReadable(true, true);
        file.setWritable(false, false);
        file.setWritable(true, true);
        file.setExecutable(false, false);
    }

    private boolean cleanupImportedFolderDatabases() {
        ConfigXml configXml = new ConfigXml(this);
        try {
            configXml.loadConfig();
        } catch (ConfigXml.OpenConfigException e) {
            Log.w(TAG, "importConfig: Unable to parse imported config for DB cleanup");
            return false;
        }

        final List<Folder> folders = configXml.getFolders();
        if (folders == null || folders.isEmpty()) {
            return false;
        }

        for (Folder folder : folders) {
            if (folder == null || folder.id == null || folder.id.isEmpty()) {
                continue;
            }

            final String folderPathValue = folder.path;
            final File folderPath = (folderPathValue == null || folderPathValue.isEmpty())
                    ? null
                    : new File(folderPathValue);
            final boolean folderPathMissing = folderPath == null || !folderPath.isDirectory();

            String markerName = folder.markerName;
            if (markerName == null || markerName.isEmpty()) {
                markerName = Constants.FILENAME_STFOLDER;
            }
            final boolean markerMissing = folderPathMissing || !new File(folderPath, markerName).exists();

            if (folderPathMissing || markerMissing) {
                Log.i(TAG, "importConfig: Folder path or marker missing for folder id \"" + folder.id + "\". Resetting Syncthing database.");
                return true;
            }
        }
        return false;
    }

    private boolean importConfigSharedPrefs(final File file) {
        Boolean failSuccess = true;
        FileInputStream fileInputStream = null;
        ObjectInputStream objectInputStream = null;
        Map<?, ?> sharedPrefsMap = null;
        try {
            
            // Read, deserialize shared preferences.
            fileInputStream = new FileInputStream(file);
            objectInputStream = new ObjectInputStream(fileInputStream);
            Object objectFromInputStream = objectInputStream.readObject();
            if (objectFromInputStream instanceof Map) {
                sharedPrefsMap = (Map<?, ?>) objectFromInputStream;

                // Store backup folder to restore it back later in the process.
                String relPathToZip = mPreferences.getString(Constants.PREF_BACKUP_REL_PATH_TO_ZIP, "");
                String backupPassword = mPreferences.getString(Constants.PREF_BACKUP_PASSWORD, "");

                // Prepare a SharedPreferences commit.
                SharedPreferences.Editor editor = mPreferences.edit();
                editor.clear();
                for (Map.Entry<?, ?> e : sharedPrefsMap.entrySet()) {
                    String prefKey = (String) e.getKey();
                    switch (prefKey) {
                        // Preferences that are no longer used and left-overs from previous versions of the app.
                        case "first_start":
                        case "advanced_folder_picker":
                        case "backup_folder_name":
                        case "bind_network":
                        case "log_to_file":
                        case "notification_type":
                        case "notify_crashes":
                        case "suggest_new_folder_root":
                        case "use_legacy_hashing":
                        case "pref_current_language":
                        case "restartOnWakeup":
                        case "wakelock_while_binary_running":
                        case "use_root":
                        case "important_news_shown_version":
                            LogV("importConfig: Ignoring deprecated pref \"" + prefKey + "\".");
                            break;
                        // Cached information which is not available on SettingsActivity.
                        case Constants.PREF_APP_START_COUNTER:
                        case Constants.PREF_BTNSTATE_FORCE_START_STOP:
                        case Constants.PREF_DEBUG_FACILITIES_AVAILABLE:
                        case Constants.PREF_EVENT_PROCESSOR_LAST_SYNC_ID:
                        case Constants.PREF_LAST_BINARY_VERSION:
                        case Constants.PREF_LOCAL_DEVICE_ID:
                        case Constants.PREF_LAST_RUN_TIME:
                            LogV("importConfig: Ignoring cache pref \"" + prefKey + "\".");
                            break;
                        default:
                            Log.i(TAG, "importConfig: Adding pref \"" + prefKey + "\" to commit ...");

                            // The editor only provides typed setters.
                            if (e.getValue() instanceof Boolean) {
                                editor.putBoolean(prefKey, (Boolean) e.getValue());
                            } else if (e.getValue() instanceof String) {
                                editor.putString(prefKey, (String) e.getValue());
                            } else if (e.getValue() instanceof Integer) {
                                editor.putInt(prefKey, (Integer) e.getValue());
                            } else if (e.getValue() instanceof Float) {
                                editor.putFloat(prefKey, (Float) e.getValue());
                            } else if (e.getValue() instanceof Long) {
                                editor.putLong(prefKey, (Long) e.getValue());
                            } else if (e.getValue() instanceof Set) {
                                editor.putStringSet(prefKey, asSet((Set<?>) e.getValue(), String.class));
                            } else {
                                Log.w(TAG, "importConfig: SharedPref type " + e.getValue().getClass().getName() + " is unknown");
                            }
                            break;
                    }
                }
                editor.putString(Constants.PREF_BACKUP_REL_PATH_TO_ZIP, relPathToZip);
                editor.putString(Constants.PREF_BACKUP_PASSWORD, backupPassword);

                /**
                 * If all shared preferences have been added to the commit successfully,
                 * apply the commit.
                 */
                failSuccess = failSuccess && editor.commit();
            } else {
                Log.e(TAG, "importConfig: Invalid object stream");
            }
        } catch (IOException | ClassNotFoundException e) {
            Log.e(TAG, "importConfig: Failed to import SharedPreferences #1", e);
            failSuccess = false;
        } finally {
            try {
                if (objectInputStream != null) {
                    objectInputStream.close();
                }
                if (fileInputStream != null) {
                    fileInputStream.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "importConfig: Failed to import SharedPreferences #2", e);
            }
        }
        return failSuccess;
    }

    public static <T> Set<T> asSet(Set<?> c, Class<? extends T> type) {
        if (c == null) {
            return null;
        }
        Set<T> set = new HashSet<T>();
        for (Object o : c) {
            set.add(type.cast(o));
        }
        return set;
    }

    private void LogV(String logMessage) {
        if (ENABLE_VERBOSE_LOG) {
            Log.v(TAG, logMessage);
        }
    }
}
