package com.jilla.account

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class EmergencyContact(val name: String, val phone: String) {
    /** Form carried inside the sealed block (authority-only). */
    fun sealedForm() = "$name <$phone>"
}

/** The registered user's profile. Stays on the device; name/contacts only ever leave inside the sealed block. */
class AccountStore(ctx: Context) {
    private val p = ctx.getSharedPreferences("jilla_account", Context.MODE_PRIVATE)

    var name: String
        get() = p.getString("name", "") ?: ""
        set(v) = p.edit().putString("name", v).apply()

    var phone: String
        get() = p.getString("phone", "") ?: ""
        set(v) = p.edit().putString("phone", v).apply()

    /** True once the server accepted our signed registration. */
    var registered: Boolean
        get() = p.getBoolean("registered", false)
        set(v) = p.edit().putBoolean("registered", v).apply()

    /** "phone" or "device_id": how the server identifies this user. */
    var identifiedBy: String
        get() = p.getString("identified_by", "") ?: ""
        set(v) = p.edit().putString("identified_by", v).apply()

    /** True once the user finished onboarding (permissions + contacts). */
    var onboarded: Boolean
        get() = p.getBoolean("onboarded", false)
        set(v) = p.edit().putBoolean("onboarded", v).apply()

    var contacts: List<EmergencyContact>
        get() {
            val arr = JSONArray(p.getString("contacts", "[]"))
            return List(arr.length()) { i ->
                arr.getJSONObject(i).let { EmergencyContact(it.getString("name"), it.getString("phone")) }
            }
        }
        set(v) {
            val arr = JSONArray()
            v.forEach { arr.put(JSONObject().put("name", it.name).put("phone", it.phone)) }
            p.edit().putString("contacts", arr.toString()).apply()
        }
}
