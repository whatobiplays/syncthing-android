package com.nutomic.syncthingandroid.settings

import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.EntryProviderScope
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.service.Constants
import me.zhanghai.compose.preference.SwitchPreference
import me.zhanghai.compose.preference.TextFieldPreference
import me.zhanghai.compose.preference.rememberPreferenceState


fun EntryProviderScope<SettingsRoute>.settingsExperimentalEntry() {
    entry<SettingsRoute.Experimental> {
        SettingsExperimentalScreen()
    }
}


@Composable
fun SettingsExperimentalScreen() {
    val context = LocalContext.current
    val service = LocalSyncthingService.current
    val serviceTick = LocalServiceUpdateTick.current

    val useTor = rememberPreferenceState(Constants.PREF_USE_TOR, false)
    val socksProxy = rememberPreferenceState(Constants.PREF_SOCKS_PROXY_ADDRESS, "")
    val httpProxy = rememberPreferenceState(Constants.PREF_HTTP_PROXY_ADDRESS, "")
    val savedInotifySetting = rememberPreferenceState(Constants.PREF_INOTIFY_WATCH_LIMIT, false)
    var inotifyEnabled by remember(service) {
        mutableStateOf(service?.isInotifyWatchLimitEnabled() ?: savedInotifySetting.value)
    }
    var inotifyApplying by remember { mutableStateOf(false) }

    LaunchedEffect(service, serviceTick) {
        if (!inotifyApplying) {
            inotifyEnabled = service?.isInotifyWatchLimitEnabled() ?: savedInotifySetting.value
        }
    }

    SettingsScaffold(
        title = stringResource(R.string.category_experimental),
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = service != null && !inotifyApplying) {
                        requestInotifyWatchLimit(
                            context, service, !inotifyEnabled,
                            onStarted = { inotifyApplying = true },
                            onComplete = { persisted, succeeded, detail ->
                                inotifyApplying = false
                                inotifyEnabled = persisted
                                Toast.makeText(context, detail, Toast.LENGTH_LONG).show()
                            },
                        )
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.inotify_watch_limit_title))
                    Text(stringResource(R.string.inotify_watch_limit_summary))
                }
                Switch(
                    checked = inotifyEnabled,
                    enabled = service != null && !inotifyApplying,
                    onCheckedChange = { enabled ->
                        requestInotifyWatchLimit(
                            context, service, enabled,
                            onStarted = { inotifyApplying = true },
                            onComplete = { persisted, succeeded, detail ->
                                inotifyApplying = false
                                inotifyEnabled = persisted
                                Toast.makeText(context, detail, Toast.LENGTH_LONG).show()
                            },
                        )
                    },
                )
            }
        }
        item {
            SwitchPreference(
                title = { Text(stringResource(R.string.use_tor_title)) },
                summary = { Text(stringResource(R.string.use_tor_summary)) },
                state = useTor,
            )
        }
        item {
            val socksProxySummary = if (socksProxy.value.isBlank())
                "${stringResource(R.string.do_not_use_proxy)} ${stringResource(R.string.generic_example)}: ${stringResource(R.string.socks_proxy_address_example)}"
            else
                "${stringResource(R.string.use_proxy)} ${socksProxy.value}"
            TextFieldPreference(
                title = { Text(stringResource(R.string.socks_proxy_address_title)) },
                summary = { Text(socksProxySummary) },
                state = socksProxy,
                textToValue = {
                    validateProxy(
                        newValue = it,
                        regex = Regex("^socks5://.*:\\d{1,5}$"),
                        errorResId = R.string.toast_invalid_socks_proxy_address,
                        context = context,
                    )
                },
                enabled = !useTor.value,
            )
        }
        item {
            val httpProxySummary = if (httpProxy.value.isBlank())
                "${stringResource(R.string.do_not_use_proxy)} ${stringResource(R.string.generic_example)}: ${stringResource(R.string.http_proxy_address_example)}"
            else
                "${stringResource(R.string.use_proxy)} ${httpProxy.value}"
            TextFieldPreference(
                title = { Text(stringResource(R.string.http_proxy_address_title)) },
                summary = { Text(httpProxySummary) },
                state = httpProxy,
                textToValue = {
                    validateProxy(
                        newValue = it,
                        regex = Regex("^https?://.*:\\d{1,5}$"),
                        errorResId = R.string.toast_invalid_http_proxy_address,
                        context = context,
                    )
                },
                enabled = !useTor.value,
            )
        }
    }
}

private fun requestInotifyWatchLimit(
    context: Context,
    service: com.nutomic.syncthingandroid.service.SyncthingService?,
    enabled: Boolean,
    onStarted: () -> Unit,
    onComplete: (persisted: Boolean, succeeded: Boolean, detail: String) -> Unit,
) {
    if (service == null) {
        Toast.makeText(context, R.string.inotify_service_unavailable, Toast.LENGTH_LONG).show()
        return
    }
    onStarted()
    service.requestInotifyWatchLimit(enabled) { _, succeeded, detail ->
        onComplete(service.isInotifyWatchLimitEnabled(), succeeded, detail)
    }
}

private fun validateProxy(
    newValue: String,
    regex: Regex,
    @StringRes errorResId: Int,
    context: Context
): String? {
    return when {
        newValue.isEmpty() -> newValue
        newValue.matches(regex) -> newValue
        else -> {
            Toast.makeText(context, errorResId, Toast.LENGTH_LONG).show()
            null
        }
    }
}
