package io.github.dancommitsgit.friendar.ui

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview

/** Light and dark on a phone-sized canvas. */
@Preview(name = "Light", device = Devices.PIXEL_7)
@Preview(name = "Dark", device = Devices.PIXEL_7, uiMode = Configuration.UI_MODE_NIGHT_YES)
internal annotation class PhonePreviews

@Composable
internal fun PreviewTheme(content: @Composable () -> Unit) {
    FriendarTheme(dark = isSystemInDarkTheme()) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            content = content,
        )
    }
}
