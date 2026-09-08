package com.waterdistrict.meterreader.hardware.bluetooth

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Wraps [onReady] so it only runs once BLUETOOTH_CONNECT is granted —
 * required at runtime on Android 12+ (API 31+) before touching any
 * Bluetooth API, including reading paired devices. Below API 31 the
 * manifest's BLUETOOTH/BLUETOOTH_ADMIN permissions are granted at install
 * time, so [onReady] runs immediately.
 *
 * Without this, printing would fail with a permission error the reader has
 * no way to resolve from inside the app.
 */
@Composable
fun rememberBluetoothConnectAction(onReady: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) onReady() }

    return remember(context) {
        {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                launcher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                onReady()
            }
        }
    }
}
