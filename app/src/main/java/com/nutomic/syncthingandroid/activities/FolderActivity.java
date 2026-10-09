package com.nutomic.syncthingandroid.activities;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.DrawableRes;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.gson.Gson;
import com.nutomic.syncthingandroid.R;
import com.nutomic.syncthingandroid.SyncthingApp;
import com.nutomic.syncthingandroid.model.Device;
import com.nutomic.syncthingandroid.model.Folder;
import com.nutomic.syncthingandroid.model.FolderIgnoreList;
import com.nutomic.syncthingandroid.model.SharedWithDevice;
import com.nutomic.syncthingandroid.runtime.DefaultSyncthingRuntime;
import com.nutomic.syncthingandroid.runtime.FolderOperationException;
import com.nutomic.syncthingandroid.runtime.FolderTypePolicy;
import com.nutomic.syncthingandroid.runtime.FolderWriteability;
import com.nutomic.syncthingandroid.runtime.IgnoreListEditorGate;
import com.nutomic.syncthingandroid.runtime.FolderEditorStartupLoad;
import com.nutomic.syncthingandroid.runtime.FolderWorkQueue;
import com.nutomic.syncthingandroid.service.Constants;
import com.nutomic.syncthingandroid.service.RestApi;
import com.nutomic.syncthingandroid.service.SyncthingService;
import com.nutomic.syncthingandroid.service.SyncthingServiceBinder;
import com.nutomic.syncthingandroid.util.ConfigRouter;
import com.nutomic.syncthingandroid.util.FileUtils;
import com.nutomic.syncthingandroid.util.FileUtils.ExternalStorageDirType;
import com.nutomic.syncthingandroid.util.TextWatcherAdapter;
import com.nutomic.syncthingandroid.util.Util;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import static android.util.TypedValue.COMPLEX_UNIT_DIP;
import static android.view.Gravity.CENTER_VERTICAL;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static androidx.core.view.MarginLayoutParamsCompat.setMarginEnd;
import static androidx.core.view.MarginLayoutParamsCompat.setMarginStart;

import static com.nutomic.syncthingandroid.service.RunConditionMonitor.ACTION_SYNC_TRIGGER_FIRED;
import static com.nutomic.syncthingandroid.service.RunConditionMonitor.EXTRA_BEGIN_ACTIVE_TIME_WINDOW;

/**
 * Shows folder details and allows changing them.
 */
public class FolderActivity extends SyncthingActivity {
    public static final String EXTRA_DEVICE_ID =
            ".activities.FolderActivity.DEVICE_ID";

    public static final String EXTRA_FOLDER_ID =
            ".activities.FolderActivity.FOLDER_ID";
    public static final String EXTRA_FOLDER_LABEL =
            ".activities.FolderActivity.FOLDER_LABEL";
    public static final String EXTRA_IS_CREATE =
            ".activities.FolderActivity.IS_CREATE";
    public static final String EXTRA_NOTIFICATION_ID =
            ".activities.FolderActivity.NOTIFICATION_ID";
    public static final String EXTRA_RECEIVE_ENCRYPTED =
            ".activities.FolderActivity.RECEIVE_ENCRYPTED";
    public static final String EXTRA_REMOTE_ENCRYPTED =
            ".activities.FolderActivity.REMOTE_ENCRYPTED";

    private static final String TAG = "FolderActivity";

    private static final String IS_SHOWING_DELETE_DIALOG = "DELETE_FOLDER_DIALOG_STATE";
    private static final String IS_SHOW_DISCARD_DIALOG = "DISCARD_FOLDER_DIALOG_STATE";

    private static final int FILE_VERSIONING_DIALOG_REQUEST = 3454;
    private static final int PULL_ORDER_DIALOG_REQUEST = 3455;
    private static final int FOLDER_TYPE_DIALOG_REQUEST =3456;
    private static final int CHOOSE_FOLDER_REQUEST = 3459;

    public static final int FOLDER_ADD_CODE = 402;

    private ConfigRouter mConfig;
    private Folder mFolder;
    // Contains SAF readwrite access URI on API level >= Build.VERSION_CODES.LOLLIPOP (21)
    private Uri mFolderUri = null;
    // Indicates the result of the write test to mFolder.path on dialog init or after a path change.
    Boolean mCanWriteToPath = false;

    /** Delivers results of folder work back to the main thread. */
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    /**
     * Runs folder work away from the main thread.
     *
     * <p>Folder work touches the filesystem and, in superuser mode, acquires a bounded helper
     * session, so it must never run on the thread that draws the editor. The queue also drops the
     * results of superseded requests, which is what keeps a verdict computed for an older path from
     * being applied after the user already chose another one.</p>
     */
    private final FolderWorkQueue mFolderWork =
            new FolderWorkQueue(Executors.newSingleThreadExecutor(), mMainHandler::post);

    /** Reads the configuration this editor opens with, on the same queue as its folder work. */
    private final FolderEditorStartupLoad<EditorStartup> mEditorStartupLoad =
            new FolderEditorStartupLoad<>(mFolderWork);

    private EditText mLabelView;
    private EditText mIdView;
    private TextView mPathView;
    private View mSelectAdvancedDirectory;
    private TextView mAccessExplanationView;
    private TextView mFolderTypeTitleView;
    private TextView mFolderTypeView;
    private TextView mFolderTypeDescriptionView;
    private ViewGroup mDevicesContainer;
    private SwitchCompat mFolderFileWatcher;
    private SwitchCompat mFolderPaused;
    private SwitchCompat mCustomSyncConditionsSwitch;
    private TextView mCustomSyncConditionsDescription;
    private TextView mCustomSyncConditionsDialog;
    private ViewGroup mPullOrderContainer;
    private TextView mPullOrderTypeView;
    private TextView mPullOrderDescriptionView;
    private TextView mVersioningDescriptionView;
    private TextView mVersioningTypeView;
    private ViewGroup mIgnoreDeleteContainer;
    private SwitchCompat mRunScriptSwitch;
    private ViewGroup mRunScriptContainer;
    private SwitchCompat mIgnoreDelete;
    private TextView mEditIgnoreListTitle;
    private EditText mEditIgnoreListContent;
    private View mSavingOverlay;
    private ViewGroup mFolderTypeContainer;

    @Inject
    SharedPreferences mPreferences;

    @Inject
    DefaultSyncthingRuntime mRuntime;

    private boolean mPrefExpertMode = false;

    private boolean mIsCreateMode;
    private boolean mFolderNeedsToUpdate = false;
    private boolean mIgnoreListNeedsToUpdate = false;
    /** Whether the ignore-list editor holds the list that was read for its folder. */
    private boolean mIgnoreListDelivered = false;
    /** Path the ignore list was last requested for, or {@code null} while none was requested. */
    private String mIgnoreListReadPath;
    /**
     * Path the list the ignore-list editor holds was read for, or {@code null} while the editor
     * holds no list that was read.
     */
    private String mIgnoreListHeldPath;
    /**
     * Path the authoritative configuration holds for the edited folder, or {@code null} while no
     * configured path is known.
     *
     * <p>An ignore-list read resolves the folder's path from the configuration instead of from the
     * path the editor shows, so a read is only requested while both are the same, and a held list
     * is only kept while the configuration still holds the path it was read for.</p>
     */
    private String mConfiguredFolderPath;
    /**
     * Whether the editor is busy: it is either waiting for the configuration it opens with or
     * writing the folder. A busy editor accepts no edits and runs no save, removal or back press.
     */
    private boolean mIsSaving = false;
    /** The newest writeability verdict for the path the editor currently shows. */
    private FolderWriteability mPathWriteability = FolderWriteability.UNKNOWN;
    /** Whether the verdict for the path the editor currently shows is still on its way. */
    private boolean mPathWriteabilityPending = false;
    /** Whether a save is waiting for that verdict before it writes the folder. */
    private boolean mSaveAwaitsWriteability = false;

    private Dialog mDeleteDialog;
    private Dialog mDiscardDialog;

    private OnBackPressedCallback mBackPressedCallback = new OnBackPressedCallback(true) {
        @Override
        public void handleOnBackPressed() {
            if (mIsSaving) {
                return;
            }
            if (mFolderNeedsToUpdate) {
                showDiscardDialog();
            } else {
                // Let default behavior handle it
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            }
        }
    };

    private final TextWatcher mTextWatcher = new TextWatcherAdapter() {
        @Override
        public void afterTextChanged(Editable s) {
            mFolder.label        = mLabelView.getText().toString().trim();
            mFolder.id           = mIdView.getText().toString();

            // Loop through devices the folder is shared to and update encryptionPassword property.
            for (int i = 0; i < mDevicesContainer.getChildCount(); i++) {
                if (mDevicesContainer.getChildAt(i) instanceof TextView) {
                    continue;
                }
                LinearLayout deviceView = (LinearLayout) mDevicesContainer.getChildAt(i);

                SwitchCompat switchView = (SwitchCompat) deviceView.getChildAt(0);
                SharedWithDevice device = mFolder.getDevice(((SharedWithDevice) switchView.getTag()).deviceID);
                if (device != null) {
                    EditText encryptPassView = (EditText) deviceView.getChildAt(1);
                    String newEncryptionPassword = encryptPassView.getText().toString();
                    if (!device.encryptionPassword.equals(newEncryptionPassword)) {
                        device.encryptionPassword = newEncryptionPassword;
                        mFolderNeedsToUpdate = true;
                    }
                }
            }

            // mPathView must not be handled here as it's handled by {@link onActivityResult}
            // mEditIgnoreListContent must not be handled here as it's written back when the dialog ends.
            mFolderNeedsToUpdate = true;
        }
    };

