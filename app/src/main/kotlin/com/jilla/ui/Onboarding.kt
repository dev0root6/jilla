package com.jilla.ui

import android.Manifest
import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jilla.account.RegistrationClient
import com.jilla.service.MeshNode
import com.jilla.service.logi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Step { WELCOME, SERVER, PERMISSIONS, PROFILE, CONTACTS }

@Composable
fun OnboardingFlow(node: MeshNode, onFinished: () -> Unit) {
    val acct = node.account
    var step by remember { mutableStateOf(Step.WELCOME) }

    BackHandler(enabled = step != Step.WELCOME) { step = Step.entries[step.ordinal - 1] }

    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        if (step != Step.WELCOME) {
            LinearProgressIndicator(
                progress = { step.ordinal / (Step.entries.size - 1f) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                (slideInHorizontally { it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 3 } + fadeOut())
            },
            label = "onboarding",
        ) { s ->
            when (s) {
                Step.WELCOME -> Welcome { step = Step.SERVER }
                Step.SERVER -> ServerStep(node) { step = Step.PERMISSIONS }
                Step.PERMISSIONS -> Permissions { step = Step.PROFILE }
                Step.PROFILE -> Profile(node) { step = Step.CONTACTS }
                Step.CONTACTS -> ContactsStep(node) {
                    acct.onboarded = true
                    onFinished()
                }
            }
        }
    }
}

@Composable
private fun StepPage(
    icon: ImageVector,
    title: String,
    subtitle: String,
    footer: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(16.dp))
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(56.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(28.dp))
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(title, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            content()
            Spacer(Modifier.height(24.dp))
        }
        Column(Modifier.padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { footer() }
    }
}

