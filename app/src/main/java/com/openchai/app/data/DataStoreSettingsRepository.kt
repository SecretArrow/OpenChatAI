package com.openchai.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.openchai.core.data.SecureStore
import com.openchai.core.model.ProviderId
import com.openchai.core.settings.AppSettings
import com.openchai.core.settings.ChatDensity
import com.openchai.core.settings.EngineMode
import com.openchai.core.settings.SettingsRepository
import com.openchai.core.settings.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** DataStore preferences tunggal untuk seluruh pengaturan aplikasi (file-level singleton). */
private val Context.dataStore by preferencesDataStore(name = "open_chat_ai_settings")

private val DEFAULTS = AppSettings()

private val KEY_ENGINE_MODE = stringPreferencesKey("engine_mode")
private val KEY_ACTIVE_PROVIDER = stringPreferencesKey("active_provider")
private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
private val KEY_CHAT_DENSITY = stringPreferencesKey("chat_density")
private val KEY_SELECTED_MODEL = stringPreferencesKey("selected_model")
private val KEY_OLLAMA_ENDPOINT = stringPreferencesKey("ollama_endpoint")
private val KEY_OPENAI_ENDPOINT = stringPreferencesKey("openai_endpoint")
private val KEY_ANTHROPIC_ENDPOINT = stringPreferencesKey("anthropic_endpoint")
private val KEY_GOOGLE_ENDPOINT = stringPreferencesKey("google_endpoint")
private val KEY_CUSTOM_ENDPOINT = stringPreferencesKey("custom_endpoint")
private val KEY_LOCAL_MODEL_PATH = stringPreferencesKey("local_model_path")
private val KEY_LOCAL_CONTEXT_SIZE = intPreferencesKey("local_context_size")
private val KEY_LOCAL_THREADS = intPreferencesKey("local_threads")
private val KEY_LOCAL_AUTO_LOAD = booleanPreferencesKey("local_auto_load")
private val KEY_OPENCODE_SERVER_URL = stringPreferencesKey("opencode_server_url")
private val KEY_PREFER_OPENCODE = booleanPreferencesKey("prefer_opencode_engine")
private val KEY_MAX_AGENT_ITERATIONS = intPreferencesKey("max_agent_iterations")
private val KEY_AUTO_APPROVE_COMMANDS = booleanPreferencesKey("auto_approve_commands")
private val KEY_TERMINAL_FONT_SIZE = intPreferencesKey("terminal_font_size")
private val KEY_TERMINAL_AUTO_SCROLL = booleanPreferencesKey("terminal_auto_scroll")
private val KEY_CHAT_FONT_SCALE = floatPreferencesKey("chat_font_scale")
private val KEY_EXTRA_PATH_DIRS = stringPreferencesKey("extra_path_dirs")

private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
    if (name.isNullOrBlank()) default
    else runCatching { enumValueOf<T>(name) }.getOrDefault(default)

/**
 * Implementasi [SettingsRepository] di atas Jetpack DataStore (Preferences).
 * Semua field [AppSettings] dipetakan ke Preferences.Key; enum disimpan sebagai
 * `name` (String). API key dibaca/ditulis lewat [SecureStore].
 */
