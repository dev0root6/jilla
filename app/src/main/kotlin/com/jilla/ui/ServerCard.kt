package com.jilla.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Help
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jilla.service.MeshNode
import com.jilla.service.NodeConfig
import com.jilla.ui.theme.Amber
import com.jilla.ui.theme.SafeGreen

/** Server IP entry + live status: reachable, authority key pinned, registered (by phone or device id). */
@Composable
fun ServerEditor(node: MeshNode, onSaved: () -> Unit = {}) {
    val st by node.server.collectAsStateWithLifecycle()
    var host by remember { mutableStateOf(node.config.serverHost) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = host,
            onValueChange = { host = it.trim() },
            label = { Text("Server IP or URL") },
            placeholder = { Text("192.168.1.20  or  https://xyz.trycloudflare.com") },
            supportingText = {
                Text(
                    if (host.startsWith("https://") && Regex(":\\d+$").containsMatchIn(NodeConfig.cleanServer(host).substringAfter("://")))
                        "Tunnel URLs use HTTPS on the default port: remove the :port."
                    else if ('?' in host || host.substringAfter("://").contains('/'))
                        "Will connect to ${NodeConfig.cleanServer(host)} (the dashboard token isn't needed here)."
                    else "Laptop IP on the same Wi-Fi, a tunnel URL, or 127.0.0.1 over USB (adb reverse)."
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                st.checking -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                st.reachable == true && st.keyMatches == true -> Icon(Icons.Default.CheckCircle, null, tint = SafeGreen)
                st.reachable == null -> Icon(Icons.Default.Help, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                st.keyMatches == false -> Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error)
                else -> Icon(Icons.Default.Error, null, tint = Amber)
            }
            Spacer(Modifier.width(10.dp))
            Text(
                when {
                    st.checking -> "Checking ${node.config.backendUrl}…"
                    st.reachable == null -> "Not checked yet"
                    st.reachable == true && st.keyMatches == true ->
                        "Connected · authority key verified" + if (st.registered) {
                            " · registered by " + if (st.identifiedBy == "device_id") "device ID" else "phone number"
                        } else ""
                    else -> st.message
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        }
        Button(
            enabled = host.isNotBlank() && !st.checking,
            onClick = { node.setServer(host); onSaved() },
        ) { Text("Save & connect") }
    }
}
