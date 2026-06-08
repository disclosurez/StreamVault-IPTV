package com.streamvault.app.device

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

fun Context.isTelevisionDevice(): Boolean {
    val packageManager = packageManager
    if (packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
        return true
    }
    if (packageManager.hasSystemFeature("android.software.leanback_only")) {
        return true
    }
    if (packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION)) {
        return true
    }
    if (packageManager.hasSystemFeature("amazon.hardware.fire_tv")) {
        return true
    }
    val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) {
        return true
    }

    val screenWidthDp = resources.configuration.screenWidthDp
    return !packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN) && screenWidthDp >= 900
}

fun Context.isFireTvDevice(): Boolean {
    val packageManager = packageManager
    if (packageManager.hasSystemFeature("amazon.hardware.fire_tv")) {
        return true
    }

    val manufacturer = android.os.Build.MANUFACTURER.orEmpty()
    val model = android.os.Build.MODEL.orEmpty()
    return manufacturer.equals("Amazon", ignoreCase = true) ||
        model.contains("AFT", ignoreCase = true)
}

@Composable
fun rememberIsTelevisionDevice(): Boolean {
    val context = LocalContext.current
    return remember(context) { context.isTelevisionDevice() }
}

@Composable
fun rememberIsFireTvDevice(): Boolean {
    val context = LocalContext.current
    return remember(context) { context.isFireTvDevice() }
}
