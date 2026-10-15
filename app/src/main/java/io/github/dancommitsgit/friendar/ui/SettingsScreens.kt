@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package io.github.dancommitsgit.friendar.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.Manifest
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.uwb.UwbManager
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import io.github.dancommitsgit.friendar.finder.FinderState
import io.github.dancommitsgit.friendar.finder.FlipMode
import io.github.dancommitsgit.friendar.finder.ThemeMode
import io.github.dancommitsgit.friendar.finder.UnitMode
import io.github.dancommitsgit.friendar.R

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
internal fun rememberResumeCount(): Int {
    var count by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { count++ }
    return count
}

@Composable
private fun SettingsPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        // The list scrolls under the navigation bar; the spacer at its end clears it.
        contentWindowInsets = WindowInsets(0),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(AppIcons.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            content()
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

/** Cards sit one step above the page so the rows read as separate pieces. */
@Composable
private fun rowColors() = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)

private class GroupScope {
    val rows = mutableListOf<@Composable (ListItemShapes) -> Unit>()

    fun row(content: @Composable (ListItemShapes) -> Unit) {
        rows += content
    }
}

/** Rows set apart by a small gap, with the outer corners of the first and last rounded more. */
@Composable
private fun Group(label: String? = null, build: GroupScope.() -> Unit) {
    val rows = GroupScope().apply(build).rows
    Column {
        if (label != null) {
            Text(
                label,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleSmallEmphasized,
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            rows.forEachIndexed { index, row -> row(ListItemDefaults.segmentedShapes(index, rows.size)) }
        }
    }
}

@Composable
private fun SwitchRow(shapes: ListItemShapes, title: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    SegmentedListItem(
        onClick = { onChange(!checked) },
        shapes = shapes,
        colors = rowColors(),
        supportingContent = if (detail != null) {
            { Text(detail) }
        } else {
            null
        },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = null,
                thumbContent = if (checked) {
                    { Icon(AppIcons.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
                } else {
                    null
                },
            )
        },
    ) { Text(title) }
}

@Composable
private fun LinkRow(shapes: ListItemShapes, title: String, detail: String?, onClick: () -> Unit) {
    SegmentedListItem(
        onClick = onClick,
        shapes = shapes,
        colors = rowColors(),
        supportingContent = if (detail != null) {
            { Text(detail) }
        } else {
            null
        },
        trailingContent = {
            Icon(
                AppIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    ) { Text(title) }
}

@Composable
private fun StaticRow(
    shapes: ListItemShapes,
    title: String,
    detail: String?,
    detailColor: Color = Color.Unspecified,
    trailing: (@Composable () -> Unit)? = null,
) {
    Surface(shape = shapes.shape, color = rowColors().containerColor) {
        ListItem(
            supportingContent = if (detail != null) {
                { Text(detail, color = detailColor) }
            } else {
                null
            },
            trailingContent = trailing,
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        ) { Text(title) }
    }
}

@Composable
private fun <T> ChoiceRow(
    shapes: ListItemShapes,
    title: String,
    detail: String?,
    options: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    val motion = MaterialTheme.motionScheme
    Surface(shape = shapes.shape, color = rowColors().containerColor) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(motion.defaultSpatialSpec())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            ) {
                options.forEachIndexed { index, (label, value) ->
                    ToggleButton(
                        checked = value == selected,
                        onCheckedChange = { onSelect(value) },
                        modifier = Modifier.weight(1f),
                        colors = connectedToggleColors(),
                        shapes = when (index) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                    ) { Text(label) }
                }
            }
            AnimatedContent(
                targetState = detail,
                transitionSpec = { fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()) },
                label = "detail",
            ) { text ->
                if (text != null) {
                    Text(
                        text,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun SettingsScreen(
    state: FinderState,
    onBack: () -> Unit,
    onFlip: (FlipMode) -> Unit,
    onUnits: (UnitMode) -> Unit,
    onTheme: (ThemeMode) -> Unit,
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
    SettingsContent(
        state = state,
        featuresAvailable = features.count { it.state != FeatureState.Missing },
        featuresTotal = features.size,
        missingPermissions = missingPermissions,
        onBack = onBack,
        onFlip = onFlip,
        onUnits = onUnits,
        onTheme = onTheme,
        onBackground = onBackground,
        onDebug = onDebug,
        onOpenPhone = onOpenPhone,
        onOpenPermissions = onOpenPermissions,
    )
}

@Composable
private fun SettingsContent(
    state: FinderState,
    featuresAvailable: Int,
    featuresTotal: Int,
    missingPermissions: List<String>,
    onBack: () -> Unit,
    onFlip: (FlipMode) -> Unit,
    onUnits: (UnitMode) -> Unit,
    onTheme: (ThemeMode) -> Unit,
    onBackground: (Boolean) -> Unit,
    onDebug: (Boolean) -> Unit,
    onOpenPhone: () -> Unit,
    onOpenPermissions: () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()

    SettingsPage(stringResource(R.string.settings), onBack) {
        Group {
            row {
                SwitchRow(
                    it,
                    title = stringResource(R.string.setting_background),
                    detail = stringResource(R.string.setting_background_detail),
                    checked = state.background,
                    onChange = onBackground,
                )
            }
            row {
                ChoiceRow(
                    it,
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
            }
            row {
                ChoiceRow(
                    it,
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
            }
            row {
                ChoiceRow(
                    it,
                    title = stringResource(R.string.setting_theme),
                    detail = if (state.themeMode == ThemeMode.System) {
                        stringResource(if (systemDark) R.string.theme_auto_dark else R.string.theme_auto_light)
                    } else {
                        null
                    },
                    options = listOf(
                        stringResource(R.string.theme_system) to ThemeMode.System,
                        stringResource(R.string.theme_light) to ThemeMode.Light,
                        stringResource(R.string.theme_dark) to ThemeMode.Dark,
                    ),
                    selected = state.themeMode,
                    onSelect = onTheme,
                )
            }
        }
        Group {
            row {
                LinkRow(
                    it,
                    title = stringResource(R.string.this_phone),
                    detail = stringResource(
                        R.string.this_phone_summary,
                        featuresAvailable,
                        featuresTotal,
                    ),
                    onClick = onOpenPhone,
                )
            }
            row {
                LinkRow(
                    it,
                    title = stringResource(R.string.permissions),
                    detail = if (missingPermissions.isEmpty()) {
                        stringResource(R.string.permissions_all_allowed)
                    } else {
                        stringResource(R.string.permissions_missing, missingPermissions.joinToString(", "))
                    },
                    onClick = onOpenPermissions,
                )
            }
        }
        Group {
            row {
                SwitchRow(
                    it,
                    title = stringResource(R.string.debug_info),
                    detail = null,
                    checked = state.debug,
                    onChange = onDebug,
                )
            }
        }
    }
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
    PhoneContent(features, onBack)
}

@Composable
private fun PhoneContent(features: List<Feature>, onBack: () -> Unit) {
    SettingsPage(stringResource(R.string.this_phone), onBack) {
        Group(stringResource(R.string.section_ranging)) {
            features.filter { !it.direction }.forEach { feature -> row { FeatureRow(it, feature) } }
        }
        Group(stringResource(R.string.section_direction)) {
            features.filter { it.direction }.forEach { feature -> row { FeatureRow(it, feature) } }
        }
    }
}

@Composable
private fun FeatureRow(shapes: ListItemShapes, feature: Feature) {
    val scheme = MaterialTheme.colorScheme
    val status = stringResource(
        when (feature.state) {
            FeatureState.Supported -> R.string.feature_supported
            FeatureState.Missing -> R.string.feature_missing
            FeatureState.Off -> R.string.feature_off
        },
    )
    StaticRow(
        shapes,
        title = stringResource(feature.label),
        detail = if (feature.state == FeatureState.Supported) stringResource(feature.detail) else status,
        detailColor = if (feature.state == FeatureState.Off) MaterialTheme.warning else Color.Unspecified,
    ) {
        when (feature.state) {
            FeatureState.Supported -> StatusIcon(AppIcons.Check, status, scheme.tertiaryContainer, scheme.onTertiaryContainer)
            FeatureState.Missing -> StatusIcon(AppIcons.Close, status, scheme.surfaceContainerHighest, scheme.onSurfaceVariant)
            FeatureState.Off -> StatusIcon(
                AppIcons.Warning,
                status,
                MaterialTheme.warning.copy(alpha = 0.18f),
                MaterialTheme.warning,
            )
        }
    }
}

@Composable
private fun StatusIcon(icon: ImageVector, description: String, container: Color, tint: Color) {
    Box(
        modifier = Modifier.size(32.dp).clip(CircleShape).background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(20.dp))
    }
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

    val allowed = remember(resumed, changes) { permissionGroups.associateWith { it.allowed(context) } }
    PermissionsContent(
        allowed = allowed,
        onRequest = ::request,
        onOpenSettings = { openAppSettings(context) },
        onBack = onBack,
    )
}

@Composable
private fun PermissionsContent(
    allowed: Map<PermissionGroup, Boolean>,
    onRequest: (PermissionGroup) -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val allowedLabel = stringResource(R.string.permission_allowed)
    SettingsPage(stringResource(R.string.permissions), onBack) {
        Group {
            permissionGroups.forEach { group ->
                row { shapes ->
                    StaticRow(
                        shapes,
                        title = stringResource(group.label),
                        detail = stringResource(group.detail),
                    ) {
                        if (allowed[group] == true) {
                            StatusIcon(AppIcons.Check, allowedLabel, scheme.tertiaryContainer, scheme.onTertiaryContainer)
                        } else {
                            Button(onClick = { onRequest(group) }, shapes = ButtonDefaults.shapes()) {
                                Text(stringResource(R.string.permission_allow))
                            }
                        }
                    }
                }
            }
        }
        Group {
            row {
                LinkRow(it, title = stringResource(R.string.open_settings), detail = null, onClick = onOpenSettings)
            }
        }
    }
}

@PhonePreviews
@Composable
private fun SettingsPreview() {
    var state by remember { mutableStateOf(FinderState()) }
    PreviewTheme(state.themeMode) {
        SettingsContent(
            state = state,
            featuresAvailable = 5,
            featuresTotal = 6,
            missingPermissions = listOf("Nearby devices"),
            onBack = {},
            onFlip = { state = state.copy(flipMode = it) },
            onUnits = { state = state.copy(unitMode = it, metric = it != UnitMode.Imperial) },
            onTheme = { state = state.copy(themeMode = it) },
            onBackground = { state = state.copy(background = it) },
            onDebug = { state = state.copy(debug = it) },
            onOpenPhone = {},
            onOpenPermissions = {},
        )
    }
}

@PhonePreviews
@Composable
private fun PhonePreview() {
    PreviewTheme {
        PhoneContent(
            features = listOf(
                Feature(R.string.feature_bluetooth, R.string.feature_bluetooth_detail, FeatureState.Supported),
                Feature(R.string.feature_uwb, R.string.feature_uwb_detail, FeatureState.Off),
                Feature(R.string.feature_wifi_aware, R.string.feature_wifi_aware_detail, FeatureState.Supported),
                Feature(R.string.feature_wifi_ranging, R.string.feature_wifi_ranging_detail, FeatureState.Missing),
                Feature(R.string.feature_gps, R.string.feature_gps_detail, FeatureState.Supported, direction = true),
                Feature(R.string.feature_compass, R.string.feature_compass_detail, FeatureState.Supported, direction = true),
            ),
            onBack = {},
        )
    }
}

@PhonePreviews
@Composable
private fun PermissionsPreview() {
    var allowed by remember {
        mutableStateOf(permissionGroups.mapIndexed { index, group -> group to (index != 0) }.toMap())
    }
    PreviewTheme {
        PermissionsContent(
            allowed = allowed,
            onRequest = { group -> allowed = allowed + (group to true) },
            onOpenSettings = {},
            onBack = {},
        )
    }
}
