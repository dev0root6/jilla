package com.jilla.ui

import android.Manifest
import android.app.Activity
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jilla.service.FeedItem
import com.jilla.service.MeshNode
import com.jilla.service.SosState
import com.jilla.ui.theme.Amber
import com.jilla.ui.theme.SafeGreen
import com.jilla.ui.theme.SosRed
import com.jilla.ui.theme.SosRedDark
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val HOLD_MS = 2000

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(node: MeshNode) {
    val sos by node.sos.collectAsStateWithLifecycle()
    val status by node.node.collectAsStateWithLifecycle()
    val feed by node.feed.collectAsStateWithLifecycle()
    val nearby by node.nearby.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }
    var confirmSafe by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Jilla Safety", style = MaterialTheme.typography.titleLarge)
                        Text(
                            node.account.name.ifBlank { "Not registered" } + " · " + node.account.phone,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = { IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "Developer settings") } },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { StatusRow(status.linked, status.gateway, status.transports) }
            item { LocationBanner() }
            item { RegistrationBanner(node) }
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    SosButton(active = sos.active, onTrigger = { node.triggerSos() })
                }
            }
            item {
                AnimatedVisibility(sos.active) {
                    ActiveSosCard(sos, onSafe = { confirmSafe = true })
                }
            }
            if (nearby.isNotEmpty()) {
                item {
                    Text("Someone nearby needs help", style = MaterialTheme.typography.titleMedium, color = SosRed)
                }
                items(nearby, key = { it.streamKey }) { NearbyAlertCard(it, node.config.relayWindowMin) }
            }
            item {
                SectionCard("Emergency contacts", Icons.Default.Lock, "Sealed inside your alert; relays can't see them.") {
                    var contacts by remember { mutableStateOf(node.account.contacts) }
                    ContactsEditor(contacts) { contacts = it; node.account.contacts = it }
                    Spacer(Modifier.height(8.dp))
                    SmsToggle(node)
                }
            }
            item {
                SectionCard("Authority server", Icons.Default.Dns, "Where alerts are delivered when this phone has internet.") {
                    ServerEditor(node)
                }
            }
            item {
                Text(
                    "Nearby activity",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "Alerts you relay show only a ~5 km area. Identities stay sealed. Relayed so far: ${status.relayedCount}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (feed.isEmpty()) {
                item {
                    Text(
                        "Nothing yet. Your phone is listening for nearby alerts.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
            items(feed.take(30), key = { it.atMs.toString() + it.title + it.detail }) { FeedRow(it) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (confirmSafe) {
        AlertDialog(
            onDismissRequest = { confirmSafe = false },
            title = { Text("End SOS?") },
            text = { Text("Live location updates stop. A new SOS later starts a fresh, unlinkable incident.") },
            confirmButton = {
                TextButton(onClick = { node.cancelSos(); confirmSafe = false }) { Text("I'm safe") }
            },
            dismissButton = { TextButton(onClick = { confirmSafe = false }) { Text("Keep SOS on") } },
        )
    }
    if (showSettings) DevSettingsDialog(node) { showSettings = false }
}

@Composable
private fun StatusRow(linked: Boolean, gateway: Boolean, transport: String) {
    // transport is e.g. "TCP+BLE"
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusChip(
            if (linked) "Mesh online" else "Mesh connecting…",
            if (linked) Icons.Default.Hub else Icons.Default.CellTower,
            if (linked) SafeGreen else Amber,
        )
        StatusChip(
            if (gateway) "Gateway on" else "Gateway off",
            if (gateway) Icons.Default.CloudDone else Icons.Default.CloudOff,
            if (gateway) SafeGreen else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StatusChip(transport.uppercase(), Icons.Default.Lock, MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatusChip(label: String, icon: ImageVector, tint: Color) {
    AssistChip(
        onClick = {},
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        leadingIcon = { Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp)) },
        colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = null,
    )
}

/** Warns if location permission is missing or GPS is off: an SOS without coordinates is far less useful. */
@Composable
private fun LocationBanner() {
    val ctx = LocalContext.current
    val activity = ctx as Activity
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val gpsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { tick++ }

    val (permOk, gpsOn) = remember(tick) {
        ctx.granted(Manifest.permission.ACCESS_FINE_LOCATION) to
            ctx.getSystemService(LocationManager::class.java).isLocationEnabled
    }
    if (permOk && gpsOn) return
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.large) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.LocationOff, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(12.dp))
            Text(
                if (!permOk) "Location permission is off. Your SOS can't include where you are."
                else "GPS is turned off. Turn it on so your SOS includes your location.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                if (!permOk) permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                else checkLocationSettings(activity, onOk = { tick++ }, onResolve = { gpsLauncher.launch(it) })
            }) { Text("Fix") }
        }
    }
}

