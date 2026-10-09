package com.jilla.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Embedded Google Map (keyless embed URL in a WebView). Needs internet; when offline the
 * caller still shows coordinates and an "Open in Maps" button, which works with offline maps.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun GoogleMapEmbed(lat: Double, lng: Double, zoom: Int, heightDp: Int = 180) {
    val url = "https://maps.google.com/maps?q=$lat,$lng&z=$zoom&output=embed"
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(heightDp.dp).clip(MaterialTheme.shapes.large),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = WebViewClient()
                loadUrl(url)
                tag = url
            }
        },
        update = { wv -> if (wv.tag != url) { wv.tag = url; wv.loadUrl(url) } },
    )
}

fun openInMaps(ctx: android.content.Context, lat: Double, lng: Double, label: String) {
    val geo = Uri.parse("geo:$lat,$lng?q=$lat,$lng(${Uri.encode(label)})")
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, geo).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com/?q=$lat,$lng")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
}
