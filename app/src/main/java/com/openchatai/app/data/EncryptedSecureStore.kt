package com.openchai.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.openchai.core.data.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Implementasi [SecureStore] berbasis EncryptedSharedPreferences (Android Keystore).
 *
 * Mapping key: setiap kredensial disimpan dengan key `"api_key_<provider>"`,
 * mis. `api_key_OLLAMA`.
 *
 * Fallback (terdokumentasi): bila inisialisasi EncryptedSharedPreferences gagal
 * (mis. Android Keystore korup setelah restore backup, perangkat dengan Keystore
 * tidak stabil, atau error sistem lain), store jatuh kembali ke SharedPreferences
 * biasa pada file yang sama. Keputusan desain: **keandalan > crash** — aplikasi
 * tetap berfungsi dan API key tetap tersimpan walau tanpa enkripsi perangkat.
 * Enkripsi data-at-rest perangkat tetap melindungi file preferences ini.
 *
 * Semua akses di-serialisasi lewat [Mutex] dan dijalankan di [Dispatchers.IO].
 */
class EncryptedSecureStore(private val context: Context) : SecureStore {

    private val mutex = Mutex()

    private val prefs: SharedPreferences by lazy { createPrefs() }

    private fun createPrefs(): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Exception) {
            // Fallback: SharedPreferences biasa bila Keystore/EncryptedSharedPreferences gagal.
            context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        }
    }

    private fun keyFor(provider: String) = "api_key_$provider"

    override suspend fun apiKey(provider: String): String = withContext(Dispatchers.IO) {
        mutex.withLock { prefs.getString(keyFor(provider), "").orEmpty() }
    }

    override suspend fun saveApiKey(provider: String, value: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock { prefs.edit().putString(keyFor(provider), value).apply() }
    }

    override suspend fun clearApiKey(provider: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock { prefs.edit().remove(keyFor(provider)).apply() }
    }

    private companion object {
        const val PREFS_FILE = "open_chat_ai_secure"
    }
}
