package io.github.dancommitsgit.friendar

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.uwb.UwbManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

internal fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ),
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private enum class FeatureState { Supported, Missing, Off }

private class Feature(
    @StringRes val label: Int,
    @StringRes val detail: Int,
    val state: FeatureState,
    val direction: Boolean = false,
)

private fun phoneFeatures(context: Context, uwbOn: Boolean?): List<Feature> {
    val pm = context.packageManager
    fun has(feature: String) = if (pm.hasSystemFeature(feature)) FeatureState.Supported else FeatureState.Missing

    val uwb = when {
        !pm.hasSystemFeature(PackageManager.FEATURE_UWB) -> FeatureState.Missing
        uwbOn == false -> FeatureState.Off
        else -> FeatureState.Supported
    }
    val compass = context.getSystemService(SensorManager::class.java)
        ?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    return listOf(
        Feature(R.string.feature_bluetooth, R.string.feature_bluetooth_detail, has(PackageManager.FEATURE_BLUETOOTH_LE)),
        Feature(R.string.feature_uwb, R.string.feature_uwb_detail, uwb),
        Feature(R.string.feature_wifi_aware, R.string.feature_wifi_aware_detail, has(PackageManager.FEATURE_WIFI_AWARE)),
        Feature(R.string.feature_wifi_ranging, R.string.feature_wifi_ranging_detail, has(PackageManager.FEATURE_WIFI_RTT)),
        Feature(
            R.string.feature_gps,
            R.string.feature_gps_detail,
            has(PackageManager.FEATURE_LOCATION_GPS),
            direction = true,
        ),
        Feature(
            R.string.feature_compass,
            R.string.feature_compass_detail,
            if (compass != null) FeatureState.Supported else FeatureState.Missing,
            direction = true,
        ),
    )
}

private class PermissionGroup(
    @StringRes val label: Int,
    @StringRes val detail: Int,
    val permissions: List<String>,
) {
    fun allowed(context: Context) = permissions.all {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }
}

private val permissionGroups = listOf(
    PermissionGroup(
        R.string.permission_nearby,
        R.string.permission_nearby_detail,
        listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.UWB_RANGING,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        ),
    ),
    PermissionGroup(
        R.string.permission_location,
        R.string.permission_location_detail,
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
    ),
    PermissionGroup(
        R.string.permission_notifications,
        R.string.permission_notifications_detail,
        listOf(Manifest.permission.POST_NOTIFICATIONS),
    ),
)

/** Bumps whenever the app comes back to the front, so status read from the system is looked at again. */
@Composable
private fun rememberResumeCount(): Int {
    var count by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { count++ }
    return count
}

@Composable
private fun SettingsPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
        TextButton(onClick = onBack, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(stringResource(R.string.back), color = Muted)
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
            content = content,
        )
    }
}

@Composable
private fun Item(
    title: String,
    detail: String?,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) {
                Text(detail, color = Muted, style = MaterialTheme.typography.bodyMedium)
            }
        }
        trailing()
    }
    HorizontalDivider(color = Raised)
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = Muted,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun <T> Choice(
    title: String,
    detail: String?,
    options: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (label, value) ->
                SegmentedButton(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = Raised,
                        activeContentColor = OnInk,
                        inactiveContentColor = Muted,
                    ),
                    label = { Text(label) },
                )
            }
        }
        if (detail != null) {
            Spacer(Modifier.height(6.dp))
            Text(detail, color = Muted, style = MaterialTheme.typography.bodyMedium)
        }
    }
    HorizontalDivider(color = Raised)
}

