package com.openchai.core.skills

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Pemuat & pengelola skill (katalog plugin ringan).
 *
 * Sumber data:
 * - Builtin : assets "skills/builtin.json" (List<Skill>, builtin=true). Dimuat
 *             SEKALI lalu di-cache selama proses hidup; tidak bisa diubah.
 * - User    : <filesDir>/skills/user_skills.json (List<Skill>, builtin=false).
 *             CRUD penuh: [add], [update], [remove], [save].
 * - Override: <filesDir>/skills/prefs.json (Map<String,Boolean>, skillId → enabled).
 *             Khusus override status enabled untuk skill bawaan, karena file
 *             assets tidak boleh ditulis. Untuk skill milik pengguna, [setEnabled]
 *             langsung memperbarui field di user_skills.json.
 *
 * Thread-safety: cache dibaca lewat double-checked locking ([cacheLock]); semua
 * operasi tulis berjalan di [Dispatchers.IO] dan di-serialisasi lewat [writeMutex]
 * dengan penulisan file atomik (tmp → rename), mengikuti pola repo.
 */
class SkillLoader private constructor(context: Context) {

    private val appContext: Context = context.applicationContext
    private val skillsDir: File = File(appContext.filesDir, DIR_NAME).apply { mkdirs() }
    private val userFile: File = File(skillsDir, USER_FILE_NAME)
    private val prefsFile: File = File(skillsDir, PREFS_FILE_NAME)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val skillListSerializer = ListSerializer(Skill.serializer())
    private val prefsSerializer = MapSerializer(String.serializer(), Boolean.serializer())

    private val writeMutex = Mutex()
    private val cacheLock = Any()

