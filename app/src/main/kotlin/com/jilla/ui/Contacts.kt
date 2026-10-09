package com.jilla.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jilla.account.EmergencyContact

const val MAX_CONTACTS = 5

/** Selected emergency contacts + "add from contacts" (asks READ_CONTACTS) + "add manually". */
@Composable
fun ContactsEditor(contacts: List<EmergencyContact>, onChange: (List<EmergencyContact>) -> Unit) {
    val ctx = LocalContext.current
    var showPicker by remember { mutableStateOf(false) }
    var showManual by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) showPicker = true else denied = true
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (contacts.isEmpty()) {
            Text(
                "No emergency contacts yet. Add at least one person you trust.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        contacts.forEach { c ->
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(c.name)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                        Text(c.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(c.phone, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { onChange(contacts - c) }) { Icon(Icons.Default.Close, "Remove ${c.name}") }
                }
            }
        }
        if (denied) {
            Text(
                "Contacts permission denied. You can still add numbers manually.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (contacts.size < MAX_CONTACTS) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = {
                    if (ctx.granted(Manifest.permission.READ_CONTACTS)) showPicker = true
                    else permLauncher.launch(Manifest.permission.READ_CONTACTS)
                }) {
                    Icon(Icons.Default.Contacts, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("From contacts")
                }
                OutlinedButton(onClick = { showManual = true }) {
                    Icon(Icons.Default.PersonAdd, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Add number")
                }
            }
        }
    }

    if (showPicker) {
        ContactPickerDialog(
            already = contacts,
            limit = MAX_CONTACTS,
            onDismiss = { showPicker = false },
            onDone = { onChange(it); showPicker = false },
        )
    }
    if (showManual) {
        ManualContactDialog(onDismiss = { showManual = false }, onAdd = { onChange(contacts + it); showManual = false })
    }
}

@Composable
fun Avatar(name: String, size: Int = 36) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(size.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                name.trim().firstOrNull()?.uppercase() ?: "?",
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactPickerDialog(
    already: List<EmergencyContact>,
    limit: Int,
    onDismiss: () -> Unit,
    onDone: (List<EmergencyContact>) -> Unit,
) {
    val ctx = LocalContext.current
    var all by remember { mutableStateOf<List<EmergencyContact>?>(null) }
    var query by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<EmergencyContact>().apply { addAll(already) } }
    LaunchedEffect(Unit) { all = ctx.loadPhoneContacts() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Choose contacts (${selected.size}/$limit)") },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") } },
                    actions = { TextButton(onClick = { onDone(selected.toList()) }) { Text("Done") } },
                )
            },
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    placeholder = { Text("Search name or number") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                val list = all
                when {
                    list == null -> Text("Loading…", Modifier.padding(16.dp))
                    list.isEmpty() -> Text("No contacts with phone numbers found.", Modifier.padding(16.dp))
                    else -> {
                        val q = query.trim().lowercase()
                        val shown = if (q.isEmpty()) list else list.filter { q in it.name.lowercase() || q in it.phone }
                        LazyColumn {
                            items(shown, key = { it.name + it.phone }) { c ->
                                val checked = c in selected
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clickable(enabled = checked || selected.size < limit) {
                                            if (checked) selected.remove(c) else selected.add(c)
                                        }
                                        .padding(horizontal = 16.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Avatar(c.name)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(c.name, style = MaterialTheme.typography.bodyLarge)
                                        Text(c.phone, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Checkbox(checked = checked, onCheckedChange = null)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ManualContactDialog(onDismiss: () -> Unit, onAdd: (EmergencyContact) -> Unit) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    val digits = phone.count { it.isDigit() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add emergency contact") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(
                    phone, { phone = it }, label = { Text("Phone number") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && digits in 8..15,
                onClick = { onAdd(EmergencyContact(name.trim(), phone.filter { it.isDigit() || it == '+' })) },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
