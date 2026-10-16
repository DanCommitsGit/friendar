package io.github.dancommitsgit.friendar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.dancommitsgit.friendar.finder.FinderViewModel
import io.github.dancommitsgit.friendar.finder.ThemeMode
import io.github.dancommitsgit.friendar.ui.FriendarApp
import io.github.dancommitsgit.friendar.ui.FriendarTheme
import io.github.dancommitsgit.friendar.ui.LocalSplashHandoff
import io.github.dancommitsgit.friendar.ui.STALL_MS
import io.github.dancommitsgit.friendar.ui.SplashHandoff
import android.graphics.Color as AndroidColor

class MainActivity : ComponentActivity() {
    private val viewModel: FinderViewModel by viewModels()
    private lateinit var handoff: SplashHandoff

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A recreated activity has no splash, so its hero shows straight away.
        handoff = SplashHandoff(pending = savedInstanceState == null)
        splashScreen.setOnExitAnimationListener(handoff::run)
        window.decorView.postDelayed(handoff::skip, STALL_MS)
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val dark = when (state.themeMode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT) { dark },
                )
                onDispose {}
            }
            FriendarTheme(dark) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                ) {
                    CompositionLocalProvider(LocalSplashHandoff provides handoff) {
                        FriendarApp(viewModel, state)
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) viewModel.onAppHidden()
    }
}
