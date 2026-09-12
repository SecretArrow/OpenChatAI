package com.openchatai.app.ai

import com.openchai.core.ai.AiProvider
import com.openchai.core.model.ProviderId

/**
 * Registry semua provider AI. Instance dibuat oleh AppContainer sehingga
 * implementasi provider dapat diganti tanpa menyentuh UI.
 */
class ProviderRegistry(
    val ollama: AiProvider,
    val openai: AiProvider,
    val anthropic: AiProvider,
    val google: AiProvider,
    val poolside: AiProvider,
    val custom: AiProvider,
    val local: AiProvider
) {
    private val map: Map<ProviderId, AiProvider> = mapOf(
        ProviderId.OLLAMA to ollama,
        ProviderId.OPENAI to openai,
        ProviderId.ANTHROPIC to anthropic,
        ProviderId.GOOGLE to google,
        ProviderId.POOLSIDE to poolside,
        ProviderId.CUSTOM to custom,
        ProviderId.LOCAL to local
    )

    fun get(id: ProviderId): AiProvider? = map[id]

    fun all(): List<AiProvider> = map.values.toList()
}
