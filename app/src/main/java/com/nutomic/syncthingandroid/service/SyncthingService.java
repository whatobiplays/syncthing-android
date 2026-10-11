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
import com.nutomic.syncthingandroid.onboarding.InstallationRecognition;
import com.nutomic.syncthingandroid.http.PollWebGuiAvailableTask;
import com.nutomic.syncthingandroid.model.Device;
import com.nutomic.syncthingandroid.model.Folder;
import com.nutomic.syncthingandroid.runtime.DefaultSyncthingRuntime;
import com.nutomic.syncthingandroid.runtime.ExecutionAdmissionException;
import com.nutomic.syncthingandroid.runtime.ExecutionIdentity;
import com.nutomic.syncthingandroid.runtime.ConfiguredFolderReference;
import com.nutomic.syncthingandroid.runtime.FolderEvent;
import com.nutomic.syncthingandroid.runtime.FolderOperationException;
import com.nutomic.syncthingandroid.runtime.FolderOperationFailure;
import com.nutomic.syncthingandroid.runtime.FolderScriptOutcome;
import com.nutomic.syncthingandroid.runtime.RunScriptRootAuthorization;
import com.nutomic.syncthingandroid.runtime.ExecutionOwnershipManager;
import com.nutomic.syncthingandroid.runtime.ExecutionRecoveryException;
import com.nutomic.syncthingandroid.runtime.HttpsCertificateState;
import com.nutomic.syncthingandroid.runtime.HttpsCertificateStorage;
import com.nutomic.syncthingandroid.runtime.LifecycleLaunchPermit;
import com.nutomic.syncthingandroid.runtime.ManagedStateException;
import com.nutomic.syncthingandroid.runtime.ManagedStateFailure;
import com.nutomic.syncthingandroid.runtime.ManagedStateLocations;
import com.nutomic.syncthingandroid.runtime.ManagedStateStaging;
import com.nutomic.syncthingandroid.runtime.OwnedExecutionShutdown;
import com.nutomic.syncthingandroid.runtime.RootFailure;
import com.nutomic.syncthingandroid.runtime.RootTransportException;
import com.nutomic.syncthingandroid.runtime.SyncthingCommand;
import com.nutomic.syncthingandroid.runtime.TuningOutcome;
import com.nutomic.syncthingandroid.util.ConfigRouter;
import com.nutomic.syncthingandroid.util.ConfigurationProjection;
import com.nutomic.syncthingandroid.util.ConfigXml;
import com.nutomic.syncthingandroid.util.PermissionUtil;
import com.nutomic.syncthingandroid.util.Util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.inject.Inject;

import net.lingala.zip4j.ZipFile;

/**
 * Holds the native syncthing instance and provides an API to access it.
 */
public class SyncthingService extends Service {

    private static final String TAG = "SyncthingService";
    private static final long STARTUP_CONFIGURATION_TIMEOUT_MILLIS = 90_000;

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

    /** Receives progress updates for an Execution Mode request. */
    public interface OnExecutionModeTransitionStatusChangedListener {
        void onExecutionModeTransitionStatusChanged(ExecutionModeTransitionStatus status);
    }

    /** Receives changes to automatic root-script authorization consent and suspension. */
    public interface OnRootRunScriptPromptStatusChangedListener {
        void onRootRunScriptPromptStatusChanged(RootRunScriptAuthorizationPolicy.Status status);
    }

    /** Receives the result of an explicit inotify tuning request. */
    public interface OnInotifyWatchLimitResultListener {
        void onComplete(boolean enabled, boolean succeeded, String detail);
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
    @Nullable private StartupConfiguration mStartupConfiguration;
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
    @Nullable
    private Runnable mAfterShutdownFailure;
    @Nullable
    private ActionRestartContinuation mActionRestartContinuation;
    @Nullable
    private ActionResetDeltasContinuation mActionResetDeltasContinuation;
    @Nullable private FileMutationBarrier mFileMutationBarrier;
    @Nullable private CertificateVerificationStopHandler mCertificateVerificationStopHandler;
    @Nullable private LifecycleLaunchPermit mStartupLaunchPermit;
    private final StartingShutdownDeferral mStartingShutdownDeferral =
            new StartingShutdownDeferral();
    private final ShutdownStartIntent mShutdownStartIntent = new ShutdownStartIntent();
    private final CertificateRestoreDeferredStart mCertificateRestoreDeferredStart =
            new CertificateRestoreDeferredStart();
    private boolean mCertificateRestoreWaitRegistered;
    @Nullable private Boolean mCertificateRestoreSafeToStart;
    private final PostMutationStartupGate mPostMutationStartupGate =
            new PostMutationStartupGate();
    private final DatabaseResetOwnership mDatabaseResetOwnership =
            new DatabaseResetOwnership();
    /** Runs one certificate-storage operation and holds at most one serialized continuation. */
    private final ThreadPoolExecutor mCertificateStorageExecutor =
            CertificateStorageMutation.newWorkerExecutor();

    private volatile boolean mShutdownInProgress;
    private boolean mShutdownExitProven;
    private boolean mShutdownSafetyEstablished;
    private boolean mShutdownWorkerStarted;
    private boolean mShutdownRecoveryCheckStarted;
    private boolean mLastExecutionExitProven;
    private volatile boolean mDestroying;
    private boolean mDestructionShutdownCompletionPending;
    private ModeTransitionRequest mDestructionAbandonedTransition;
    private volatile long mLifecycleGeneration;
    @Nullable private volatile RootRunScriptAuthorizationPolicy mRootRunScriptPromptPolicy;
    private final AtomicBoolean mRootRunScriptWorkerActive = new AtomicBoolean();
    /** Counts accepted preference workers, including an OFF write that supersedes an opt-in. */
    private final AtomicInteger mRootScriptConsentMutationsInFlight = new AtomicInteger();
    private volatile boolean mRootScriptOptOutPending;
    private volatile long mRootScriptConsentGeneration;
    @Nullable private volatile Thread mRootRunScriptWorker;
    /** Serializes automatic script acceptance with transition, shutdown, and helper admission. */
    private final Object mRootScriptLifecycleLock = new Object();
    private long mRootScriptLifecycleEpoch;
    private boolean mRootScriptShutdownClosed;
    private boolean mRootRunScriptExecuting;
    /** Narrow test seam for pausing one automatic script or consent worker before it starts. */
    @FunctionalInterface
    interface RootScriptWorkerStarter {
        void start(Thread worker);
    }
    private RootScriptWorkerStarter mRootScriptWorkerStarter = Thread::start;

    void setRootScriptWorkerStarterForTest(RootScriptWorkerStarter starter) {
        mRootScriptWorkerStarter = starter;
    }
    private final HashSet<OnRootRunScriptPromptStatusChangedListener>
            mRootRunScriptPromptStatusListeners = new HashSet<>();
    private boolean mCertificateMutationPending;
    @Nullable private volatile FileMutationBarrier.ExecutionModeTransitionReservation
            mExecutionModeTransitionReservation;
    @Nullable private volatile ModeTransitionRequest mModeTransitionRequest;
    private ExecutionModeTransitionStatus mModeTransitionStatus =
            ExecutionModeTransitionStatus.idle(false);
    private boolean mStateAccessRepairRequired;
    private boolean mRootStartupAuthorizationAttempted;
    private volatile boolean mRootStartupAuthorizationPending;
    private boolean mRootStartupAuthorizationComplete;
    private boolean mRootStartupStatusPending;
    private long mRootStartupRequestId;
    private boolean mStartupConfigLoadPending;
    private long mStartupConfigLoadRequestId;
    private long mStartupRequestId;
    private boolean mStartupConfigLoadTimedOut;
    @Nullable private Runnable mStartupConfigLoadTimeout;
    private boolean mStoppedCustomRunConditionsPending;
    private boolean mStoppedCustomRunConditionsReapplyRequested;
    private long mStoppedCustomRunConditionsRequestId;
    private boolean mExecutionModeReconciliationPending;
    private boolean mExecutionModeReconciliationRetryRequired;
    private boolean mExecutionModeReconciliationWaitRegistered;
    private volatile boolean mRuntimeReconciliationInFlight;
    private volatile boolean mRuntimeReconciliationShutdownSafe;
    private final Object mExecutionModeReconciliationLock = new Object();
    @Nullable private SharedPreferences.OnSharedPreferenceChangeListener
            mExecutionModePreferenceListener;
    private boolean mStartupStateAccessProbePending;
    private boolean mStartupStateAccessProbeComplete;
    private long mStartupStateAccessProbeGeneration;
    private final HashSet<OnExecutionModeTransitionStatusChangedListener>
            mModeTransitionStatusListeners = new HashSet<>();
    @Nullable private ExecutionModeTransitionTestSeam mExecutionModeTransitionTestSeam;
    @Nullable private AtomicBoolean mCertificateMutationCancellation;
    @Nullable private FileMutationBarrier.CertificateRestoreReservation
            mCertificateStorageReservation;
    @Nullable private CertificateDestructionRestore mCertificateDestructionRestore;
    private boolean mStopAfterDeltaResetWhenNotRequired;
    private SyncthingCommand mStartupCommand;

    private interface CertificateVerificationStopHandler {
        void onExplicitStop(boolean crashedNativeStop);
        void onServiceDestroy();
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

    /** Installs deterministic side effects for JVM tests of the real transition coordinator. */
    void setExecutionModeTransitionTestSeam(ExecutionModeTransitionTestSeam seam) {
        mExecutionModeTransitionTestSeam = seam;
    }

    /** Seeds only lifecycle facts that Android supplies before a service transition begins. */
    void setExecutionModeTransitionTestState(
            boolean shouldRun,
            boolean stateAccessRepairRequired,
            State currentState
    ) {
        mLastDeterminedShouldRun = shouldRun;
        mStateAccessRepairRequired = stateAccessRepairRequired;
        mCurrentState = currentState;
    }

    /** Runs the production durable-mode reconciliation path in a JVM test. */
    void reconcileRuntimeExecutionModeForTest() {
        reconcileRuntimeExecutionModeFromPreferences();
    }

    /** Starts one root startup configuration attempt through the normal service load path. */
    void startRootStartupConfigurationLoadForTest() {
        mLastDeterminedShouldRun = true;
        mRootStartupStatusPending = true;
        loadStartupConfiguration(SyncthingCommand.SERVE, ++mStartupRequestId);
    }

    /** Starts the normal root-selected authorization path from a deterministic JVM test. */
    void startRootSelectedStartupForTest() {
        beginRootSelectedStartup(SyncthingCommand.SERVE, false);
    }

    /** Applies the same lifecycle invalidation and shutdown result handling used by onDestroy. */
    void destroyExecutionModeLifecycleForTest() {
        ModeTransitionRequest abandoned = markServiceDestroying();
        ExecutionModeTransitionTestSeam seam = mExecutionModeTransitionTestSeam;
        if (seam == null) throw new IllegalStateException("Transition test seam is not installed");
        seam.shutdown(safe -> onServiceDestructionShutdownComplete(abandoned, safe));
    }

    ExecutionModeTransitionStatus executionModeTransitionStatusForTest() {
        return mModeTransitionStatus;
    }

    /** Delivers a terminal state callback through the production readiness/status handler. */
    void onServiceStateChangeForTest(State newState) {
        onServiceStateChange(newState);
    }

    private boolean postTransitionCallback(Runnable callback) {
        ExecutionModeTransitionTestSeam seam = mExecutionModeTransitionTestSeam;
        if (seam != null) return seam.post(callback);
        Handler handler = mHandler;
        return handler != null && handler.post(callback);
    }

    private boolean executeTransitionWorker(String name, Runnable work) {
        ExecutionModeTransitionTestSeam seam = mExecutionModeTransitionTestSeam;
        if (seam != null) return seam.executeWorker(name, work);
        Thread worker = new Thread(work, name);
        worker.setDaemon(true);
        try {
            worker.start();
            return true;
        } catch (RuntimeException error) {
            Log.e(TAG, "Could not start " + name, error);
            return false;
        }
    }

    private boolean durableExecutionMode() {
        ExecutionModeTransitionTestSeam seam = mExecutionModeTransitionTestSeam;
        return seam != null
                ? seam.durableMode()
                : mPreferences.getBoolean(Constants.PREF_USE_ROOT, false);
    }

    private boolean runtimeExecutionMode() {
        ExecutionModeTransitionTestSeam seam = mExecutionModeTransitionTestSeam;
        return seam != null ? seam.runtimeMode() : mRuntime.isSuperuserModeSelected();
    }

    private boolean closeBackendOperationAdmission(long timeoutMillis)
            throws InterruptedException {
        ExecutionModeTransitionTestSeam seam = mExecutionModeTransitionTestSeam;
        return seam != null
                ? seam.closeBackendAdmission(timeoutMillis)
                : mRuntime.closeBackendOperationAdmission(timeoutMillis);
    }

    private void reopenBackendOperationAdmissionWhenDrained() {
        ExecutionModeTransitionTestSeam seam = mExecutionModeTransitionTestSeam;
        if (seam != null) seam.reopenBackendAdmissionWhenDrained();
        else mRuntime.reopenBackendOperationAdmissionWhenDrained();
    }

    private void openBackendOperationAdmission() {
        ExecutionModeTransitionTestSeam seam = mExecutionModeTransitionTestSeam;
        if (seam != null) seam.openBackendAdmission();
        else mRuntime.openBackendOperationAdmission();
    }

    private void selectRuntimeExecutionMode(boolean useRoot) {
        ExecutionModeTransitionTestSeam seam = mExecutionModeTransitionTestSeam;
        if (seam != null) seam.selectMode(useRoot);
        else mRuntime.selectExecutionMode(useRoot);
    }

    private void launchModeTransitionStartup(SyncthingCommand command) {
        ExecutionModeTransitionTestSeam seam = mExecutionModeTransitionTestSeam;
        if (seam != null) seam.launchStartup(command);
        else launchStartupTask(command);
    }

    /**
     * Starts the native binary.
     */
    @Override
    public void onCreate() {
        super.onCreate();
        ((SyncthingApp) getApplication()).component().inject(this);
        mRootRunScriptPromptPolicy = new RootRunScriptAuthorizationPolicy(
                new RootRunScriptAuthorizationPolicy.Store() {
                    @Override public String read() {
                        return mPreferences.getString(
                                Constants.PREF_ROOT_RUN_SCRIPT_PROMPT_POLICY, "OFF");
                    }
                    @Override public Object identity() { return mPreferences; }
                    @Override public boolean commit(String value) {
                        return mPreferences.edit().putString(
                                Constants.PREF_ROOT_RUN_SCRIPT_PROMPT_POLICY, value).commit();
                    }
                }
        );
        ENABLE_VERBOSE_LOG = AppPrefs.getPrefVerboseLog(mPreferences);
        LogV("onCreate");
        mConfigRouter = new ConfigRouter(SyncthingService.this);
        mHandler = new Handler();
        registerExecutionModePreferenceListener();

        /**
         * If runtime permissions are revoked, android kills and restarts the service.
         * We need to recheck if we still have the storage permission.
         */
        mStoragePermissionGranted = PermissionUtil.haveStoragePermission(this);

        if (mNotificationHandler != null) {
            mNotificationHandler.setAppShutdownInProgress(false);
        }
        reconcileRuntimeExecutionModeFromPreferences();
        reapplyInotifyLimitIfAuthorized();
    }

    private void registerExecutionModePreferenceListener() {
        mExecutionModePreferenceListener = (preferences, key) -> {
            if (!Constants.PREF_USE_ROOT.equals(key)) return;
            postTransitionCallback(this::reconcileRuntimeExecutionModeFromPreferences);
        };
        mPreferences.registerOnSharedPreferenceChangeListener(
                mExecutionModePreferenceListener
        );
    }

    /** Registers the production preference callback against deterministic JVM test preferences. */
    void registerExecutionModePreferenceListenerForTest(SharedPreferences preferences) {
        mPreferences = Objects.requireNonNull(preferences);
        registerExecutionModePreferenceListener();
    }

