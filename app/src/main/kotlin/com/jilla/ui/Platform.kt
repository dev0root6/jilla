package com.jilla.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.ContactsContract
import android.telephony.SubscriptionManager
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.auth.api.identity.GetPhoneNumberHintIntentRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import com.jilla.account.EmergencyContact

/** One runtime permission group, with the reason shown to the user. */
data class PermissionGroup(val title: String, val why: String, val perms: List<String>, val required: Boolean)

val permissionGroups: List<PermissionGroup> = buildList {
    add(
        PermissionGroup(
            "Precise location",
            "Your exact coordinates are sealed into every SOS so responders can find you. Only the authority can read them.",
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            required = true,
        )
    )
    add(
        PermissionGroup(
            "Phone number",
            "Registers you with the number of the SIM in this phone, so responders can call you back.",
            listOf(Manifest.permission.READ_PHONE_NUMBERS),
            required = false,
        )
    )
    add(
        PermissionGroup(
            "SMS",
            "Texts your emergency contacts from your own number with your live location when you raise an SOS.",
            listOf(Manifest.permission.SEND_SMS),
            required = false,
        )
    )
    add(
        PermissionGroup(
            "Contacts",
            "Pick the emergency contacts who should be told when you raise an SOS.",
            listOf(Manifest.permission.READ_CONTACTS),
            required = false,
        )
    )
    if (Build.VERSION.SDK_INT >= 31) add(
        PermissionGroup(
            "Nearby devices",
            "Lets your phone relay SOS alerts to nearby phones over Bluetooth when there's no internet.",
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT),
            required = false,
        )
    )
    if (Build.VERSION.SDK_INT >= 33) add(
        PermissionGroup(
            "Notifications",
            "Shows that the safety mesh is active and when an alert is relayed.",
            listOf(Manifest.permission.POST_NOTIFICATIONS),
            required = false,
        )
    )
}

fun Context.granted(perm: String) = checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
fun Context.grantedAll(g: PermissionGroup) = g.perms.all { granted(it) }

/** Google Phone Number Hint: lets the user pick their SIM number without any permission. */
fun requestPhoneHint(activity: Activity, onIntent: (IntentSenderRequest) -> Unit, onUnavailable: () -> Unit) {
    Identity.getSignInClient(activity)
        .getPhoneNumberHintIntent(GetPhoneNumberHintIntentRequest.builder().build())
        .addOnSuccessListener { pi -> onIntent(IntentSenderRequest.Builder(pi.intentSender).build()) }
        .addOnFailureListener { onUnavailable() }
}

fun phoneFromHintResult(activity: Activity, data: android.content.Intent?): String? =
    runCatching { Identity.getSignInClient(activity).getPhoneNumberFromIntent(data) }.getOrNull()

/** Fallback: read the SIM number directly (needs READ_PHONE_NUMBERS; often blank on carriers that don't provision it). */
@SuppressLint("MissingPermission", "HardwareIds")
fun Context.simPhoneNumber(): String? {
    if (!granted(Manifest.permission.READ_PHONE_NUMBERS)) return null
    return runCatching {
        val sm = getSystemService(SubscriptionManager::class.java)
        val n = if (Build.VERSION.SDK_INT >= 33) {
            sm.getPhoneNumber(SubscriptionManager.getDefaultSubscriptionId())
        } else {
            @Suppress("DEPRECATION")
            sm.activeSubscriptionInfoList?.firstOrNull()?.number
        }
        n?.takeIf { it.isNotBlank() }
    }.getOrNull()
}

/** Checks GPS is on; if not, hands back the system "turn on location" dialog to launch. */
fun checkLocationSettings(activity: Activity, onOk: () -> Unit, onResolve: (IntentSenderRequest) -> Unit) {
    val req = LocationSettingsRequest.Builder()
        .addLocationRequest(LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000).build())
        .setAlwaysShow(true)
        .build()
    LocationServices.getSettingsClient(activity).checkLocationSettings(req)
        .addOnSuccessListener { onOk() }
        .addOnFailureListener { e ->
            if (e is ResolvableApiException) onResolve(IntentSenderRequest.Builder(e.resolution).build()) else onOk()
        }
}

/** All phone-number contacts on the device (requires READ_CONTACTS). */
fun Context.loadPhoneContacts(): List<EmergencyContact> {
    if (!granted(Manifest.permission.READ_CONTACTS)) return emptyList()
    val out = LinkedHashMap<String, EmergencyContact>()
    contentResolver.query(
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
        null, null,
        "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE ASC",
    )?.use { c ->
        while (c.moveToNext()) {
            val name = c.getString(0) ?: continue
            val num = c.getString(1)?.filter { it.isDigit() || it == '+' } ?: continue
            out.putIfAbsent("$name|$num", EmergencyContact(name, num))
        }
    }
    return out.values.toList()
}
