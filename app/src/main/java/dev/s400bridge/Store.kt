package dev.s400bridge

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.secureData by preferencesDataStore("bridge")
class Store(context: Context) {
    private val data = context.secureData
    private val field = stringPreferencesKey("sealed_state_v1")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val key: SecretKey by lazy {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("s400-state-v1", null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("s400-state-v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun seal(s: AppState): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        return Base64.encodeToString(c.iv + c.doFinal(json.encodeToString(s).toByteArray()), Base64.NO_WRAP)
    }
    private fun open(value: String?): AppState {
        if (value == null) return AppState()
        val b = Base64.decode(value, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, b.copyOfRange(0, 12))) }
        return json.decodeFromString<AppState>(c.doFinal(b.copyOfRange(12, b.size)).toString(Charsets.UTF_8))
    }
    val states = data.data.map { open(it[field]) }
    suspend fun read() = states.first()
    suspend fun update(change: (AppState) -> AppState) { data.edit { it[field] = seal(change(open(it[field]))) } }
}