    private final TextWatcher mIgnoreListContentTextWatcher = new TextWatcherAdapter() {
        @Override
        public void afterTextChanged(Editable s) {
            mIgnoreListNeedsToUpdate = true;
            mFolderNeedsToUpdate = true;
        }
    };

    private final CompoundButton.OnCheckedChangeListener mCheckedListener =
            new CompoundButton.OnCheckedChangeListener() {
        @Override
        public void onCheckedChanged(CompoundButton view, boolean isChecked) {
            int id = view.getId();
            if (id == R.id.fileWatcher) {
                mFolder.fsWatcherEnabled = isChecked;
                mFolderNeedsToUpdate = true;
            } else if (id == R.id.folderPause) {
                mFolder.paused = isChecked;
                mFolderNeedsToUpdate = true;
            } else if (id == R.id.customSyncConditionsSwitch) {
                mCustomSyncConditionsDescription.setEnabled(isChecked);
                mCustomSyncConditionsDialog.setFocusable(isChecked);
                mCustomSyncConditionsDialog.setEnabled(isChecked);
                // This is needed to display the "discard changes dialog".
                mFolderNeedsToUpdate = true;
            } else if (id == R.id.device_toggle) {
                SharedWithDevice device = (SharedWithDevice) view.getTag();

                // Loop through devices the folder is shared to and show/hide encryptionPassword UI.
                for (int i = 0; i < mDevicesContainer.getChildCount(); i++) {
                    LinearLayout deviceView = (LinearLayout) mDevicesContainer.getChildAt(i);
                    SwitchCompat switchView = (SwitchCompat) deviceView.getChildAt(0);
                    if (device == ((SharedWithDevice) switchView.getTag())) {
                        EditText encryptPassView = (EditText) deviceView.getChildAt(1);
                        encryptPassView.setVisibility(isChecked ? View.VISIBLE : View.GONE);
                        break;
                    }
                }

                if (isChecked) {
                    mFolder.addDevice(device);
                } else {
                    mFolder.removeDevice(device.deviceID);
                }
                mFolderNeedsToUpdate = true;
            } else if (id == R.id.runScriptSwitch) {
                // Stored in pref.
                mFolderNeedsToUpdate = true;
            } else if (id == R.id.ignoreDelete) {
                mFolder.ignoreDelete = isChecked;
                mFolderNeedsToUpdate = true;
            }
        }
    };

    public static Intent createIntent(Context context) {
        Intent intent = new Intent(context, FolderActivity.class);
        intent.putExtra(EXTRA_IS_CREATE, true);
        return intent;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        mConfig = new ConfigRouter(FolderActivity.this);

        super.onCreate(savedInstanceState);
        ((SyncthingApp) getApplication()).component().inject(this);
        setContentView(R.layout.activity_folder);

        mIsCreateMode = getIntent().getBooleanExtra(EXTRA_IS_CREATE, false);
        setTitle(mIsCreateMode ? R.string.create_folder : R.string.edit_folder);

        mPrefExpertMode = mPreferences.getBoolean(Constants.PREF_EXPERT_MODE, false);

        mLabelView = findViewById(R.id.label);
        mIdView = findViewById(R.id.id);
        mPathView = findViewById(R.id.directoryTextView);
        mSelectAdvancedDirectory = findViewById(R.id.selectAdvancedDirectory);
        mAccessExplanationView = findViewById(R.id.accessExplanationView);
        mFolderTypeTitleView = findViewById(R.id.folderTypeTitle);
        mFolderTypeView = findViewById(R.id.folderType);
        mFolderTypeDescriptionView = findViewById(R.id.folderTypeDescription);
        mFolderFileWatcher = findViewById(R.id.fileWatcher);
        mFolderPaused = findViewById(R.id.folderPause);
        mCustomSyncConditionsSwitch = findViewById(R.id.customSyncConditionsSwitch);
        mCustomSyncConditionsDescription = findViewById(R.id.customSyncConditionsDescription);
        mCustomSyncConditionsDialog = findViewById(R.id.customSyncConditionsDialog);
        mPullOrderContainer = findViewById(R.id.pullOrderContainer);
        mPullOrderTypeView = findViewById(R.id.pullOrderType);
        mPullOrderDescriptionView = findViewById(R.id.pullOrderDescription);
        mVersioningDescriptionView = findViewById(R.id.versioningDescription);
        mVersioningTypeView = findViewById(R.id.versioningType);
        mIgnoreDeleteContainer = findViewById(R.id.ignoreDeleteContainer);
        mRunScriptContainer = findViewById(R.id.runScriptContainer);
        mRunScriptSwitch = findViewById(R.id.runScriptSwitch);
        mIgnoreDelete = findViewById(R.id.ignoreDelete);
        mDevicesContainer = findViewById(R.id.devicesContainer);
        mEditIgnoreListTitle = findViewById(R.id.edit_ignore_list_title);
        mEditIgnoreListContent = findViewById(R.id.edit_ignore_list_content);
        mSavingOverlay = findViewById(R.id.savingOverlay);
        if (mSavingOverlay != null && mSavingOverlay.getBackground() != null) {
            mSavingOverlay.getBackground().mutate().setAlpha(210);
        }

        // Android 11 disallows selecting the "Downloads" and the emulated storage root directory.
        mSelectAdvancedDirectory.setVisibility(
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ? View.VISIBLE : View.GONE
        );
        mSelectAdvancedDirectory.setOnClickListener(view -> onSelectAdvancedDirectoryClick());

        mPathView.setOnClickListener(view -> onPathViewClick());
        mCustomSyncConditionsDialog.setOnClickListener(view -> onCustomSyncConditionsDialogClick());

        mFolderTypeContainer = findViewById(R.id.folderTypeContainer);
        mFolderTypeContainer.setOnClickListener(v -> showFolderTypeDialog());
        mPullOrderContainer.setOnClickListener(v -> showPullOrderDialog());
        findViewById(R.id.versioningContainer).setOnClickListener(v -> showVersioningDialog());

        if (savedInstanceState != null) {
            Log.d(TAG, "Retrieving state from savedInstanceState ...");
            mFolder = new Gson().fromJson(savedInstanceState.getString("mFolder"), Folder.class);
            mFolderNeedsToUpdate = savedInstanceState.getBoolean("mFolderNeedsToUpdate");
            mIgnoreListNeedsToUpdate = savedInstanceState.getBoolean("mIgnoreListNeedsToUpdate");
            mFolderUri = savedInstanceState.getParcelable("mFolderUri");
            restoreDialogStates(savedInstanceState);
        } else {
            // Fresh init of the edit or create mode.
            if (mIsCreateMode) {
                Log.d(TAG, "Initializing create mode ...");
                initFolder();
                mFolderNeedsToUpdate = true;
                // If the extra is set, we should automatically share the current folder with the
                // given device.
                applySharedDeviceExtra();
            } else {
                // Edit mode. The folder is looked up in the authoritative configuration below, which
                // in superuser mode can wait for a bounded helper session.
                Log.d(TAG, "Initializing edit mode: folder.id="
                        + getIntent().getStringExtra(EXTRA_FOLDER_ID));
            }
        }

        // Show expert options conditionally.
        mIgnoreDeleteContainer.setVisibility(mPrefExpertMode ? View.VISIBLE : View.GONE);
        mRunScriptContainer.setVisibility(mPrefExpertMode ? View.VISIBLE : View.GONE);

        // Register OnBackPressedCallback
        getOnBackPressedDispatcher().addCallback(this, mBackPressedCallback);

        // The editor opens with the configuration read this starts, so until its answer arrives the
        // editor accepts no edits and cannot be saved.
        startEditorStartupLoad();
    }

    private void restoreDialogStates(Bundle savedInstanceState) {
        if (savedInstanceState.getBoolean(IS_SHOWING_DELETE_DIALOG)) {
            showDeleteDialog();
        } else if (savedInstanceState.getBoolean(IS_SHOW_DISCARD_DIALOG)) {
            showDiscardDialog();
        }

    }
    /**
     * Opens this editor with the authoritative configuration.
     *
     * <p>Reading that configuration resolves configured folder paths through the selected backend,
     * which in superuser mode can wait for a bounded helper session, so the read runs away from the
     * main thread and reports back through the main handler. Until it answers, the editor accepts no
     * edits and cannot be saved, so a field the answer fills in cannot be overwritten by an edit that
     * started while the read was still running, and a save can never run against a folder that was
     * not read yet.</p>
     *
     * <p>An editor the user left, a read a newer one replaced, and one whose activity is finishing
     * all receive no answer.</p>
     */
    private void startEditorStartupLoad() {
        // What the read has to produce is decided here, on the main thread, so that the worker never
        // reads editor state this thread can still change.
        final String folderIdToResolve = (mFolder == null && !mIsCreateMode)
                ? getIntent().getStringExtra(EXTRA_FOLDER_ID)
                : null;
        setBusyState(true, R.string.state_loading);
        mEditorStartupLoad.start(
                () -> !isFinishing() && !isDestroyed(),
                () -> readEditorStartup(mConfig, folderIdToResolve),
                this::applyEditorStartup
        );
    }