    /** Reconciles the app-singleton backend with the sole durable mode preference. */
    private void reconcileRuntimeExecutionModeFromPreferences() {
        if (mDestroying || (mExecutionModeTransitionTestSeam == null
                && (mRuntime == null || mPreferences == null))) return;
        boolean committedMode = durableExecutionMode();
        if (FileMutationBarrier.executionModeTransitionPending()) {
            mExecutionModeReconciliationPending = true;
            if (!mExecutionModeReconciliationWaitRegistered) {
                mExecutionModeReconciliationWaitRegistered = true;
                long lifecycleGeneration = mLifecycleGeneration;
                onModeTransitionStatusChanged(new ExecutionModeTransitionStatus(
                        ExecutionModeTransitionStatus.Phase.COMMITTING,
                        ExecutionModeTransitionStatus.FailureKind.NONE,
                        null,
                        null,
                        "Waiting for the previous service's transition work to finish before runtime reconciliation.",
                        committedMode,
                        false,
                        false,
                        mStateAccessRepairRequired
                ));
                FileMutationBarrier.whenExecutionModeTransitionReleased(() -> {
                    postTransitionCallback(() -> {
                        if (mDestroying || lifecycleGeneration != mLifecycleGeneration) return;
                        mExecutionModeReconciliationWaitRegistered = false;
                        mExecutionModeReconciliationPending = false;
                        reconcileRuntimeExecutionModeFromPreferences();
                    });
                });
            }
            return;
        }
        if (mExecutionModeReconciliationRetryRequired
                || mRuntimeReconciliationInFlight
                || mExecutionModeReconciliationPending) return;

        if (runtimeExecutionMode() == committedMode) {
            if (!committedMode && mExecutionModeTransitionTestSeam == null
                    && !mStartupStateAccessProbeComplete) {
                ensureStartupStateAccessProbe();
                if (mStartupStateAccessProbePending || !mStartupStateAccessProbeComplete) {
                    return;
                }
            }
            if (!committedMode && mStateAccessRepairRequired) {
                onModeTransitionStatusChanged(
                        ExecutionModeTransitionStatus.idle(true)
                );
                return;
            }
            try {
                if (committedMode || !mStateAccessRepairRequired) {
                    openBackendOperationAdmission();
                }
            } catch (RuntimeException error) {
                Log.e(TAG, "Could not reopen the durably selected runtime", error);
                mExecutionModeReconciliationPending = true;
                mExecutionModeReconciliationRetryRequired = true;
                onModeTransitionStatusChanged(new ExecutionModeTransitionStatus(
                        ExecutionModeTransitionStatus.Phase.QUIESCING,
                        ExecutionModeTransitionStatus.FailureKind.STARTUP,
                        null,
                        null,
                        "The selected backend could not reopen safely. Retry reconciliation.",
                        committedMode,
                        true,
                        false,
                        mStateAccessRepairRequired
                ));
                return;
            }
            mExecutionModeReconciliationPending = false;
            if (mLastDeterminedShouldRun && mCurrentState == State.DISABLED
                    && (committedMode || !mStateAccessRepairRequired)) {
                launchModeTransitionStartup(SyncthingCommand.SERVE);
            }
            return;
        }

        FileMutationBarrier.ExecutionModeTransitionReservation reservation =
                FileMutationBarrier.tryReserveExecutionModeTransition();
        if (reservation == null) {
            try {
                closeBackendOperationAdmission(0);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            mExecutionModeReconciliationPending = true;
            mExecutionModeReconciliationRetryRequired = true;
            onModeTransitionStatusChanged(new ExecutionModeTransitionStatus(
                    ExecutionModeTransitionStatus.Phase.FAILED,
                    ExecutionModeTransitionStatus.FailureKind.BUSY,
                    null,
                    null,
                    "Another lifecycle or Managed State owner prevents safe runtime reconciliation.",
                    committedMode,
                    true,
                    false,
                    mStateAccessRepairRequired
            ));
            return;
        }
        mExecutionModeTransitionReservation = reservation;

        mExecutionModeReconciliationPending = true;
        mRuntimeReconciliationInFlight = true;
        long lifecycleGeneration = mLifecycleGeneration;
        onModeTransitionStatusChanged(new ExecutionModeTransitionStatus(
                ExecutionModeTransitionStatus.Phase.COMMITTING,
                ExecutionModeTransitionStatus.FailureKind.NONE,
                null,
                null,
                "Reconciling the runtime with the durably committed Execution Mode.",
                committedMode,
                false,
                false,
                mStateAccessRepairRequired
        ));
        boolean workerStarted = executeTransitionWorker("Execution Mode runtime reconciliation", () -> {
            boolean reconciled = false;
            try {
                if (!closeBackendOperationAdmission(5_000)) {
                    throw new IllegalStateException(
                            "Backend operations did not drain before runtime reconciliation"
                    );
                }
                boolean latestCommittedMode = durableExecutionMode();
                synchronized (mExecutionModeReconciliationLock) {
                    if (mDestroying || lifecycleGeneration != mLifecycleGeneration) {
                        throw new IllegalStateException(
                                "Runtime reconciliation was cancelled by service destruction"
                        );
                    }
                    selectRuntimeExecutionMode(latestCommittedMode);
                    if (runtimeExecutionMode() != latestCommittedMode) {
                        throw new IllegalStateException(
                                "The selected runtime does not match the durable mode preference"
                        );
                    }
                    if (latestCommittedMode) openBackendOperationAdmission();
                }
                reconciled = true;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                Log.w(TAG, "Runtime mode reconciliation was interrupted", interrupted);
            } catch (RuntimeException failure) {
                Log.e(TAG, "Runtime could not be reconciled with the durable execution mode", failure);
            }
            boolean result = reconciled;
            mRuntimeReconciliationInFlight = false;
            if (mDestroying && mRuntimeReconciliationShutdownSafe) {
                releaseModeTransitionReservation();
            }
            if (!postTransitionCallback(() -> {
                if (mDestroying || lifecycleGeneration != mLifecycleGeneration) return;
                boolean currentCommittedMode = durableExecutionMode();
                if (result && runtimeExecutionMode() == currentCommittedMode) {
                    mExecutionModeReconciliationPending = false;
                    mExecutionModeReconciliationRetryRequired = false;
                    releaseModeTransitionReservation();
                    invalidateStartupStateAccessProbe();
                    if (currentCommittedMode) {
                        mRootStartupAuthorizationComplete = false;
                        mRootStartupAuthorizationAttempted = false;
                    } else {
                        ensureStartupStateAccessProbe();
                    }
                    onModeTransitionStatusChanged(ExecutionModeTransitionStatus.idle(
                            mStateAccessRepairRequired
                    ));
                    if (mLastDeterminedShouldRun && mCurrentState == State.DISABLED
                            && (currentCommittedMode || !mStateAccessRepairRequired)
                            && !mStartupStateAccessProbePending) {
                        launchModeTransitionStartup(SyncthingCommand.SERVE);
                    }
                } else {
                    mExecutionModeReconciliationPending = true;
                    mExecutionModeReconciliationRetryRequired = true;
                    releaseModeTransitionReservation();
                    onModeTransitionStatusChanged(new ExecutionModeTransitionStatus(
                            ExecutionModeTransitionStatus.Phase.FAILED,
                            ExecutionModeTransitionStatus.FailureKind.STARTUP,
                            null,
                            null,
                            "The committed execution backend could not be selected safely. Retry reconciliation.",
                            currentCommittedMode,
                            true,
                            false,
                            mStateAccessRepairRequired
                    ));
                }
            })) {
                Log.e(TAG, "Runtime reconciliation result could not return to the service thread");
            }
        });
        if (!workerStarted) {
            mRuntimeReconciliationInFlight = false;
            mExecutionModeReconciliationPending = true;
            mExecutionModeReconciliationRetryRequired = true;
            releaseModeTransitionReservation();
            onModeTransitionStatusChanged(new ExecutionModeTransitionStatus(
                    ExecutionModeTransitionStatus.Phase.FAILED,
                    ExecutionModeTransitionStatus.FailureKind.STARTUP,
                    null,
                    null,
                    "The runtime reconciliation worker could not be started. Retry reconciliation.",
                    committedMode,
                    true,
                    false,
                    mStateAccessRepairRequired
            ));
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
        ensureStartupStateAccessProbe();

        if (intent == null) {
            return START_STICKY;
        }

        if (ACTION_RESTART.equals(intent.getAction()) && mCurrentState == State.ACTIVE) {
            ActionRestartContinuation restart = new ActionRestartContinuation(() -> {
                if (mLastDeterminedShouldRun) {
                    launchStartupTask(SyncthingCommand.SERVE);
                } else {
                    onServiceStateChange(State.DISABLED);
                }
            });
            mActionRestartContinuation = restart;
            shutdown(State.INIT, () -> {
                if (mActionRestartContinuation == restart) {
                    mActionRestartContinuation = null;
                }
                restart.complete();
            });
        } else if (ACTION_STOP.equals(intent.getAction())) {
            boolean crashedNativeStop =
                    intent.getBooleanExtra(EXTRA_STOP_AFTER_CRASHED_NATIVE, false);
            revokeStartupLaunchPermit();
            mDatabaseResetOwnership.suppressAutomaticStartup();
            boolean restartContinuationCancelled = false;
            if (mActionRestartContinuation != null) {
                restartContinuationCancelled = mActionRestartContinuation.cancel();
                mActionRestartContinuation = null;
            }
            if (!crashedNativeStop) {
                cancelActionResetDeltasContinuation();
                if (mCertificateRestoreDeferredStart.cancelForExplicitStop()
                        == SyncthingCommand.RESET_DELTAS) {
                    mStopAfterDeltaResetWhenNotRequired = false;
                }
            }
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
                SyncthingStopPolicy.stopForNormalAction(
                        mCurrentState, mOwnedExecution != null, restartContinuationCancelled,
                        () -> shutdown(State.DISABLED)
                );
            }
        } else if (ACTION_RESET_DATABASE.equals(intent.getAction())) {
            // Reject conflicts before reserving or starting any reset work.
            boolean accepted = SyncthingResetPolicy.runExternalResetIfUnowned(
                    mFileMutationBarrier != null,
                    mPostMutationStartupGate.ownsStartup(),
                    mDatabaseResetOwnership.isReserved(),
                    mShutdownInProgress,
                    () -> {
                        Log.i(TAG, "Invoking reset of database");
                        requestResetDatabase(SyncthingResetPolicy.relaunchAfterReset(
                                () -> mLastDeterminedShouldRun,
                                () -> launchStartupTask(SyncthingCommand.SERVE)
                        ));
                    }
            );
            if (!accepted) {
                Log.w(TAG, "Ignoring external database reset while another lifecycle operation owns admission");
            }
        } else if (ACTION_RESET_DELTAS.equals(intent.getAction())) {
            // Check ownership before setting the delta-reset follow-up state or launching work.
            boolean accepted = SyncthingResetPolicy.runExternalDeltaResetIfUnowned(
                    mFileMutationBarrier != null,
                    mPostMutationStartupGate.ownsStartup(),
                    mDatabaseResetOwnership.isReserved(),
                    mShutdownInProgress,
                    mActionResetDeltasContinuation != null,
                    () -> {
                        Log.i(TAG, "Invoking reset of delta indexes");
                        mStopAfterDeltaResetWhenNotRequired = true;
                        ActionResetDeltasContinuation deltaReset =
                                new ActionResetDeltasContinuation(
                                        () -> launchStartupTask(SyncthingCommand.RESET_DELTAS),
                                        () -> mStopAfterDeltaResetWhenNotRequired = false
                                );
                        mActionResetDeltasContinuation = deltaReset;
                        Runnable completeDeltaReset = () -> {
                            if (mActionResetDeltasContinuation == deltaReset) {
                                mActionResetDeltasContinuation = null;
                            }
                            deltaReset.complete();
                        };
                        if (mCurrentState != State.DISABLED || mSyncthingRunnable != null) {
                            shutdown(State.DISABLED, completeDeltaReset);
                        } else {
                            completeDeltaReset.run();
                        }
                    }
            );
            if (!accepted) {
                Log.w(TAG, "Ignoring external delta reset while another lifecycle operation owns admission");
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
        // A stopped service has no reason to read or migrate authoritative configuration. An
        // eligible launch performs that work on StartupConfigurationLoad's bounded worker.
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
                boolean lifecycleBlocksStartup = mStartingShutdownDeferral
                        .blocksStartup(mShutdownInProgress)
                        || mModeTransitionRequest != null
                        || mExecutionModeTransitionReservation != null
                        || mStartupStateAccessProbePending
                        || (mStateAccessRepairRequired && !runtimeExecutionMode())
                        || mFileMutationBarrier != null
                        || FileMutationBarrier.certificateRestorePending()
                        || mCertificateRestoreDeferredStart.isPending()
                        || mPostMutationStartupGate.ownsStartup()
                        || !mDatabaseResetOwnership.canStartLifecycle()
                        || ((mCurrentState == State.DISABLED || mCurrentState == State.INIT)
                        && (mOwnedExecution != null
                        || mSyncthingRunnable != null
                        || mSyncthingRunnableThread != null));
                mShutdownStartIntent.onRunConditionChanged(true, lifecycleBlocksStartup);
                if (mModeTransitionRequest != null
                        || mExecutionModeTransitionReservation != null
                        || FileMutationBarrier.executionModeTransitionPending()) {
                    return;
                }
                if (lifecycleBlocksStartup) {
                    if (FileMutationBarrier.certificateRestorePending()) {
                        deferStartupUntilCertificateRestore(SyncthingCommand.SERVE);
                    }
                    if (!mShutdownInProgress && !mStartingShutdownDeferral.isPending()
                            && mFileMutationBarrier == null
                            && !FileMutationBarrier.certificateRestorePending()
                            && !mCertificateRestoreDeferredStart.isPending()
                            && !mPostMutationStartupGate.ownsStartup()
                            && mDatabaseResetOwnership.canStartLifecycle()) {
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
                mCertificateRestoreDeferredStart.onRunConditionChanged(false);
                if (mModeTransitionRequest == null) {
                    invalidateRootStartupForStoppedRunConditions();
                }
                if (mExecutionModeTransitionReservation != null
                        || FileMutationBarrier.executionModeTransitionPending()) {
                    return;
                }
                revokeStartupLaunchPermit();
                // Stop syncthing.
                if (mCurrentState == State.DISABLED) {
                    return;
                }
                shutdown(State.DISABLED);
            }
        }
    }

    /** Invalidates a stopped launch without turning a prior denial into automatic consent. */
    private void invalidateRootStartupForStoppedRunConditions() {
        ++mStartupRequestId;
        mRootStartupAuthorizationPending = false;
        // A previously authorized launch may be restarted by a new eligible Run Condition
        // cycle. A denied authorization still requires the explicit Retry action.
        if (mRootStartupAuthorizationComplete) mRootStartupAuthorizationAttempted = false;
        mRootStartupAuthorizationComplete = false;
        if (mRootStartupStatusPending) {
            mRootStartupStatusPending = false;
            onModeTransitionStatusChanged(ExecutionModeTransitionStatus.idle(
                    mStateAccessRepairRequired
            ));
        }
    }

    /** Retries a blocked startup after a process-wide certificate restore reaches a safe outcome. */
    private void deferStartupUntilCertificateRestore(SyncthingCommand command) {
        mCertificateRestoreDeferredStart.defer(command);
        mShutdownStartIntent.onRunConditionChanged(true, true);
        if (mCertificateRestoreWaitRegistered) return;
        mCertificateRestoreWaitRegistered = true;
        FileMutationBarrier.whenCertificateRestoreReleased(safeToStart -> {
            if (!mHandler.post(() -> {
                mCertificateRestoreSafeToStart = safeToStart;
                resumeStartupAfterCertificateRestore(safeToStart);
            })) {
                Log.e(TAG, "Could not resume startup after certificate restoration");
            }
        });
    }

    private void resumeStartupAfterCertificateRestore(boolean safeToStart) {
        mCertificateRestoreWaitRegistered = false;
        mCertificateRestoreSafeToStart = safeToStart;
        SyncthingCommand command = mCertificateRestoreDeferredStart.peek();
        if (command == null) return;
        if (mDestroying) {
            mCertificateRestoreDeferredStart.take();
            return;
        }
        if (!safeToStart) {
            mCertificateRestoreDeferredStart.take();
            mShutdownStartIntent.clear();
            return;
        }
        if (mShutdownInProgress) {
            shutdown(
                    State.DISABLED,
                    () -> resumeStartupAfterCertificateRestore(safeToStart),
                    true,
                    false
            );
            return;
        }

        boolean serviceCanStart = (mCurrentState == State.DISABLED || mCurrentState == State.INIT)
                && !mShutdownInProgress
                && mExecutionModeTransitionReservation == null
                && !FileMutationBarrier.executionModeTransitionPending()
                && !mCertificateMutationPending
                && mFileMutationBarrier == null
                && !mPostMutationStartupGate.ownsStartup()
                && mOwnedExecution == null
                && mSyncthingRunnable == null
                && mSyncthingRunnableThread == null
                && mDatabaseResetOwnership.canStartLifecycle();
        if (!CertificateRestoreDeferredStart.canResume(
                command, safeToStart, mLastDeterminedShouldRun, serviceCanStart
        )) {
            if (mDatabaseResetOwnership.isReserved()) return;
            if (command == SyncthingCommand.SERVE
                    && mCurrentState != State.DISABLED && mCurrentState != State.INIT) {
                mCertificateRestoreDeferredStart.take();
            }
            return;
        }
        command = mCertificateRestoreDeferredStart.take();
        if (command == SyncthingCommand.RESET_DELTAS) {
            launchStartupTask(command);
            return;
        }
        if (mPostMutationStartupGate.consumeDeferredStart(
                mShutdownStartIntent,
                mLastDeterminedShouldRun,
                true,
                true,
                serviceCanStart,
                false
        )) {
            launchStartupTask(command);
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

        if (mRuntime.isSuperuserModeSelected()
                || mPreferences.getBoolean(Constants.PREF_USE_ROOT, false)
                || mModeTransitionRequest != null
                || mExecutionModeTransitionReservation != null
                || FileMutationBarrier.executionModeTransitionPending()
                || mStateAccessRepairRequired) {
            // The saved per-object preferences remain the pending intent. A passive callback must
            // not read or write privileged config.xml; an active REST session reapplies them.
            Log.d(TAG, "Deferring custom Run Condition updates until authoritative REST config is active");
            return;
        }

        applyStoppedCustomRunConditions(runConditionMonitor);
    }

    /** Applies pending Run Condition changes to stopped Normal Mode config off the service thread. */
    private void applyStoppedCustomRunConditions(RunConditionMonitor runConditionMonitor) {
        if (mHandler == null || mDestroying) return;
        if (mStoppedCustomRunConditionsPending) {
            mStoppedCustomRunConditionsReapplyRequested = true;
            return;
        }
        FileMutationBarrier.StoppedStateMutationReservation mutation =
                FileMutationBarrier.tryReserveStoppedStateMutation();
        if (mutation == null) return;

        mStoppedCustomRunConditionsPending = true;
        long requestId = ++mStoppedCustomRunConditionsRequestId;
        long lifecycleGeneration = mLifecycleGeneration;
        Thread worker = new Thread(() -> {
            Throwable failure = null;
            try {
                ConfigXml configXml = new ConfigXml(this);
                configXml.loadConfig();
                Map<String, Boolean> requestedFolderPauses = new LinkedHashMap<>();
                Map<String, Boolean> requestedDevicePauses = new LinkedHashMap<>();
                boolean changed = false;

                List<Folder> folders = configXml.getFolders();
                if (folders == null) throw new IllegalStateException("Folder configuration is unavailable");
                for (Folder folder : folders) {
                    String key = Constants.PREF_OBJECT_PREFIX_FOLDER + folder.id;
                    if (!mPreferences.getBoolean(
                            Constants.DYN_PREF_OBJECT_CUSTOM_SYNC_CONDITIONS(key), false
                    )) continue;
                    boolean paused = !runConditionMonitor.checkObjectSyncConditions(key);
                    requestedFolderPauses.put(folder.id, paused);
                    if (folder.paused != paused) {
                        configXml.setFolderPause(folder.id, paused);
                        changed = true;
                    }
                }

                List<Device> devices = configXml.getDevices(false);
                if (devices == null) throw new IllegalStateException("Device configuration is unavailable");
                for (Device device : devices) {
                    String key = Constants.PREF_OBJECT_PREFIX_DEVICE + device.deviceID;
                    if (!mPreferences.getBoolean(
                            Constants.DYN_PREF_OBJECT_CUSTOM_SYNC_CONDITIONS(key), false
                    )) continue;
                    boolean paused = !runConditionMonitor.checkObjectSyncConditions(key);
                    requestedDevicePauses.put(device.deviceID, paused);
                    if (device.paused != paused) {
                        configXml.setDevicePause(device.deviceID, paused);
                        changed = true;
                    }
                }

                if (changed) {
                    if (mDestroying || lifecycleGeneration != mLifecycleGeneration
                            || requestId != mStoppedCustomRunConditionsRequestId) return;
                    if (!configXml.saveChanges()) {
                        throw new IllegalStateException("Stopped Run Condition config write failed");
                    }
                    ConfigXml authoritative = new ConfigXml(this);
                    authoritative.loadConfig();
                    Set<String> verifiedFolders = new HashSet<>();
                    for (Folder folder : authoritative.getFolders()) {
                        Boolean expected = requestedFolderPauses.get(folder.id);
                        if (expected != null) {
                            if (expected != folder.paused) {
                                throw new IllegalStateException(
                                        "Folder Run Condition read-back mismatch"
                                );
                            }
                            verifiedFolders.add(folder.id);
                        }
                    }
                    if (!verifiedFolders.equals(requestedFolderPauses.keySet())) {
                        throw new IllegalStateException(
                                "Folder Run Condition read-back omitted requested objects"
                        );
                    }
                    Set<String> verifiedDevices = new HashSet<>();
                    for (Device device : authoritative.getDevices(false)) {
                        Boolean expected = requestedDevicePauses.get(device.deviceID);
                        if (expected != null) {
                            if (expected != device.paused) {
                                throw new IllegalStateException(
                                        "Device Run Condition read-back mismatch"
                                );
                            }
                            verifiedDevices.add(device.deviceID);
                        }
                    }
                    if (!verifiedDevices.equals(requestedDevicePauses.keySet())) {
                        throw new IllegalStateException(
                                "Device Run Condition read-back omitted requested objects"
                        );
                    }
                }
            } catch (Exception error) {
                failure = error;
            } finally {
                mutation.release();
            }

            Throwable result = failure;
            if (mHandler == null || !mHandler.post(() -> {
                if (requestId != mStoppedCustomRunConditionsRequestId) return;
                mStoppedCustomRunConditionsPending = false;
                if (mDestroying || lifecycleGeneration != mLifecycleGeneration) return;
                if (result != null) {
                    Log.w(TAG, "Stopped custom Run Condition changes remain pending", result);
                } else {
                    Log.d(TAG, "Stopped custom Run Condition changes match authoritative config");
                }
                if (mStoppedCustomRunConditionsReapplyRequested) {
                    mStoppedCustomRunConditionsReapplyRequested = false;
                    applyCustomRunConditions(runConditionMonitor);
                }
            })) {
                Log.e(TAG, "Stopped Run Condition result could not return to the service thread");
            }
        }, "Stopped Run Condition config update");
        worker.setDaemon(true);
        try {
            worker.start();
        } catch (RuntimeException error) {
            mutation.release();
            mStoppedCustomRunConditionsPending = false;
            Log.e(TAG, "Could not start stopped Run Condition config worker", error);
        }
    }

    private enum ModeTransitionPurpose {
        CHANGE_MODE,
        REPAIR_STATE
    }

    /** Request data retained only while the service owns one mode-transition admission lease. */
    private static final class ModeTransitionRequest {
        private final boolean targetUseRoot;
        private final ModeTransitionPurpose purpose;
        private boolean disableAnywayConfirmed;
        private boolean gatesClosed;
        private boolean shutdownProven;
        private boolean stateRepairSucceeded;
        private boolean rootAuthorizationSucceeded;
        private boolean commitStarted;
        private boolean cancelled;
        @Nullable private RestApi transitionRestApi;
        private boolean shutdownStarted;
        private int backgroundOperationsInFlight;
        private boolean destructionShutdownSafe;

        private ModeTransitionRequest(boolean targetUseRoot, ModeTransitionPurpose purpose) {
            this.targetUseRoot = targetUseRoot;
            this.purpose = purpose;
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
        if (mDestroying || mExecutionModeReconciliationPending) return;
        if (durableExecutionMode() != runtimeExecutionMode()) {
            reconcileRuntimeExecutionModeFromPreferences();
            return;
        }
        if (mModeTransitionRequest != null || mExecutionModeTransitionReservation != null
                || FileMutationBarrier.executionModeTransitionPending()) {
            Log.d(TAG, "launchStartupTask deferred while Execution Mode transition owns admission");
            return;
        }
        ensureStartupStateAccessProbe();
        if (!runtimeExecutionMode()
                && (mStartupStateAccessProbePending || mStateAccessRepairRequired)) {
            Log.d(TAG, "launchStartupTask deferred until Managed State access is available");
            return;
        }
        if (runtimeExecutionMode() && !mRootStartupAuthorizationComplete) {
            beginRootSelectedStartup(command, false);
            return;
        }
        synchronized (mStateLock) {
            if (mCurrentState != State.DISABLED && mCurrentState != State.INIT) {
                Log.e(TAG, "launchStartupTask: Wrong state " + mCurrentState + " detected. Cancelling.");
                return;
            }
        }

        if (mCertificateMutationPending) {
            Log.d(TAG, "launchStartupTask deferred until certificate storage work completes");
            return;
        }
        if (FileMutationBarrier.certificateRestorePending()) {
            Log.d(TAG, "launchStartupTask deferred until certificate restoration completes");
            deferStartupUntilCertificateRestore(command);
            return;
        }
        if (mFileMutationBarrier != null || mPostMutationStartupGate.ownsStartup()
                || !mDatabaseResetOwnership.canStartLifecycle()) {
            Log.d(TAG, "launchStartupTask deferred until stopped-state ownership is released");
            return;
        }
        if (FileMutationBarrier.certificateRestorePending()) {
            Log.d(TAG, "launchStartupTask deferred until certificate restoration completes");
            deferStartupUntilCertificateRestore(command);
            return;
        }

        if (mSyncthingRunnable != null || mSyncthingRunnableThread != null) {
            Log.e(TAG, "launchStartupTask: Syncthing binary lifecycle violated");
            return;
        }
        if (mStartupConfigLoadPending) return;
        loadStartupConfiguration(command, ++mStartupRequestId);
    }

    private void beginRootSelectedStartup(SyncthingCommand command, boolean explicitRetry) {
        if (mRootStartupAuthorizationPending || mStartupConfigLoadPending
                || !ExecutionModeTransitionPolicy.mayAttemptRootStartup(
                runtimeExecutionMode(),
                mLastDeterminedShouldRun,
                mRootStartupAuthorizationAttempted,
                explicitRetry
        )) {
            return;
        }
        mRootStartupAuthorizationAttempted = true;
        mRootStartupAuthorizationPending = true;
        mRootStartupStatusPending = true;
        long requestId = ++mStartupRequestId;
        long lifecycleGeneration = mLifecycleGeneration;
        onModeTransitionStatusChanged(new ExecutionModeTransitionStatus(
                ExecutionModeTransitionStatus.Phase.PREPARING_ROOT,
                ExecutionModeTransitionStatus.FailureKind.NONE,
                null,
                null,
                "Checking root authorization before the eligible startup.",
                true,
                false,
                false,
                mStateAccessRepairRequired
        ));
        runRootAuthorizationPreflight(
                () -> {
                    if (!isCurrentStartupRequest(requestId, lifecycleGeneration)) return;
                    mRootStartupAuthorizationPending = false;
                    if (!mLastDeterminedShouldRun) {
                        mRootStartupStatusPending = false;
                        onModeTransitionStatusChanged(ExecutionModeTransitionStatus.idle(
                                mStateAccessRepairRequired
                        ));
                        return;
                    }
                    mRootStartupAuthorizationComplete = true;
                    loadStartupConfiguration(command, requestId);
                },
                failure -> {
                    if (!isCurrentStartupRequest(requestId, lifecycleGeneration)) return;
                    mRootStartupAuthorizationPending = false;
                    mRootStartupAuthorizationComplete = false;
                    failRootStartup(
                            ExecutionModeTransitionStatus.FailureKind.ROOT,
                            failure,
                            null,
                            "Root startup authorization failed. Retry after resolving the cause."
                    );
                }
        );
    }

    private void loadStartupConfiguration(SyncthingCommand command, long requestId) {
        if (!isCurrentStartupRequest(requestId, mLifecycleGeneration)
                || mStartupConfigLoadPending || mDestroying) return;
        if (mModeTransitionRequest != null || mExecutionModeTransitionReservation != null
                || FileMutationBarrier.executionModeTransitionPending()) return;
        if (FileMutationBarrier.certificateRestorePending()) {
            deferStartupUntilCertificateRestore(command);
            return;
        }
        FileMutationBarrier.StoppedStateMutationReservation mutation =
                FileMutationBarrier.tryReserveStoppedStateMutation();
        if (mutation == null) {
            FileMutationBarrier.whenStoppedStateMutationReleased(() -> {
                if (mHandler != null) {
                    mHandler.post(() -> loadStartupConfiguration(command, requestId));
                }
            });
            return;
        }
        mStartupConfigLoadPending = true;
        mStartupConfigLoadTimedOut = false;
        long loadRequestId = ++mStartupConfigLoadRequestId;
        long lifecycleGeneration = mLifecycleGeneration;
        Runnable timeout = () -> {
            if (!isCurrentStartupRequest(requestId, lifecycleGeneration)
                    || loadRequestId != mStartupConfigLoadRequestId
                    || !mStartupConfigLoadPending) return;
            mStartupConfigLoadTimedOut = true;
            if (mRootStartupStatusPending) {
                onModeTransitionStatusChanged(new ExecutionModeTransitionStatus(
                        ExecutionModeTransitionStatus.Phase.QUIESCING,
                        ExecutionModeTransitionStatus.FailureKind.STARTUP,
                        null,
                        null,
                        "The startup deadline elapsed; the configuration worker still owns storage admission. Retry becomes available after it settles.",
                        true,
                        false,
                        false,
                        mStateAccessRepairRequired
                ));
            }
        };
        mStartupConfigLoadTimeout = timeout;
        ExecutionModeTransitionTestSeam seam = mExecutionModeTransitionTestSeam;
        if (seam == null) {
            mHandler.postDelayed(timeout, STARTUP_CONFIGURATION_TIMEOUT_MILLIS);
        } else {
            seam.scheduleStartupConfigurationTimeout(timeout,
                    STARTUP_CONFIGURATION_TIMEOUT_MILLIS);
        }
        try {
            StartupConfigurationLoad.Source source = () -> {
                ConfigXml config = new ConfigXml(this);
                config.loadConfig();
                return StartupConfiguration.from(config);
            };
            StartupConfigurationLoad.Callback callback = result -> {
                mutation.release();
                if (!postTransitionCallback(() -> onStartupConfigurationLoaded(
                        command,
                        requestId,
                        loadRequestId,
                        lifecycleGeneration,
                        result
                ))) {
                    Log.e(TAG, "Startup configuration result could not return to the service thread");
                }
            };
            if (seam == null) StartupConfigurationLoad.start(source, callback);
            else seam.startStartupConfigurationLoad(source, callback);
        } catch (RuntimeException workerStartFailure) {
            mutation.release();
            if (mHandler != null) mHandler.removeCallbacks(timeout);
            mStartupConfigLoadPending = false;
            if (mRootStartupStatusPending) {
                failRootStartup(
                        ExecutionModeTransitionStatus.FailureKind.STARTUP,
                        null,
                        null,
                        "The startup configuration worker could not be started."
                );
            } else {
                showConfigurationStartupFailure(workerStartFailure);
            }
        }
    }

    private void onStartupConfigurationLoaded(
            SyncthingCommand command,
            long requestId,
            long loadRequestId,
            long lifecycleGeneration,
            StartupConfigurationLoad.Result result
    ) {
        if (lifecycleGeneration != mLifecycleGeneration || mDestroying
                || loadRequestId != mStartupConfigLoadRequestId) return;
        mStartupConfigLoadPending = false;
        if (mStartupConfigLoadTimeout != null) {
            if (mHandler != null) mHandler.removeCallbacks(mStartupConfigLoadTimeout);
            mStartupConfigLoadTimeout = null;
        }
        if (!isCurrentStartupRequest(requestId, lifecycleGeneration)) {
            // A Run Condition cycle can obsolete this request while its worker still owns
            // storage. Once it settles, the latest eligible request may claim admission.
            if (mLastDeterminedShouldRun && !mShutdownInProgress
                    && mModeTransitionRequest == null && !mDestroying
                    && (mCurrentState == State.DISABLED || mCurrentState == State.INIT)) {
                launchStartupTask(command);
            }
            return;
        }
        if (mStartupConfigLoadTimedOut) {
            if (mRootStartupStatusPending) {
                failRootStartup(
                        ExecutionModeTransitionStatus.FailureKind.STARTUP,
                        null,
                        null,
                        "Configuration access timed out. Retry the root-selected startup."
                );
            }
            return;
        }
        if (!result.succeeded()) {
            if (mRootStartupStatusPending) {
                failRootStartup(
                        result.rootFailure() == null
                                ? ExecutionModeTransitionStatus.FailureKind.STARTUP
                                : ExecutionModeTransitionStatus.FailureKind.ROOT,
                        result.rootFailure(),
                        null,
                        "Startup configuration could not be read: " + result.detail()
                );
            } else {
                showConfigurationStartupFailure(new ConfigXml.OpenConfigException());
            }
            return;
        }
        if (mModeTransitionRequest != null || mExecutionModeTransitionReservation != null
                || FileMutationBarrier.executionModeTransitionPending()
                || !mLastDeterminedShouldRun && command == SyncthingCommand.SERVE) {
            if (mRootStartupStatusPending && !mLastDeterminedShouldRun) {
                mRootStartupStatusPending = false;
                mRootStartupAuthorizationComplete = false;
                onModeTransitionStatusChanged(ExecutionModeTransitionStatus.idle(
                        mStateAccessRepairRequired
                ));
            }
            return;
        }
        startPreparedStartup(command, requestId, result.configuration());
    }

    private boolean isCurrentStartupRequest(long requestId, long lifecycleGeneration) {
        return !mDestroying && lifecycleGeneration == mLifecycleGeneration
                && requestId == mStartupRequestId;
    }

    private void failRootStartup(
            ExecutionModeTransitionStatus.FailureKind failureKind,
            @Nullable RootFailure rootFailure,
            @Nullable ManagedStateFailure managedStateFailure,
            String detail
    ) {
        mRootStartupStatusPending = false;
        // A failed launch must wait for explicit Retry even if Run Conditions cycle later.
        mRootStartupAuthorizationComplete = false;
        onModeTransitionStatusChanged(new ExecutionModeTransitionStatus(
                ExecutionModeTransitionStatus.Phase.FAILED,
                failureKind,
                rootFailure,
                managedStateFailure,
                detail,
                true,
                true,
                false,
                mStateAccessRepairRequired
        ));
    }

    private void showConfigurationStartupFailure(Throwable error) {
        Log.e(TAG, "Startup configuration could not be read", error);
        mNotificationHandler.showCrashedNotification(
                R.string.config_read_failed,
                "launchStartupTask:OpenConfigException"
        );
        synchronized (mStateLock) {
            if (mCurrentState != State.ERROR) onServiceStateChange(State.ERROR);
        }
        stopSelf();
    }

    /** Starts Syncthing after the authoritative configuration has been loaded off-thread. */
    private void startPreparedStartup(
            SyncthingCommand command,
            long requestId,
            StartupConfiguration configuration
    ) {
        if (!isCurrentStartupRequest(requestId, mLifecycleGeneration)
                || mExecutionModeReconciliationPending
                || mPreferences.getBoolean(Constants.PREF_USE_ROOT, false)
                != mRuntime.isSuperuserModeSelected()) return;
        if (mExecutionModeTransitionReservation != null
                || FileMutationBarrier.executionModeTransitionPending()) return;
        mStartupConfiguration = configuration;
        Integer webGuiTcpPort = configuration.webGuiBindPort();

        onServiceStateChange(State.STARTING);
        mStartupCommand = command;
        mLastExecutionExitProven = false;
        mShutdownExitProven = false;
        mShutdownWorkerStarted = false;
        mShutdownRecoveryCheckStarted = false;
        mRecoveryRestApi = createRecoveryRestApi(configuration);
        mStartupReadiness = new StartupReadiness((delayMillis, task) -> {
            mHandler.postDelayed(task, delayMillis);
            return () -> mHandler.removeCallbacks(task);
        }, this::onStartupDeadlineExceeded);

        RestApi recoveryApi = mRecoveryRestApi;
        LifecycleLaunchPermit startupPermit = new LifecycleLaunchPermit();
        mStartupLaunchPermit = startupPermit;
        DefaultSyncthingRuntime.OwnedExecutionRecoveryHandler recoveryHandler =
                identity -> stopOwnedExecution(identity, recoveryApi);
        DefaultSyncthingRuntime.LifecycleLaunchCheck portCheck =
                new DefaultSyncthingRuntime.LifecycleLaunchCheck() {
                    @Override
                    public void commitRecoveryBlocked() {
                        startupPermit.commitRecoveryBlocked();
                    }

                    @Override
                    public void check() {
                        if (Util.isTcpPortListening(webGuiTcpPort)) {
                            throw new SyncthingRunnable.GuiPortUnavailableException();
                        }
                        if (!FileMutationBarrier.commitLifecycleLaunchIfAvailable(
                                startupPermit::commitLaunch
                        )) {
                            throw new ExecutionAdmissionException();
                        }
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

    private RestApi createRecoveryRestApi(StartupConfiguration configuration) {
        return new RestApi(
                this, configuration.webGuiUrl(), configuration.apiKey(), () -> { }, () -> { }
        );
    }

    private RestApi createCurrentRestApi() {
        RestApi[] reference = new RestApi[1];
        reference[0] = new RestApi(
                this,
                mStartupConfiguration.webGuiUrl(),
                mStartupConfiguration.apiKey(),
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
                () -> recoveryApi == null ? null : recoveryApi.prepareShutdown(),
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
        verifyOwnershipAsync(identity, callback, false);
    }

    /**
     * Verifies the recorded ownership of one execution on a worker thread.
     *
     * <p>The check never runs on the service thread. When the caller asks for it, the optional
     * process tuning runs on this same worker directly after a successful verification, so neither
     * the check nor the privileged command can block the service thread.</p>
     *
     * @param applyProcessTuning whether the optional tuning runs after a verified observation
     */
    private void verifyOwnershipAsync(
            ExecutionIdentity identity,
            OwnershipVerificationCallback callback,
            boolean applyProcessTuning
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
            // The verdict travels to the service thread before any optional tuning starts, so a
            // slow privileged command can never delay startup or REST readiness.
            OwnershipVerification result = new OwnershipVerification(identity, observation);
            mHandler.post(() -> callback.onComplete(result));
            if (applyProcessTuning
                    && observation == ExecutionOwnershipManager.Observation.OWNED) {
                requestOwnedExecutionTuning(identity);
            }
        }, "Syncthing ownership verification");
        verificationThread.setDaemon(true);
        verificationThread.start();
    }

    /**
     * Asks for the optional process tuning of one verified owned execution, off the service thread.
     *
     * <p>Tuning is best effort and never part of startup: the ownership verdict has already been
     * handed to the service thread, and the privileged command runs on a worker of its own. The
     * service thread confirms first that the execution is still the current one, so tuning of an
     * execution that already exited or was replaced never starts, and the privileged operation
     * re-verifies the exact identity of the identifier immediately before it targets a process.</p>
     */
    private void requestOwnedExecutionTuning(ExecutionIdentity identity) {
        mHandler.post(() -> {
            if (!sameExecution(mOwnedExecution, identity)) {
                return;
            }
            Thread tuningThread = new Thread(
                    () -> tuneOwnedExecution(identity),
                    "Syncthing execution tuning"
            );
            tuningThread.setDaemon(true);
            tuningThread.start();
        });
    }
    /**
     * Applies the optional process tuning to one verified owned execution.
     *
     * <p>Tuning never changes lifecycle state and never raises: the outcome is logged and the
     * service keeps running with the system default when a device cannot apply it. The privileged
     * operation re-verifies the exact ownership of the identifier before it targets anything, so
     * an identifier that was reused by an unrelated process is never tuned.</p>
     */
    private void tuneOwnedExecution(ExecutionIdentity identity) {
        try {
            TuningOutcome outcome = mRuntime.applyIoPriority(identity);
            Log.i(TAG, "Owned execution tuning: " + outcome.status() + ": " + outcome.detail());
        } catch (RuntimeException unexpected) {
            Log.w(TAG, "Owned execution tuning did not complete", unexpected);
        }
    }

    private void onLifecycleOutcome(SyncthingRunnable.LifecycleOutcome outcome) {
        mStartupLaunchPermit = null;
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
                    verifyOwnershipAsync(
                            outcome.identity(),
                            this::onInitialOwnershipVerified,
                            true
                    );
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
            case EXIT_UNVERIFIED:
                handleExitUnverified(outcome);
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
                        Integer.toString(mStartupConfiguration.webGuiBindPort())
                );
                synchronized (mStateLock) {
                    if (mCurrentState == State.STARTING) onServiceStateChange(State.DISABLED);
                }
                clearWorkerHandlesWhenStopped(mSyncthingRunnableThread);
                break;
            case LAUNCH_CANCELLED:
                mLastExecutionExitProven = true;
                mShutdownExitProven = true;
                cancelStartupRequests();
                if (mStartupReadiness != null) {
                    mStartupReadiness.cancel();
                    mStartupReadiness = null;
                }
                mRecoveryRestApi = null;
                if (!mShutdownInProgress && mCurrentState == State.STARTING) {
                    synchronized (mStateLock) {
                        onServiceStateChange(
                                SyncthingLaunchFailurePolicy.cancelledStartupState(mCurrentState)
                        );
                    }
                }
                if (mShutdownInProgress) checkShutdownRecovery();
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
        synchronized (mRootScriptLifecycleLock) {
            // The replacement REST owner may accept future events after the prior shutdown.
            mRootScriptShutdownClosed = false;
        }
        RestApi expectedApi = mRestApi;
        Log.i(TAG, "Web GUI will be available at " + mStartupConfiguration.webGuiUrl());
        mPollWebGuiAvailableTask = new PollWebGuiAvailableTask(
                this,
                mStartupConfiguration.webGuiUrl(),
                mStartupConfiguration.apiKey(),
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
            ConfigurationProjection.update(
                    expectedApi.getFolders(),
                    expectedApi.getDevices(false),
                    expectedApi.getDevices(true)
            );
            if (mRunConditionMonitor != null) {
                applyCustomRunConditions(mRunConditionMonitor);
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

    /**
     * Handles an execution that ended without an authenticated Syncthing exit status.
     *
     * <p>The run never reaches the restart or crash policy in this case, because no authenticated
     * status exists to classify. A proven exit means the child is gone and only its status stayed
     * unavailable; an unproven exit means the launched process may still be alive, so the durable
     * handle is kept and only an exact-ownership shutdown may replace it later.</p>
     */
    private void handleExitUnverified(SyncthingRunnable.LifecycleOutcome outcome) {
        mLastExecutionExitProven = outcome.exitObserved();
        if (outcome.exitObserved() && sameExecution(mOwnedExecution, outcome.identity())) {
            mOwnedExecution = null;
        }
        UnverifiedExitResolutionPolicy.Resolution resolution =
                UnverifiedExitResolutionPolicy.resolution(
                        mShutdownInProgress,
                        outcome.exitObserved(),
                        mShutdownWorkerStarted || mShutdownRecoveryCheckStarted,
                        mShutdownExitProven
                );
        if (resolution == UnverifiedExitResolutionPolicy.Resolution.ACCOUNT_PROVEN_EXIT) {
            mShutdownExitProven = true;
            clearWorkerHandlesWhenStopped(mSyncthingRunnableThread);
            checkShutdownRecovery();
            return;
        }
        if (resolution == UnverifiedExitResolutionPolicy.Resolution.AWAIT_SHUTDOWN) {
            // The shutdown resolves this execution through its own exact-ownership steps: the
            // bounded shutdown worker proves or refutes the exit independently of the failed
            // verification, and the post-shutdown recovery check reclassifies what remains. Keep
            // that machinery - and the restart it may be completing - in charge instead of
            // reporting the unverified wait as a failed restart. A shutdown that already proved
            // the exit may still be waiting for the lifecycle thread to terminate before its
            // recovery check can start, so asking for that check here guarantees the deferred
            // resolution runs even though this wait published no proven exit.
            clearWorkerHandlesWhenStopped(mSyncthingRunnableThread);
            checkShutdownRecovery();
            return;
        }
        mShutdownStartIntent.clear();
        if (mStartupReadiness != null) {
            mStartupReadiness.cancel();
            mStartupReadiness = null;
        }
        cancelStartupRequests();
        mRestApi = null;
        mRecoveryRestApi = null;
        clearWorkerHandlesWhenStopped(mSyncthingRunnableThread);
        synchronized (mStateLock) {
            onServiceStateChange(State.ERROR);
        }
        if (mNotificationHandler != null) {
            mNotificationHandler.showCrashedNotification(
                    R.string.notification_crash_title,
                    outcome.exitObserved()
                            ? "Syncthing exited without an authenticated exit status"
                            : "Syncthing exit could not be verified; the process may still be running"
            );
        }
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
                            () -> restApi == null ? null : restApi.prepareShutdown(),
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

    /** Immutable result produced by the database reset worker. */
    private static final class DatabaseResetOutcome {
        enum Type { COMPLETED, FAILED }

        private final Type type;
        @Nullable private final Throwable failure;

        private DatabaseResetOutcome(Type type, @Nullable Throwable failure) {
            this.type = type;
            this.failure = failure;
        }

        static DatabaseResetOutcome completed() {
            return new DatabaseResetOutcome(Type.COMPLETED, null);
        }

        static DatabaseResetOutcome failed(Throwable failure) {
            return new DatabaseResetOutcome(Type.FAILED, failure);
        }

        boolean succeeded() {
            return type == Type.COMPLETED;
        }

        @Nullable Throwable failure() {
            return failure;
        }
    }

    /**
     * Requests the existing database reset operation after any service-owned Syncthing execution
     * has been shut down. Admission rejection is handled here so one-shot failures cannot detach
     * or terminate the invocation that currently owns runtime admission.
     */
    private void requestResetDatabase(@Nullable Runnable afterReset) {
        requestResetDatabase(
                afterReset,
                null,
                DatabaseResetOwnership.ContinuationPolicy.AUTOMATIC_STARTUP
        );
    }

    private void requestResetDatabase(
            @Nullable Runnable afterReset,
            @Nullable Runnable onFailure
    ) {
        requestResetDatabase(
                afterReset,
                onFailure,
                DatabaseResetOwnership.ContinuationPolicy.REQUIRED_OPERATION
        );
    }

    private void requestResetDatabase(
            @Nullable Runnable afterReset,
            @Nullable Runnable onFailure,
            DatabaseResetOwnership.ContinuationPolicy continuationPolicy
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            if (!mHandler.post(() -> requestResetDatabase(
                    afterReset, onFailure, continuationPolicy
            ))) {
                Log.e(TAG, "Could not schedule database reset ownership on the service thread");
                dispatchResetFailure(onFailure);
            }
            return;
        }
        if (mDestroying) return;
        if (mExecutionModeTransitionReservation != null
                || FileMutationBarrier.executionModeTransitionPending()) {
            Log.w(TAG, "Database reset rejected while an execution-mode transition owns admission");
            dispatchResetFailure(onFailure);
            return;
        }
        if (FileMutationBarrier.certificateRestorePending()) {
            Log.w(TAG, "Database reset rejected while certificate storage is reserved");
            dispatchResetFailure(onFailure);
            return;
        }

        DatabaseResetOwnership.Operation operation =
                mDatabaseResetOwnership.reserve(afterReset, onFailure, continuationPolicy);
        if (operation == null) {
            Log.w(TAG, "Database reset rejected because another reset owns lifecycle admission");
            dispatchResetFailure(onFailure);
            return;
        }

        try {
            RestApi recoveryApi = createRecoveryRestApiFromDisk();
            DefaultSyncthingRuntime.OwnedExecutionRecoveryHandler recoveryHandler =
                    identity -> stopOwnedExecution(identity, recoveryApi);
            Runnable reset = () -> startDatabaseResetWorker(operation, recoveryHandler);

            if (SyncthingResetPolicy.shouldWaitForShutdownComplete(
                    mCurrentState,
                    mSyncthingRunnable != null || mSyncthingRunnableThread != null,
                    mShutdownInProgress
            )) {
                shutdown(State.DISABLED, reset);
            } else {
                reset.run();
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not begin database reset", e);
            completeDatabaseReset(operation, DatabaseResetOutcome.failed(e));
        }
    }

    private void startDatabaseResetWorker(
            DatabaseResetOwnership.Operation operation,
            DefaultSyncthingRuntime.OwnedExecutionRecoveryHandler recoveryHandler
    ) {
        Thread resetWorker = new Thread(() -> {
            DatabaseResetOutcome outcome;
            try {
                DefaultSyncthingRuntime.LifecycleLaunchCheck launchCheck =
                        new DefaultSyncthingRuntime.LifecycleLaunchCheck() {
                            @Override
                            public void commitRecoveryBlocked() {
                                operation.commitRecoveryBlocked();
                            }

                            @Override
                            public void check() {
                                if (!FileMutationBarrier.commitLifecycleLaunchIfAvailable(
                                        operation::commitLaunch
                                )) {
                                    throw new ExecutionAdmissionException();
                                }
                            }
                        };
                SyncthingRunnable.forOneShotWithLifecycleCheck(
                        this,
                        SyncthingCommand.RESET_DATABASE,
                        recoveryHandler,
                        launchCheck
                ).run();
                outcome = DatabaseResetOutcome.completed();
            } catch (LifecycleLaunchPermit.CancelledException cancelledBeforeCreation) {
                // Destruction already revoked and released this operation. No failure callback
                // may start new service work after that terminal cancellation.
                return;
            } catch (ExecutionAdmissionException e) {
                Log.e(TAG, "Database reset rejected because another invocation owns admission", e);
                outcome = DatabaseResetOutcome.failed(e);
            } catch (RuntimeException e) {
                Log.e(TAG, "Database reset could not recover an exact execution", e);
                if (mNotificationHandler != null) {
                    mHandler.post(() -> mNotificationHandler.showCrashedNotification(
                            R.string.notification_crash_title,
                            "Database reset was blocked by Syncthing execution recovery"
                    ));
                }
                outcome = DatabaseResetOutcome.failed(e);
            }
            DatabaseResetOutcome completedOutcome = outcome;
            if (!mHandler.post(() -> completeDatabaseReset(operation, completedOutcome))) {
                Log.e(TAG, "Database reset outcome could not return to the service thread");
            }
        }, "Syncthing database reset");
        resetWorker.setDaemon(true);
        try {
            resetWorker.start();
        } catch (RuntimeException e) {
            Log.e(TAG, "Database reset worker could not start", e);
            completeDatabaseReset(operation, DatabaseResetOutcome.failed(e));
        }
    }

    private void completeDatabaseReset(
            DatabaseResetOwnership.Operation operation,
            DatabaseResetOutcome outcome
    ) {
        if (!mDatabaseResetOwnership.complete(operation)) return;
        if (mDestroying) return;

        if (outcome.succeeded()) {
            try {
                operation.runAfterReset();
            } catch (RuntimeException e) {
                Log.e(TAG, "Database reset completion failed", e);
                runResetFailureContinuation(operation);
            }
            resumeCertificateRestoreStartAfterDatabaseReset();
            return;
        }

        mShutdownStartIntent.clear();
        Log.e(TAG, "Database reset failed", outcome.failure());
        runResetFailureContinuation(operation);
        resumeCertificateRestoreStartAfterDatabaseReset();
    }

    private void resumeCertificateRestoreStartAfterDatabaseReset() {
        Boolean safeToStart = mCertificateRestoreSafeToStart;
        if (safeToStart != null && mCertificateRestoreDeferredStart.isPending()) {
            resumeStartupAfterCertificateRestore(safeToStart);
        }
    }

    private void runResetFailureContinuation(DatabaseResetOwnership.Operation operation) {
        Runnable onFailure = operation.onFailure();
        if (onFailure != null && !mDestroying) onFailure.run();
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
        StartupConfiguration configuration = mStartupConfiguration;
        if (configuration == null) return null;
        return createRecoveryRestApi(configuration);
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
        ModeTransitionRequest abandonedTransition = markServiceDestroying();
        mDestructionAbandonedTransition = abandonedTransition;
        mDestructionShutdownCompletionPending = true;
        if (mExecutionModePreferenceListener != null && mPreferences != null) {
            mPreferences.unregisterOnSharedPreferenceChangeListener(
                    mExecutionModePreferenceListener
            );
            mExecutionModePreferenceListener = null;
        }
        DatabaseResetOwnership.Operation resetOperation =
                mDatabaseResetOwnership.currentOperation();
        if (resetOperation != null) {
            mDatabaseResetOwnership.cancelBeforeLaunch(resetOperation);
        }
        revokeStartupLaunchPermit();
        mCertificateRestoreDeferredStart.take();
        if (mCertificateMutationCancellation != null) {
            mCertificateMutationCancellation.set(true);
        }
        CertificateVerificationStopHandler certificateVerification =
                mCertificateVerificationStopHandler;
        if (certificateVerification != null) certificateVerification.onServiceDestroy();
        if (mCertificateStorageReservation != null) {
            mCertificateStorageReservation.holdUntilShutdownCompletion();
        }
        mPostMutationStartupGate.cancel(mShutdownStartIntent);
        if (!mCertificateMutationPending) failFileMutationBarrier();
        if (!mCertificateMutationPending) mCertificateStorageExecutor.shutdown();
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
        shutdown(
                State.DISABLED,
                null,
                true,
                false,
                this::failCertificateDestructionRestoreAfterShutdown
        );
        super.onDestroy();
    }

    private ModeTransitionRequest markServiceDestroying() {
        synchronized (mRootScriptLifecycleLock) {
            synchronized (mExecutionModeReconciliationLock) {
                mDestroying = true;
            }
            ++mRootScriptLifecycleEpoch;
            RootRunScriptAuthorizationPolicy scriptPolicy = mRootRunScriptPromptPolicy;
            if (scriptPolicy != null) scriptPolicy.invalidate();
            Thread scriptWorker = mRootRunScriptWorker;
            if (scriptWorker != null && !mRootRunScriptExecuting) scriptWorker.interrupt();
        }
        ++mLifecycleGeneration;
        ++mStartupRequestId;
        mRootStartupAuthorizationPending = false;
        mRootStartupAuthorizationComplete = false;
        mRootStartupStatusPending = false;
        ModeTransitionRequest abandonedTransition = mModeTransitionRequest;
        if (abandonedTransition != null) {
            synchronized (abandonedTransition) {
                if (!abandonedTransition.commitStarted) abandonedTransition.cancelled = true;
            }
        }
        mModeTransitionRequest = null;
        return abandonedTransition;
    }

    private void onServiceDestructionShutdownComplete(
            @Nullable ModeTransitionRequest abandonedTransition,
            boolean safe
    ) {
        if (!safe) return;
        if (mExecutionModeTransitionReservation == null) {
            reopenBackendOperationAdmissionWhenDrained();
            RestApi api = abandonedTransition == null
                    ? null : abandonedTransition.transitionRestApi;
            if (api != null) api.reopenFolderScriptAdmissionAfterModeTransitionFailure();
            return;
        }
        if (abandonedTransition != null) {
            synchronized (abandonedTransition) {
                abandonedTransition.destructionShutdownSafe = true;
            }
            maybeReleaseDestroyedTransitionReservation(abandonedTransition);
        } else {
            mRuntimeReconciliationShutdownSafe = true;
            maybeReleaseDestroyedTransitionReservation(null);
        }
    }

    /** Refuses certificate writes when destruction cannot prove the old execution has exited. */
    private void failCertificateDestructionRestoreAfterShutdown() {
        CertificateDestructionRestore restore = mCertificateDestructionRestore;
        if (restore == null) return;
        restore.onExecutionExitUnproven(new IllegalStateException(
                "Could not prove Syncthing stopped; certificate restoration was not safe."
        ));
    }

    /** Releases retained certificate storage only after shutdown and recovery are both terminal. */
    private void completeCertificateStorageShutdown(boolean safeToStart) {
        FileMutationBarrier.CertificateRestoreReservation reservation =
                mCertificateStorageReservation;
        if (reservation == null) return;
        reservation.completeShutdown(safeToStart);
        clearReleasedCertificateStorageReservation(reservation);
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
        void run(CertificateMutationOwnership ownership);
    }

    /** Holds lifecycle admission until certificate file work has returned to the service thread. */
    private final class CertificateMutationOwnership {
        private final FileMutationBarrier barrier;
        private final boolean automaticStartupAllowed;
        private boolean released;

        CertificateMutationOwnership(
                FileMutationBarrier barrier,
                boolean automaticStartupAllowed
        ) {
            this.barrier = barrier;
            this.automaticStartupAllowed = automaticStartupAllowed;
        }

        boolean release() {
            if (released) return false;
            released = true;
            boolean startupSuppressed = barrier.clearDeferredStartIfSuppressed(
                    mShutdownStartIntent
            );
            if (mFileMutationBarrier == barrier) mFileMutationBarrier = null;
            return automaticStartupAllowed && !startupSuppressed;
        }
    }

    /** Reserves the shared mutation owner before shutdown and stopped-state file work begin. */
    private void shutdownForFileMutation(
            StoppedFileMutation afterMutation,
            Runnable onRejected,
            Runnable onShutdownFailure,
            Runnable onLifecycleConflict,
            @Nullable FileMutationBarrier.CertificateRestoreReservation permittedReservation
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            if (!mHandler.post(() -> shutdownForFileMutation(
                    afterMutation, onRejected, onShutdownFailure, onLifecycleConflict,
                    permittedReservation
            ))) {
                onShutdownFailure.run();
            }
            return;
        }

        if (FileMutationBarrier.executionModeTransitionPending()) {
            onLifecycleConflict.run();
            return;
        }

        if (FileMutationBarrier.rejectIfShutdownContinuationPending(
                mShutdownInProgress, mAfterShutdown != null, onLifecycleConflict
        )) return;
        if (mCertificateMutationPending) {
            onRejected.run();
            return;
        }
        if (FileMutationBarrier.rejectIfCertificateRestorePendingExcept(
                permittedReservation, onRejected
        )) return;
        if (mPostMutationStartupGate.rejectNewMutation(onRejected)) return;
        if (mDatabaseResetOwnership.isReserved()) {
            onRejected.run();
            return;
        }
        boolean shutdownRequired = FileMutationBarrier.requiresShutdownBeforeCertificateWork(
                hasServiceExecution(), permittedReservation != null
        );

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
                            afterMutation.run(new CertificateMutationOwnership(
                                    owner, !startupSuppressed
                            ));
                        });
                        if (shutdownRequired) {
                            shutdown(State.DISABLED, null);
                        } else {
                            owner.completeAsyncOwner().run();
                        }
                    }
            );
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not begin shutdown for HTTPS certificate mutation", e);
            if (mFileMutationBarrier == owner) mFileMutationBarrier = null;
            owner.stopFailed();
        }
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
        shutdown(
                newState,
                afterShutdown,
                duringStartup,
                mutationBeforeDeferredCompletion,
                null
        );
    }

    private void shutdown(
            State newState,
            @Nullable Runnable afterShutdown,
            boolean duringStartup,
            boolean mutationBeforeDeferredCompletion,
            @Nullable Runnable afterShutdownFailure
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mHandler.post(() -> shutdown(
                    newState,
                    afterShutdown,
                    duringStartup,
                    mutationBeforeDeferredCompletion,
                    afterShutdownFailure
            ));
            return;
        }
        revokeStartupLaunchPermit();
        if (mCurrentState == State.STARTING && !duringStartup) {
            mStartingShutdownDeferral.defer();
            Log.w(TAG, "Deferring shutdown until State.STARTING was left");
            mHandler.postDelayed(() -> {
                shutdown(
                        newState,
                        afterShutdown,
                        false,
                        mutationBeforeDeferredCompletion,
                        afterShutdownFailure
                );
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
            mStartingShutdownDeferral.transferToShutdown();
            mAfterShutdown = mutationBeforeDeferredCompletion
                    ? FileMutationBarrier.mutationBeforeDeferredCompletion(
                            afterShutdown, mAfterShutdown
                    )
                    : appendCompletion(mAfterShutdown, afterShutdown);
            mAfterShutdownFailure = appendCompletion(
                    mAfterShutdownFailure, afterShutdownFailure
            );
            if (mShutdownRestApi == null) mShutdownRestApi = restApi;
            return;
        }

        mShutdownInProgress = true;
        mShutdownSafetyEstablished = false;
        mStartingShutdownDeferral.transferToShutdown();
        mShutdownExitProven = mLastExecutionExitProven;
        mShutdownWorkerStarted = false;
        mShutdownRecoveryCheckStarted = false;
        mShutdownRestApi = restApi;
        mAfterShutdown = afterShutdown;
        mAfterShutdownFailure = afterShutdownFailure;

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

    /** Revokes a startup worker before a stop request can defer behind its current state. */
    private void revokeStartupLaunchPermit() {
        if (mStartupLaunchPermit != null) mStartupLaunchPermit.revoke();
    }

    private void cancelActionResetDeltasContinuation() {
        ActionResetDeltasContinuation continuation = mActionResetDeltasContinuation;
        ShutdownFailureContinuationCleanup.cancelAndReleaseDeltaReset(
                continuation,
                () -> {
                    if (mActionResetDeltasContinuation == continuation) {
                        mActionResetDeltasContinuation = null;
                    }
                }
        );
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
        mShutdownSafetyEstablished = false;
        completeCertificateStorageShutdown(false);
        mShutdownStartIntent.clear();
        mShutdownInProgress = false;
        ShutdownFailureContinuationCleanup.discard(
                () -> mAfterShutdown = null,
                this::cancelActionResetDeltasContinuation
        );
        runShutdownFailureContinuation();
        mShutdownRestApi = null;
        failFileMutationBarrier();
        synchronized (mStateLock) {
            if (mCurrentState != State.ERROR) onServiceStateChange(State.ERROR);
        }
        DatabaseResetOwnership.Operation resetOperation =
                mDatabaseResetOwnership.currentOperation();
        if (resetOperation != null) {
            completeDatabaseReset(resetOperation, DatabaseResetOutcome.failed(
                    new IllegalStateException("Owned execution shutdown could not be proven")
            ));
        }
    }

    /** Runs and clears work that must terminally resolve if shutdown itself fails. */
    private void runShutdownFailureContinuation() {
        Runnable failure = mAfterShutdownFailure;
        mAfterShutdownFailure = null;
        if (failure == null) return;
        try {
            failure.run();
        } catch (RuntimeException e) {
            Log.e(TAG, "Shutdown failure continuation failed", e);
        }
    }

    /** Reclassifies after exit so no callback can mutate state while another candidate remains. */
    private void checkShutdownRecovery() {
        if (!mShutdownInProgress
                || !mShutdownExitProven
                || mShutdownWorkerStarted
                || mShutdownRecoveryCheckStarted) {
            return;
        }
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
                                () -> recoveryApi == null ? null : recoveryApi.prepareShutdown(),
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
            mShutdownSafetyEstablished = false;
            completeCertificateStorageShutdown(false);
            mShutdownStartIntent.clear();
            Log.e(TAG, "Shutdown completed but recovery remains blocked: "
                    + (assessment == null ? "unknown" : assessment.classification()));
            mShutdownInProgress = false;
            mShutdownRecoveryCheckStarted = false;
            ShutdownFailureContinuationCleanup.discard(
                    () -> mAfterShutdown = null,
                    this::cancelActionResetDeltasContinuation
            );
            runShutdownFailureContinuation();
            mShutdownRestApi = null;
            failFileMutationBarrier();
            synchronized (mStateLock) {
                if (mCurrentState != State.ERROR) onServiceStateChange(State.ERROR);
            }
            DatabaseResetOwnership.Operation resetOperation =
                    mDatabaseResetOwnership.currentOperation();
            if (resetOperation != null) {
                completeDatabaseReset(resetOperation, DatabaseResetOutcome.failed(
                        new IllegalStateException("Execution recovery did not permit replacement")
                ));
            }
            return;
        }

        Runnable completion = mAfterShutdown;
        mAfterShutdown = null;
        mAfterShutdownFailure = null;
        mShutdownRestApi = null;
        mShutdownInProgress = false;
        mShutdownRecoveryCheckStarted = false;
        mShutdownSafetyEstablished = true;
        completeCertificateStorageShutdown(true);
        mOwnedExecution = null;
        if (mDestroying) {
            failFileMutationBarrier();
            CertificateDestructionRestore destructionRestore = mCertificateDestructionRestore;
            if (destructionRestore != null) destructionRestore.onExecutionExitProven();
            if (mDestructionShutdownCompletionPending) {
                mDestructionShutdownCompletionPending = false;
                onServiceDestructionShutdownComplete(mDestructionAbandonedTransition,
                        mShutdownExitProven && mShutdownSafetyEstablished);
                mDestructionAbandonedTransition = null;
            }
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
        if (mCertificateMutationPending) return;
        if (mPostMutationStartupGate.consumeDeferredStart(
                mShutdownStartIntent,
                mLastDeterminedShouldRun,
                executionExitProven,
                true,
                mCurrentState == State.DISABLED
                        && mOwnedExecution == null
                        && mSyncthingRunnable == null
                        && mSyncthingRunnableThread == null
                        && mDatabaseResetOwnership.canStartLifecycle(),
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
            if (mDestroying || mFileMutationBarrier != null || mCertificateMutationPending
                    || mExecutionModeTransitionReservation != null
                    || FileMutationBarrier.executionModeTransitionPending()
                    || FileMutationBarrier.certificateRestorePending()) {
                barrier.stopFailed();
                return;
            }
            if (mPostMutationStartupGate.rejectNewMutation(barrier::stopFailed)) return;
            if (mDatabaseResetOwnership.isReserved()) {
                barrier.stopFailed();
                return;
            }
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

    /** Returns the committed backend selection currently used by the runtime. */
    public boolean isSuperuserModeSelected() {
        return mRuntime.isSuperuserModeSelected();
    }

    /** Returns the durable mode selection shown in settings, including a post-commit retry case. */
    public boolean isSuperuserModeCommitted() {
        return mPreferences.getBoolean(Constants.PREF_USE_ROOT, false);
    }

    /** Returns the latest transition state for settings and other service clients. */
    public ExecutionModeTransitionStatus getExecutionModeTransitionStatus() {
        return mModeTransitionStatus;
    }

    /** Returns consent and suspension without attempting root authorization. */
    @Nullable
    public RootRunScriptAuthorizationPolicy.Status getRootRunScriptPromptStatus() {
        RootRunScriptAuthorizationPolicy policy = mRootRunScriptPromptPolicy;
        return policy == null ? null : policy.status();
    }

    public void registerRootRunScriptPromptStatusListener(
            OnRootRunScriptPromptStatusChangedListener listener
    ) {
        mRootRunScriptPromptStatusListeners.add(listener);
        RootRunScriptAuthorizationPolicy.Status status = getRootRunScriptPromptStatus();
        if (status != null) listener.onRootRunScriptPromptStatusChanged(status);
    }

    public void unregisterRootRunScriptPromptStatusListener(
            OnRootRunScriptPromptStatusChangedListener listener
    ) {
        mRootRunScriptPromptStatusListeners.remove(listener);
    }

    private void notifyRootRunScriptPromptStatusChanged() {
        if (mHandler == null) return;
        mHandler.post(() -> {
            RootRunScriptAuthorizationPolicy.Status status = getRootRunScriptPromptStatus();
            if (status == null || mDestroying) return;
            for (OnRootRunScriptPromptStatusChangedListener listener
                    : new HashSet<>(mRootRunScriptPromptStatusListeners)) {
                listener.onRootRunScriptPromptStatusChanged(status);
            }
        });
    }

    /** Changes consent on a worker. Resume only arms events received after this call succeeds. */
    public void requestRootRunScriptPromptConsent(boolean enabled) {
        RootRunScriptAuthorizationPolicy policy = mRootRunScriptPromptPolicy;
        if (policy == null) return;
        final long consentGeneration;
        Thread scriptWorker = null;
        synchronized (mRootScriptLifecycleLock) {
            if (mDestroying) return;
            if (enabled) {
                if (mRootScriptOptOutPending
                        || mRootScriptConsentMutationsInFlight.get() != 0
                        || !isSuperuserModeCommitted()
                        || isExecutionModeTransitionBusy()) return;
            } else {
                // OFF supersedes a queued or blocked opt-in. The old worker's generation and
                // policy epoch then prevent its eventual write from restoring authorization.
                if (mRootScriptOptOutPending) return;
                mRootScriptOptOutPending = true;
                ++mRootScriptConsentGeneration;
                ++mRootScriptLifecycleEpoch;
                policy.markOptOutPending();
                scriptWorker = mRootRunScriptWorker;
            }
            consentGeneration = mRootScriptConsentGeneration;
            mRootScriptConsentMutationsInFlight.incrementAndGet();
        }
        if (scriptWorker != null) scriptWorker.interrupt();
        long generation = mLifecycleGeneration;
        Thread worker = new Thread(() -> {
            try {
                // An accepted opt-out must commit even if this service is destroyed before the
                // worker starts. Opt-in and Resume remain bound to the requesting lifecycle.
                if (enabled && (mDestroying || generation != mLifecycleGeneration
                        || consentGeneration != mRootScriptConsentGeneration)) return;
                boolean persisted = enabled
                        ? policy.armIf(() -> !mDestroying
                                && generation == mLifecycleGeneration
                                && consentGeneration == mRootScriptConsentGeneration
                                && isSuperuserModeCommitted()
                                && !isExecutionModeTransitionBusy())
                        : policy.disable();
                if (!persisted) logRootRunScriptFailure(
                        "Root Run Script prompt consent could not be saved", null);
            } finally {
                mRootScriptConsentMutationsInFlight.decrementAndGet();
                if (!enabled) mRootScriptOptOutPending = false;
                notifyRootRunScriptPromptStatusChanged();
            }
        }, "root-script-consent");
        worker.setDaemon(true);
        try {
            mRootScriptWorkerStarter.start(worker);
        } catch (RuntimeException failure) {
            mRootScriptConsentMutationsInFlight.decrementAndGet();
            if (!enabled) {
                policy.markOptOutSchedulingFailed();
                mRootScriptOptOutPending = false;
            }
            logRootRunScriptFailure("Root Run Script consent worker could not start", failure);
        }
    }

    public boolean isRootRunScriptPromptConsentMutationPending() {
        return mRootScriptConsentMutationsInFlight.get() != 0;
    }

    /** Reports whether an accepted script still owns its worker and lifecycle admissions. */
    public boolean isRootRunScriptOperationActive() {
        return mRootRunScriptWorkerActive.get();
    }

    /** A typed immediate decision; accepted work retains both admission gates on its worker. */
    public enum RootRunScriptDispatchDecision {
        ACCEPTED, BUSY, OBSOLETE, UNAVAILABLE
    }

    /** Cancels only the admitted automatic root script when its owning core is stopping. */
    public void cancelAdmittedRootRunScript() {
        synchronized (mRootScriptLifecycleLock) {
            mRootScriptShutdownClosed = true;
            ++mRootScriptLifecycleEpoch;
            RootRunScriptAuthorizationPolicy policy = mRootRunScriptPromptPolicy;
            if (policy != null) policy.invalidate();
            Thread worker = mRootRunScriptWorker;
            if (worker != null && !mRootRunScriptExecuting) worker.interrupt();
        }
    }

    private boolean rootScriptWorkerCurrent(long lifecycleGeneration, long scriptEpoch) {
        synchronized (mRootScriptLifecycleLock) {
            return !mDestroying && !mRootScriptShutdownClosed
                    && mModeTransitionRequest == null
                    && mExecutionModeTransitionReservation == null
                    && !mShutdownInProgress
                    && lifecycleGeneration == mLifecycleGeneration
                    && scriptEpoch == mRootScriptLifecycleEpoch
                    && isSuperuserModeCommitted() && mRuntime.isSuperuserModeSelected();
        }
    }

    /**
     * Accepts at most one root-script event without queueing. REST already reserved both gates;
     * this service owns the authorization policy, worker lifetime, and cancellation generation.
     */
    public RootRunScriptDispatchDecision tryDispatchRootRunScript(
            DefaultSyncthingRuntime.FolderScriptReservation reservation,
            ConfiguredFolderReference folder,
            FolderEvent event,
            Runnable releaseAdmission
    ) {
        RootRunScriptAuthorizationPolicy policy = mRootRunScriptPromptPolicy;
        if (policy == null) return RootRunScriptDispatchDecision.UNAVAILABLE;
        final long lifecycleGeneration;
        final long scriptEpoch;
        synchronized (mRootScriptLifecycleLock) {
            if (mDestroying || mRootScriptShutdownClosed || mModeTransitionRequest != null
                    || mExecutionModeTransitionReservation != null || mShutdownInProgress
                    || !isSuperuserModeCommitted() || !reservation.isSuperuserMode()
                    || !mRuntime.isSuperuserModeSelected()) {
                return RootRunScriptDispatchDecision.OBSOLETE;
            }
            if (!mRootRunScriptWorkerActive.compareAndSet(false, true)) {
                return RootRunScriptDispatchDecision.BUSY;
            }
            if (policy.status().state() != RootRunScriptAuthorizationPolicy.State.ARMED) {
                mRootRunScriptWorkerActive.set(false);
                return RootRunScriptDispatchDecision.UNAVAILABLE;
            }
            lifecycleGeneration = mLifecycleGeneration;
            scriptEpoch = mRootScriptLifecycleEpoch;
        }
        Thread worker = new Thread(() -> {
            RootRunScriptAuthorizationPolicy.Attempt attempt = null;
            RootRunScriptAuthorizationPolicy.Result result =
                    RootRunScriptAuthorizationPolicy.Result.CANCELLED;
            try {
                if (!rootScriptWorkerCurrent(lifecycleGeneration, scriptEpoch)) return;
                // A failed pre-commit transition may have cancelled a queued event without
                // recording an attempt. Only that settled ARMED state can serve a future event.
                policy.restoreUnattemptedConsent();
                if (!rootScriptWorkerCurrent(lifecycleGeneration, scriptEpoch)) return;
                attempt = policy.begin(reservation.generation());
                notifyRootRunScriptPromptStatusChanged();
                if (attempt == null) {
                    return;
                }
                RootRunScriptAuthorizationPolicy.Attempt ownedAttempt = attempt;
                RunScriptRootAuthorization authorization = new RunScriptRootAuthorization() {
                    private boolean acquisitionStarted;

                    @Override public boolean isCurrent() {
                        return rootScriptWorkerCurrent(lifecycleGeneration, scriptEpoch)
                                && policy.isCurrent(ownedAttempt, reservation.generation());
                    }

                    @Override public boolean tryBeginAuthorization() {
                        synchronized (mRootScriptLifecycleLock) {
                            if (acquisitionStarted || mDestroying || mRootScriptShutdownClosed
                                    || mModeTransitionRequest != null
                                    || mExecutionModeTransitionReservation != null
                                    || mShutdownInProgress
                                    || lifecycleGeneration != mLifecycleGeneration
                                    || scriptEpoch != mRootScriptLifecycleEpoch
                                    || !isSuperuserModeCommitted()
                                    || !mRuntime.isSuperuserModeSelected()
                                    || !policy.isCurrent(ownedAttempt,
                                            reservation.generation())) return false;
                            acquisitionStarted = true;
                            return true;
                        }
                    }

                    @Override public boolean tryBeginScriptExecution() {
                        synchronized (mRootScriptLifecycleLock) {
                            if (mRootRunScriptExecuting || !isCurrent()) return false;
                            mRootRunScriptExecuting = true;
                            return true;
                        }
                    }

                    @Override public void onAuthorizationTimeout() {
                        policy.suspendPending(ownedAttempt,
                                RootRunScriptAuthorizationPolicy.Reason.TIMEOUT);
                        notifyRootRunScriptPromptStatusChanged();
                    }
                };
                if (!authorization.isCurrent()) return;
                List<FolderScriptOutcome> outcomes = reservation.run(folder, event, authorization);
                result = RootRunScriptAuthorizationPolicy.Result.SUCCESS;
                for (FolderScriptOutcome outcome : outcomes) {
                    if (!outcome.succeeded()) {
                        result = RootRunScriptAuthorizationPolicy.Result.SCRIPT_FAILED;
                        logRootRunScriptFailure("Root Run Script '" + outcome.scriptName()
                                + "' exited with status " + outcome.exitStatus(), null);
                    }
                }
            } catch (FolderOperationException failure) {
                switch (failure.failure()) {
                    case ROOT_SCRIPT_AUTH_DENIED:
                        result = RootRunScriptAuthorizationPolicy.Result.DENIED; break;
                    case ROOT_SCRIPT_AUTH_LOST:
                        result = RootRunScriptAuthorizationPolicy.Result.LOST; break;
                    case ROOT_SCRIPT_AUTH_TIMEOUT:
                        result = RootRunScriptAuthorizationPolicy.Result.TIMEOUT; break;
                    case ROOT_SCRIPT_AUTH_UNAVAILABLE:
                        result = RootRunScriptAuthorizationPolicy.Result.UNAVAILABLE; break;
                    case FOLDER_OPERATION_CANCELLED:
                        result = RootRunScriptAuthorizationPolicy.Result.CANCELLED; break;
                    default:
                        result = RootRunScriptAuthorizationPolicy.Result.SCRIPT_FAILED;
                }
                logRootRunScriptFailure("Root Run Script failed: " + failure.failure(), failure);
            } catch (RuntimeException failure) {
                result = RootRunScriptAuthorizationPolicy.Result.UNAVAILABLE;
                logRootRunScriptFailure("Root Run Script worker failed", failure);
            } finally {
                // A timeout observed elsewhere never releases these gates. The worker releases
                // them only after the semantic backend call has actually returned.
                if (attempt != null) policy.complete(attempt, result);
                reservation.close();
                releaseAdmission.run();
                synchronized (mRootScriptLifecycleLock) {
                    mRootRunScriptWorker = null;
                    mRootRunScriptExecuting = false;
                    mRootRunScriptWorkerActive.set(false);
                }
                notifyRootRunScriptPromptStatusChanged();
            }
        }, "root-run-script");
        worker.setDaemon(true);
        synchronized (mRootScriptLifecycleLock) {
            if (!rootScriptWorkerCurrent(lifecycleGeneration, scriptEpoch)) {
                mRootRunScriptWorkerActive.set(false);
                return RootRunScriptDispatchDecision.OBSOLETE;
            }
            mRootRunScriptWorker = worker;
            try {
                mRootScriptWorkerStarter.start(worker);
                return RootRunScriptDispatchDecision.ACCEPTED;
            } catch (RuntimeException failure) {
                mRootRunScriptWorker = null;
                mRootRunScriptWorkerActive.set(false);
                Log.w(TAG, "Root Run Script worker could not start", failure);
                return RootRunScriptDispatchDecision.UNAVAILABLE;
            }
        }
    }

    /** Diagnostic logging must not replace the typed terminal result or skip gate release. */
    private static void logRootRunScriptFailure(String message, @Nullable Throwable failure) {
        try {
            if (failure == null) Log.w(TAG, message);
            else Log.w(TAG, message, failure);
        } catch (RuntimeException loggingFailure) {
            // Android's JVM test stubs can reject Log calls; lifecycle cleanup still must run.
        }
    }

    /** Reports whether a mode request still owns admission or is preparing authorization. */
    public boolean isExecutionModeTransitionBusy() {
        return mModeTransitionRequest != null
                || mExecutionModeTransitionReservation != null
                || mRootStartupAuthorizationPending;
    }

    /** Returns whether Normal Mode startup is suppressed pending explicit Managed State repair. */
    public boolean isStateAccessRepairRequired() {
        return mStateAccessRepairRequired;
    }

    public boolean isInotifyWatchLimitEnabled() {
        return mPreferences.getBoolean(Constants.PREF_INOTIFY_WATCH_LIMIT, false);
    }

    /** Applies or disables optional inotify reapplication; enabling explicitly may request root. */
    public void requestInotifyWatchLimit(
            boolean enabled,
            OnInotifyWatchLimitResultListener listener
    ) {
        if (mHandler == null) {
            listener.onComplete(isInotifyWatchLimitEnabled(), false,
                    "The service is not ready to apply inotify tuning.");
            return;
        }
        mHandler.post(() -> {
            if (mModeTransitionRequest != null || mExecutionModeTransitionReservation != null
                    || FileMutationBarrier.executionModeTransitionPending()) {
                listener.onComplete(isInotifyWatchLimitEnabled(), false,
                        "Inotify tuning is unavailable during an execution-mode transition.");
                return;
            }
            Thread worker = new Thread(() -> {
                String failure = null;
                boolean succeeded = false;
                try {
                    if (enabled) {
                        mRuntime.verifyRootAuthorization();
                        TuningOutcome outcome = mRuntime.applyInotifyWatchLimit();
                        succeeded = outcome.status() == TuningOutcome.Status.APPLIED;
                        if (!succeeded) failure = outcome.detail();
                    } else {
                        succeeded = true;
                    }
                    if (succeeded && !mPreferences.edit()
                            .putBoolean(Constants.PREF_INOTIFY_WATCH_LIMIT, enabled)
                            .commit()) {
                        succeeded = false;
                        failure = "The inotify preference could not be saved.";
                    }
                } catch (RootTransportException error) {
                    failure = error.failure().name();
                } catch (IOException | RuntimeException error) {
                    Log.e(TAG, "Inotify tuning request failed", error);
                    failure = error.getMessage();
                }
                boolean result = succeeded;
                String detail = failure == null
                        ? (enabled ? "Inotify watch limit applied." : "Automatic reapplication disabled.")
                        : failure;
                if (!mHandler.post(() -> listener.onComplete(enabled, result, detail))) {
                    Log.e(TAG, "Inotify tuning result could not return to the service thread");
                }
            }, "Explicit inotify watch-limit request");
            worker.setDaemon(true);
            try {
                worker.start();
            } catch (RuntimeException startFailure) {
                Log.e(TAG, "Could not start inotify tuning worker", startFailure);
                listener.onComplete(isInotifyWatchLimitEnabled(), false,
                        "The inotify tuning worker could not start.");
            }
        });
    }

    /** Reapplies a saved tuning choice in the background without requesting new root access. */
    private void reapplyInotifyLimitIfAuthorized() {
        if (!mPreferences.getBoolean(Constants.PREF_INOTIFY_WATCH_LIMIT, false)) return;
        Thread worker = new Thread(() -> {
            TuningOutcome outcome = mRuntime.applyInotifyWatchLimitIfAuthorized();
            Log.i(TAG, "Optional inotify reapplication: " + outcome.status() + " — "
                    + outcome.detail());
        }, "Optional inotify watch-limit reapplication");
        worker.setDaemon(true);
        try {
            worker.start();
        } catch (RuntimeException error) {
            Log.w(TAG, "Optional inotify reapplication could not be scheduled", error);
        }
    }

    public void registerExecutionModeTransitionStatusListener(
            OnExecutionModeTransitionStatusChangedListener listener
    ) {
        mModeTransitionStatusListeners.add(listener);
        listener.onExecutionModeTransitionStatusChanged(mModeTransitionStatus);
    }

    public void unregisterExecutionModeTransitionStatusListener(
            OnExecutionModeTransitionStatusChangedListener listener
    ) {
        mModeTransitionStatusListeners.remove(listener);
    }

    /** Requests a user-confirmed change of the device-local execution mode. */
    public void requestExecutionModeTransition(boolean useRoot) {
        postTransitionCallback(() -> beginModeTransition(
                new ModeTransitionRequest(useRoot, ModeTransitionPurpose.CHANGE_MODE)
        ));
    }

    /** Retries the transition, committed-mode reconciliation, state repair, or startup in status. */
    public void retryExecutionModeTransition() {
        postTransitionCallback(() -> {
            if (mExecutionModeReconciliationRetryRequired
                    && mModeTransitionRequest == null) {
                mExecutionModeReconciliationRetryRequired = false;
                mExecutionModeReconciliationPending = false;
                reconcileRuntimeExecutionModeFromPreferences();
                return;
            }
            ModeTransitionRequest current = mModeTransitionRequest;
            if (current == null && durableExecutionMode() && runtimeExecutionMode()) {
                retryRootSelectedStartupOnServiceThread();
                return;
            }
            if (current != null && current.purpose == ModeTransitionPurpose.REPAIR_STATE
                    && current.shutdownProven && current.gatesClosed) {
                onModeTransitionStatusChanged(modeStatus(
                        ExecutionModeTransitionStatus.Phase.PREPARING_ROOT,
                        ExecutionModeTransitionStatus.FailureKind.NONE,
                        null,
                        null,
                        "Retrying explicit Managed State repair.",
                        current,
                        false,
                        false
                ));
                runRootAuthorizationPreflight(
                        () -> repairManagedStateForTransition(current),
                        failure -> onModeTransitionStatusChanged(modeStatus(
                                ExecutionModeTransitionStatus.Phase.FAILED,
                                ExecutionModeTransitionStatus.FailureKind.ROOT,
                                failure,
                                ManagedStateFailure.STATE_REPAIR_FAILED,
                                "Root authorization failed. Retry repair explicitly.",
                                current,
                                true,
                                false
                        ))
                );
                return;
            }
            if (current != null && current.shutdownProven
                    && current.purpose == ModeTransitionPurpose.CHANGE_MODE
                    && !current.targetUseRoot
                    && (mModeTransitionStatus.phase()
                    == ExecutionModeTransitionStatus.Phase.WAITING_FOR_DISABLE_CONFIRMATION
                    || mModeTransitionStatus.phase()
                    == ExecutionModeTransitionStatus.Phase.FAILED)) {
                retryManagedStateRepair(current);
                return;
            }
            if (current != null && current.shutdownProven
                    && durableExecutionMode()
                    == current.targetUseRoot) {
                finishCommittedModeTransition(current);
                return;
            }
            boolean target = mModeTransitionStatus.requestedUseRoot();
            if (mStateAccessRepairRequired && !target) {
                beginModeTransition(new ModeTransitionRequest(
                        false, ModeTransitionPurpose.REPAIR_STATE
                ));
            } else {
                beginModeTransition(new ModeTransitionRequest(
                        target, ModeTransitionPurpose.CHANGE_MODE
                ));
            }
        });
    }

    /** Confirms the exceptional path that disables root mode without restoring app access. */
    public void confirmDisableAnyway() {
        postTransitionCallback(() -> {
            ModeTransitionRequest request = mModeTransitionRequest;
            if (request == null || request.purpose != ModeTransitionPurpose.CHANGE_MODE
                    || !ExecutionModeTransitionPolicy.mayConfirmDisableAnyway(
                    mModeTransitionStatus,
                    true,
                    request.targetUseRoot,
                    request.shutdownProven,
                    request.gatesClosed
            )) {
                return;
            }
            request.disableAnywayConfirmed = true;
            persistTransitionMode(request, true);
        });
    }

    /** Explicitly retries root repair while Normal Mode remains the committed selection. */
    public void requestStateAccessRepair() {
        postTransitionCallback(() -> {
            if (!mStateAccessRepairRequired) {
                ensureStartupStateAccessProbe();
                return;
            }
            beginModeTransition(new ModeTransitionRequest(
                    false, ModeTransitionPurpose.REPAIR_STATE
            ));
        });
    }

    /** Explicitly retries a root-selected startup after the old execution is safely stopped. */
    public void retryRootSelectedStartup() {
        postTransitionCallback(this::retryRootSelectedStartupOnServiceThread);
    }

    private void retryRootSelectedStartupOnServiceThread() {
        if (!runtimeExecutionMode() || mModeTransitionRequest != null
                || mExecutionModeTransitionReservation != null
                || mRootStartupAuthorizationPending || mStartupConfigLoadPending
                || mDestroying) {
            return;
        }
        if (!mLastDeterminedShouldRun) {
            if (mModeTransitionStatus.retryAvailable()
                    && mModeTransitionStatus.requestedUseRoot()) {
                onModeTransitionStatusChanged(ExecutionModeTransitionStatus.idle(
                        mStateAccessRepairRequired
                ));
            }
            return;
        }
        if (mCurrentState == State.STARTING || mCurrentState == State.ACTIVE) return;
        mRootStartupAuthorizationAttempted = false;
        mRootStartupAuthorizationComplete = false;
        if (mCurrentState == State.ERROR) {
            onModeTransitionStatusChanged(new ExecutionModeTransitionStatus(
                    ExecutionModeTransitionStatus.Phase.STOPPING,
                    ExecutionModeTransitionStatus.FailureKind.NONE,
                    null,
                    null,
                    "Proving the previous startup has stopped before retrying.",
                    true,
                    false,
                    false,
                    mStateAccessRepairRequired
            ));
            Runnable retryStartup = () -> beginRootSelectedStartup(
                    SyncthingCommand.SERVE, true
            );
            ExecutionModeTransitionTestSeam seam = mExecutionModeTransitionTestSeam;
            if (seam == null) {
                shutdown(State.DISABLED, retryStartup, true);
            } else {
                long lifecycleGeneration = mLifecycleGeneration;
                seam.shutdown(safe -> postTransitionCallback(() -> {
                    if (mDestroying || lifecycleGeneration != mLifecycleGeneration) return;
                    mShutdownExitProven = safe;
                    mShutdownSafetyEstablished = safe;
                    if (!safe) {
                        failRootStartup(
                                ExecutionModeTransitionStatus.FailureKind.EXECUTION_UNRESOLVED,
                                null,
                                null,
                                "The previous root startup could not be proven stopped. Retry after resolving the execution state."
                        );
                        return;
                    }
                    mLastExecutionExitProven = true;
                    onServiceStateChange(State.DISABLED);
                    retryStartup.run();
                }));
            }
            return;
        }
        beginRootSelectedStartup(SyncthingCommand.SERVE, true);
    }

    private void beginModeTransition(ModeTransitionRequest request) {
        if (mExecutionModeReconciliationPending) {
            onModeTransitionStatusChanged(new ExecutionModeTransitionStatus(
                    ExecutionModeTransitionStatus.Phase.FAILED,
                    ExecutionModeTransitionStatus.FailureKind.BUSY,
                    null,
                    null,
                    "Runtime reconciliation must finish before another mode transition.",
                    request.targetUseRoot,
                    true,
                    false,
                    mStateAccessRepairRequired
            ));
            return;
        }
        if (mModeTransitionRequest != null || mExecutionModeTransitionReservation != null
                || mModeTransitionStatus.busy()) {
            onModeTransitionStatusChanged(new ExecutionModeTransitionStatus(
                    ExecutionModeTransitionStatus.Phase.FAILED,
                    ExecutionModeTransitionStatus.FailureKind.BUSY,
                    null,
                    null,
                    "Another lifecycle or execution-mode operation is still active.",
                    request.targetUseRoot,
                    true,
                    false,
                    mStateAccessRepairRequired
            ));
            return;
        }
        if (request.purpose == ModeTransitionPurpose.REPAIR_STATE
                && (!mStateAccessRepairRequired || runtimeExecutionMode())) {
            onModeTransitionStatusChanged(ExecutionModeTransitionStatus.idle(false));
            return;
        }
        if (request.purpose == ModeTransitionPurpose.CHANGE_MODE
                && request.targetUseRoot == runtimeExecutionMode()
                && !mStateAccessRepairRequired) {
            onModeTransitionStatusChanged(ExecutionModeTransitionStatus.idle(false));
            return;
        }

        synchronized (mRootScriptLifecycleLock) {
            // This is the transition's root-script cancellation point. A worker that has not
            // claimed its helper boundary cannot initiate a new authorization afterward.
            mModeTransitionRequest = request;
            ++mRootScriptLifecycleEpoch;
            RootRunScriptAuthorizationPolicy scriptPolicy = mRootRunScriptPromptPolicy;
            if (scriptPolicy != null) scriptPolicy.invalidate();
            Thread admittedScriptWorker = mRootRunScriptWorker;
            if (admittedScriptWorker != null && !mRootRunScriptExecuting) {
                admittedScriptWorker.interrupt();
            }
        }
        if (request.targetUseRoot || request.purpose == ModeTransitionPurpose.REPAIR_STATE) {
            onModeTransitionStatusChanged(modeStatus(
                    ExecutionModeTransitionStatus.Phase.PREPARING_ROOT,
                    ExecutionModeTransitionStatus.FailureKind.NONE,
                    null,
                    null,
                    "Checking root authorization.",
                    request,
                    false,
                    false
            ));
            runRootAuthorizationPreflight(
                    () -> {
                        if (mModeTransitionRequest != request || mDestroying) return;
                        request.rootAuthorizationSucceeded = true;
                        admitModeTransition(request);
                    },
                    failure -> failBeforeCommit(
                            request,
                            ExecutionModeTransitionStatus.FailureKind.ROOT,
                            failure,
                            null,
                            "Root authorization was not confirmed. Normal Mode remains active."
                    )
            );
        } else {
            admitModeTransition(request);
        }
    }

    private interface RootAuthorizationFailure {
        void onFailure(RootFailure failure);
    }

    private void runRootAuthorizationPreflight(
            Runnable onSuccess,
            RootAuthorizationFailure onFailure
    ) {
        long lifecycleGeneration = mLifecycleGeneration;
        boolean workerStarted = executeTransitionWorker("Execution Mode root authorization", () -> {
            RootFailure failure = null;
            try {
                ExecutionModeTransitionTestSeam seam = mExecutionModeTransitionTestSeam;
                if (seam != null) failure = seam.authorizeRoot();
                else mRuntime.verifyRootAuthorization();
            } catch (RootTransportException rootFailure) {
                failure = rootFailure.failure();
            } catch (IOException | RuntimeException error) {
                failure = RootFailure.ROOT_TRANSPORT_FAILED;
                Log.e(TAG, "Root authorization preflight failed", error);
            }
            RootFailure result = failure;
            if (!postTransitionCallback(() -> {
                if (mDestroying || lifecycleGeneration != mLifecycleGeneration) return;
                if (result == null) onSuccess.run();
                else onFailure.onFailure(result);
            })) {
                Log.e(TAG, "Root authorization result could not return to the service thread");
            }
        });
        if (!workerStarted) {
            Log.e(TAG, "Could not start root authorization worker");
            if (!mDestroying && lifecycleGeneration == mLifecycleGeneration) {
                onFailure.onFailure(RootFailure.ROOT_TRANSPORT_FAILED);
            }
        }
    }

    private void admitModeTransition(ModeTransitionRequest request) {
        if (mModeTransitionRequest != request) return;
        if (mDestroying || mFileMutationBarrier != null || mCertificateMutationPending
                || mCertificateStorageReservation != null
                || FileMutationBarrier.certificateRestorePending()
                || mCertificateRestoreDeferredStart.isPending()
                || mPostMutationStartupGate.ownsStartup()
                || mDatabaseResetOwnership.isReserved() || mShutdownInProgress
                || mStartingShutdownDeferral.isPending()) {
            failBeforeCommit(
                    request,
                    ExecutionModeTransitionStatus.FailureKind.BUSY,
                    null,
                    null,
                    "Another lifecycle owner prevents an atomic mode transition."
            );
            return;
        }
        FileMutationBarrier.ExecutionModeTransitionReservation reservation =
                FileMutationBarrier.tryReserveExecutionModeTransition();
        if (reservation == null) {
            failBeforeCommit(
                    request,
                    ExecutionModeTransitionStatus.FailureKind.BUSY,
                    null,
                    null,
                    "Certificate recovery or another mode transition owns admission."
            );
            return;
        }
        mExecutionModeTransitionReservation = reservation;
        request.transitionRestApi = mRestApi;
        onModeTransitionStatusChanged(modeStatus(
                ExecutionModeTransitionStatus.Phase.QUIESCING,
                ExecutionModeTransitionStatus.FailureKind.NONE,
                null,
                null,
                "Waiting for admitted backend work and Run Scripts to finish.",
                request,
                false,
                false
        ));
        beginTransitionBackgroundOperation(request);
        boolean workerStarted = executeTransitionWorker("Execution Mode admission drain", () -> {
            boolean scriptsDrained = false;
            boolean backendDrained = false;
            try {
                RestApi api = request.transitionRestApi;
                ExecutionModeTransitionTestSeam seam = mExecutionModeTransitionTestSeam;
                scriptsDrained = seam != null
                        ? seam.closeScriptAdmission(2_000)
                        : api == null || api.closeFolderScriptAdmissionForModeTransition(2_000);
                backendDrained = closeBackendOperationAdmission(5_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                Log.w(TAG, "Execution Mode admission drain was interrupted", interrupted);
            } catch (RuntimeException error) {
                Log.e(TAG, "Execution Mode admission drain failed", error);
            }
            boolean scriptsReady = scriptsDrained;
            boolean backendReady = backendDrained;
            finishTransitionBackgroundOperation(request);
            if (!postTransitionCallback(() -> onModeTransitionAdmissionsDrained(
                    request, scriptsReady, backendReady
            ))) {
                if (!mDestroying) {
                    reopenBackendOperationAdmissionWhenDrained();
                    RestApi api = request.transitionRestApi;
                    if (api != null) api.reopenFolderScriptAdmissionAfterModeTransitionFailure();
                    reservation.release();
                    mExecutionModeTransitionReservation = null;
                }
            }
        });
        if (!workerStarted) {
            finishTransitionBackgroundOperation(request);
            Log.e(TAG, "Could not start Execution Mode admission drain");
            failBeforeCommit(
                    request,
                    ExecutionModeTransitionStatus.FailureKind.BUSY,
                    null,
                    null,
                    "The transition could not safely wait for admitted work."
            );
        }
    }

    private void onModeTransitionAdmissionsDrained(
            ModeTransitionRequest request,
            boolean scriptsDrained,
            boolean backendDrained
    ) {
        if (mModeTransitionRequest != request) return;
        if (!scriptsDrained || !backendDrained) {
            failBeforeCommit(
                    request,
                    ExecutionModeTransitionStatus.FailureKind.BUSY,
                    null,
                    null,
                    "Admitted work did not reach a safe terminal result before the transition deadline."
            );
            return;
        }
        request.gatesClosed = true;
        onModeTransitionStatusChanged(modeStatus(
                ExecutionModeTransitionStatus.Phase.STOPPING,
                ExecutionModeTransitionStatus.FailureKind.NONE,
                null,
                null,
                "Stopping and verifying the currently owned Syncthing execution.",
                request,
                false,
                false
        ));
        request.shutdownStarted = true;
        shutdownForModeTransition(request);
    }

    private void shutdownForModeTransition(ModeTransitionRequest request) {
        ExecutionModeTransitionTestSeam seam = mExecutionModeTransitionTestSeam;
        if (seam == null) {
            shutdown(
                    State.DISABLED,
                    () -> onModeTransitionShutdownProven(request),
                    true,
                    false,
                    () -> onModeTransitionShutdownFailed(request)
            );
            return;
        }
        seam.shutdown(safe -> {
            mShutdownExitProven = safe;
            mShutdownSafetyEstablished = safe;
            if (safe) {
                onServiceStateChange(State.DISABLED);
                onModeTransitionShutdownProven(request);
            }
            else onModeTransitionShutdownFailed(request);
        });
    }

    private void onModeTransitionShutdownProven(ModeTransitionRequest request) {
        if (mModeTransitionRequest != request) return;
        request.shutdownProven = mShutdownExitProven && mShutdownSafetyEstablished;
        if (!request.shutdownProven) {
            failBeforeCommit(
                    request,
                    ExecutionModeTransitionStatus.FailureKind.EXECUTION_UNRESOLVED,
                    null,
                    null,
                    "Shutdown did not prove a safe terminal execution result."
            );
            return;
        }
        if (request.purpose == ModeTransitionPurpose.REPAIR_STATE
                || !request.targetUseRoot && !request.disableAnywayConfirmed) {
            // Disabling Superuser Mode is an explicit user request. The bounded repair worker
            // acquires its own operation-scoped shell, so a separate root preflight cannot race
            // authorization revocation or trigger a second prompt.
            repairManagedStateForTransition(request, true);
        } else {
            persistTransitionMode(request, request.disableAnywayConfirmed);
        }
    }

    private void repairManagedStateForTransition(ModeTransitionRequest request) {
        repairManagedStateForTransition(
                request,
                request.purpose == ModeTransitionPurpose.REPAIR_STATE
        );
    }

    private void repairManagedStateForTransition(
            ModeTransitionRequest request,
            boolean allowExplicitAuthorization
    ) {
        onModeTransitionStatusChanged(modeStatus(
                ExecutionModeTransitionStatus.Phase.REPAIRING_STATE,
                ExecutionModeTransitionStatus.FailureKind.NONE,
                null,
                null,
                "Restoring application access to the Managed State files.",
                request,
                false,
                false
        ));
        beginTransitionBackgroundOperation(request);
        boolean workerStarted = executeTransitionWorker("Managed State mode repair", () -> {
            ManagedStateException failure = null;
            ExecutionModeTransitionTestSeam seam = mExecutionModeTransitionTestSeam;
            if (seam != null) {
                failure = seam.repairState(allowExplicitAuthorization);
                if (failure == null && !seam.applicationStateAccessible()) {
                    failure = new ManagedStateException(
                            ManagedStateFailure.STATE_REPAIR_FAILED,
                            "Managed State access verification failed after repair"
                    );
                }
            } else {
                try {
                    if (allowExplicitAuthorization) {
                        mRuntime.repairManagedStateAccessDuringModeTransition();
                    } else {
                        if (!mRuntime.isRootAuthorizationAvailableWithoutPrompt()) {
                            throw new ManagedStateException(
                                    ManagedStateFailure.STATE_REPAIR_FAILED,
                                    "Previously authorized root access is unavailable for automatic mode-disable repair",
                                    new RootTransportException(
                                            RootFailure.ROOT_UNAVAILABLE,
                                            "Disable repair does not trigger a new root authorization prompt"
                                    )
                            );
                        }
                        mRuntime.repairManagedStateAccessDuringModeTransitionIfAuthorized();
                    }
                    if (!mRuntime.isAppUidManagedStateAccessible()) {
                        throw new ManagedStateException(
                                ManagedStateFailure.STATE_REPAIR_FAILED,
                                "Managed State access verification failed after repair"
                        );
                    }
                } catch (ManagedStateException error) {
                    failure = error;
                } catch (RuntimeException error) {
                    failure = new ManagedStateException(
                            ManagedStateFailure.STATE_REPAIR_FAILED,
                            "Managed State repair failed",
                            error
                    );
                }
            }
            ManagedStateException result = failure;
            finishTransitionBackgroundOperation(request);
            if (!postTransitionCallback(() -> onManagedStateRepairFinished(request, result))) {
                Log.e(TAG, "Managed State repair result could not return to the service thread");
            }
        });
        if (!workerStarted) {
            finishTransitionBackgroundOperation(request);
            Log.e(TAG, "Could not start Managed State repair worker");
            onManagedStateRepairFinished(request, new ManagedStateException(
                    ManagedStateFailure.STATE_REPAIR_FAILED,
                    "Managed State repair worker could not start"
            ));
        }
    }

    private void onManagedStateRepairFinished(
            ModeTransitionRequest request,
            @Nullable ManagedStateException failure
    ) {
        if (mModeTransitionRequest != request) return;
        if (failure != null) {
            boolean disableRequest = request.purpose == ModeTransitionPurpose.CHANGE_MODE
                    && !request.targetUseRoot;
            RootFailure rootFailure = rootFailureFrom(failure);
            boolean disableAnywayAvailable = disableRequest
                    && ExecutionModeTransitionPolicy.mayOfferDisableAnyway(
                            failure.failure(), rootFailure
                    );
            if (disableAnywayAvailable) {
                onModeTransitionStatusChanged(modeStatus(
                        ExecutionModeTransitionStatus.Phase.WAITING_FOR_DISABLE_CONFIRMATION,
                        ExecutionModeTransitionStatus.FailureKind.STATE_REPAIR_UNAVAILABLE,
                        rootFailure,
                        failure.failure(),
                        "Root authorization is unavailable for Managed State repair. Retry or confirm Disable anyway.",
                        request,
                        true,
                        true
                ));
            } else {
                if (!disableRequest) mStateAccessRepairRequired = true;
                onModeTransitionStatusChanged(modeStatus(
                        ExecutionModeTransitionStatus.Phase.FAILED,
                        rootFailure == null
                                ? ExecutionModeTransitionStatus.FailureKind.STATE_REPAIR_UNAVAILABLE
                                : ExecutionModeTransitionStatus.FailureKind.ROOT,
                        rootFailure,
                        failure.failure(),
                        disableRequest
                                ? "Managed State repair failed. Retry repair; Disable anyway is unavailable for this failure."
                                : "Managed State repair failed. Normal Mode remains suppressed.",
                        request,
                        true,
                        false
                ));
                if (!disableRequest) releaseTransitionAdmissionAfterFailure(request, true);
            }
            return;
        }
        request.stateRepairSucceeded = true;
        if (request.purpose == ModeTransitionPurpose.REPAIR_STATE) {
            mStateAccessRepairRequired = false;
            mStartupStateAccessProbeComplete = true;
            finishSuccessfulModeTransition(request, true);
        } else {
            persistTransitionMode(request, false);
        }
    }

    private void retryManagedStateRepair(ModeTransitionRequest request) {
        if (mModeTransitionRequest != request || !request.shutdownProven
                || !request.gatesClosed) return;
        onModeTransitionStatusChanged(modeStatus(
                ExecutionModeTransitionStatus.Phase.PREPARING_ROOT,
                ExecutionModeTransitionStatus.FailureKind.NONE,
                null,
                null,
                "Retrying root authorization for Managed State repair.",
                request,
                false,
                false
        ));
        // The explicit Retry owns the authorization prompt and the repair in one bounded
        // operation. A separate preflight followed by a second `su` launch could lose the
        // just-authorized capability between those requests.
        repairManagedStateForTransition(request, true);
    }

    private void persistTransitionMode(ModeTransitionRequest request, boolean disableAnyway) {
        if (mDestroying || mModeTransitionRequest != request) return;
        onModeTransitionStatusChanged(modeStatus(
                ExecutionModeTransitionStatus.Phase.COMMITTING,
                ExecutionModeTransitionStatus.FailureKind.NONE,
                null,
                null,
                "Persisting the selected execution mode.",
                request,
                false,
                false
        ));
        long lifecycleGeneration = mLifecycleGeneration;
        beginTransitionBackgroundOperation(request);
        boolean workerStarted = executeTransitionWorker("Execution Mode preference commit", () -> {
            boolean commitAdmitted;
            synchronized (request) {
                commitAdmitted = !request.cancelled && !mDestroying
                        && lifecycleGeneration == mLifecycleGeneration
                        && mModeTransitionRequest == request;
                if (commitAdmitted) request.commitStarted = true;
            }
            boolean committed = false;
            if (commitAdmitted) {
                try {
                    // Device-local consent must be durably off before Normal Mode is committed.
                    // A mode commit failure can leave root selected with prompts off, which is safe.
                    RootRunScriptAuthorizationPolicy scriptPolicy = mRootRunScriptPromptPolicy;
                    boolean scriptConsentCleared = request.targetUseRoot
                            || (scriptPolicy != null
                                    ? scriptPolicy.disable()
                                    : mExecutionModeTransitionTestSeam != null);
                    ExecutionModeTransitionTestSeam seam = mExecutionModeTransitionTestSeam;
                    committed = scriptConsentCleared && (seam != null
                            ? seam.commitMode(request.targetUseRoot)
                            : ExecutionModePreferenceCommit.commit(
                                    mPreferences, request.targetUseRoot
                            ));
                    if (scriptConsentCleared && !request.targetUseRoot) {
                        notifyRootRunScriptPromptStatusChanged();
                    }
                } catch (RuntimeException error) {
                    Log.e(TAG, "Execution Mode preference commit failed", error);
                }
            }
            boolean result = committed;
            finishTransitionBackgroundOperation(request);
            if (!postTransitionCallback(() -> {
                if (mDestroying || lifecycleGeneration != mLifecycleGeneration
                        || mModeTransitionRequest != request) return;
                if (!result) {
                    failBeforeCommit(
                            request,
                            ExecutionModeTransitionStatus.FailureKind.PREFERENCE_PERSISTENCE,
                            null,
                            null,
                            "Execution Mode could not be durably saved; the backend was not switched."
                    );
                    return;
                }
                if (disableAnyway) {
                    mStateAccessRepairRequired = true;
                } else if (request.stateRepairSucceeded) {
                    mStateAccessRepairRequired = false;
                }
                finishCommittedModeTransition(request);
            })) {
                Log.e(TAG, "Execution Mode preference result could not return to the service thread");
            }
        });
        if (!workerStarted) {
            finishTransitionBackgroundOperation(request);
            Log.e(TAG, "Could not start Execution Mode preference worker");
            failBeforeCommit(
                    request,
                    ExecutionModeTransitionStatus.FailureKind.PREFERENCE_PERSISTENCE,
                    null,
                    null,
                    "Execution Mode could not be durably saved; the backend was not switched."
            );
        }
    }

    private void finishCommittedModeTransition(ModeTransitionRequest request) {
        if (mModeTransitionRequest != request || !request.shutdownProven || !request.gatesClosed) {
            return;
        }
        boolean useRoot = durableExecutionMode();
        if (useRoot != request.targetUseRoot) {
            onModeTransitionStatusChanged(modeStatus(
                    ExecutionModeTransitionStatus.Phase.FAILED,
                    ExecutionModeTransitionStatus.FailureKind.PREFERENCE_PERSISTENCE,
                    null,
                    null,
                    "The durable execution mode does not match the completed request.",
                    request,
                    true,
                    false
            ));
            return;
        }
        try {
            selectRuntimeExecutionMode(useRoot);
        } catch (RuntimeException error) {
            if (mExecutionModeTransitionTestSeam == null) {
                Log.e(TAG, "Committed mode could not be selected in the runtime", error);
            }
            onModeTransitionStatusChanged(modeStatus(
                    ExecutionModeTransitionStatus.Phase.FAILED,
                    ExecutionModeTransitionStatus.FailureKind.STARTUP,
                    null,
                    null,
                    "The committed backend could not be selected. Retry the transition.",
                    request,
                    true,
                    false
            ));
            return;
        }
        invalidateStartupStateAccessProbe();
        if (!useRoot && request.stateRepairSucceeded) {
            mStartupStateAccessProbeComplete = true;
            mStateAccessRepairRequired = false;
        }
        mRootStartupAuthorizationComplete = useRoot && request.rootAuthorizationSucceeded;
        if (useRoot) {
            mRootStartupAuthorizationAttempted = true;
            mRootStartupStatusPending = mLastDeterminedShouldRun;
        }
        finishSuccessfulModeTransition(request, true);
    }

    private void finishSuccessfulModeTransition(
            ModeTransitionRequest request,
            boolean launchIfRequired
    ) {
        if (mModeTransitionRequest != request) return;
        boolean rootMode = runtimeExecutionMode();
        if (rootMode || (request.stateRepairSucceeded && !mStateAccessRepairRequired)) {
            openBackendOperationAdmission();
        }
        releaseModeTransitionReservation();
        mModeTransitionRequest = null;
        if (mRootStartupStatusPending) {
            onModeTransitionStatusChanged(new ExecutionModeTransitionStatus(
                    ExecutionModeTransitionStatus.Phase.PREPARING_ROOT,
                    ExecutionModeTransitionStatus.FailureKind.NONE,
                    null,
                    null,
                    "Root authorization succeeded; waiting for the selected backend to become ready.",
                    true,
                    false,
                    false,
                    mStateAccessRepairRequired
            ));
        } else {
            onModeTransitionStatusChanged(ExecutionModeTransitionStatus.idle(
                    mStateAccessRepairRequired
            ));
        }
        if (!rootMode && !request.stateRepairSucceeded) {
            ensureStartupStateAccessProbe();
        }
        if (launchIfRequired && (rootMode || !mStateAccessRepairRequired)
                && !mStartupStateAccessProbePending && mLastDeterminedShouldRun
                && mCurrentState == State.DISABLED) {
            launchModeTransitionStartup(SyncthingCommand.SERVE);
        }
    }

    private void onModeTransitionShutdownFailed(ModeTransitionRequest request) {
        if (mModeTransitionRequest != request) return;
        failBeforeCommit(
                request,
                ExecutionModeTransitionStatus.FailureKind.EXECUTION_UNRESOLVED,
                null,
                null,
                "The existing Syncthing execution could not be proven stopped; the mode was not changed."
        );
    }

    private void failBeforeCommit(
            ModeTransitionRequest request,
            ExecutionModeTransitionStatus.FailureKind failureKind,
            @Nullable RootFailure rootFailure,
            @Nullable ManagedStateFailure managedStateFailure,
            String detail
    ) {
        if (mModeTransitionRequest != request) return;
        onModeTransitionStatusChanged(modeStatus(
                ExecutionModeTransitionStatus.Phase.FAILED,
                failureKind,
                rootFailure,
                managedStateFailure,
                detail,
                request,
                true,
                false
        ));
        releaseTransitionAdmissionAfterFailure(request, true);
    }

    private void releaseTransitionAdmissionAfterFailure(
            ModeTransitionRequest request,
        boolean clearRequest
    ) {
        if (mExecutionModeTransitionReservation != null) {
            if (!mStateAccessRepairRequired || runtimeExecutionMode()) {
                reopenBackendOperationAdmissionWhenDrained();
            }
            RestApi api = request.transitionRestApi;
            if (api != null) api.reopenFolderScriptAdmissionAfterModeTransitionFailure();
            releaseModeTransitionReservation();
        }
        if (clearRequest && mModeTransitionRequest == request) {
            mModeTransitionRequest = null;
        }
        if (clearRequest && !mStateAccessRepairRequired && mLastDeterminedShouldRun
                && mCurrentState == State.DISABLED && !mShutdownInProgress) {
            Runnable retryStartup = () -> {
                if (mModeTransitionRequest == null
                        && mExecutionModeTransitionReservation == null) {
                    launchModeTransitionStartup(SyncthingCommand.SERVE);
                }
            };
            ExecutionModeTransitionTestSeam seam = mExecutionModeTransitionTestSeam;
            if (seam != null) seam.post(retryStartup);
            else if (mHandler != null) mHandler.postDelayed(retryStartup, 0);
        }
    }

    private void releaseModeTransitionReservation() {
        FileMutationBarrier.ExecutionModeTransitionReservation reservation =
                mExecutionModeTransitionReservation;
        mExecutionModeTransitionReservation = null;
        if (reservation != null) reservation.release();
    }

    private static void beginTransitionBackgroundOperation(ModeTransitionRequest request) {
        synchronized (request) {
            request.backgroundOperationsInFlight++;
        }
    }

    private void finishTransitionBackgroundOperation(ModeTransitionRequest request) {
        synchronized (request) {
            if (request.backgroundOperationsInFlight > 0) {
                request.backgroundOperationsInFlight--;
            }
        }
        if (mDestroying) maybeReleaseDestroyedTransitionReservation(request);
    }

    /** Retains process-wide launch admission until destroyed transition work and shutdown settle. */
    private void maybeReleaseDestroyedTransitionReservation(
            @Nullable ModeTransitionRequest request
    ) {
        if (!mDestroying) return;
        boolean workSettled;
        if (request == null) {
            workSettled = mRuntimeReconciliationShutdownSafe
                    && !mRuntimeReconciliationInFlight;
        } else {
            synchronized (request) {
                workSettled = request.destructionShutdownSafe
                        && request.backgroundOperationsInFlight == 0;
            }
        }
        if (!workSettled) return;
        synchronized (mExecutionModeReconciliationLock) {
            if (!mDestroying) return;
            releaseModeTransitionReservation();
        }
    }

    private ExecutionModeTransitionStatus modeStatus(
            ExecutionModeTransitionStatus.Phase phase,
            ExecutionModeTransitionStatus.FailureKind failureKind,
            @Nullable RootFailure rootFailure,
            @Nullable ManagedStateFailure managedStateFailure,
            @Nullable String detail,
            ModeTransitionRequest request,
            boolean retryAvailable,
            boolean disableAnywayAvailable
    ) {
        return new ExecutionModeTransitionStatus(
                phase,
                failureKind,
                rootFailure,
                managedStateFailure,
                detail,
                request.targetUseRoot,
                retryAvailable,
                disableAnywayAvailable,
                mStateAccessRepairRequired
        );
    }

    private void onModeTransitionStatusChanged(ExecutionModeTransitionStatus status) {
        mModeTransitionStatus = status;
        for (OnExecutionModeTransitionStatusChangedListener listener
                : new HashSet<>(mModeTransitionStatusListeners)) {
            listener.onExecutionModeTransitionStatusChanged(status);
        }
    }

    @Nullable
    private static RootFailure rootFailureFrom(ManagedStateException exception) {
        Throwable cause = exception.getCause();
        while (cause != null) {
            if (cause instanceof RootTransportException) {
                return ((RootTransportException) cause).failure();
            }
            cause = cause.getCause();
        }
        return null;
    }

    private void ensureStartupStateAccessProbe() {
        if (durableExecutionMode()) {
            mStartupStateAccessProbePending = false;
            return;
        }
        if (mStartupStateAccessProbeComplete || mStartupStateAccessProbePending) return;
        ExecutionModeTransitionTestSeam seam = mExecutionModeTransitionTestSeam;
        if (seam != null) {
            // The test seam supplies the same app-access verdict consumed by the production
            // worker, so integrated transition tests still exercise this launch gate.
            mStartupStateAccessProbeComplete = true;
            mStateAccessRepairRequired = !seam.applicationStateAccessible();
            return;
        }
        if (mHandler == null) return;
        mStartupStateAccessProbePending = true;
        long probeGeneration = ++mStartupStateAccessProbeGeneration;
        long lifecycleGeneration = mLifecycleGeneration;
        try {
            if (!mRuntime.closeBackendOperationAdmission(0)) {
                mRuntime.whenBackendOperationAdmissionDrained(() -> postTransitionCallback(() -> {
                    if (isCurrentStartupStateAccessProbe(
                            probeGeneration, lifecycleGeneration
                    )) {
                        startStartupStateAccessProbe(probeGeneration, lifecycleGeneration);
                    }
                }));
                return;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            mStartupStateAccessProbePending = false;
            return;
        }
        startStartupStateAccessProbe(probeGeneration, lifecycleGeneration);
    }

    /** Invalidates an asynchronous probe whose result belongs to an earlier backend selection. */
    private void invalidateStartupStateAccessProbe() {
        mStartupStateAccessProbeGeneration++;
        mStartupStateAccessProbePending = false;
        mStartupStateAccessProbeComplete = false;
    }

    private boolean isCurrentStartupStateAccessProbe(
            long probeGeneration,
            long lifecycleGeneration
    ) {
        return !mDestroying
                && lifecycleGeneration == mLifecycleGeneration
                && probeGeneration == mStartupStateAccessProbeGeneration
                && mStartupStateAccessProbePending
                && !durableExecutionMode()
                && !runtimeExecutionMode();
    }

    private void startStartupStateAccessProbe(
            long probeGeneration,
            long lifecycleGeneration
    ) {
        Thread worker = new Thread(() -> {
            boolean accessible;
            try {
                accessible = mRuntime.isAppUidManagedStateAccessible();
            } catch (RuntimeException error) {
                Log.e(TAG, "Could not inspect application access to Managed State", error);
                accessible = false;
            }
            boolean installationMarkerPresent = mPreferences.getBoolean(
                    Constants.PREF_INSTALLATION_MARKER, false
            );
            boolean configurationExists = Constants.getConfigFile(this).exists();
            boolean stateRepairRequired = InstallationRecognition.shouldRequireStateRepair(
                    accessible,
                    installationMarkerPresent,
                    false,
                    configurationExists
            );
            if (!postTransitionCallback(() -> {
                if (!isCurrentStartupStateAccessProbe(
                        probeGeneration, lifecycleGeneration
                )) return;
                mStartupStateAccessProbePending = false;
                mStartupStateAccessProbeComplete = true;
                mStateAccessRepairRequired = stateRepairRequired;
                if (!mStateAccessRepairRequired) {
                    try {
                        openBackendOperationAdmission();
                    } catch (RuntimeException failure) {
                        Log.e(TAG, "Verified Managed State access could not reopen admission",
                                failure);
                        mStateAccessRepairRequired = true;
                    }
                }
                onModeTransitionStatusChanged(ExecutionModeTransitionStatus.idle(
                        mStateAccessRepairRequired
                ));
                if (mStateAccessRepairRequired) {
                    Log.e(TAG, "Normal Mode startup is suppressed until Managed State is repaired");
                } else if (mLastDeterminedShouldRun
                        && mCurrentState == State.DISABLED
                        && mExecutionModeTransitionReservation == null) {
                    launchStartupTask(SyncthingCommand.SERVE);
                }
            })) {
                Log.e(TAG, "Managed State accessibility result could not return to the service thread");
            }
        }, "Managed State app access probe");
        worker.setDaemon(true);
        try {
            worker.start();
        } catch (RuntimeException error) {
            if (!isCurrentStartupStateAccessProbe(probeGeneration, lifecycleGeneration)) return;
            boolean stateRepairRequired = InstallationRecognition.shouldRequireStateRepair(
                    false,
                    mPreferences.getBoolean(Constants.PREF_INSTALLATION_MARKER, false),
                    false,
                    Constants.getConfigFile(this).exists()
            );
            mStartupStateAccessProbePending = false;
            mStartupStateAccessProbeComplete = true;
            mStateAccessRepairRequired = stateRepairRequired;
            if (!stateRepairRequired) openBackendOperationAdmission();
            Log.e(TAG, "Could not start Managed State accessibility probe", error);
            onModeTransitionStatusChanged(
                    ExecutionModeTransitionStatus.idle(mStateAccessRepairRequired)
            );
        }
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
            if (mExecutionModeTransitionTestSeam == null) {
                Log.d(TAG, "onServiceStateChange: Called with unchanged state " + newState);
            }
            return;
        }
        if (mExecutionModeTransitionTestSeam == null) {
            Log.i(TAG, "onServiceStateChange: from " + mCurrentState + " to " + newState);
        }
        State previousState = mCurrentState;
        mCurrentState = newState;
        if (mRootStartupStatusPending && newState == State.ACTIVE) {
            mRootStartupStatusPending = false;
            onModeTransitionStatusChanged(ExecutionModeTransitionStatus.idle(
                    mStateAccessRepairRequired
            ));
        } else if (mRootStartupStatusPending && newState == State.ERROR) {
            failRootStartup(
                    ExecutionModeTransitionStatus.FailureKind.STARTUP,
                    null,
                    null,
                    "The root-selected Syncthing startup failed. Retry is available."
            );
        } else if (mRootStartupStatusPending && previousState == State.STARTING
                && newState == State.DISABLED) {
            failRootStartup(
                    ExecutionModeTransitionStatus.FailureKind.STARTUP,
                    null,
                    null,
                    "The root-selected Syncthing startup stopped before it became ready. Retry is available."
            );
        }
        if (mExecutionModeTransitionTestSeam != null) return;
        postTransitionCallback(() -> {
            if (mNotificationHandler != null) {
                mNotificationHandler.updatePersistentNotification(this);
            }
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
        Log.d(TAG, "exportConfig BEGIN");
        if (!shutdownForFileMutation()) return false;
        try {
            try (ManagedStateStaging staging = mRuntime.managedStateTransfer()
                    .snapshotForExport()) {
                File sharedPreferencesFile = new File(
                        staging.directory(), SyncthingBackupArchive.SHARED_PREFERENCES
                );
                try (ObjectOutputStream preferences = new ObjectOutputStream(
                        new FileOutputStream(sharedPreferencesFile)
                )) {
                    preferences.writeObject(preferencesForBackup(mPreferences.getAll()));
                }
                String password = mPreferences.getString(
                        Constants.PREF_BACKUP_PASSWORD, ""
                );
                SyncthingBackupArchive.write(getBackupZipFile(), staging, password);
                Log.d(TAG, "exportConfig END");
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "exportConfig: Failed to export config", e);
            return false;
        } finally {
            finishFileMutation(null, true);
        }
    }

    /** Builds a backup preference snapshot without this device's automatic root-prompt consent. */
    static Map<String, ?> preferencesForBackup(Map<String, ?> current) {
        Map<String, ?> exported = new LinkedHashMap<>(current);
        exported.remove(Constants.PREF_ROOT_RUN_SCRIPT_PROMPT_POLICY);
        return exported;
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
        Log.d(TAG, "importConfig PRECHECK");
        File zipFilePath = getBackupZipFile();
        if (!zipFilePath.exists()) {
            Log.e(TAG, "importConfig: ZIP file is missing. Please check if it is present at '" + zipFilePath.getAbsolutePath() + "' as specified in the settings screen.");
            return false;
        }
        ManagedStateStaging staging = null;
        ZipFile zipFile;
        Map<?, ?> importedSharedPreferences;
        try {
            staging = ManagedStateLocations.forApplication(this).newStaging();
            String password = mPreferences.getString(Constants.PREF_BACKUP_PASSWORD, "");
            if (password.isEmpty()) {
                zipFile = new ZipFile(zipFilePath);
            } else {
                zipFile = new ZipFile(zipFilePath, password.toCharArray());
                if (!zipFile.isEncrypted()) {
                    Log.e(TAG, "importConfig: ZIP file is not encrypted, but password was specified in settings screen. Try to specify an empty password temporarily.");
                    staging.close();
                    return false;
                }
            }
            importedSharedPreferences = SyncthingBackupArchive.extract(zipFile, staging);
        } catch (Exception e) {
            Log.e(TAG, "importConfig: Failed to validate or extract backup", e);
            if (staging != null) staging.close();
            return false;
        }

        if (!shutdownForFileMutation()) {
            staging.close();
            return false;
        }

        boolean failSuccess = true;
        Log.d(TAG, "importConfig BEGIN");
        Runnable afterImport = null;
        boolean resetRequested = false;
        boolean useRootPresent = mPreferences.contains(Constants.PREF_USE_ROOT);
        Object localUseRootValue = mPreferences.getAll().get(Constants.PREF_USE_ROOT);
        try {
            RootRunScriptAuthorizationPolicy scriptPolicy = mRootRunScriptPromptPolicy;
            if (scriptPolicy != null && !scriptPolicy.disable()) {
                Log.e(TAG, "importConfig: Could not clear automatic root-script consent");
                failSuccess = false;
                return false;
            }
            try {
                mRuntime.managedStateTransfer().installImportedState(staging);
                ConfigurationProjection.clear();
            } catch (Exception e) {
                Log.e(TAG, "importConfig: Failed to install staged Managed State", e);
                failSuccess = false;
                return false;
            }

            if (importedSharedPreferences != null) {
                Log.d(TAG, "importConfig: Importing shared preferences");
                failSuccess = importConfigSharedPrefs(
                        importedSharedPreferences, useRootPresent, localUseRootValue
                );
            } else if (!mPreferences.edit()
                    .putBoolean(Constants.PREF_INSTALLATION_MARKER, true)
                    .commit()) {
                Log.e(TAG, "importConfig: Could not record installation lineage");
                failSuccess = false;
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
            staging.close();
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
        byte[] certificate = certPem.clone();
        byte[] key = keyPem.clone();
        mHandler.post(() -> doReplaceHttpsCertificate(certificate, key, listener));
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
        if (mCertificateMutationPending) {
            listener.onResult(HttpsCertReplaceResult.FAILED, FILE_MUTATION_IN_PROGRESS_MESSAGE);
            return;
        }
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
        if (mDatabaseResetOwnership.isReserved()) {
            listener.onResult(
                    HttpsCertReplaceResult.FAILED, CERT_MUTATION_LIFECYCLE_PENDING_MESSAGE
            );
            return;
        }

        FileMutationBarrier.CertificateRestoreReservation reservation =
                FileMutationBarrier.tryReserveCertificateMutation();
        if (reservation == null) {
            listener.onResult(HttpsCertReplaceResult.FAILED, FILE_MUTATION_IN_PROGRESS_MESSAGE);
            return;
        }
        mCertificateStorageReservation = reservation;

        final HttpsCertificateStorage certificateStorage = mRuntime.httpsCertificateStorage();
        StoppedFileMutation replaceFiles = ownership -> startCertificateMutation(
                certificateStorage,
                CertificateStorageMutation.replace(certPem, keyPem),
                ownership,
                reservation,
                listener
        );
        shutdownForFileMutation(
                replaceFiles,
                () -> failCertificateMutationBeforeWrite(
                        reservation, listener, FILE_MUTATION_IN_PROGRESS_MESSAGE
                ),
                () -> failCertificateMutationBeforeWrite(
                        reservation, listener, CERT_MUTATION_SHUTDOWN_FAILURE_MESSAGE
                ),
                () -> failCertificateMutationBeforeWrite(
                        reservation, listener, CERT_MUTATION_LIFECYCLE_PENDING_MESSAGE
                ),
                reservation
        );
    }

    private void doResetHttpsCertificate(OnHttpsCertReplaceResultListener listener) {
        if (mCertificateMutationPending) {
            listener.onResult(HttpsCertReplaceResult.FAILED, FILE_MUTATION_IN_PROGRESS_MESSAGE);
            return;
        }
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
        if (mDatabaseResetOwnership.isReserved()) {
            listener.onResult(
                    HttpsCertReplaceResult.FAILED, CERT_MUTATION_LIFECYCLE_PENDING_MESSAGE
            );
            return;
        }

        FileMutationBarrier.CertificateRestoreReservation reservation =
                FileMutationBarrier.tryReserveCertificateMutation();
        if (reservation == null) {
            listener.onResult(HttpsCertReplaceResult.FAILED, FILE_MUTATION_IN_PROGRESS_MESSAGE);
            return;
        }
        mCertificateStorageReservation = reservation;

        final HttpsCertificateStorage certificateStorage = mRuntime.httpsCertificateStorage();
        StoppedFileMutation resetFiles = ownership -> startCertificateMutation(
                certificateStorage,
                CertificateStorageMutation.reset(),
                ownership,
                reservation,
                listener
        );
        shutdownForFileMutation(
                resetFiles,
                () -> failCertificateMutationBeforeWrite(
                        reservation, listener, FILE_MUTATION_IN_PROGRESS_MESSAGE
                ),
                () -> failCertificateMutationBeforeWrite(
                        reservation, listener, CERT_MUTATION_SHUTDOWN_FAILURE_MESSAGE
                ),
                () -> failCertificateMutationBeforeWrite(
                        reservation, listener, CERT_MUTATION_LIFECYCLE_PENDING_MESSAGE
                ),
                reservation
        );
    }

    private boolean hasServiceExecution() {
        boolean serviceExecution = mShutdownInProgress
                || mFileMutationBarrier != null
                || mCurrentState != State.DISABLED
                || mOwnedExecution != null
                || mSyncthingRunnable != null
                || mSyncthingRunnableThread != null;
        return SyncthingResetPolicy.certificateMutationRequiresShutdown(
                serviceExecution,
                mPostMutationStartupGate.ownsStartup(),
                mDatabaseResetOwnership.isReserved()
        );
    }

    private void startCertificateMutation(
            HttpsCertificateStorage storage,
            CertificateStorageMutation.Action action,
            CertificateMutationOwnership ownership,
            FileMutationBarrier.CertificateRestoreReservation reservation,
            OnHttpsCertReplaceResultListener listener
    ) {
        if (mCertificateMutationPending) {
            ownership.release();
            reservation.release(true);
            clearReleasedCertificateStorageReservation(reservation);
            listener.onResult(HttpsCertReplaceResult.FAILED, FILE_MUTATION_IN_PROGRESS_MESSAGE);
            return;
        }
        AtomicBoolean cancelled = new AtomicBoolean();
        mCertificateMutationPending = true;
        mCertificateMutationCancellation = cancelled;
        mCertificateStorageReservation = reservation;
        boolean submitted = CertificateStorageMutation.submit(
                mCertificateStorageExecutor,
                storage,
                action,
                cancelled,
                result -> {
                    if (!postCertificateMutationCompletion(() -> completeCertificateMutation(
                            storage, result, ownership, reservation, listener
                    ))) {
                        reservation.release(false);
                    }
                }
        );
        if (!submitted) Log.w(TAG, "Certificate storage worker rejected the mutation");
    }

    private void failCertificateMutationBeforeWrite(
            FileMutationBarrier.CertificateRestoreReservation reservation,
            OnHttpsCertReplaceResultListener listener,
            String detail
    ) {
        reservation.release(true);
        clearReleasedCertificateStorageReservation(reservation);
        listener.onResult(HttpsCertReplaceResult.FAILED, detail);
    }

    /** Defers destruction rollback until the shutdown path has established stopped-state safety. */
    private void startDestructionRestoreAfterMutation(
            HttpsCertificateStorage storage,
            HttpsCertificateState previous,
            CertificateMutationOwnership ownership,
            FileMutationBarrier.CertificateRestoreReservation reservation,
            OnHttpsCertReplaceResultListener listener
    ) {
        CertificateDestructionRestore[] restoreRef = new CertificateDestructionRestore[1];
        CertificateDestructionRestore restore = new CertificateDestructionRestore(
                mCertificateStorageExecutor,
                storage,
                previous,
                reservation,
                outcome -> {
                    boolean posted = postCertificateMutationCompletion(() -> {
                        mCertificateMutationPending = false;
                        mCertificateMutationCancellation = null;
                        if (mCertificateDestructionRestore == restoreRef[0]) {
                            mCertificateDestructionRestore = null;
                        }
                        ownership.release();
                        clearReleasedCertificateStorageReservation(reservation);
                        String detail;
                        if (!outcome.executionExitProven()) {
                            detail = "Service was destroyed, but Syncthing exit could not be"
                                    + " proven; the previous HTTPS certificate state was not"
                                    + " changed. " + outcome.shutdownFailure().getMessage();
                        } else if (outcome.restoration().stateRestored()) {
                            detail = "Service was destroyed during the HTTPS certificate"
                                    + " operation; the previous certificate state was restored.";
                        } else {
                            Throwable failure = outcome.restoration().failure();
                            detail = "Service was destroyed and the previous HTTPS certificate"
                                    + " state could not be restored: " + (failure == null
                                            ? "unknown restoration failure"
                                            : failure.getMessage());
                        }
                        try {
                            listener.onResult(HttpsCertReplaceResult.FAILED, detail);
                        } finally {
                            if (mDestroying) mCertificateStorageExecutor.shutdown();
                        }
                    });
                    if (!posted) mCertificateStorageExecutor.shutdown();
                }
        );
        restoreRef[0] = restore;
        mCertificateDestructionRestore = restore;
        mCertificateMutationPending = true;
        mCertificateMutationCancellation = null;

        if (mShutdownInProgress) return;
        if (mShutdownSafetyEstablished) {
            restore.onExecutionExitProven();
        } else {
            restore.onExecutionExitUnproven(new IllegalStateException(
                    "Could not prove Syncthing stopped; certificate restoration was not safe."
            ));
        }
    }

    /** Restores saved certificate files on the worker and resolves the result on the service thread. */
    private void startCertificateRestore(
            HttpsCertificateStorage storage,
            HttpsCertificateState previous,
            CertificateStorageMutation.Completion completion
    ) {
        if (mCertificateMutationPending) {
            completion.onComplete(CertificateStorageMutation.failedRestore(
                    previous,
                    new IllegalStateException("Certificate storage work is already pending")
            ));
            return;
        }
        FileMutationBarrier.CertificateRestoreReservation reservation =
                FileMutationBarrier.tryReserveCertificateRestore();
        if (reservation == null) {
            completeCertificateRestore(null, null, completion,
                    CertificateStorageMutation.failedRestore(
                            previous,
                            new IllegalStateException(
                                    "Certificate storage is owned by another restore"
                            )
                    ));
            return;
        }

        FileMutationBarrier restoreBarrier = FileMutationBarrier.reserveAsyncOwner(
                mFileMutationBarrier, () -> { }, () -> { }, () -> { }
        );
        if (restoreBarrier == null) {
            reservation.release(false);
            completeCertificateRestore(null, null, completion,
                    CertificateStorageMutation.failedRestore(
                            previous,
                            new IllegalStateException(
                                    "Another file mutation owns certificate storage"
                            )
                    ));
            return;
        }
        restoreBarrier.setAsyncMutation(() -> { });
        mFileMutationBarrier = restoreBarrier;

        mCertificateMutationPending = true;
        mCertificateMutationCancellation = null;
        mCertificateStorageReservation = reservation;
        FileMutationBarrier ownedRestoreBarrier = restoreBarrier;
        FileMutationBarrier.CertificateRestoreReservation ownedReservation = reservation;
        CertificateStorageMutation.submitRestore(
                mCertificateStorageExecutor,
                storage,
                previous,
                result -> {
                    boolean posted = postCertificateMutationCompletion(() ->
                            completeCertificateRestore(
                                    ownedRestoreBarrier,
                                    ownedReservation,
                                    completion,
                                    result
                            )
                    );
                    if (!posted) ownedReservation.release(false);
                }
        );
    }

    private void completeCertificateRestore(
            @Nullable FileMutationBarrier restoreBarrier,
            @Nullable FileMutationBarrier.CertificateRestoreReservation reservation,
            CertificateStorageMutation.Completion completion,
            CertificateStorageMutation.Result result
    ) {
        mCertificateMutationPending = false;
        mCertificateMutationCancellation = null;
        if (restoreBarrier != null && mFileMutationBarrier == restoreBarrier) {
            mFileMutationBarrier = null;
        }
        if (reservation != null) {
            reservation.completeStorage(result.stateRestored());
            clearReleasedCertificateStorageReservation(reservation);
        }
        try {
            completion.onComplete(result);
        } finally {
            if (mDestroying) mCertificateStorageExecutor.shutdown();
        }
    }

    /** Returns storage results to the main thread before changing lifecycle state or ownership. */
    private boolean postCertificateMutationCompletion(Runnable completion) {
        if (!mHandler.post(completion)) {
            Log.e(TAG, "Certificate storage outcome could not return to the service thread");
            return false;
        }
        return true;
    }

    private void completeCertificateMutation(
            HttpsCertificateStorage storage,
            CertificateStorageMutation.Result result,
            CertificateMutationOwnership ownership,
            FileMutationBarrier.CertificateRestoreReservation reservation,
            OnHttpsCertReplaceResultListener listener
    ) {
        mCertificateMutationPending = false;
        mCertificateMutationCancellation = null;
        if (mDestroying) {
            if (result.previous() != null
                    && (result.succeeded() || !result.stateRestored())) {
                startDestructionRestoreAfterMutation(
                        storage, result.previous(), ownership, reservation, listener
                );
                return;
            }
            reservation.completeStorage(result.succeeded() || result.stateRestored());
            ownership.release();
            clearReleasedCertificateStorageReservation(reservation);
            try {
                listener.onResult(
                        HttpsCertReplaceResult.FAILED,
                        "Service was destroyed during the HTTPS certificate operation."
                );
            } finally {
                mCertificateStorageExecutor.shutdown();
            }
            return;
        }
        reservation.completeStorage(result.succeeded() || result.stateRestored());
        clearReleasedCertificateStorageReservation(reservation);
        boolean automaticStartupAllowed = ownership.release();
        if (result.succeeded()) {
            applyCertChangeWithVerify(
                    storage, result.previous(), listener, automaticStartupAllowed
            );
            return;
        }

        Throwable failure = result.failure();
        Throwable rollbackFailure = result.rollbackFailure();
        String detail = result.cancelled()
                ? "HTTPS certificate operation was cancelled."
                : failure == null ? "HTTPS certificate operation failed."
                : failure.getMessage();
        if (rollbackFailure != null) {
            detail = (detail == null ? "HTTPS certificate operation failed" : detail)
                    + "; previous certificate state could not be restored: "
                    + rollbackFailure.getMessage();
            Log.e(TAG, "Could not restore HTTPS certificate state after mutation failure",
                    rollbackFailure);
        }
        if (failure != null) Log.e(TAG, "HTTPS certificate mutation failed", failure);
        if (result.stateRestored() && automaticStartupAllowed && mLastDeterminedShouldRun) {
            launchStartupTask(SyncthingCommand.SERVE);
        }
        listener.onResult(HttpsCertReplaceResult.FAILED, detail);
    }

    private void clearReleasedCertificateStorageReservation(
            FileMutationBarrier.CertificateRestoreReservation reservation
    ) {
        if (reservation.isReleased() && mCertificateStorageReservation == reservation) {
            mCertificateStorageReservation = null;
        }
    }

    private void applyCertChangeWithVerify(
            HttpsCertificateStorage storage,
            HttpsCertificateState previous,
            OnHttpsCertReplaceResultListener listener,
            boolean automaticStartupAllowed
    ) {
        if (automaticStartupAllowed && mLastDeterminedShouldRun) {
            verifyRestartAndRollback(storage, previous, listener);
        } else {
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
    private void verifyRestartAndRollback(
            HttpsCertificateStorage storage,
            HttpsCertificateState previous,
            OnHttpsCertReplaceResultListener listener
    ) {
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
            listener.onResult(HttpsCertReplaceResult.SUCCESS, null);
        };
        final Runnable finishPendingStart = () -> {
            verification.completePendingStart(
                    () -> { },
                    () -> {
                        clearStopHandler.run();
                        listener.onResult(HttpsCertReplaceResult.SUCCESS_PENDING_START, null);
                    }
            );
        };
        final Runnable finishFailure = () -> {
            Runnable finishAfterRestore = verification.completeFailureRecovery(
                    () -> { },
                    () -> mLastDeterminedShouldRun,
                    () -> launchStartupTask(SyncthingCommand.SERVE),
                    () -> {
                        // An explicit STOP can suppress certificate recovery's relaunch while a
                        // later Run Conditions transition creates a generic deferred start. Clear
                        // that generic intent at the exact remain-stopped decision so
                        // runShutdownCompletion cannot bypass the certificate workflow's STOP.
                        mShutdownStartIntent.clear();
                        onServiceStateChange(State.DISABLED);
                    },
                    () -> {
                        clearStopHandler.run();
                        listener.onResult(HttpsCertReplaceResult.FAILED,
                                "Syncthing did not come online with the new certificate.");
                    }
            );
            Runnable restoreSafely = () -> startCertificateRestore(
                    storage,
                    previous,
                    result -> {
                        if (mDestroying) {
                            clearStopHandler.run();
                            String detail = result.stateRestored()
                                    ? "Service was destroyed before certificate verification completed."
                                    : "Service was destroyed and the previous HTTPS certificate"
                                            + " state could not be restored: "
                                            + result.failure().getMessage();
                            listener.onResult(HttpsCertReplaceResult.FAILED, detail);
                        } else if (!result.stateRestored()) {
                            String detail = "Could not restore the previous HTTPS certificate state: "
                                    + result.failure().getMessage();
                            verification.failFailureRecoveryShutdown(() -> {
                                clearStopHandler.run();
                                mShutdownStartIntent.clear();
                                listener.onResult(HttpsCertReplaceResult.FAILED, detail);
                            });
                        } else {
                            Runnable shutdownFailure = () ->
                                    verification.failFailureRecoveryShutdown(() -> {
                                        clearStopHandler.run();
                                        mShutdownStartIntent.clear();
                                        listener.onResult(
                                                HttpsCertReplaceResult.FAILED,
                                                "Syncthing shutdown could not be proven safe after"
                                                        + " certificate restoration."
                                        );
                                    });
                            CertificateRestoreShutdownContinuation.runAfterShutdown(
                                    mShutdownInProgress,
                                    finishAfterRestore,
                                    shutdownFailure,
                                    (continuation, failure) -> shutdown(
                                            State.DISABLED, continuation, true, false, failure
                                    ),
                                    this::runShutdownCompletion
                            );
                        }
                    }
            );
            if (hasServiceExecution()) {
                Runnable shutdownFailure = () -> verification.failFailureRecoveryShutdown(
                        () -> {
                            clearStopHandler.run();
                            listener.onResult(
                                    HttpsCertReplaceResult.FAILED,
                                    "Could not prove Syncthing stopped; certificate recovery was"
                                            + " not safe to complete."
                            );
                        }
                );
                shutdown(
                        State.INIT,
                        restoreSafely,
                        true,
                        false,
                        shutdownFailure
                );
            } else {
                restoreSafely.run();
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

        stopHandler[0] = new CertificateVerificationStopHandler() {
            @Override
            public void onExplicitStop(boolean crashedNativeStop) {
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
            }

            @Override
            public void onServiceDestroy() {
                try {
                    mHandler.removeCallbacks(watchdog[0]);
                } catch (RuntimeException e) {
                    Log.e(TAG, "Could not cancel certificate verification watchdog", e);
                }
                try {
                    unregisterOnServiceStateChangeListener(verifyListener[0]);
                } catch (RuntimeException e) {
                    Log.e(TAG, "Could not remove certificate verification listener", e);
                }
                boolean mutationAlreadyPending = mCertificateMutationPending;
                CertificateDestructionRestore[] restore =
                        new CertificateDestructionRestore[1];
                try {
                    restore[0] = CertificateDestructionRestore.prepareForDestruction(
                            verification,
                            mutationAlreadyPending,
                            mCertificateStorageExecutor,
                            storage,
                            previous,
                            outcome -> postCertificateMutationCompletion(() -> {
                                mCertificateMutationPending = false;
                                mCertificateMutationCancellation = null;
                                if (mCertificateDestructionRestore == restore[0]) {
                                    mCertificateDestructionRestore = null;
                                }
                                String detail;
                                if (!outcome.executionExitProven()) {
                                    detail = "Service was destroyed, but Syncthing"
                                            + " exit could not be proven; the previous"
                                            + " HTTPS certificate state was not changed."
                                            + " " + outcome.shutdownFailure().getMessage();
                                } else if (outcome.restoration().stateRestored()) {
                                    detail = "Service was destroyed before certificate"
                                            + " verification completed; the previous"
                                            + " certificate state was restored.";
                                } else {
                                    Throwable failure = outcome.restoration().failure();
                                    detail = "Service was destroyed and the previous"
                                            + " HTTPS certificate state could not be"
                                            + " restored: " + (failure == null
                                                    ? "unknown restoration failure"
                                                    : failure.getMessage());
                                }
                                try {
                                    listener.onResult(HttpsCertReplaceResult.FAILED, detail);
                                } finally {
                                    if (mDestroying) {
                                        mCertificateStorageExecutor.shutdown();
                                    }
                                }
                            })
                    );
                    if (restore[0] != null) {
                        mCertificateMutationPending = true;
                        mCertificateMutationCancellation = null;
                        mCertificateDestructionRestore = restore[0];
                    }
                } catch (RuntimeException e) {
                    Log.e(TAG, "Could not resolve certificate verification during destruction", e);
                } finally {
                    clearStopHandler.run();
                }
            }
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

    private boolean importConfigSharedPrefs(
            Map<?, ?> sharedPrefsMap,
            boolean useRootPresent,
            Object localUseRootValue
    ) {
        boolean failSuccess = true;
        try {
            RootRunScriptAuthorizationPolicy scriptPolicy = mRootRunScriptPromptPolicy;
            if (scriptPolicy != null) scriptPolicy.invalidate();
            // Store backup folder to restore it back later in the process.
            String relPathToZip = mPreferences.getString(Constants.PREF_BACKUP_REL_PATH_TO_ZIP, "");
            String backupPassword = mPreferences.getString(Constants.PREF_BACKUP_PASSWORD, "");
            boolean localInotifyWatchLimit = mPreferences.getBoolean(
                    Constants.PREF_INOTIFY_WATCH_LIMIT, false
            );

            // Prepare a SharedPreferences commit only after the complete archive value is valid.
            SharedPreferences.Editor editor = mPreferences.edit();
            editor.clear();
            for (Map.Entry<?, ?> e : sharedPrefsMap.entrySet()) {
                String prefKey = (String) e.getKey();
                if (isDeviceLocalPreference(prefKey)) {
                    LogV("importConfig: Ignoring device-local pref \"" + prefKey + "\".");
                    continue;
                }
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
                        }
                        break;
                }
            }
            editor.putString(Constants.PREF_BACKUP_REL_PATH_TO_ZIP, relPathToZip);
            editor.putString(Constants.PREF_BACKUP_PASSWORD, backupPassword);

            if (!restoreLocalDevicePreferences(
                    editor, useRootPresent, localUseRootValue, localInotifyWatchLimit
            )) {
                Log.e(TAG, "importConfig: Could not preserve device-local preferences");
                failSuccess = false;
            }

            if (failSuccess) {
                failSuccess = editor.commit();
                if (failSuccess && scriptPolicy != null) {
                    scriptPolicy.observeImportedReset();
                    notifyRootRunScriptPromptStatusChanged();
                }
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "importConfig: Failed to import SharedPreferences #1", e);
            failSuccess = false;
        }
        return failSuccess;
    }

    private static boolean putPreferenceValue(
            SharedPreferences.Editor editor, String key, Object value
    ) {
        if (value instanceof Boolean) {
            editor.putBoolean(key, (Boolean) value);
        } else if (value instanceof String) {
            editor.putString(key, (String) value);
        } else if (value instanceof Integer) {
            editor.putInt(key, (Integer) value);
        } else if (value instanceof Float) {
            editor.putFloat(key, (Float) value);
        } else if (value instanceof Long) {
            editor.putLong(key, (Long) value);
        } else if (value instanceof Set) {
            editor.putStringSet(key, asSet((Set<?>) value, String.class));
        } else {
            return false;
        }
        return true;
    }

    static boolean isDeviceLocalPreference(String key) {
        return Constants.PREF_USE_ROOT.equals(key)
                || Constants.PREF_ROOT_RUN_SCRIPT_PROMPT_POLICY.equals(key)
                || Constants.PREF_INSTALLATION_MARKER.equals(key)
                || Constants.PREF_INOTIFY_WATCH_LIMIT.equals(key);
    }

    static boolean restoreLocalDevicePreferences(
            SharedPreferences.Editor editor,
            boolean useRootWasPresent,
            Object localUseRootValue,
            boolean localInotifyWatchLimit
    ) {
        if (!restoreLocalExecutionModePreference(
                editor, useRootWasPresent, localUseRootValue
        )) {
            return false;
        }
        editor.putBoolean(Constants.PREF_INSTALLATION_MARKER, true);
        editor.putBoolean(Constants.PREF_INOTIFY_WATCH_LIMIT, localInotifyWatchLimit);
        return true;
    }

    static boolean restoreLocalExecutionModePreference(
            SharedPreferences.Editor editor, boolean wasPresent, Object value
    ) {
        return !wasPresent || putPreferenceValue(editor, Constants.PREF_USE_ROOT, value);
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