@Composable
private fun Welcome(onNext: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(112.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Shield, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(60.dp))
            }
        }
        Spacer(Modifier.height(28.dp))
        Text("Jilla Safety", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Emergency SOS that works even without internet: nearby phones relay your alert until it reaches help.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
        Feature(Icons.Default.Lock, "Sealed end-to-end", "Only the authority can read who you are and where you are.")
        Feature(Icons.Default.Hub, "Works offline", "Alerts hop phone-to-phone over Bluetooth to reach a connected device.")
        Feature(Icons.Default.Shield, "Signed and verified", "Alerts can't be faked, altered or replayed.")
        Spacer(Modifier.height(40.dp))
        Button(onClick = onNext, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Get started") }
        Spacer(Modifier.height(12.dp))
        Text(
            "Proof of concept, not a certified emergency service. In a real emergency, call 112.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Feature(icon: ImageVector, title: String, body: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ServerStep(node: MeshNode, onNext: () -> Unit) {
    val st by node.server.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { node.ensureRegistered() }
    StepPage(
        Icons.Default.Dns,
        "Connect to the authority",
        "Enter the IP of the authority server (in this proof of concept, your laptop). Alerts are sealed with its key.",
        footer = {
            Button(
                enabled = st.reachable == true && st.keyMatches == true,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                onClick = onNext,
            ) { Text("Continue") }
            TextButton(modifier = Modifier.align(Alignment.CenterHorizontally), onClick = onNext) {
                Text("Skip: no server reachable right now")
            }
        },
    ) {
        ServerEditor(node)
        Spacer(Modifier.height(12.dp))
        InfoNote(
            "The app checks the server's public key against the authority key built into the app, so alerts " +
                "are never sent to a server that can't (or shouldn't) open them.",
        )
    }
}

@Composable
private fun Profile(node: MeshNode, onNext: () -> Unit) {
    val ctx = LocalContext.current
    val st by node.server.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf(node.account.name) }
    // Number of the active SIM; blank means the server will identify us by device id.
    val detected = remember { node.detectPhone() }
    var phone by remember { mutableStateOf(node.account.phone.ifBlank { detected }) }
    var editing by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }
    val phonePerm = ctx.granted(Manifest.permission.READ_PHONE_NUMBERS)

    LaunchedEffect(st.registered, submitted) { if (submitted && st.registered) onNext() }

    StepPage(
        Icons.Default.PhoneAndroid,
        "Register this phone",
        "You're registered with the number of the SIM in this phone. Responders see it only after opening a sealed alert.",
        footer = {
            if (submitted && !st.checking && !st.registered && st.message.isNotBlank()) {
                Text(st.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Button(
                enabled = name.isNotBlank() && !st.checking,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                onClick = {
                    node.account.name = name.trim()
                    node.account.phone = RegistrationClient.normalize(phone)
                    submitted = true
                    node.ensureRegistered(force = true)
                },
            ) {
                if (st.checking) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Register")
            }
            if (submitted && !st.checking && !st.registered) {
                TextButton(modifier = Modifier.align(Alignment.CenterHorizontally), onClick = onNext) {
                    Text("Continue offline (register later)")
                }
            }
        },
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Full name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.SimCard, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (phone.isNotBlank()) "Registering with this number" else "No SIM number available",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            phone.ifBlank { "Using device ID ${node.ids.deviceUuid.take(8)}… as a temporary identity" },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    TextButton(onClick = { editing = !editing }) { Text(if (editing) "Done" else "Edit") }
                }
                if (editing) {
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text("Mobile number") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
                if (!phonePerm && detected.isBlank()) {
                    Text(
                        "Phone-number permission is off, so the SIM number couldn't be read.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        InfoNote(
            "Registration is signed with a private key that never leaves this phone, " +
                "so no one else can register or impersonate your identity.",
        )
    }
}

@Composable
private fun Permissions(onNext: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as Activity
    var tick by remember { mutableIntStateOf(0) } // re-read grants after each request / resume
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val gpsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { onNext() }

    @Suppress("UNUSED_EXPRESSION") tick
    val locationOk = ctx.grantedAll(permissionGroups.first())

    StepPage(
        Icons.Default.Shield,
        "Allow access",
        "Jilla needs a few permissions to send your location and relay alerts. Location is required.",
        footer = {
            Button(
                enabled = locationOk,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                onClick = { checkLocationSettings(activity, onOk = onNext, onResolve = { gpsLauncher.launch(it) }) },
            ) { Text(if (locationOk) "Continue" else "Allow location to continue") }
            TextButton(
                modifier = Modifier.align(Alignment.CenterHorizontally),
                onClick = { launcher.launch(permissionGroups.flatMap { it.perms }.toTypedArray()) },
            ) { Text("Allow all") }
        },
    ) {
        permissionGroups.forEach { g ->
            val ok = remember(tick) { ctx.grantedAll(g) }
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(g.title, style = MaterialTheme.typography.titleMedium)
                            if (g.required) {
                                Spacer(Modifier.width(8.dp))
                                Text("Required", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(g.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(12.dp))
                    if (ok) {
                        Icon(Icons.Default.CheckCircle, "Granted", tint = com.jilla.ui.theme.SafeGreen)
                    } else {
                        FilledTonalButton(onClick = { launcher.launch(g.perms.toTypedArray()) }) { Text("Allow") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactsStep(node: MeshNode, onDone: () -> Unit) {
    var contacts by remember { mutableStateOf(node.account.contacts) }
    StepPage(
        Icons.Default.PhoneAndroid,
        "Emergency contacts",
        "These people are notified along with the authority when you raise an SOS. Their details are sealed inside the alert.",
        footer = {
            Button(
                enabled = contacts.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
                onClick = { node.account.contacts = contacts; onDone() },
            ) { Text("Finish setup") }
            TextButton(modifier = Modifier.align(Alignment.CenterHorizontally), onClick = onDone) { Text("Skip for now") }
        },
    ) {
        ContactsEditor(contacts) { contacts = it; node.account.contacts = it }
    }
}

@Composable
fun InfoNote(text: String) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(12.dp),
            fontWeight = FontWeight.Normal,
        )
    }
}