    /**
     * Reads the configuration one folder editor opens with.
     *
     * <p>Runs on the queue's worker. A configuration that cannot be read reports no folder and no
     * devices instead of throwing, so that the editor always receives an answer and can never be
     * left waiting for one that will not come.</p>
     *
     * @param config configuration the editor reads through
     * @param folderIdToResolve identifier of the folder to look up, or {@code null} when the editor
     *                          already holds its folder and only the device list is needed
     */
    private static EditorStartup readEditorStartup(ConfigRouter config, String folderIdToResolve) {
        Folder folder = null;
        List<Device> devices = new ArrayList<>();
        try {
            if (folderIdToResolve != null) {
                folder = FolderEditorStartupLoad.select(
                        config.getFolders(null),
                        folderIdToResolve,
                        configured -> configured.id
                );
            }
            devices = config.getDevices(null, false);
        } catch (RuntimeException unreadableConfiguration) {
            Log.w(TAG, "readEditorStartup: Could not read the configuration: "
                    + unreadableConfiguration.getMessage());
        }
        return new EditorStartup(folder, devices);
    }

    /**
     * Opens this editor once the configuration read it started answered.
     *
     * <p>Runs on the main thread, and only while this editor is still there: a read that this editor
     * replaced, or that outlived the editor, never reaches it.</p>
     *
     * @param startup configuration this editor was waiting for
     */
    private void applyEditorStartup(EditorStartup startup) {
        if (mFolder == null && !mIsCreateMode) {
            mFolder = startup.folder;
            if (mFolder == null) {
                Log.w(TAG, "Folder not found in API update, maybe it was deleted?");
                setResult(Activity.RESULT_CANCELED);
                finish();
                return;
            }
            mFolderNeedsToUpdate = false;
            // The loaded folder carries the path the configuration holds, and the editor may only
            // read and keep an ignore list for that path.
            mConfiguredFolderPath = mFolder.path;
            // The ignore list read resolves the folder path through the selected backend as well, so
            // it runs away from the main thread and opens the editor once the list arrived.
            loadFolderIgnoreList(mFolder);
            applySharedDeviceExtra();
        }
        openEditor(startup.devices);
    }

    /**
     * Shares this folder with the device this editor was opened for, when one was passed.
     *
     * <p>Runs only once the editor holds the folder the sharing belongs to.</p>
     */
    private void applySharedDeviceExtra() {
        if (!getIntent().hasExtra(EXTRA_DEVICE_ID)) {
            return;
        }
        SharedWithDevice device = new SharedWithDevice();
        device.deviceID = getIntent().getStringExtra(EXTRA_DEVICE_ID);
        mFolder.addDevice(device);
        mFolderNeedsToUpdate = true;
    }

    /**
     * Shows this editor once it knows the folder it edits.
     *
     * @param devices devices the configuration holds, which the editor lists as the folder's sharing
     */
    private void openEditor(List<Device> devices) {
        if (mIsCreateMode) {
            mEditIgnoreListTitle.setEnabled(false);
            mEditIgnoreListContent.setEnabled(false);
        } else {
            // Edit mode.
            mIdView.setFocusable(false);
            mIdView.setEnabled(false);
            mPathView.setFocusable(false);
            mPathView.setEnabled(false);
            mSelectAdvancedDirectory.setVisibility(View.GONE);
            // The ignore list is still being read, so the editor starts closed to edits and opens
            // once the list it shows has arrived. A draft restored after a rotation is the user's
            // own list already, so that editor opens for editing right away.
            mIgnoreListDelivered = mIgnoreListNeedsToUpdate;
            applyIgnoreListEditorAvailability();
        }
        mFolderTypeContainer.setEnabled(!mFolder.type.equals(Constants.FOLDER_TYPE_RECEIVE_ENCRYPTED));
        checkWriteAndUpdateUI();
        updateViewsAndSetListeners(devices);

        // The editor holds everything it edits now, so it accepts edits and can be saved.
        setSavingState(false);

        // Open keyboard on label view in edit mode.
        mLabelView.requestFocus();
    }

    /** Configuration one folder editor opens with, read away from the main thread. */
    private static final class EditorStartup {
        /** Folder the editor was opened for, or {@code null} when it is not configured. */
        private final Folder folder;

        /** Devices of the configuration the folder was read from. */
        private final List<Device> devices;

        private EditorStartup(Folder folder, List<Device> devices) {
            this.folder = folder;
            this.devices = devices;
        }
    }


    /**
     * Invoked after user clicked on the {@link #mPathView} label.
     */
    @SuppressLint("InlinedAPI")
    private void onPathViewClick() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        
        // Determine directory initialUri for SAF file picker dialog.
        // This has to be android.net.Uri as it implements a Parcelable.
        android.net.Uri initialUri = null;
        android.net.Uri externalFilesDirUri = FileUtils.getExternalFilesDirUri(FolderActivity.this, ExternalStorageDirType.INT_MEDIA);
        if (FileUtils.directoryUriExists(FolderActivity.this, externalFilesDirUri)) {
            initialUri = externalFilesDirUri;
        } else {
            android.net.Uri internalFilesDirUri = FileUtils.getInternalStorageRootUri();
            if (FileUtils.directoryUriExists(FolderActivity.this, internalFilesDirUri)) {
                initialUri = internalFilesDirUri;
            }
        }
        if (initialUri != null) {
            Log.v(TAG, "onPathViewClick: INITIAL_URI = " + initialUri);
            intent.putExtra("android.provider.extra.INITIAL_URI", initialUri);
        }

