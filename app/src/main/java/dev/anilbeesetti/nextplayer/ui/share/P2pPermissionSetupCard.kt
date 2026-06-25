package dev.anilbeesetti.nextplayer.ui.share

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState

/**
 * Phase 7 — Modern permission + auto-enable UX for P2P file transfer.
 *
 * Old behaviour: blind-fire `permissionsState.launchMultiplePermissionRequest()`
 * the moment the user opened the screen, then nag them with an "Permissions
 * Required" dialog if anything was missing. Users had no idea why we needed
 * Wi-Fi + Location and would deny, then wonder why nothing worked.
 *
 * New behaviour:
 *  1. Don't ask for anything upfront. Show a friendly "Tap here to turn on
 *     Wi-Fi and Location" card with a one-tap button.
 *  2. When the user taps the button:
 *     a. Use ActivityResult API to open the system Wi-Fi settings screen
 *        (or call `WifiManager.setWifiEnabled(true)` on pre-Q devices).
 *     b. Use ActivityResult API to open Location settings.
 *     c. THEN request the runtime permissions (Camera, Nearby Wi-Fi, etc.)
 *        only if the user actually enabled Wi-Fi/Location.
 *  3. Show live status of "Wi-Fi: ON/OFF", "Location: ON/OFF", "Permissions: granted".
 *
 * The UI is composable — drop it into any screen that needs P2P permissions.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun P2pPermissionSetupCard(
    onAllReady: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // ── Track Wi-Fi + Location enabled state ──────────────────────────────
    var wifiEnabled by remember { mutableStateOf(isWifiEnabled(context)) }
    var locationEnabled by remember { mutableStateOf(isLocationEnabled(context)) }

    // Refresh state when the user returns from settings
    val settingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { _ ->
        wifiEnabled = isWifiEnabled(context)
        locationEnabled = isLocationEnabled(context)
    }

    // ── Runtime permissions (only requested AFTER Wi-Fi/Location are on) ─
    val requiredPermissions = remember {
        buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }
    }
    val permissionsState = rememberMultiplePermissionsState(permissions = requiredPermissions)
    val allPermsGranted = permissionsState.revokedPermissions.isEmpty()

    val allReady = wifiEnabled && locationEnabled && allPermsGranted

    // ── Gradient card ─────────────────────────────────────────────────────
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "P2P Sharing Setup",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "To send and receive files over local Wi-Fi, SHS Player needs Wi-Fi and Location turned on. " +
                    "Location is required by Android to identify nearby devices — it is never stored or transmitted.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Status chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusChip(
                    label = "Wi-Fi",
                    enabled = wifiEnabled,
                    modifier = Modifier.weight(1f),
                )
                StatusChip(
                    label = "Location",
                    enabled = locationEnabled,
                    modifier = Modifier.weight(1f),
                )
                StatusChip(
                    label = "Permissions",
                    enabled = allPermsGranted,
                    modifier = Modifier.weight(1f),
                )
            }

            // Action button — different label depending on state
            Button(
                onClick = {
                    when {
                        // Step 1: enable Wi-Fi (system settings)
                        !wifiEnabled -> {
                            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                Intent(Settings.ACTION_WIFI_SETTINGS)
                            } else {
                                // Pre-Q: we can toggle directly via WifiManager
                                @Suppress("DEPRECATION")
                                val wm = context.getSystemService(Context.WIFI_SERVICE)
                                    as android.net.wifi.WifiManager
                                @Suppress("DEPRECATION")
                                wm.isWifiEnabled = true
                                null
                            }
                            intent?.let { settingsLauncher.launch(it) }
                        }
                        // Step 2: enable Location
                        !locationEnabled -> {
                            settingsLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                        }
                        // Step 3: request runtime permissions
                        !allPermsGranted -> {
                            permissionsState.launchMultiplePermissionRequest()
                        }
                        // Step 4: all ready — invoke callback
                        else -> onAllReady()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                val label = when {
                    !wifiEnabled -> "Tap to turn on Wi-Fi"
                    !locationEnabled -> "Tap to turn on Location"
                    !allPermsGranted -> "Grant permissions"
                    else -> "Start sharing files"
                }
                Text(label, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = if (!wifiEnabled) Icons.Default.Wifi else Icons.Default.LocationOn,
                    contentDescription = null,
                )
            }
        }
    }
}

@Composable
private fun StatusChip(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = if (enabled) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "$label: ",
                style = MaterialTheme.typography.labelMedium,
                color = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                if (enabled) "ON" else "OFF",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

// ── Helpers ─────────────────────────────────────────────────────────────────

private fun isWifiEnabled(context: Context): Boolean {
    val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE)
        as? android.net.wifi.WifiManager ?: return false
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        wm.isWifiEnabled
    } else {
        @Suppress("DEPRECATION")
        wm.isWifiEnabled
    }
}

private fun isLocationEnabled(context: Context): Boolean {
    val lm = context.applicationContext.getSystemService(Context.LOCATION_SERVICE)
        as? LocationManager ?: return false
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        lm.isLocationEnabled
    } else {
        @Suppress("DEPRECATION")
        lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }
}
