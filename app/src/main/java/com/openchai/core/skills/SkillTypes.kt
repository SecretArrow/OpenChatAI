package com.openchai.core.skills

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Satu "skill": pak instruksi yang dapat diaktifkan/nonaktifkan dan otomatis
 * disuntikkan ke system prompt agent ketika tugas pengguna cocok.
 *
 * Skill adalah bentuk plugin paling ringan: tanpa kode, hanya teks instruksi.
 * Plugin berat (mis. MCP server yang menyediakan tools) akan memakai kontrak
 * lain; katalog skill ini menjadi fondasi pertama sistem plugin.
 *
 * @param id           Identitas unik; default UUID acak. Skill bawaan memakai id
 *                     stabil berprefiks "builtin-" agar preferensi enabled tetap
 *                     tersimpan meski aplikasi di-restart.
 * @param name         Nama yang tampil di UI dan di blok prompt agent.
 * @param description  Ringkasan singkat (bilingual ID/EN) untuk UI dan pencocokan tugas.
 * @param instructions Isi instruksi yang disuntikkan ke prompt (dipotong 1200 char).
 * @param enabled      Skill aktif atau tidak; untuk skill bawaan dapat dioverride
 *                     lewat prefs.json (lihat [SkillLoader.setEnabled]).
 * @param builtin      true = dari assets, tidak bisa diedit/dihapus pengguna.
 * @param triggers     Kata/frasa pemicu untuk pencocokan tugas (case-insensitive).
 */
@Serializable
data class Skill(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String,
    val instructions: String,
    val enabled: Boolean = true,
    val builtin: Boolean = false,
    val triggers: List<String> = emptyList()
)