class DataStoreSettingsRepository(
    private val context: Context,
    private val secure: SecureStore
) : SettingsRepository {

    override val settings: StateFlow<AppSettings> =
        context.dataStore.data
            .map { it.toAppSettings() }
            .stateIn(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                started = SharingStarted.Eagerly,
                initialValue = AppSettings()
            )

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val next = transform(prefs.toAppSettings())
            prefs[KEY_ENGINE_MODE] = next.engineMode.name
            prefs[KEY_ACTIVE_PROVIDER] = next.activeProvider.name
            prefs[KEY_THEME_MODE] = next.themeMode.name
            prefs[KEY_CHAT_DENSITY] = next.chatDensity.name
            prefs[KEY_SELECTED_MODEL] = next.selectedModel
            prefs[KEY_OLLAMA_ENDPOINT] = next.ollamaEndpoint
            prefs[KEY_OPENAI_ENDPOINT] = next.openaiEndpoint
            prefs[KEY_ANTHROPIC_ENDPOINT] = next.anthropicEndpoint
            prefs[KEY_GOOGLE_ENDPOINT] = next.googleEndpoint
            prefs[KEY_CUSTOM_ENDPOINT] = next.customEndpoint
            prefs[KEY_LOCAL_MODEL_PATH] = next.localModelPath
            prefs[KEY_LOCAL_CONTEXT_SIZE] = next.localContextSize
            prefs[KEY_LOCAL_THREADS] = next.localThreads
            prefs[KEY_LOCAL_AUTO_LOAD] = next.localAutoLoad
            prefs[KEY_OPENCODE_SERVER_URL] = next.openCodeServerUrl
            prefs[KEY_PREFER_OPENCODE] = next.preferOpenCodeEngine
            prefs[KEY_MAX_AGENT_ITERATIONS] = next.maxAgentIterations
            prefs[KEY_AUTO_APPROVE_COMMANDS] = next.autoApproveCommands
            prefs[KEY_TERMINAL_FONT_SIZE] = next.terminalFontSize
            prefs[KEY_TERMINAL_AUTO_SCROLL] = next.terminalAutoScroll
            prefs[KEY_CHAT_FONT_SCALE] = next.chatFontScale
            prefs[KEY_EXTRA_PATH_DIRS] = next.extraPathDirs
        }
    }

    override suspend fun apiKey(provider: ProviderId): String =
        secure.apiKey(API_KEY_PREFIX + provider.name)

    override suspend fun setApiKey(provider: ProviderId, key: String): Unit =
        secure.saveApiKey(API_KEY_PREFIX + provider.name, key)

    private fun Preferences.toAppSettings(): AppSettings = AppSettings(
        engineMode = enumOrDefault(this[KEY_ENGINE_MODE], EngineMode.AGENT),
        activeProvider = enumOrDefault(this[KEY_ACTIVE_PROVIDER], ProviderId.OLLAMA),
        selectedModel = this[KEY_SELECTED_MODEL] ?: DEFAULTS.selectedModel,
        ollamaEndpoint = this[KEY_OLLAMA_ENDPOINT] ?: DEFAULTS.ollamaEndpoint,
        openaiEndpoint = this[KEY_OPENAI_ENDPOINT] ?: DEFAULTS.openaiEndpoint,
        anthropicEndpoint = this[KEY_ANTHROPIC_ENDPOINT] ?: DEFAULTS.anthropicEndpoint,
        googleEndpoint = this[KEY_GOOGLE_ENDPOINT] ?: DEFAULTS.googleEndpoint,
        customEndpoint = this[KEY_CUSTOM_ENDPOINT] ?: DEFAULTS.customEndpoint,
        localModelPath = this[KEY_LOCAL_MODEL_PATH] ?: DEFAULTS.localModelPath,
        localContextSize = this[KEY_LOCAL_CONTEXT_SIZE] ?: DEFAULTS.localContextSize,
        localThreads = this[KEY_LOCAL_THREADS] ?: DEFAULTS.localThreads,
        localAutoLoad = this[KEY_LOCAL_AUTO_LOAD] ?: DEFAULTS.localAutoLoad,
        openCodeServerUrl = this[KEY_OPENCODE_SERVER_URL] ?: DEFAULTS.openCodeServerUrl,
        preferOpenCodeEngine = this[KEY_PREFER_OPENCODE] ?: DEFAULTS.preferOpenCodeEngine,
        maxAgentIterations = this[KEY_MAX_AGENT_ITERATIONS] ?: DEFAULTS.maxAgentIterations,
        autoApproveCommands = this[KEY_AUTO_APPROVE_COMMANDS] ?: DEFAULTS.autoApproveCommands,
        terminalFontSize = this[KEY_TERMINAL_FONT_SIZE] ?: DEFAULTS.terminalFontSize,
        terminalAutoScroll = this[KEY_TERMINAL_AUTO_SCROLL] ?: DEFAULTS.terminalAutoScroll,
        themeMode = enumOrDefault(this[KEY_THEME_MODE], ThemeMode.SYSTEM),
        chatFontScale = this[KEY_CHAT_FONT_SCALE] ?: DEFAULTS.chatFontScale,
        chatDensity = enumOrDefault(this[KEY_CHAT_DENSITY], ChatDensity.COMFORTABLE),
        extraPathDirs = this[KEY_EXTRA_PATH_DIRS] ?: DEFAULTS.extraPathDirs
    )

    private companion object {
        const val API_KEY_PREFIX = "API_KEY_"
    }
}