/**
 * Press-and-hold SOS: a 2 s hold fills the ring, then fires. Prevents pocket/accidental taps
 * while staying one-handed. While active, the button pulses and a tap pushes an extra update.
 */
@Composable
private fun SosButton(active: Boolean, onTrigger: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    val pulse = rememberInfiniteTransition(label = "pulse")
    val ring by pulse.animateFloat(
        initialValue = 1f, targetValue = if (active) 1.18f else 1.04f,
        animationSpec = infiniteRepeatable(tween(if (active) 700 else 1600, easing = LinearEasing), RepeatMode.Reverse),
        label = "ring",
    )
    val primaryRing = MaterialTheme.colorScheme.onSurface

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(260.dp)) {
            // halo
            Box(
                Modifier.size(220.dp).scale(ring)
                    .background(SosRed.copy(alpha = if (active) 0.25f else 0.12f), CircleShape),
            )
            // hold-progress ring
            Canvas(Modifier.size(236.dp)) {
                if (progress.value > 0f) {
                    drawArc(
                        color = primaryRing,
                        startAngle = -90f,
                        sweepAngle = 360f * progress.value,
                        useCenter = false,
                        topLeft = Offset(6f, 6f),
                        size = Size(size.width - 12f, size.height - 12f),
                        style = Stroke(width = 10f, cap = StrokeCap.Round),
                    )
                }
            }
            Surface(
                shape = CircleShape,
                color = if (active) SosRedDark else SosRed,
                shadowElevation = 10.dp,
                modifier = Modifier
                    .size(200.dp)
                    .semantics { contentDescription = if (active) "SOS active. Tap to send an update." else "Hold for 2 seconds to send SOS" }
                    .pointerInput(active) {
                        detectTapGestures(
                            onPress = {
                                if (active) {
                                    if (tryAwaitRelease()) { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onTrigger() }
                                    return@detectTapGestures
                                }
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                val job = scope.launch {
                                    progress.snapTo(0f)
                                    progress.animateTo(1f, tween(HOLD_MS, easing = LinearEasing))
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onTrigger()
                                }
                                tryAwaitRelease()
                                if (progress.value < 1f) job.cancel()
                                scope.launch { progress.animateTo(0f, tween(250)) }
                            },
                        )
                    },
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("SOS", color = Color.White, fontSize = 56.sp, fontWeight = FontWeight.Black)
                    Text(
                        if (active) "ACTIVE" else "HOLD 2 SEC",
                        color = Color.White.copy(alpha = 0.9f),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
        Text(
            if (active) "Help is being alerted. Tap to send an extra update."
            else "Press and hold to alert the authority and your contacts",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ActiveSosCard(sos: SosState, onSafe: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, null, tint = SosRed)
                Spacer(Modifier.width(8.dp))
                Text("SOS in progress", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            if (sos.locating) {
                Text("Getting your location…", style = MaterialTheme.typography.bodyMedium)
            } else {
                KeyValue("Alert", "#${sos.alertShort} · ${sos.updatesSent} update(s) sent")
                val loc = if (sos.lat != null && sos.lng != null && !(sos.lat == 0.0 && sos.lng == 0.0)) {
                    "%.5f, %.5f".format(sos.lat, sos.lng) + (sos.accuracyM?.let { " (±${it.toInt()} m)" } ?: "")
                } else "unavailable"
                KeyValue("Location", loc)
                if (sos.lat != null && sos.lng != null && !(sos.lat == 0.0 && sos.lng == 0.0)) {
                    GoogleMapEmbed(sos.lat, sos.lng, zoom = 16, heightDp = 170)
                }
                if (sos.smsSent > 0) KeyValue("SMS", "sent to ${sos.smsSent} contact(s) from your number")
                KeyValue(
                    "Delivery",
                    if (sos.deliveredCount > 0) "reached authority (${sos.deliveredCount}×)" else "relaying through mesh…",
                )
                sos.warning?.let {
                    Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LocationOn, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Live location shared every ${MeshNode.LIVE_UPDATE_S} s",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = onSafe,
                colors = ButtonDefaults.buttonColors(containerColor = SafeGreen),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("I'm safe") }
        }
    }
}

@Composable
private fun KeyValue(k: String, v: String) {
    Row {
        Text("$k  ", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(v, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun SectionCard(title: String, icon: ImageVector, subtitle: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

@Composable
private fun FeedRow(item: FeedItem) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.padding(top = 6.dp).size(10.dp)
                .background(if (item.mine) SosRed else MaterialTheme.colorScheme.outline, CircleShape),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(item.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(timeFmt.format(Date(item.atMs)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider(Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.surfaceVariant)
}

/** Developer panel: link layers, gateway role and relay window for adb / lab testing. */
@Composable
private fun DevSettingsDialog(node: MeshNode, onDismiss: () -> Unit) {
    val c = node.config
    var name by remember { mutableStateOf(c.nodeName) }
    var port by remember { mutableStateOf(c.meshPort.toString()) }
    var tcp by remember { mutableStateOf(c.tcpMesh) }
    var ble by remember { mutableStateOf(c.bluetooth) }
    var gateway by remember { mutableStateOf(c.gateway) }
    var window by remember { mutableStateOf(c.relayWindowMin.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Developer settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Node name") }, singleLine = true)
                SwitchRow("Bluetooth mesh (BLE)", ble) { ble = it }
                SwitchRow("TCP dev mesh (mesh-sim on server)", tcp) { tcp = it }
                if (tcp) {
                    OutlinedTextField(
                        port, { port = it.filter(Char::isDigit) }, label = { Text("mesh-sim port") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                SwitchRow("Act as internet gateway", gateway) { gateway = it }
                OutlinedTextField(
                    window, { window = it.filter(Char::isDigit) }, label = { Text("Relay window (minutes)") },
                    supportingText = { Text("Dev: 2 min. Real deployment: 60+ min.") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Text(
                    "Identity ${node.ids.identity.publicKey.joinToString("") { "%02x".format(it) }.take(16)}… · device ${node.ids.deviceUuid.take(8)}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                c.nodeName = name.trim(); c.meshPort = port.toIntOrNull() ?: 7777
                c.tcpMesh = tcp; c.bluetooth = ble; c.gateway = gateway
                c.relayWindowMin = window.toIntOrNull() ?: 2
                node.restart(); onDismiss()
            }) { Text("Save & restart") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(value, onChange)
    }
}

@Composable
private fun NearbyAlertCard(a: com.jilla.service.NearbyAlert, windowMin: Int) {
    val ctx = LocalContext.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, null, tint = SosRed)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("SOS within ~5 km of you", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                    Text(
                        "Alert #${a.alertShort} · ${a.updates} update(s) · last heard ${timeFmt.format(Date(a.lastSeenMs))}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            if (a.areaLat != 0.0 || a.areaLng != 0.0) GoogleMapEmbed(a.areaLat, a.areaLng, zoom = 12, heightDp = 160)
            Text(
                (when {
                    a.rejectedReason != null -> "The server REFUSED it (${a.rejectedReason}). "
                    a.forwarded -> "Your phone forwarded it to the authority. "
                    else -> "Your phone is relaying it to nearby phones. "
                }) +
                    "Who they are and their exact location are encrypted for responders only. " +
                    "Kept relaying for $windowMin min.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButtonSmall("Approximate area in Maps") { openInMaps(ctx, a.areaLat, a.areaLng, "Approximate SOS area") }
                OutlinedButtonSmall("Call 112") {
                    ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:112")))
                }
            }
        }
    }
}

@Composable
private fun OutlinedButtonSmall(label: String, onClick: () -> Unit) {
    androidx.compose.material3.OutlinedButton(onClick = onClick) { Text(label, style = MaterialTheme.typography.labelMedium) }
}

@Composable
private fun SmsToggle(node: MeshNode) {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(node.config.smsContacts) }
    var granted by remember { mutableStateOf(ctx.granted(Manifest.permission.SEND_SMS)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Text them from my number", style = MaterialTheme.typography.bodyLarge)
            Text(
                if (granted) "SMS with your live location link when SOS starts, and when you're safe"
                else "SMS permission needed",
                style = MaterialTheme.typography.bodySmall,
                color = if (granted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        }
        Switch(checked = on && granted, onCheckedChange = {
            if (it && !granted) launcher.launch(Manifest.permission.SEND_SMS)
            on = it; node.config.smsContacts = it
        })
    }
}


/** An unregistered phone's SOS reaches responders only as UNVERIFIED, so make that impossible to miss. */
@Composable
private fun RegistrationBanner(node: MeshNode) {
    val st by node.server.collectAsStateWithLifecycle()
    if (st.registered || node.account.registered) return
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.large) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(12.dp))
            Text(
                "This phone isn't registered with the server yet. Your SOS still goes out, but responders " +
                    "will see it as UNVERIFIED. Connect to the server once (Authority server below) to register.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { node.ensureRegistered(force = true) }) { Text("Retry") }
        }
    }
}
