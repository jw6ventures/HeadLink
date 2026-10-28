package com.jw6ventures.headlink

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [HomeAssistantSettings] in a preferences file of their own, `home_assistant`, which the
 * data extraction rules keep out of every backup and transfer, as they do traffic's.
 *
 * The token is stored encrypted with an AES key that lives in the Android Keystore and
 * never leaves it, so a copy of the preferences file alone does not give the token away.
 * A token that can no longer be decrypted (the key gone with a reset of the lock screen,
 * say) reads as none, and the card asks for it again.
 */
object HomeAssistantPrefs {
    private const val FILE = "home_assistant"
    private const val KEY_ALIAS = "headlink_home_assistant_token"
    private const val GCM_TAG_BITS = 128

    /**
     * Told on the main thread after every change the card saves, so a projection under way
     * shows or hides the button at once. Set by [ProjectionService] while it projects.
     */
    @Volatile
    var listener: ((HomeAssistantSettings) -> Unit)? = null

    /**
     * What was last read or saved, kept so the phone's screen, which renders on every
     * session event, does not ask the Keystore each time. Only [update] writes the file.
     */
    @Volatile
    private var cached: HomeAssistantSettings? = null

    fun load(context: Context): HomeAssistantSettings = cached ?: read(context).also { cached = it }

    private fun read(context: Context): HomeAssistantSettings {
        val stored = HashMap<String, Any?>(prefs(context).all)
        stored[HomeAssistantSettings.TOKEN] = (stored[HomeAssistantSettings.TOKEN] as? String)?.let(::decrypt) ?: ""
        return HomeAssistantSettings.fromStored(stored)
    }

    /** Reads, changes, stores; the same pattern as [TrafficPrefs.update]. */
    fun update(context: Context, change: (HomeAssistantSettings) -> HomeAssistantSettings): HomeAssistantSettings {
        val stored = synchronized(this) {
            val next = change(load(context)).repaired()
            val editor = prefs(context).edit()
            for ((key, value) in next.toStored()) {
                when {
                    key == HomeAssistantSettings.TOKEN -> {
                        val token = value as String
                        // Not stored at all when the Keystore cannot seal it; the card then says none is saved.
                        val sealed = token.takeIf { it.isNotEmpty() }?.let(::encrypt)
                        if (sealed == null) editor.remove(key) else editor.putString(key, sealed)
                    }
                    value is String -> editor.putString(key, value)
                    value is Boolean -> editor.putBoolean(key, value)
                }
            }
            editor.apply()
            // Read back, so a token that could not be sealed is not reported as saved.
            read(context).also { cached = it }
        }
        listener?.invoke(stored)
        return stored
    }

    /** The client the app uses: settings read afresh for every press. */
    fun client(context: Context): HomeAssistantClient {
        val app = context.applicationContext
        return HomeAssistantClient(settings = { load(app) }, http = UrlConnectionHomeAssistant())
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The IV, then the ciphertext and its tag, as Base64. */
    private fun encrypt(plain: String): String? = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(sealed, Base64.NO_WRAP)
    } catch (e: GeneralSecurityException) {
        null
    } catch (e: java.io.IOException) {
        null
    }

    private fun decrypt(stored: String): String? = try {
        val sealed = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, sealed, 0, GCM_IV_BYTES))
        String(cipher.doFinal(sealed, GCM_IV_BYTES, sealed.size - GCM_IV_BYTES), Charsets.UTF_8)
    } catch (e: GeneralSecurityException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: java.io.IOException) {
        null
    }

    private const val GCM_IV_BYTES = 12

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
