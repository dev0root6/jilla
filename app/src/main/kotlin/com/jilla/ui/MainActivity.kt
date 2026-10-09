package com.jilla.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.jilla.JillaApp
import com.jilla.service.MeshService
import com.jilla.ui.theme.JillaTheme

/** First launch: registration + permissions + contacts. Afterwards: the SOS home screen. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val node = (application as JillaApp).node
        setContent {
            JillaTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    var onboarded by remember { mutableStateOf(node.account.onboarded) }
                    if (onboarded) {
                        LaunchedEffect(Unit) { MeshService.start(this@MainActivity) }
                        HomeScreen(node)
                    } else {
                        OnboardingFlow(node) { onboarded = true }
                    }
                }
            }
        }
    }
}