    /** Skill bawaan: dimuat sekali dari assets, di-cache permanen. */
    private val builtinSkills: List<Skill> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        readBuiltinAssets()
    }

    @Volatile private var userCache: List<Skill>? = null
    @Volatile private var prefsCache: Map<String, Boolean>? = null

    // ------------------------------------------------------------------
    // API publik
    // ------------------------------------------------------------------

    /** Semua skill (builtin + user) dengan override enabled diterapkan. */
    fun all(): List<Skill> {
        val prefs = prefsNow()
        val builtins = builtinSkills.map { skill ->
            prefs[skill.id]?.let { override -> skill.copy(enabled = override) } ?: skill
        }
        return builtins + userSkillsNow()
    }

    /** Hanya skill yang aktif (enabled == true). */
    fun active(): List<Skill> = all().filter { it.enabled }

    /**
     * Cari skill aktif yang relevan untuk [task]. Skor match:
     * - trigger substring case-insensitive  → +5 (sinyal terkuat);
     * - kata kunci nama (batas kata)        → +2 per kata;
     * - kata kunci deskripsi (batas kata)   → +1 per kata, maks 4 kata.
     * Hanya skill ber-skor > 0 yang dikembalikan, urut skor menurun, maks [max].
     */
    suspend fun matchForTask(task: String, max: Int = 3): List<Skill> =
        withContext(Dispatchers.Default) {
            val taskLower = task.lowercase().trim()
            if (taskLower.isEmpty() || max <= 0) return@withContext emptyList()
            active()
                .asSequence()
                .map { skill -> skill to scoreSkill(skill, taskLower) }
                .filter { it.second > 0 }
                .sortedWith(compareByDescending<Pair<Skill, Int>> { it.second }.thenBy { it.first.name })
                .take(max)
                .map { it.first }
                .toList()
        }

    /** Tambah skill milik pengguna (builtin dipaksa false). */
    suspend fun add(skill: Skill): Unit = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val entry = skill.copy(
                id = skill.id.ifBlank { UUID.randomUUID().toString() },
                builtin = false
            )
            persistUserLocked(userSkillsNow() + entry)
        }
    }

    /** Perbarui skill milik pengguna berdasarkan id; false bila tidak ditemukan. */
    suspend fun update(skill: Skill): Boolean = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val users = userSkillsNow()
            val idx = users.indexOfFirst { it.id == skill.id }
            if (idx < 0) return@withContext false
            val next = users.toMutableList()
            next[idx] = skill.copy(builtin = false)
            persistUserLocked(next)
            true
        }
    }

    /** Hapus skill milik pengguna berdasarkan id; false bila tidak ditemukan. */
    suspend fun remove(id: String): Boolean = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val users = userSkillsNow()
            val next = users.filterNot { it.id == id }
            if (next.size == users.size) return@withContext false
            persistUserLocked(next)
            true
        }
    }

    /**
     * Atur status enabled. Skill bawaan → override disimpan ke prefs.json;
     * skill milik pengguna → field enabled diperbarui di user_skills.json.
     * Id yang belum dikenal ditulis ke prefs (aman; berlaku bila muncul nanti).
     */
    suspend fun setEnabled(id: String, enabled: Boolean): Unit = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val users = userSkillsNow()
            if (users.any { it.id == id }) {
                persistUserLocked(users.map { if (it.id == id) it.copy(enabled = enabled) else it })
            } else {
                persistPrefsLocked(prefsNow() + (id to enabled))
            }
        }
    }

    /** Simpan eksplisit seluruh state yang bisa ditulis (user + prefs). */
    suspend fun save(): Unit = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            persistUserLocked(userSkillsNow())
            persistPrefsLocked(prefsNow())
        }
    }

    // ------------------------------------------------------------------
    // Pencocokan tugas
    // ------------------------------------------------------------------

    private fun scoreSkill(skill: Skill, taskLower: String): Int {
        var score = 0
        // Trigger: substring case-insensitive — pemicu paling spesifik.
        for (trigger in skill.triggers) {
            val t = trigger.trim().lowercase()
            if (t.isNotEmpty() && taskLower.contains(t)) score += WEIGHT_TRIGGER
        }
        // Nama: kata kunci dengan batas kata agar "spa" tidak cocok dengan "spaghetti".
        for (word in keywordsOf(skill.name, MIN_NAME_WORD_LENGTH)) {
            if (wordRegex(word).containsMatchIn(taskLower)) score += WEIGHT_NAME
        }
        // Deskripsi: kata kunci, dibatasi agar tidak menenggelamkan trigger/nama.
        var descHits = 0
        for (word in keywordsOf(skill.description, MIN_DESC_WORD_LENGTH)) {
            if (wordRegex(word).containsMatchIn(taskLower)) {
                descHits++
                if (descHits >= MAX_DESC_HITS) break
            }
        }
        return score + descHits * WEIGHT_DESC
    }

    /** Token sederhana: huruf/angka saja, panjang minimal, unik. */
    private fun keywordsOf(text: String, minLength: Int): List<String> =
        text.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= minLength }
            .distinct()

    private fun wordRegex(word: String): Regex = Regex("\\b${Regex.escape(word)}\\b")

    // ------------------------------------------------------------------
    // Pembacaan sumber data
    // ------------------------------------------------------------------

    private fun readBuiltinAssets(): List<Skill> {
        val text = runCatching {
            appContext.assets.open(BUILTIN_ASSET_PATH).bufferedReader().use { it.readText() }
        }.getOrNull() ?: return emptyList()
        val decoded = runCatching { json.decodeFromString(skillListSerializer, text) }
            .getOrDefault(emptyList())
        // Paksa builtin=true agar sumber selalu konsisten meski JSON diubah tangan.
        return decoded.map { it.copy(builtin = true) }
    }

    private fun userSkillsNow(): List<Skill> =
        userCache ?: synchronized(cacheLock) {
            userCache ?: readUserFile().also { userCache = it }
        }

    private fun prefsNow(): Map<String, Boolean> =
        prefsCache ?: synchronized(cacheLock) {
            prefsCache ?: readPrefsFile().also { prefsCache = it }
        }

    /** File user korup dianggap kosong (tidak crash), konsisten dengan pola repo. */
    private fun readUserFile(): List<Skill> {
        if (!userFile.exists()) return emptyList()
        val decoded = runCatching {
            json.decodeFromString(skillListSerializer, userFile.readText())
        }.getOrDefault(emptyList())
        return decoded.map { it.copy(builtin = false) }
    }

    private fun readPrefsFile(): Map<String, Boolean> {
        if (!prefsFile.exists()) return emptyMap()
        return runCatching {
            json.decodeFromString(prefsSerializer, prefsFile.readText())
        }.getOrDefault(emptyMap())
    }

    // ------------------------------------------------------------------
    // Penulisan (harus dipanggil di dalam writeMutex)
    // ------------------------------------------------------------------

    private fun persistUserLocked(skills: List<Skill>) {
        userCache = skills
        writeAtomic(userFile, json.encodeToString(skillListSerializer, skills))
    }

    private fun persistPrefsLocked(prefs: Map<String, Boolean>) {
        prefsCache = prefs
        writeAtomic(prefsFile, json.encodeToString(prefsSerializer, prefs))
    }

    /** Tulis atomik: file tmp lalu rename; rename gagal → tulis langsung (pola repo). */
    private fun writeAtomic(file: File, content: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    companion object {
        private const val DIR_NAME = "skills"
        private const val USER_FILE_NAME = "user_skills.json"
        private const val PREFS_FILE_NAME = "prefs.json"
        private const val BUILTIN_ASSET_PATH = "skills/builtin.json"

        // Bobot skor matchForTask.
        private const val WEIGHT_TRIGGER = 5
        private const val WEIGHT_NAME = 2
        private const val WEIGHT_DESC = 1
        private const val MAX_DESC_HITS = 4
        private const val MIN_NAME_WORD_LENGTH = 3
        private const val MIN_DESC_WORD_LENGTH = 4

        @Volatile
        private var INSTANCE: SkillLoader? = null

        /** Singleton satu instance per proses; selalu memakai applicationContext. */
        fun getInstance(context: Context): SkillLoader =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: SkillLoader(context.applicationContext).also { INSTANCE = it }
            }
    }
}

/**
 * Pintu masuk singleton [SkillLoader] untuk UI maupun agent:
 * `SkillLoaderProvider.get(context)` aman dipanggil berulang kali.
 */
object SkillLoaderProvider {
    fun get(context: Context): SkillLoader = SkillLoader.getInstance(context)
}
