package com.jilla.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import com.jilla.account.EmergencyContact
import com.jilla.ui.MainActivity

/** "Someone nearby needs help" notifications for alerts this phone receives over the mesh. */
class AlertNotifier(private val ctx: Context) {
    private val nm = ctx.getSystemService(NotificationManager::class.java)

    init {
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Nearby SOS alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Someone near you raised an SOS"
                enableVibration(true)
            }
        )
    }

    fun nearby(a: NearbyAlert) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Someone nearby needs help")
            .setContentText("SOS within ~5 km (area ${a.geohash}). Your phone is relaying it to responders.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "An encrypted SOS was received near you (area ${a.geohash}). Your phone is relaying it " +
                        "to the authority. Their identity and exact location are sealed: only responders can read them."
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(a.streamKey.hashCode(), n)
    }

    companion object { const val CHANNEL = "nearby_alerts" }
}

/**
 * Texts emergency contacts from the user's own SIM. Plain SMS goes through the carrier
 * unencrypted: acceptable for people the user chose; the mesh alert itself stays sealed.
 */
object SmsAlerter {
    fun canSend(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    fun sos(ctx: Context, contacts: List<EmergencyContact>, name: String, lat: Double, lng: Double, accM: Float?): Int {
        val where = if (lat == 0.0 && lng == 0.0) "Location unavailable."
        else "Location: https://maps.google.com/?q=%.6f,%.6f".format(lat, lng) + (accM?.let { " (±${it.toInt()} m)" } ?: "")
        return send(ctx, contacts, "EMERGENCY: $name needs help! $where Sent automatically by Jilla SOS.")
    }

    fun safe(ctx: Context, contacts: List<EmergencyContact>, name: String) =
        send(ctx, contacts, "$name is safe now. SOS ended. (Jilla SOS)")

    private fun send(ctx: Context, contacts: List<EmergencyContact>, text: String): Int {
        if (!canSend(ctx) || contacts.isEmpty()) return 0
        val sms = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java)
        else @Suppress("DEPRECATION") SmsManager.getDefault()
        var sent = 0
        for (c in contacts) {
            runCatching {
                sms.sendMultipartTextMessage(c.phone, null, sms.divideMessage(text), null, null)
                sent++
            }.onFailure { logi("sms: failed to ${c.phone}: ${it.message}") }
        }
        logi("sms: sent to $sent/${contacts.size} contact(s)")
        return sent
    }
}
