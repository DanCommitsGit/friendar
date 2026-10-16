package io.github.dancommitsgit.friendar.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.dancommitsgit.friendar.finder.FinderState
import io.github.dancommitsgit.friendar.finder.FinderViewModel

private val requiredPermissions = arrayOf(
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_CONNECT,
    Manifest.permission.BLUETOOTH_ADVERTISE,
    Manifest.permission.UWB_RANGING,
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
    Manifest.permission.NEARBY_WIFI_DEVICES,
    Manifest.permission.POST_NOTIFICATIONS,
)

private val bluetoothPermissions = setOf(
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_CONNECT,
    Manifest.permission.BLUETOOTH_ADVERTISE,
)

@Composable
internal fun FriendarApp(viewModel: FinderViewModel, state: FinderState) {
    val context = LocalContext.current
    var stack by rememberSaveable { mutableStateOf(listOf(Page.Main)) }
    var showMap by rememberSaveable { mutableStateOf(false) }
    var denied by rememberSaveable { mutableStateOf(false) }
    val resumed = rememberResumeCount()
    val bluetoothAllowed = remember(resumed, denied) {
        bluetoothPermissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        // Every other permission only adds a ranging method, so the app still runs without it.
        val bluetoothOk = bluetoothPermissions.all { grants[it] == true }
        denied = !bluetoothOk
        if (bluetoothOk) pending?.invoke()
    }

    fun start() {
        val missing = requiredPermissions.any {
            context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing) {
            pending = viewModel::start
            launcher.launch(requiredPermissions)
        } else {
            viewModel.start()
        }
    }

    fun push(page: Page) {
        if (stack.last() != page) stack = stack + page
    }

    fun pop() {
        if (stack.size > 1) stack = stack.dropLast(1)
    }

    PageHost(stack = stack, onBack = ::pop) { page ->
        when (page) {
            Page.Main -> MainScreen(
                state = state,
                showMap = showMap,
                onShowMap = { showMap = it },
                permissionDenied = denied && !bluetoothAllowed,
                onStart = ::start,
                onStop = viewModel::stop,
                onMetric = viewModel::setMetric,
                onSettings = { push(Page.Settings) },
            )

            Page.Settings -> SettingsScreen(
                state,
                onBack = ::pop,
                onFlip = viewModel::setFlipMode,
                onUnits = viewModel::setMetric,
                onTheme = viewModel::setThemeMode,
                onBackground = viewModel::setBackground,
                onDebug = viewModel::setDebug,
                onOpenPhone = { push(Page.Phone) },
                onOpenPermissions = { push(Page.Permissions) },
            )

            Page.Phone -> PhoneScreen(onBack = ::pop)
            Page.Permissions -> PermissionsScreen(onBack = ::pop)
        }
    }
}