@Composable
internal fun SettingsScreen(
    state: FinderState,
    onBack: () -> Unit,
    onFlip: (FlipMode) -> Unit,
    onUnits: (UnitMode) -> Unit,
    onBackground: (Boolean) -> Unit,
    onDebug: (Boolean) -> Unit,
    onOpenPhone: () -> Unit,
    onOpenPermissions: () -> Unit,
) {
    val context = LocalContext.current
    val resumed = rememberResumeCount()
    val features = remember(resumed) { phoneFeatures(context, uwbOn = null) }
    val missingPermissions = remember(resumed) {
        permissionGroups.filter { !it.allowed(context) }.map { context.getString(it.label) }
    }

    SettingsPage(stringResource(R.string.settings), onBack) {
        Item(
            title = stringResource(R.string.setting_background),
            detail = stringResource(R.string.setting_background_detail),
            onClick = { onBackground(!state.background) },
        ) {
            Switch(checked = state.background, onCheckedChange = onBackground)
        }
        Choice(
            title = stringResource(R.string.setting_flip),
            detail = if (state.flipMode == FlipMode.Auto) {
                stringResource(
                    when (state.flipAuto) {
                        true -> R.string.flip_auto_flipped
                        false -> R.string.flip_auto_normal
                        null -> R.string.flip_auto_unknown
                    },
                )
            } else {
                null
            },
            options = listOf(
                stringResource(R.string.flip_yes) to FlipMode.Yes,
                stringResource(R.string.flip_no) to FlipMode.No,
                stringResource(R.string.flip_auto) to FlipMode.Auto,
            ),
            selected = state.flipMode,
            onSelect = onFlip,
        )
        Choice(
            title = stringResource(R.string.setting_units),
            detail = if (state.unitMode == UnitMode.Auto) {
                stringResource(if (state.metric) R.string.units_auto_metric else R.string.units_auto_imperial)
            } else {
                null
            },
            options = listOf(
                stringResource(R.string.units_auto) to UnitMode.Auto,
                stringResource(R.string.units_metric) to UnitMode.Metric,
                stringResource(R.string.units_imperial) to UnitMode.Imperial,
            ),
            selected = state.unitMode,
            onSelect = onUnits,
        )
        Item(
            title = stringResource(R.string.this_phone),
            detail = stringResource(
                R.string.this_phone_summary,
                features.count { it.state != FeatureState.Missing },
                features.size,
            ),
            onClick = onOpenPhone,
        ) { Chevron() }
        Item(
            title = stringResource(R.string.permissions),
            detail = if (missingPermissions.isEmpty()) {
                stringResource(R.string.permissions_all_allowed)
            } else {
                stringResource(R.string.permissions_missing, missingPermissions.joinToString(", "))
            },
            onClick = onOpenPermissions,
        ) { Chevron() }
        Item(
            title = stringResource(R.string.debug_info),
            detail = null,
            onClick = { onDebug(!state.debug) },
        ) {
            Switch(checked = state.debug, onCheckedChange = onDebug)
        }
    }
}

@Composable
private fun Chevron() {
    Text("›", color = Muted, style = MaterialTheme.typography.headlineSmall)
}

@Composable
internal fun PhoneScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val resumed = rememberResumeCount()
    var uwbOn by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(resumed) {
        val hasUwb = context.packageManager.hasSystemFeature(PackageManager.FEATURE_UWB)
        uwbOn = if (hasUwb) runCatching { UwbManager.createInstance(context).isAvailable() }.getOrNull() else null
    }
    val features = remember(resumed, uwbOn) { phoneFeatures(context, uwbOn) }

    SettingsPage(stringResource(R.string.this_phone), onBack) {
        SectionLabel(stringResource(R.string.section_ranging))
        features.filter { !it.direction }.forEach { FeatureRow(it) }
        SectionLabel(stringResource(R.string.section_direction))
        features.filter { it.direction }.forEach { FeatureRow(it) }
    }
}

@Composable
private fun FeatureRow(feature: Feature) {
    val (text, color) = when (feature.state) {
        FeatureState.Supported -> R.string.feature_supported to Green
        FeatureState.Missing -> R.string.feature_missing to Muted
        FeatureState.Off -> R.string.feature_off to Amber
    }
    Item(stringResource(feature.label), stringResource(feature.detail)) {
        StatusText(stringResource(text), color)
    }
}

@Composable
private fun StatusText(text: String, color: Color) {
    Text(text, color = color, style = MaterialTheme.typography.bodyMedium)
}

@Composable
internal fun PermissionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val asked = remember { context.getSharedPreferences("permissions", Context.MODE_PRIVATE) }
    var changes by remember { mutableIntStateOf(0) }
    val resumed = rememberResumeCount()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        changes++
    }

    fun request(group: PermissionGroup) {
        val missing = group.permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        val activity = context.findActivity()
        val everAsked = missing.any { asked.getBoolean(it, false) }
        val canAsk = !everAsked || missing.any {
            activity != null && androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
        }
        if (canAsk) {
            asked.edit().apply { missing.forEach { putBoolean(it, true) } }.apply()
            launcher.launch(missing.toTypedArray())
        } else {
            openAppSettings(context)
        }
    }

    SettingsPage(stringResource(R.string.permissions), onBack) {
        permissionGroups.forEach { group ->
            val allowed = remember(resumed, changes) { group.allowed(context) }
            Item(stringResource(group.label), stringResource(group.detail)) {
                if (allowed) {
                    StatusText(stringResource(R.string.permission_allowed), Green)
                } else {
                    OutlinedButton(onClick = { request(group) }) {
                        Text(stringResource(R.string.permission_allow))
                    }
                }
            }
        }
        TextButton(onClick = { openAppSettings(context) }, modifier = Modifier.padding(top = 8.dp)) {
            Text(stringResource(R.string.open_settings))
        }
    }
}
