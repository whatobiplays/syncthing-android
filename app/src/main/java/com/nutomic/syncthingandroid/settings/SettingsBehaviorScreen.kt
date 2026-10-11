package com.nutomic.syncthingandroid.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.EntryProviderScope
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.ExecutionModeTransitionStatus
import com.nutomic.syncthingandroid.service.RootRunScriptAuthorizationPolicy
import me.zhanghai.compose.preference.SwitchPreference
import me.zhanghai.compose.preference.rememberPreferenceState


fun EntryProviderScope<SettingsRoute>.settingsBehaviorEntry() {
    entry<SettingsRoute.Behavior> {
        SettingsBehaviorScreen()
    }
}


@Composable
fun SettingsBehaviorScreen() {
    val autoStart = rememberPreferenceState(Constants.PREF_START_SERVICE_ON_BOOT, false)
    val broadcast = rememberPreferenceState(Constants.PREF_BROADCAST_SERVICE_CONTROL, false)
    val overwrite = rememberPreferenceState(Constants.PREF_ALLOW_OVERWRITE_FILES, false)
    val committedRootPreference = rememberPreferenceState(Constants.PREF_USE_ROOT, false)
    val service = LocalSyncthingService.current
    val serviceTick = LocalServiceUpdateTick.current
    val selectedUseRoot = remember(service, serviceTick, committedRootPreference.value) {
        service?.isSuperuserModeCommitted() ?: committedRootPreference.value
    }
    val status = remember(service, serviceTick) {
        service?.getExecutionModeTransitionStatus()
    }
    val rootScriptPromptStatus = remember(service, serviceTick) {
        service?.getRootRunScriptPromptStatus()
    }
    val phase = status?.phase()
    val operationRunning = phase == ExecutionModeTransitionStatus.Phase.PREPARING_ROOT
            || phase == ExecutionModeTransitionStatus.Phase.QUIESCING
            || phase == ExecutionModeTransitionStatus.Phase.STOPPING
            || phase == ExecutionModeTransitionStatus.Phase.REPAIRING_STATE
            || phase == ExecutionModeTransitionStatus.Phase.COMMITTING
    val switchLocked = operationRunning || service?.isExecutionModeTransitionBusy() == true
    var showDisableAnywayConfirmation by rememberSaveable { mutableStateOf(false) }

    SettingsScaffold(
        title = stringResource(R.string.category_behaviour),
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = service != null && !switchLocked) {
                        service?.requestExecutionModeTransition(!selectedUseRoot)
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.superuser_mode_title))
                    Text(stringResource(R.string.superuser_mode_summary))
                }
                Switch(
                    checked = selectedUseRoot,
                    enabled = service != null && !switchLocked,
                    onCheckedChange = { enabled ->
                        service?.requestExecutionModeTransition(enabled)
                    },
                )
            }
        }

        if (status != null && status.phase() != ExecutionModeTransitionStatus.Phase.IDLE) {
            item {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    val failureTitle = when (status.failureKind()) {
                        ExecutionModeTransitionStatus.FailureKind.BUSY ->
                            stringResource(R.string.execution_mode_failure_busy)
                        ExecutionModeTransitionStatus.FailureKind.ROOT ->
                            stringResource(R.string.execution_mode_failure_root)
                        ExecutionModeTransitionStatus.FailureKind.EXECUTION_UNRESOLVED ->
                            stringResource(R.string.execution_mode_failure_execution)
                        ExecutionModeTransitionStatus.FailureKind.STATE_REPAIR_UNAVAILABLE ->
                            stringResource(R.string.execution_mode_failure_repair)
                        ExecutionModeTransitionStatus.FailureKind.PREFERENCE_PERSISTENCE ->
                            stringResource(R.string.execution_mode_failure_persistence)
                        ExecutionModeTransitionStatus.FailureKind.STATE_ACCESS_REQUIRED ->
                            stringResource(R.string.execution_mode_failure_access)
                        ExecutionModeTransitionStatus.FailureKind.STARTUP ->
                            stringResource(R.string.execution_mode_failure_startup)
                        ExecutionModeTransitionStatus.FailureKind.NONE -> null
                        else -> null
                    }
                    if (failureTitle != null) Text(failureTitle)
                    status.detail()?.takeIf { it.isNotBlank() }?.let { Text(it) }

                    if (status.retryAvailable()) {
                        TextButton(
                            enabled = !operationRunning,
                            onClick = {
                                if (status.stateAccessRepairRequired() && !selectedUseRoot) {
                                    service?.requestStateAccessRepair()
                                } else {
                                    service?.retryExecutionModeTransition()
                                }
                            },
                        ) {
                            Text(stringResource(R.string.execution_mode_retry))
                        }
                    }

                    if (status.stateAccessRepairRequired()) {
                        Button(
                            enabled = !operationRunning,
                            onClick = { service?.requestStateAccessRepair() },
                        ) {
                            Text(stringResource(R.string.execution_mode_repair_state))
                        }
                    }

                    if (status.disableAnywayAvailable()) {
                        TextButton(
                            enabled = !operationRunning,
                            onClick = { showDisableAnywayConfirmation = true },
                        ) {
                            Text(stringResource(R.string.execution_mode_disable_anyway))
                        }
                    }
                }
            }
        } else if (service?.isStateAccessRepairRequired() == true) {
            item {
                Button(
                    enabled = !operationRunning,
                    onClick = { service.requestStateAccessRepair() },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(stringResource(R.string.execution_mode_repair_state))
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = service != null && selectedUseRoot && !switchLocked
                            && service.isRootRunScriptPromptConsentMutationPending() != true) {
                        service?.requestRootRunScriptPromptConsent(
                            rootScriptPromptStatus?.optedIn() != true
                        )
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.root_script_prompt_opt_in_title))
                    Text(stringResource(R.string.root_script_prompt_opt_in_summary))
                }
                Switch(
                    checked = rootScriptPromptStatus?.optedIn() == true,
                    enabled = service != null && selectedUseRoot && !switchLocked
                            && !service.isRootRunScriptPromptConsentMutationPending(),
                    onCheckedChange = { enabled ->
                        service?.requestRootRunScriptPromptConsent(enabled)
                    },
                )
            }
        }
        if (rootScriptPromptStatus?.state()
                == RootRunScriptAuthorizationPolicy.State.SUSPENDED) {
            item {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    val reason = when (rootScriptPromptStatus.reason()) {
                        RootRunScriptAuthorizationPolicy.Reason.DENIED ->
                            stringResource(R.string.root_script_prompt_reason_denied)
                        RootRunScriptAuthorizationPolicy.Reason.LOST ->
                            stringResource(R.string.root_script_prompt_reason_lost)
                        RootRunScriptAuthorizationPolicy.Reason.TIMEOUT ->
                            stringResource(R.string.root_script_prompt_reason_timeout)
                        RootRunScriptAuthorizationPolicy.Reason.UNAVAILABLE ->
                            stringResource(R.string.root_script_prompt_reason_unavailable)
                        RootRunScriptAuthorizationPolicy.Reason.INTERRUPTED ->
                            stringResource(R.string.root_script_prompt_reason_interrupted)
                        RootRunScriptAuthorizationPolicy.Reason.PERSISTENCE ->
                            stringResource(R.string.root_script_prompt_reason_persistence)
                        else -> stringResource(R.string.root_script_prompt_reason_unknown)
                    }
                    Text(stringResource(R.string.root_script_prompt_suspended,
                        reason))
                    if (selectedUseRoot) {
                        TextButton(
                            enabled = service != null && !switchLocked
                                    && !service.isRootRunScriptOperationActive()
                                    && !service.isRootRunScriptPromptConsentMutationPending(),
                            onClick = { service?.requestRootRunScriptPromptConsent(true) },
                        ) {
                            Text(stringResource(R.string.root_script_prompt_resume))
                        }
                    }
                }
            }
        }

        item {
            SwitchPreference(
                title = { Text(stringResource(R.string.behaviour_autostart_title)) },
                summary = { Text(stringResource(R.string.behaviour_autostart_summary)) },
                state = autoStart,
            )
        }
        item {
            SwitchPreference(
                title = { Text(stringResource(R.string.broadcast_service_control_title)) },
                summary = { Text(stringResource(R.string.broadcast_service_control_summary)) },
                state = broadcast,
            )
        }
        item {
            SwitchPreference(
                title = { Text(stringResource(R.string.allow_overwrite_files_title)) },
                summary = { Text(stringResource(R.string.allow_overwrite_files_summary)) },
                state = overwrite,
            )
        }
    }

    if (showDisableAnywayConfirmation) {
        AlertDialog(
            onDismissRequest = { showDisableAnywayConfirmation = false },
            title = { Text(stringResource(R.string.execution_mode_disable_anyway_title)) },
            text = { Text(stringResource(R.string.execution_mode_disable_anyway_summary)) },
            confirmButton = {
                TextButton(onClick = {
                    showDisableAnywayConfirmation = false
                    service?.confirmDisableAnyway()
                }) {
                    Text(stringResource(R.string.execution_mode_disable_anyway))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisableAnywayConfirmation = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}