        // Display storage access framework directory picker UI.
        intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
        intent.putExtra("android.content.extra.SHOW_ADVANCED", true);
        try {
            startActivityForResult(intent, CHOOSE_FOLDER_REQUEST);
        } catch (android.content.ActivityNotFoundException e) {
            Log.e(TAG, "onPathViewClick exception, falling back to built-in FolderPickerActivity.", e);
            startActivityForResult(FolderPickerActivity.createIntent(this, mFolder.path, null),
                FolderPickerActivity.DIRECTORY_REQUEST_CODE);
        }
    }

    /**
     * Open dialog if the user clicked on empty device list view.
     */
    private void showAddDeviceDialog() {
        startActivityForResult(DeviceActivity.createIntent(this), DeviceActivity.DEVICE_ADD_CODE);
    }

    /**
     * Invoked after user clicked on the {@link #mCustomSyncConditionsDialog} label.
     */
    private void onCustomSyncConditionsDialogClick() {
        startActivityForResult(
            SyncConditionsActivity.createIntent(
                this, Constants.PREF_OBJECT_PREFIX_FOLDER + mFolder.id, mFolder.label
            ),
            0
        );
    }

    /**
     * Invoked after user clicked on the select advanced directory button.
     */
    private void onSelectAdvancedDirectoryClick() {
        startActivityForResult(FolderPickerActivity.createIntent(this, mFolder.path, null),
            FolderPickerActivity.DIRECTORY_REQUEST_CODE);
    }

    private void showFolderTypeDialog() {
        if (TextUtils.isEmpty(mFolder.path)) {
            Toast.makeText(this, R.string.folder_path_required, Toast.LENGTH_LONG)
                    .show();
            return;
        }
        if (!mCanWriteToPath) {
            /**
             * Do not handle the click as the children in the folder type layout are disabled
             * and an explanation is already given on the UI why the only allowed folder type
             * is "sendonly". Only a verdict that proved read-only access may say so; an
             * undetermined verdict states the undetermined result instead of claiming a
             * permission state the probe never established.
             */
            Toast.makeText(
                    this,
                    FolderTypePolicy.provesReadOnlyAccess(mPathWriteability)
                            ? R.string.folder_path_readonly
                            : R.string.state_unknown,
                    Toast.LENGTH_LONG
            ).show();
            return;
        }
        // The user selected folder path is writeable, offer to choose from all available folder types.
        Intent intent = new Intent(this, FolderTypeDialogActivity.class);
        intent.putExtra(FolderTypeDialogActivity.EXTRA_FOLDER_TYPE, mFolder.type);
        startActivityForResult(intent, FOLDER_TYPE_DIALOG_REQUEST);
    }

    private void showPullOrderDialog() {
        Intent intent = new Intent(this, PullOrderDialogActivity.class);
        intent.putExtra(PullOrderDialogActivity.EXTRA_PULL_ORDER, mFolder.order);
        startActivityForResult(intent, PULL_ORDER_DIALOG_REQUEST);
    }

    private void showVersioningDialog() {
        Intent intent = new Intent(this, VersioningDialogActivity.class);
        intent.putExtras(getVersioningBundle());
        startActivityForResult(intent, FILE_VERSIONING_DIALOG_REQUEST);
    }

    private Bundle getVersioningBundle() {
        Bundle bundle = new Bundle();
        for (Map.Entry<String, String> entry: mFolder.versioning.params.entrySet()){
            bundle.putString(entry.getKey(), entry.getValue());
        }

        if (TextUtils.isEmpty(mFolder.versioning.type)){
            bundle.putString("type", "none");
        } else{
            bundle.putString("type", mFolder.versioning.type);
        }

        return bundle;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // Stops accepting folder work and interrupts what is still running, so no result of
        // this editor can reach a view after it was left.
        mFolderWork.close();
        SyncthingService syncthingService = getService();
        if (syncthingService != null) {
            syncthingService.getNotificationHandler().cancelConsentNotification(getIntent().getIntExtra(EXTRA_NOTIFICATION_ID, 0));
        }
        mLabelView.removeTextChangedListener(mTextWatcher);
        mIdView.removeTextChangedListener(mTextWatcher);
        mEditIgnoreListContent.removeTextChangedListener(mIgnoreListContentTextWatcher);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("mFolder", new Gson().toJson(mFolder));
        outState.putBoolean("mFolderNeedsToUpdate", mFolderNeedsToUpdate);
        outState.putBoolean("mIgnoreListNeedsToUpdate", mIgnoreListNeedsToUpdate);
        outState.putParcelable("mFolderUri", mFolderUri);

        outState.putBoolean(IS_SHOWING_DELETE_DIALOG, mDeleteDialog != null && mDeleteDialog.isShowing());
        Util.dismissDialogSafe(mDeleteDialog, this);

        outState.putBoolean(IS_SHOW_DISCARD_DIALOG, mDiscardDialog != null && mDiscardDialog.isShowing());
        Util.dismissDialogSafe(mDiscardDialog, this);
    }

    /**
     * Register for service state change events.
     */
    @Override
    public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
        super.onServiceConnected(componentName, iBinder);
        SyncthingServiceBinder syncthingServiceBinder = (SyncthingServiceBinder) iBinder;
        SyncthingService syncthingService = (SyncthingService) syncthingServiceBinder.getService();
        syncthingService.getNotificationHandler().cancelConsentNotification(getIntent().getIntExtra(EXTRA_NOTIFICATION_ID, 0));
    }

    private void onReceiveFolderIgnoreList(FolderIgnoreList folderIgnoreList) {
        mEditIgnoreListContent.setMaxLines(Integer.MAX_VALUE);
        mEditIgnoreListContent.removeTextChangedListener(mIgnoreListContentTextWatcher);
        if (folderIgnoreList.ignore != null) {
            String ignoreList = TextUtils.join("\n", folderIgnoreList.ignore);
            mEditIgnoreListContent.setText(ignoreList);
        }
        mEditIgnoreListContent.addTextChangedListener(mIgnoreListContentTextWatcher);
        mIgnoreListDelivered = true;
        applyIgnoreListEditorAvailability();
    }

    /**
     * Reads one folder's ignore list away from the main thread and applies it there.
     *
     * <p>The read resolves the folder path through the selected backend, which can acquire a
     * bounded helper session in superuser mode, so it must not run on the thread that draws the
     * editor. A read that fails reports the ignore list as unavailable, because a failure must
     * never appear as an empty or missing ignore list, and an answer that arrives after the editor
     * was left is dropped instead of touching its views.</p>
     */
    private void loadFolderIgnoreList(final Folder folder) {
        final String folderId = folder.id;
        final String readPath = folder.path;
        if (!IgnoreListEditorGate.mayReadListForShownPath(mConfiguredFolderPath, readPath)) {
            // The configuration does not hold the path the editor shows yet. A read would resolve
            // the configured path instead while its answer is tagged with the path shown, so
            // another folder's rules would pass as the shown path's list. The read is therefore
            // not requested, and the editor stays closed to edits.
            closeIgnoreListToEdits();
            return;
        }
        mIgnoreListReadPath = readPath;
        // The read may take a while: in Superuser Mode it first acquires a bounded helper session
        // and answers through a callback afterwards. It therefore runs as an asynchronous request,
        // whose answer the queue hands back only while the request is still the newest one.
        mFolderWork.<FolderIgnoreList>submitAsync(
                answer -> {
                    try {
                        mConfig.getFolderIgnoreList(null, folder, answer::deliver);
                    } catch (FolderOperationException | RuntimeException failure) {
                        Log.w(TAG, "Could not read the ignore list of folder=[" + folderId
                                + "]: " + failure.getMessage());
                        // An answer that carries no list at all reports the failure, so the editor
                        // can show the ignore list as unavailable instead of as an empty one.
                        answer.deliver(null);
                    }
                },
                result -> deliverFolderIgnoreList(folderId, readPath, result));
    }

    /**
     * Applies one ignore-list read on the main thread, unless the editor moved on.
     *
     * <p>The answer belongs to the folder and to the path it was read for. An answer for a path
     * the user replaced is dropped, because applying it would leave the editor showing the rules
     * of the replaced path while the form holds the path chosen now.</p>
     */
    private void deliverFolderIgnoreList(
            String folderId,
            String readPath,
            FolderIgnoreList folderIgnoreList
    ) {
        IgnoreListEditorGate.Verdict verdict = IgnoreListEditorGate.verdict(
                !isFinishing() && !isDestroyed(),
                IgnoreListEditorGate.readBelongsToShownFolder(
                        folderId,
                        readPath,
                        mFolder == null ? null : mFolder.id,
                        mFolder == null ? null : mFolder.path
                ),
                mIgnoreListNeedsToUpdate
        );
        if (verdict != IgnoreListEditorGate.Verdict.APPLY) {
            return;
        }
        if (folderIgnoreList == null) {
            showFolderIgnoreListUnavailable();
            return;
        }
        mIgnoreListHeldPath = readPath;
        onReceiveFolderIgnoreList(folderIgnoreList);
    }

    /**
     * Reports one ignore list that could not be read, on the main thread.
     *
     * <p>The folder keeps the rules it has, and the editor stops accepting edits to a list it
     * could not read, because an unreadable list must not look like a missing or empty one that
     * the next save would replace.</p>
     */
    private void showFolderIgnoreListUnavailable() {
        // The editor never holds this list, so it stays closed to edits even if a writeability
        // verdict arrives later and would otherwise open it.
        mIgnoreListDelivered = false;
        applyIgnoreListEditorAvailability();
        Toast.makeText(this, R.string.generic_error, Toast.LENGTH_LONG).show();
    }

    /**
     * Reads the device list again after a device was added.
     *
     * <p>The editor shows the devices its folder is shared with, and the device the user just added
     * is only in the configuration the editor is not holding yet. That configuration is read on the
     * editor's worker queue: in Superuser Mode the read resolves the folder path through the
     * selected backend, which can wait for a bounded privileged helper activation, so reading it
     * here would block the thread that draws the editor whenever REST is not available.</p>
     *
     * <p>Only the device list of the answer is applied. The folder this editor holds is left
     * untouched, so the edits the user has already made are not discarded, and an answer that this
     * editor replaced, or one that arrives after it was left, never reaches any view.</p>
     */
    private void reloadDeviceList() {
        mEditorStartupLoad.start(
                () -> !isFinishing() && !isDestroyed(),
                () -> readEditorStartup(mConfig, null),
                startup -> updateViewsAndSetListeners(startup.devices)
        );
    }

    /**
     * Updates the views with the folder this editor holds.
     *
     * @param preloadedDevices device list that was read away from the main thread, or {@code null}
     *                         to read it here
     */
    private void updateViewsAndSetListeners(List<Device> preloadedDevices) {
        mLabelView.removeTextChangedListener(mTextWatcher);
        mIdView.removeTextChangedListener(mTextWatcher);
        mFolderFileWatcher.setOnCheckedChangeListener(null);
        mFolderPaused.setOnCheckedChangeListener(null);
        mCustomSyncConditionsSwitch.setOnCheckedChangeListener(null);
        mIgnoreDelete.setOnCheckedChangeListener(null);
        mRunScriptSwitch.setOnCheckedChangeListener(null);

        // Update views
        mLabelView.setText(mFolder.label);
        mIdView.setText(mFolder.id);
        updateFolderTypeDescription();
        updatePullOrderDescription();
        updateVersioningDescription();
        mFolderFileWatcher.setChecked(mFolder.fsWatcherEnabled);
        mFolderPaused.setChecked(mFolder.paused);
        mIgnoreDelete.setChecked(mFolder.ignoreDelete);
        mRunScriptSwitch.setChecked(mPreferences.getBoolean(
                Constants.DYN_PREF_OBJECT_FOLDER_RUN_SCRIPT(mFolder.id), false
            ));
        findViewById(R.id.editIgnoresContainer).setVisibility(mIsCreateMode ? View.GONE : View.VISIBLE);

        // Update views - custom sync conditions.
        mCustomSyncConditionsSwitch.setChecked(false);
        if (mIsCreateMode) {
            findViewById(R.id.customSyncConditionsContainer).setVisibility(View.GONE);
        } else {
            mCustomSyncConditionsSwitch.setChecked(mPreferences.getBoolean(
                Constants.DYN_PREF_OBJECT_CUSTOM_SYNC_CONDITIONS(Constants.PREF_OBJECT_PREFIX_FOLDER + mFolder.id), false
            ));
        }
        mCustomSyncConditionsSwitch.setEnabled(!mIsCreateMode);
        mCustomSyncConditionsDescription.setEnabled(mCustomSyncConditionsSwitch.isChecked());
        mCustomSyncConditionsDialog.setFocusable(mCustomSyncConditionsSwitch.isChecked());
        mCustomSyncConditionsDialog.setEnabled(mCustomSyncConditionsSwitch.isChecked());

        // Populate devicesList.
        // Populate devicesList, from the configuration that was read with this editor when it
        // carried one, so that opening the editor never reads the configuration on this thread.
        List<Device> devicesList = (preloadedDevices != null)
                ? preloadedDevices
                : mConfig.getDevices(getApi(), false);
        mDevicesContainer.removeAllViews();
        if (devicesList.isEmpty()) {
            addEmptyDeviceListView();
        } else {
            for (Device device : devicesList) {
                addDeviceViewAndSetListener(device, getLayoutInflater());
            }
        }

        // Keep state updated
        mLabelView.addTextChangedListener(mTextWatcher);
        mIdView.addTextChangedListener(mTextWatcher);
        mFolderFileWatcher.setOnCheckedChangeListener(mCheckedListener);
        mFolderPaused.setOnCheckedChangeListener(mCheckedListener);
        mCustomSyncConditionsSwitch.setOnCheckedChangeListener(mCheckedListener);
        mIgnoreDelete.setOnCheckedChangeListener(mCheckedListener);
        mRunScriptSwitch.setOnCheckedChangeListener(mCheckedListener);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.folder_settings, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem saveItem = menu.findItem(R.id.save);
        saveItem.setTitle(mIsCreateMode ? R.string.create : R.string.save_title);
        saveItem.setEnabled(!mIsSaving);
        MenuItem removeItem = menu.findItem(R.id.remove);
        removeItem.setVisible(!mIsCreateMode);
        removeItem.setEnabled(!mIsSaving);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (mIsSaving) {
            return true;
        }
        int itemId = item.getItemId();
        if (itemId == R.id.save) {
            item.setEnabled(false);
            onSave();
            return true;
        } else if (itemId == R.id.remove) {
            showDeleteDialog();
            return true;
        } else if (itemId == android.R.id.home) {
            mBackPressedCallback.handleOnBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void setSavingState(boolean isSaving) {
        setBusyState(isSaving, R.string.state_saving);
    }

    /**
     * Holds the editor busy, or releases it.
     *
     * <p>A busy editor accepts no edits, no save, no removal and no back press, and the overlay that
     * covers it reports the wait through the given message. Saving a folder and waiting for the
     * configuration the editor opens with are different waits, so each of them names itself.</p>
     *
     * @param busy whether the editor is busy
     * @param message string resource naming the wait
     */
    private void setBusyState(boolean busy, int message) {
        mIsSaving = busy;
        invalidateOptionsMenu();
        if (mSavingOverlay != null) {
            TextView messageView = mSavingOverlay.findViewById(R.id.savingText);
            if (messageView != null) {
                messageView.setText(message);
            }
            mSavingOverlay.setVisibility(busy ? View.VISIBLE : View.GONE);
        }
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setHomeButtonEnabled(!busy);
            // Keep the back icon visible while the editor is busy; clicks are blocked in
            // onOptionsItemSelected.
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
    }

    private void showDeleteDialog(){
        mDeleteDialog = new AlertDialog.Builder(this)
                .setMessage(R.string.remove_folder_confirm)
                .setPositiveButton(android.R.string.yes, (dialogInterface, i) -> {
                    final RestApi removeRestApi = getApi();
                    final Folder removedFolder = mFolder;
                    // Removing a folder is a configuration write the user confirmed, so it runs
                    // away from the main thread without depending on the editor staying open. The
                    // editor reports that the removal is under way and stays busy until the
                    // completion arrives, so no other edit can race the configuration write.
                    setSavingState(true);
                    runConfirmedFolderWrite(
                            () -> {
                                try {
                                    mConfig.removeFolder(removeRestApi, removedFolder.id);
                                } catch (RuntimeException failure) {
                                    Log.e(
                                            TAG,
                                            "Could not remove folder=[" + removedFolder.id + "]",
                                            failure
                                    );
                                    return Boolean.FALSE;
                                }
                                return Boolean.TRUE;
                            },
                            removed -> completeFolderRemoval(removed, removedFolder));
                })
                .setNegativeButton(android.R.string.no, null)
                .create();
        mDeleteDialog.show();
    }

    /**
     * Finishes the editor after a folder removal, on the main thread.
     *
     * <p>The removal itself runs away from the main thread, so its completion is what closes the
     * editor. A completion that arrives after the editor was already left is dropped, and a removal
     * that did not happen keeps the editor open, because closing it would claim a configuration
     * change the folder list does not reflect.</p>
     *
     * @param removed whether the removal was written, or {@code null} when it failed unexpectedly
     * @param removedFolder the folder the user confirmed, so a successful removal can withdraw
     *     the consent that only exists while that folder stays configured
     */
    private void completeFolderRemoval(Boolean removed, Folder removedFolder) {
        if (withdrawsSyncthingCameraConsent(removed, removedFolder.id)) {
            // The removal really happened, so consent to the "Syncthing Camera" feature is
            // withdrawn here, before the editor is looked at: the removal runs away from the main
            // thread and the editor that asked for it can already be destroyed, for example by a
            // rotation, while the removal was still running.
            SharedPreferences.Editor editor = mPreferences.edit();
            editor.putBoolean(Constants.PREF_ENABLE_SYNCTHING_CAMERA, false);
            editor.apply();
        }
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (removed == null || !removed) {
            // The removal did not happen, so the editor becomes usable again and reports it.
            setSavingState(false);
            Toast.makeText(this, R.string.generic_error, Toast.LENGTH_LONG).show();
            return;
        }
        mFolderNeedsToUpdate = false;
        finish();
    }

    /**
     * Returns whether one finished folder removal withdraws consent to the "Syncthing Camera"
     * feature.
     *
     * <p>Only a confirmed removal of the camera folder withdraws the consent, because a removal
     * that did not happen or failed unexpectedly keeps the folder configured. The rule lives here,
     * away from the editor, because it has to hold whether or not the editor still exists.</p>
     *
     * @param removed removal result, {@code null} when the removal failed unexpectedly
     * @param removedFolderId identifier of the folder the user confirmed for removal
     * @return {@code true} only for a confirmed removal of the camera folder
     */
    static boolean withdrawsSyncthingCameraConsent(Boolean removed, String removedFolderId) {
        return Boolean.TRUE.equals(removed)
                && Constants.syncthingCameraFolderId.equals(removedFolderId);
    }

    /**
     * Runs one configuration write the user confirmed away from the main thread.
     *
     * <p>A folder write goes through the selected backend, which in superuser mode acquires a
     * bounded helper session, so it must not run on the thread that draws the editor. It must also
     * not depend on the editor staying open: the user already confirmed the write, and a write that
     * is interrupted while the editor is left is discarded without any result. The write therefore
     * runs on an executor of its own, and only its completion returns to the main thread.</p>
     *
     * <p>A write that fails unexpectedly produces the {@code null} result, so its completion still
     * runs and the editor is never left waiting for an answer that cannot arrive.</p>
     *
     * @param write the confirmed write
     * @param onCompletion handler of the write result, run on the main thread
     */
    private <T> void runConfirmedFolderWrite(
            FolderWorkQueue.Work<T> write,
            FolderWorkQueue.OwnerDelivery<T> onCompletion
    ) {
        Handler mainHandler = new Handler(Looper.getMainLooper());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            T result;
            try {
                result = write.run();
            } catch (RuntimeException failure) {
                Log.e(TAG, "A confirmed folder write did not complete", failure);
                result = null;
            }
            T delivered = result;
            mainHandler.post(() -> onCompletion.deliver(delivered));
        });
        executor.shutdown();
    }


    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == Activity.RESULT_OK && requestCode == CHOOSE_FOLDER_REQUEST) {
            // This result case only occurs on API level >= Build.VERSION_CODES.LOLLIPOP (21)
            mFolderUri = data.getData();
            if (mFolderUri == null) {
                return;
            }
            // Get the folder path unix style, e.g. "/storage/0000-0000/DCIM"
            String targetPath = FileUtils.getAbsolutePathFromSAFUri(FolderActivity.this, mFolderUri);
            if (targetPath != null) {
                targetPath = Util.formatPath(targetPath);
            }
            if (targetPath == null || TextUtils.isEmpty(targetPath) || (targetPath.equals(File.separator))) {
                mFolder.path = "";
                mFolderUri = null;
                checkWriteAndUpdateUI();
                // Show message to the user suggesting to select a folder on internal or external storage.
                Toast.makeText(this, R.string.toast_invalid_folder_selected, Toast.LENGTH_LONG).show();
                return;
            }
            mFolder.path = FileUtils.cutTrailingSlash(targetPath);
            Log.v(TAG, "onActivityResult/CHOOSE_FOLDER_REQUEST: Got directory path '" + mFolder.path + "'");
            checkWriteAndUpdateUI();
            // Postpone sending the config changes using syncthing REST API.
            mFolderNeedsToUpdate = true;
        } else if (resultCode == Activity.RESULT_OK && requestCode == FolderPickerActivity.DIRECTORY_REQUEST_CODE) {
            mFolder.path = FileUtils.cutTrailingSlash(data.getStringExtra(FolderPickerActivity.EXTRA_RESULT_DIRECTORY));
            Log.v(TAG, "onActivityResult/DIRECTORY_REQUEST_CODE: Got directory path '" + mFolder.path + "'");
            checkWriteAndUpdateUI();
            // Postpone sending the config changes using syncthing REST API.
            mFolderNeedsToUpdate = true;
        } else if (resultCode == Activity.RESULT_OK && requestCode == FILE_VERSIONING_DIALOG_REQUEST) {
            updateVersioning(data.getExtras());
        } else if (resultCode == Activity.RESULT_OK && requestCode == FOLDER_TYPE_DIALOG_REQUEST) {
            String newFolderType = data.getStringExtra(FolderTypeDialogActivity.EXTRA_RESULT_FOLDER_TYPE);
            if (!mIsCreateMode && newFolderType.equals(Constants.FOLDER_TYPE_RECEIVE_ENCRYPTED)) {
                // Disallow switching existing folder's type to receiveEncrypted.
                // SyncthingNative also does this. Posting a wrong config will result in http code 500.
                Toast.makeText(this, R.string.folder_type_switch_to_receive_encrypted_not_allowed, Toast.LENGTH_LONG).show();
                return;
            }
            mFolder.type = newFolderType;
            updateFolderTypeDescription();
            mFolderNeedsToUpdate = true;
        } else if (resultCode == Activity.RESULT_OK && requestCode == PULL_ORDER_DIALOG_REQUEST) {
            mFolder.order = data.getStringExtra(PullOrderDialogActivity.EXTRA_RESULT_PULL_ORDER);
            updatePullOrderDescription();
            mFolderNeedsToUpdate = true;
        } else if (resultCode == Activity.RESULT_OK && requestCode == DeviceActivity.DEVICE_ADD_CODE) {
            reloadDeviceList();
        }
    }

    /**
     * Prerequisite: mFolder.path must be non-empty
     *
     * <p>The check runs off the main thread and its result is applied on the main thread. A verdict
     * that a newer check already replaced, and one that arrives after the editor was left, are both
     * discarded, and a probe that cannot reach a verdict leaves the editor with the read-only
     * capabilities instead of enabling a folder type that may not be writable.</p>
     */
    private void checkWriteAndUpdateUI() {
        mPathView.setText(mFolder.path);
        final String candidatePath = mFolder.path;
        if (TextUtils.isEmpty(candidatePath)) {
            // An empty path has no writeability to report, and it cancels every verdict that is
            // still in flight for the path the user replaced it with. A save that was waiting for
            // such a verdict is released, so it reports the missing path instead of waiting for a
            // result that can no longer arrive.
            mPathWriteabilityPending = false;
            mFolderWork.cancelPending();
            // The read of the ignore list belonged to the path the user replaced, so a path chosen
            // later is read again instead of leaving the editor closed to ignore-list edits. The
            // list the editor still holds is that path's list, so it may not keep accepting edits.
            mIgnoreListReadPath = null;
            closeIgnoreListToEdits();
            if (mSaveAwaitsWriteability) {
                mSaveAwaitsWriteability = false;
                setSavingState(false);
                onSave();
            }
            return;
        }
        mPathWriteabilityPending = true;
        mFolderWork.submit(() -> {
            try {
                return mRuntime.validateCandidateFolder(candidatePath);
            } catch (FolderOperationException | RuntimeException e) {
                Log.w(TAG, "checkWriteAndUpdateUI: Could not check path '" + candidatePath
                        + "': " + e.getMessage());
                return FolderWriteability.UNKNOWN;
            }
        }, this::applyFolderWriteability);
        // The editor may only keep accepting edits while the list it holds belongs to the path
        // shown now. A list of the path the user replaced is closed here, so the read below
        // restores the editor's edits for the path chosen now.
        if (IgnoreListEditorGate.deliveredListIsForAnotherPath(
                mIgnoreListDelivered,
                candidatePath,
                mIgnoreListReadPath
        )) {
            closeIgnoreListToEdits();
        }
        if (!mIgnoreListDelivered
                && IgnoreListEditorGate.heldListBelongsToShownPath(
                        mIgnoreListHeldPath,
                        mConfiguredFolderPath,
                        candidatePath
                )) {
            // The editor holds the list of the path shown now again, so it keeps editing it
            // without another read.
            mIgnoreListDelivered = true;
            applyIgnoreListEditorAvailability();
        }
        reloadIgnoreListForCandidatePath(candidatePath);
    }

    /**
     * Reads the ignore list again when the editor holds none for the path the user chose.
     *
     * <p>A read that was still in flight when the folder path changed is discarded, and the editor
     * stays closed to edits until a list for the current path arrives. The chosen path therefore
     * has to be read, or a folder that was confirmed for another path could never have its ignore
     * list edited.</p>
     *
     * @param candidatePath path the user chose for the folder
     */
    private void reloadIgnoreListForCandidatePath(String candidatePath) {
        if (mFolder == null
                || !IgnoreListEditorGate.needsRead(
                        mIsCreateMode,
                        mIgnoreListDelivered,
                        candidatePath,
                        mIgnoreListReadPath
                )) {
            return;
        }
        loadFolderIgnoreList(mFolder);
    }

    /**
     * Closes the ignore-list editor to edits until the list of the path shown now has arrived.
     *
     * <p>A save writes the ignore-list rules the editor holds to the folder path the form shows,
     * so the editor may only accept edits while it holds the list that was read for that same
     * path. This closes it once the path was replaced, and it stays closed until the read of the
     * path shown now delivers its list.</p>
     */
    private void closeIgnoreListToEdits() {
        if (!mIgnoreListDelivered) {
            return;
        }
        mIgnoreListDelivered = false;
        applyIgnoreListEditorAvailability();
    }

    /**
     * Applies the ignore-list editor's availability.
     *
     * <p>The editor accepts edits only while it holds the list that was read for its folder and the
     * folder was proven writable, so the patterns a user writes can only replace rules the user
     * saw. A read that failed leaves the editor closed, and a later writeability verdict cannot
     * reopen an editor whose list never arrived.</p>
     */
    private void applyIgnoreListEditorAvailability() {
        boolean editable = IgnoreListEditorGate.acceptsEdits(
                mIgnoreListDelivered,
                mCanWriteToPath
        );
        mEditIgnoreListTitle.setEnabled(editable);
        mEditIgnoreListContent.setEnabled(editable);
    }

    /**
     * Applies one candidate-folder verdict to the folder editor on the main thread.
     *
     * <p>A verdict that a newer check already replaced never reaches this method, so the editor
     * only ever shows the verdict of the newest request. A create-mode save that arrived while
     * the verdict was still on its way starts here, so the new folder is always written with the
     * folder type this verdict selected.</p>
     *
     * <p>Only a verdict that proved read-only access may change the folder type. An undetermined
     * verdict keeps the configured type because it is not evidence about the folder.</p>
     */
    private void applyFolderWriteability(FolderWriteability writeability) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        mPathWriteabilityPending = false;
        mPathWriteability = writeability;

        /**
         * Check if the permissions we have on that folder is readonly or readwrite.
         * Access level readonly: folder can only be configured "sendonly".
         * Access level readwrite: folder can be configured "sendonly" or "sendreceive".
         */
        mCanWriteToPath = writeability == FolderWriteability.WRITABLE;
        if (mCanWriteToPath) {
            mAccessExplanationView.setText(R.string.folder_path_readwrite);
            mFolderTypeView.setEnabled(true);
            if (mIsCreateMode) {
                /**
                 * Suggest folder type FOLDER_TYPE_SEND_RECEIVE for folders to be created
                 * because the user most probably intentionally chose a special folder like
                 * "[storage]/Android/data/com.nutomic.syncthingandroid/files"
                 * or enabled root mode thus having write access.
                 * Default from {@link #initFolder} was already set in {@link #onCreate}.
                 *      mFolder.type = Constants.FOLDER_TYPE_SEND_RECEIVE;
                 * We won't set it again here as this would cause user selection to be reset on
                 * screen rotation - as we don't know if we restored the activity or created
                 * a fresh one.
                 */
                updateFolderTypeDescription();
            } else {
                applyIgnoreListEditorAvailability();
            }
        } else {
            /*
             * The path was not proven writable. Only a probe that proved read-only access may
             * force "sendonly" and explain itself as read-only access; an undetermined verdict, for
             * example when privileged access timed out, is not proof and must not persist a folder
             * type downgrade the user did not ask for, nor claim an access fact the probe never
             * established. No message describes an undetermined access result yet, so the editor
             * states the undetermined result the rest of the application already uses, and the
             * dedicated wording stays with the folder editor work that owns its strings.
             */
            mAccessExplanationView.setText(
                    FolderTypePolicy.provesReadOnlyAccess(writeability)
                            ? R.string.folder_path_readonly
                            : R.string.state_unknown
            );
            mFolderTypeView.setEnabled(false);
            applyIgnoreListEditorAvailability();
            mFolder.type = FolderTypePolicy.typeFor(writeability, mFolder.type);
            updateFolderTypeDescription();
        }
        if (mSaveAwaitsWriteability) {
            // The verdict the held save was waiting for is applied, so the save runs with the
            // folder type this verdict selected instead of the default the editor started with.
            mSaveAwaitsWriteability = false;
            setSavingState(false);
            onSave();
        }
    }

    private String generateRandomFolderId() {
        char[] chars = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
        StringBuilder sb = new StringBuilder();
        Random random = new Random();
        for (int i = 0; i < 10; i++) {
            if (i == 5) {
                sb.append("-");
            }
            char c = chars[random.nextInt(chars.length)];
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Init a new folder in mIsCreateMode, used in {@link #onCreate}.
     */
    private void initFolder() {
        mFolder = new Folder();
        mFolder.id = (getIntent().hasExtra(EXTRA_FOLDER_ID))
                ? getIntent().getStringExtra(EXTRA_FOLDER_ID)
                : generateRandomFolderId();
        mFolder.label = getIntent().getStringExtra(EXTRA_FOLDER_LABEL);
        if (!TextUtils.isEmpty(mFolder.label)) {
            mFolder.label = mFolder.label.trim();
        }
        mFolder.paused = false;
        if (getIntent().getBooleanExtra(EXTRA_RECEIVE_ENCRYPTED, false)) {
            mFolder.type = Constants.FOLDER_TYPE_RECEIVE_ENCRYPTED;
        } else {
            mFolder.type = Constants.FOLDER_TYPE_SEND_RECEIVE;      // Default for {@link #checkWriteAndUpdateUI}.
        }
        mFolder.minDiskFree = new Folder.MinDiskFree();
        mFolder.versioning = new Folder.Versioning();
        mFolder.versioning.type = "trashcan";
        mFolder.versioning.params.put("cleanoutDays", Integer.toString(14));
        mFolder.versioning.cleanupIntervalS = 0;
        mFolder.versioning.fsPath = "";
        mFolder.versioning.fsType = "basic";
    }

    private void addEmptyDeviceListView() {
        int height = (int) TypedValue.applyDimension(COMPLEX_UNIT_DIP, 48, getResources().getDisplayMetrics());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(WRAP_CONTENT, height);
        int dividerInset = getResources().getDimensionPixelOffset(R.dimen.material_divider_inset);
        int contentInset = getResources().getDimensionPixelOffset(R.dimen.abc_action_bar_content_inset_material);
        setMarginStart(params, dividerInset);
        setMarginEnd(params, contentInset);
        TextView emptyView = new TextView(mDevicesContainer.getContext());
        emptyView.setGravity(CENTER_VERTICAL);
        emptyView.setText(R.string.devices_list_empty);
        mDevicesContainer.addView(emptyView, params);
        mDevicesContainer.setOnClickListener(view -> showAddDeviceDialog());
    }

    private void addDeviceViewAndSetListener(Device device, LayoutInflater inflater) {
        SharedWithDevice sharedWithDevice = new SharedWithDevice();
        sharedWithDevice.deviceID = device.deviceID;
        sharedWithDevice.introducedBy = device.introducedBy;

        inflater.inflate(R.layout.item_device_form, mDevicesContainer);
        LinearLayout deviceView = (LinearLayout) mDevicesContainer.getChildAt(mDevicesContainer.getChildCount()-1);

        SwitchCompat switchView = (SwitchCompat) deviceView.getChildAt(0);
        switchView.setOnCheckedChangeListener(null);
        switchView.setChecked(mFolder.getDevice(device.deviceID) != null);
        switchView.setText(device.getDisplayName());
        switchView.setTag(sharedWithDevice);
        switchView.setOnCheckedChangeListener(mCheckedListener);

        EditText encryptPassView = (EditText) deviceView.getChildAt(1);
        encryptPassView.removeTextChangedListener(mTextWatcher);
        if (mFolder.getDevice(device.deviceID) != null) {
            encryptPassView.setText(mFolder.getDevice(device.deviceID).encryptionPassword);
        } else {
            encryptPassView.setVisibility(View.GONE);
        }
        encryptPassView.addTextChangedListener(mTextWatcher);
    }

    private void onSave() {
        if (mFolder == null) {
            Log.e(TAG, "onSave: mFolder == null");
            return;
        }
        if (mIsSaving) {
            Log.v(TAG, "onSave: save already in progress");
            return;
        }

        // Validate fields.
        if (TextUtils.isEmpty(mFolder.id)) {
            Toast.makeText(this, R.string.folder_id_required, Toast.LENGTH_LONG).show();
            return;
        }
        if (TextUtils.isEmpty(mFolder.label)) {
            Toast.makeText(this, R.string.folder_label_required, Toast.LENGTH_LONG).show();
            return;
        }
        if (TextUtils.isEmpty(mFolder.path)) {
            Toast.makeText(this, R.string.folder_path_required, Toast.LENGTH_LONG).show();
            return;
        }

        if (mPathWriteabilityPending) {
            // A folder is written with the folder type the writeability verdict selects, so a
            // save that arrives before that verdict is held instead of writing the folder type
            // the verdict may replace. The verdict resumes it, and the overlay shows that the
            // save is under way.
            mSaveAwaitsWriteability = true;
            setSavingState(true);
            return;
        }

        setSavingState(true);

        SharedPreferences.Editor editor = mPreferences.edit();
        editor.putBoolean(
            Constants.DYN_PREF_OBJECT_FOLDER_RUN_SCRIPT(mFolder.id),
            mRunScriptSwitch.isChecked()
        );
        editor.apply();

        if (mIsCreateMode) {
            Log.v(TAG, "onSave: Adding folder with ID = '" + mFolder.id + "'");
            // Adding a folder prepares the folder structure and writes the configuration, which can
            // fail when the selected backend cannot reach the folder. The completion reports that
            // failure and clears the saving state instead of leaving the editor stuck in it.
            // The write runs off the main thread, so it reads the confirmed folder
            // through values captured here: the editor fields change while the write
            // is under way, and the write must use the folder the user saved.
            final Folder createdFolder = mFolder;
            final Uri createdFolderUri = mFolderUri;
            final ConfigRouter config = mConfig;
            final RestApi restApi = getApi();
            runConfirmedFolderWrite(
                    () -> {
                        try {
                            preCreateFolderStruct(createdFolderUri, createdFolder.path);
                            config.addFolder(restApi, createdFolder);
                        } catch (RuntimeException failure) {
                            Log.w(TAG, "Could not add folder=[" + createdFolder.id + "]", failure);
                            return FolderSaveOutcome.FAILED;
                        }
                        return FolderSaveOutcome.STORED;
                    },
                    outcome -> {
                        if (outcome == FolderSaveOutcome.STORED) {
                            // Start sync after adding a folder.
                            LocalBroadcastManager localBroadcastManager =
                                    LocalBroadcastManager.getInstance(
                                            getApplication().getApplicationContext()
                                    );
                            Intent intent = new Intent(ACTION_SYNC_TRIGGER_FIRED);
                            intent.putExtra(EXTRA_BEGIN_ACTIVE_TIME_WINDOW, true);
                        localBroadcastManager.sendBroadcast(intent);
                    }
                    completeFolderSave(outcome, createdFolder.path);
                });
            return;
        }

        // Edit mode.
        if (!mFolderNeedsToUpdate) {
            // We've got nothing to save.
            setResult(AppCompatActivity.RESULT_CANCELED);
            finish();
            return;
        }

        // Save folder specific preferences.
        Log.v(TAG, "onSave: Updating folder with ID = \'" + mFolder.id + "\'");
        editor = mPreferences.edit();
        editor.putBoolean(
            Constants.DYN_PREF_OBJECT_CUSTOM_SYNC_CONDITIONS(Constants.PREF_OBJECT_PREFIX_FOLDER + mFolder.id),
            mCustomSyncConditionsSwitch.isChecked()
        );
        editor.apply();

        // Update folder via restApi and send the config to REST endpoint.
        final RestApi restApi = getApi();
        final Folder savedFolder = mFolder;
        final String savedFolderPath = mFolder.path;
        // The editor only holds ignore patterns it read for the path the form shows. Patterns typed
        // before that path was replaced stay in the editor and are reported as the part of the save
        // that was not written, because writing them would replace another folder's rules.
        final boolean ignoreListPending = mIgnoreListNeedsToUpdate;
        final boolean ignoreListWritten = IgnoreListEditorGate.saveWritesIgnoreList(
                mIgnoreListDelivered,
                ignoreListPending
        );
        final String[] ignore = ignoreListWritten
                ? mEditIgnoreListContent.getText().toString().split("\n")
                : null;
        final boolean ignoreListLeftUnwritten = ignoreListPending && !ignoreListWritten;
        // The folder is written through the selected backend, which resolves the authoritative
        // folder path and can acquire a bounded helper session, so the write runs away from the
        // main thread. It is a save the user confirmed, so it does not depend on the editor staying
        // open, and only its completion returns to the main thread.
        runConfirmedFolderWrite(() -> {
            try {
                // The folder configuration is written first: the ignore list is written through
                // the selected backend, which resolves the folder path that configuration holds,
                // so a save that changes the path applies the path before the list is written.
                mConfig.updateFolder(restApi, savedFolder);
            } catch (RuntimeException failure) {
                // Nothing of this save happened, so it is reported as failed. The completion still
                // travels back, because an unanswered save would leave the editor in its saving
                // state without a way to try again.
                Log.w(TAG, "Could not write folder=[" + savedFolder.id + "]", failure);
                return FolderSaveOutcome.FAILED;
            }
            if (ignore == null) {
                // The folder itself is stored. Patterns the editor holds but could not be written
                // are reported as a partial save instead of being dropped silently.
                return ignoreListLeftUnwritten
                        ? FolderSaveOutcome.IGNORE_LIST_FAILED
                        : FolderSaveOutcome.STORED;
            }
            try {
                mConfig.postFolderIgnoreList(restApi, savedFolder, ignore);
            } catch (FolderOperationException | RuntimeException e) {
                // The folder itself is already written, so this failure is reported as a partial
                // save: the editor stays open with the user patterns in it, and the pending flag
                // makes a retry write them again.
                Log.w(TAG, "Could not write the ignore list of folder=[" + savedFolder.id
                        + "]: " + e.getMessage());
                return FolderSaveOutcome.IGNORE_LIST_FAILED;
            }
            return FolderSaveOutcome.STORED;
        }, outcome -> completeFolderSave(outcome, savedFolderPath));
    }

    /** How one folder save ended. */
    private enum FolderSaveOutcome {
        /** The folder and its ignore list are stored. */
        STORED,
        /** The folder is stored, but its ignore list is not. */
        IGNORE_LIST_FAILED,
        /** The folder itself could not be stored. */
        FAILED
    }

    /**
     * Finishes the editor after the folder was written, on the main thread.
     *
     * <p>The write itself runs away from the main thread, so its completion is what closes the
     * editor. A completion that arrives after the editor was already left is dropped.</p>
     *
     * <p>Only a fully stored save closes the editor with a positive result. When the ignore list
     * could not be stored, or when the folder itself could not be written, the editor stays open
     * with the user's patterns in it and the toast names what failed. Closing the editor would
     * claim a save that did not happen, and clearing the editor would discard the patterns the user
     * still wants to write.</p>
     *
     * @param outcome how the save ended, or {@code null} when the write failed unexpectedly
     * @param storedPath path the folder write stores, which the configuration holds once the folder
     *     itself was written
     */
    private void completeFolderSave(FolderSaveOutcome outcome, String storedPath) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        setSavingState(false);
        if (outcome == FolderSaveOutcome.STORED) {
            setResult(AppCompatActivity.RESULT_OK);
            finish();
            return;
        }
        String message = getString(R.string.generic_error);
        if (outcome == FolderSaveOutcome.IGNORE_LIST_FAILED) {
            // The folder itself was written, so the configuration now holds the stored path; only
            // its ignore list was not written.
            mConfiguredFolderPath = storedPath;
            message += ": " + getString(R.string.ignore_patterns);
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void preCreateFolderStruct(Uri uriFolderRoot, String absolutePath) {
        /**
         * Normally, syncthing takes care of creating the ".stfolder" marker.
         * This fails on Android 5+ if the syncthing binary only has
         * readonly access on the path and the user tries to configure a
         * sendOnly folder. To fix this, we'll precreate the marker using java code.
         */
        final String FOLDER_MARKER_DIR_NAME = new Folder().markerName;
        String strFolderMarkerPath = absolutePath + File.separator + FOLDER_MARKER_DIR_NAME;

        /**
         * Name of the dummy file created within the marker directory.
         * Creating the file is a workaround for issue where manufacturer
         * specific cleaning routines silently wipe out empty directories like
         * the marker directory.
         */
        final String DO_NOT_DELETE_FILE_NAME = "DO_NOT_DELETE";
        String strDoNotDeleteFile = strFolderMarkerPath + File.separator + DO_NOT_DELETE_FILE_NAME;

        /**
         * Precreate .stversions directory so we can put ".nomedia" in place to keep the gallery clean.
         */
        final String strStVersionsPath = absolutePath + File.separator + Constants.FOLDER_NAME_STVERSIONS;
        final String strStVersionsNoMediaFile = strStVersionsPath + File.separator + ".nomedia";

        // Fall back to classic API if uriFolderRoot is missing. E.g. in case FolderPickerActivity was used which only returns an absolute path.
        if (uriFolderRoot == null) {
            Log.w(TAG, "preCreateFolderStruct: uriFolderRoot == null. Using absolute path.");
            try {
                // ".stfolder"
                new File(strFolderMarkerPath).mkdirs();
                if (new File(strDoNotDeleteFile).createNewFile()) {
                    FileWriter writer = new FileWriter(strDoNotDeleteFile);
                    writer.write(DO_NOT_DELETE_FILE_NAME);
                    writer.close();
                }

                // ".stversions"
                new File(strStVersionsPath).mkdirs();
                new File(strStVersionsNoMediaFile).createNewFile();
            } catch (Exception e) {
                Log.e(TAG, "preCreateFolderStruct: Failed to create using absolute path.", e);
            }
            return;
        }

        // Derive DocumentFile handle from SAF tree Uri where we have write access.
        DocumentFile dfFolder = DocumentFile.fromTreeUri(this, uriFolderRoot);

        // Create ".stfolder" directory.
        DocumentFile dfFolderMarkerDir = FileUtils.safCreateDirectory(dfFolder, FOLDER_MARKER_DIR_NAME);
        if (dfFolderMarkerDir != null) {
            // Create ".stfolder/DO_NOT_DELETE.txt" file.
            FileUtils.safCreateFile(this, dfFolderMarkerDir, DO_NOT_DELETE_FILE_NAME + ".txt", DO_NOT_DELETE_FILE_NAME);
        }

        // Create ".stversions" directory.
        DocumentFile dfStVersionsDir = FileUtils.safCreateDirectory(dfFolder, Constants.FOLDER_NAME_STVERSIONS);
        if (dfStVersionsDir != null) {
            // Create ".stversions/.nomedia" file.
            FileUtils.safCreateFile(this, dfStVersionsDir, ".nomedia", "");
        }
    }

    private void showDiscardDialog(){
        mDiscardDialog = new AlertDialog.Builder(this)
                .setMessage(R.string.dialog_discard_changes)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        setResult(AppCompatActivity.RESULT_CANCELED);
                        finish();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        mDiscardDialog.show();
    }

    private void updateVersioning(Bundle arguments) {
        if (mFolder == null) {
            Log.e(TAG, "updateVersioning: mFolder == null");
            return;
        }
        if (mFolder.versioning == null) {
            mFolder.versioning = new Folder.Versioning();
        }

        String type = arguments.getString("type");
        arguments.remove("type");

        if (type.equals("none")) {
            mFolder.versioning = new Folder.Versioning();
            mFolder.versioning.type = "";
        } else {
            for (String key : arguments.keySet()) {
                mFolder.versioning.params.put(key, arguments.getString(key));
            }
            mFolder.versioning.type = type;
        }
        updateVersioningDescription();
        mFolderNeedsToUpdate = true;
    }

    private void updateFolderTypeDescription() {
        if (mFolder == null) {
            return;
        }

        switch (mFolder.type) {
            case Constants.FOLDER_TYPE_SEND_RECEIVE:
                setFolderTypeDescription(
                        getString(R.string.folder_type_sendreceive),
                        getString(R.string.folder_type_sendreceive_description),
                        R.drawable.baseline_folder_24
                );
                break;
            case Constants.FOLDER_TYPE_SEND_ONLY:
                setFolderTypeDescription(
                        getString(R.string.folder_type_sendonly),
                        getString(R.string.folder_type_sendonly_description),
                        R.drawable.baseline_upload_24
                );
                break;
            case Constants.FOLDER_TYPE_RECEIVE_ONLY:
                setFolderTypeDescription(
                        getString(R.string.folder_type_receiveonly),
                        getString(R.string.folder_type_receiveonly_description),
                        R.drawable.baseline_download_24
                );
                break;
            case Constants.FOLDER_TYPE_RECEIVE_ENCRYPTED:
                setFolderTypeDescription(
                        getString(R.string.folder_type_receive_encrypted),
                        getString(R.string.folder_type_receive_encrypted_description),
                        R.drawable.outline_lock_24
                );
                break;
        }

        // Disable "file pull order" option for sendOnly folders.
        mPullOrderContainer.setVisibility(mPrefExpertMode &&
                !mFolder.type.equals(Constants.FOLDER_TYPE_SEND_ONLY) ? View.VISIBLE : View.GONE);
    }

    private void setFolderTypeDescription(String type, String description, @DrawableRes int iconResId) {
        mFolderTypeView.setText(type);
        mFolderTypeDescriptionView.setText(description);
        mFolderTypeTitleView.setCompoundDrawablesRelativeWithIntrinsicBounds(iconResId, 0, 0, 0);
    }

    private void updatePullOrderDescription() {
        if (mFolder == null) {
            return;
        }

        if (TextUtils.isEmpty(mFolder.order)) {
            setPullOrderDescription(getString(R.string.pull_order_type_random),
                    getString(R.string.pull_order_type_random_description));
            return;
        }

        switch (mFolder.order) {
            case "random":
                setPullOrderDescription(getString(R.string.pull_order_type_random),
                        getString(R.string.pull_order_type_random_description));
                break;
            case "alphabetic":
                setPullOrderDescription(getString(R.string.pull_order_type_alphabetic),
                        getString(R.string.pull_order_type_alphabetic_description));
                break;
            case "smallestFirst":
                setPullOrderDescription(getString(R.string.pull_order_type_smallestFirst),
                        getString(R.string.pull_order_type_smallestFirst_description));
                break;
            case "largestFirst":
                setPullOrderDescription(getString(R.string.pull_order_type_largestFirst),
                        getString(R.string.pull_order_type_largestFirst_description));
                break;
            case "oldestFirst":
                setPullOrderDescription(getString(R.string.pull_order_type_oldestFirst),
                        getString(R.string.pull_order_type_oldestFirst_description));
                break;
            case "newestFirst":
                setPullOrderDescription(getString(R.string.pull_order_type_newestFirst),
                        getString(R.string.pull_order_type_newestFirst_description));
                break;
        }
    }

    private void setPullOrderDescription(String type, String description) {
        mPullOrderTypeView.setText(type);
        mPullOrderDescriptionView.setText(description);
    }

    private void updateVersioningDescription() {
        if (mFolder == null){
            return;
        }

        if (TextUtils.isEmpty(mFolder.versioning.type)) {
            setVersioningDescription(getString(R.string.none), "");
            return;
        }

        switch (mFolder.versioning.type) {
            case "simple":
                setVersioningDescription(getString(R.string.type_simple),
                        getString(R.string.simple_versioning_info, mFolder.versioning.params.get("keep")));
                break;
            case "trashcan":
                setVersioningDescription(getString(R.string.type_trashcan),
                        getString(R.string.trashcan_versioning_info, mFolder.versioning.params.get("cleanoutDays")));
                break;
            case "staggered":
                int maxAge = (int) TimeUnit.SECONDS.toDays(Long.valueOf(mFolder.versioning.params.get("maxAge")));
                setVersioningDescription(getString(R.string.type_staggered),
                        getString(R.string.staggered_versioning_info, maxAge, mFolder.versioning.params.get("versionsPath")));
                break;
            case "external":
                setVersioningDescription(getString(R.string.type_external),
                        getString(R.string.external_versioning_info, mFolder.versioning.params.get("command")));
                break;
        }
    }

    private void setVersioningDescription(String type, String description) {
        mVersioningTypeView.setText(type);
        mVersioningDescriptionView.setText(description);
    }
}
