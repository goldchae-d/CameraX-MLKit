// app/src/main/java/com/example/camerax_mlkit/crypto/SessionStore.kt
package com.example.camerax_mlkit.crypto

import android.content.Context
import android.util.Base64
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

object SessionStore {
    private const val PREF = "qr_session"
    private const val KEY_ID = "key_id"
    private const val ENC  = "enc_b64"
    private const val MAC  = "mac_b64"
    private const val EXP  = "exp_ms"

    fun save(ctx: Context, keyId: String, enc: SecretKey, mac: SecretKey, expiresAtMs: Long) {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        p.edit()
            .putString(KEY_ID, keyId)
            .putString(ENC, Base64.encodeToString(enc.encoded, Base64.NO_WRAP))
            .putString(MAC, Base64.encodeToString(mac.encoded, Base64.NO_WRAP))
            .putLong(EXP, expiresAtMs)
            .apply()
    }

    fun load(ctx: Context): Triple<String, SecretKey, SecretKey>? {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val id = p.getString(KEY_ID, null) ?: return null
        val encB = p.getString(ENC, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        val macB = p.getString(MAC, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        val now = System.currentTimeMillis()
        val exp = p.getLong(EXP, 0L)
        if (exp <= now) return null
        return Triple(id, SecretKeySpec(encB, "AES"), SecretKeySpec(macB, "HmacSHA256"))
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
