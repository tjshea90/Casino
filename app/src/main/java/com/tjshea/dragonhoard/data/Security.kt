package com.tjshea.dragonhoard.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Password hashing and wallet-file signing. */
object Security {
    private const val KEY_ALIAS = "dragonhoard_wallet_mac_v1"
    const val PBKDF2_ITERATIONS = 40_000
    private val random = SecureRandom()

    fun newSalt(): ByteArray = ByteArray(16).also { random.nextBytes(it) }

    fun hashPassword(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray) = MessageDigest.isEqual(a, b)

    fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    /**
     * HMAC key held in the Android Keystore (non-exportable; hardware-backed where
     * the device supports it). If the keystore is unavailable we fall back to a
     * random key in private storage, which still defeats casual file editing.
     */
    private fun macKey(context: Context): SecretKey {
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore")
            gen.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN).build())
            return gen.generateKey()
        } catch (e: Exception) {
            val prefs = context.getSharedPreferences("k", Context.MODE_PRIVATE)
            val existing = prefs.getString("fallback", null)
            val raw = if (existing != null) unb64(existing) else ByteArray(32).also {
                random.nextBytes(it); prefs.edit().putString("fallback", b64(it)).commit()
            }
            return SecretKeySpec(raw, "HmacSHA256")
        }
    }

    fun sign(context: Context, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(macKey(context))
        return mac.doFinal(data)
    }
}
