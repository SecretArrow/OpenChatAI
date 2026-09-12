# Open Chat AI

> **"Saya cukup chat dengan AI, lalu AI mengerjakan project saya."**

**Open Chat AI** adalah aplikasi Android **AI Coding Agent** dengan pengalaman utama seperti aplikasi chat AI modern — bukan IDE, bukan CLI wrapper. Chat adalah pusat pengalaman; agent engine bekerja di background untuk membaca, membuat, mengubah, dan mengelola project Anda.

![CI](https://github.com/SecretArrow/OpenChatAI/actions/workflows/android-ci.yml/badge.svg)

## Fitur

### Chat (fokus utama)
- Streaming AI response, Markdown, code blocks + syntax layout, copy response/code
- Regenerate, Retry, Stop generation, Edit message, lanjut percakapan
- Riwayat percakapan lokal (Today/Yesterday), percakapan per-project
- **Layar History**: pencarian, pengelompokan Today / Yesterday / Previous 7 days / Older, buka & hapus percakapan
- **Slash commands**: `/fix`, `/test`, `/commit`, `/explain`, `/review` — satu ketukan mengisi prompt lengkap
- **Export chat**: bagikan percakapan sebagai Markdown lewat share sheet Android
- Auto-scroll pintar, typing indicator, status jaringan & model

### AI Provider
- **On-device (tanpa aplikasi lain!)**: llama.cpp embedded via JNI — model GGUF (Qwen, Llama 3.2, Gemma, Phi) diunduh langsung dari dalam aplikasi (layar **Models**) dan inferensi berjalan 100% di perangkat, streaming, auto context-reset, cek RAM sebelum load
- **Local**: Ollama (`http://127.0.0.1:11434`) & **Remote Ollama** (mis. `http://192.168.1.100:11434`)
- **Cloud**: OpenAI, Anthropic (Claude), Google (Gemini), Custom OpenAI-compatible
- Deteksi model otomatis, connection status, model selector dari chat

### MCP (Model Context Protocol)
- Client MCP lengkap: transport **streamable HTTP** + **SSE legacy** + **stdio**
- Tambah server MCP apa pun dari **Settings → MCP servers** (URL / command), test koneksi, status health + jumlah tools
- Tools MCP otomatis tersedia ke agent sebagai `mcp_<server>_<tool>` dan tampil sebagai aktivitas manusiawi di chat

### Skills
- 6 skill bawaan (React SPA, FastAPI, conventional commits, code review, Android Gradle fixer, explainer)
- Skill cocok dipilih otomatis berdasarkan isi tugas lalu disuntikkan ke system prompt agent
- Buat skill sendiri dari **Settings → Skills** (nama, deskripsi, trigger, instruksi)
- Plugin = kombinasi server MCP (tools) + skill (instruksi) + provider kustom (AI)

### Agent Engine
- Arsitektur modular: `GUI → AgentOrchestrator → Engine`
- **OpenCode engine** (opsional): hubungkan ke server OpenCode (`opencode serve`) via HTTP; bila tidak tersedia, otomatis fallback
- **Built-in agent**: tool loop ter-sandbox — `list_files`, `read_file`, `write_file`, `delete_file`, `search`, `run_command` — dibatasi workspace, maksimum iterasi dapat diatur
- Aktivitas agent ditampilkan manusiawi (`✓ Analyzing project`, `⏳ Installing dependencies`), bukan raw CLI output

### Terminal (hidden by default)
- Embedded Linux terminal (sh/mksh) — toggle `Terminal ▼` di bawah chat
- Multi-session, command history, copy/paste, selection, clear, ANSI strip, monospace + font size
- **Quick commands**: chip `ls`, `git status`, `node -v`, `python3 --version`, dll.
- Environment `PATH` benar + tambahan PATH kustom (mis. runtime Termux untuk `node`/`python3` bila tersedia di perangkat)
- **Background Process Manager**: proses `npm run dev`, `node server.js`, dll. tetap hidup saat kembali ke Chat — PID, port terdeteksi, durasi, output berbatas, stop/restart, auto-restart opsional, foreground service
- **stdin untuk background process**: jalankan `python3 -i` / `node` sebagai background process lalu kirim perintah lewat input REPL (Proses → output → stdin)

### Keamanan
- Workspace sandbox: operasi file agent dibatasi direktori project
- API key disimpan via **EncryptedSharedPreferences** (Android Keystore)
- Eksekusi command hanya lewat ProcessManager + blocklist berbahaya (`rm -rf /`, fork bomb, dll.)

## Build

CI/CD penuh via **GitHub Actions** — tidak perlu build lokal:

| Workflow | Trigger | Hasil |
|---|---|---|
| `Android CI` | push/PR ke `main` | APK debug + release (artifact `OpenChatAI-APKs`) |
| `Release` | tag `v*` | GitHub Release berisi `OpenChatAI-release.apk` |
| `Auto Fix` | CI gagal | Issue berisi ringkasan error + (opsional) AI auto-fix & push patch |

Untuk auto-fix AI, tambahkan repo secret `AI_API_KEY` (+ opsional vars `AI_BASE_URL`, `AI_MODEL`, format OpenAI-compatible).

## Arsitektur

```text
Open Chat AI GUI (Compose, chat-first)
        │
        ▼
  AgentOrchestrator ──► AiProvider (Ollama / OpenAI / Anthropic / Google / Custom)
        │
        ├──► OpenCodeRuntime (client HTTP server OpenCode, opsional)
        ├──► BuiltInAgent (tool loop sandboxed)
        │
        ▼
  ProcessManager / TerminalHost ──► Files · Shell · Git (workspace sandbox)
```

Struktur modul (single-module, paket modular):

```text
com.openchai.app/          MainActivity, AppContainer (DI), AgentOrchestrator
com.openchai.core.model/   ChatMessage, Conversation, Project, ModelInfo, ...
com.openchai.core.ai/      AiProvider + StreamEvent
com.openchai.core.agent/   AgentEngine + AgentEvent + CommandRunner
com.openchai.core.*/       settings, runtime, terminal, data (kontrak)
com.openchai.app.ai/       Ollama/OpenAI/Anthropic/Google/Custom providers
com.openchai.agent/        BuiltInAgent + tools + OpenCode client/runtime
com.openchai.runtime/      AndroidProcessManager, ShellEnvironment
com.openchai.terminal/     TerminalManager, Ansi
com.openchai.app.data/     JSON conversation store, DataStore settings, secure store
com.openchai.app.ui/       chat, projects, settings, terminal, theme, navigation
```

## Catatan runtime

- Terminal menjalankan shell Android (`/system/bin/sh`, fallback bash bila ada). Utilitas Unix (`ls`, `cp`, `grep`, `find`, `tar`, ...) tersedia via toybox.
- `node`, `python3`, `git`, dll. **tidak dibundel** dalam APK demi ukuran & lisensi. Aplikasi mendeteksi tool di `PATH` dan mendukung **PATH tambahan** di Settings → Runtime (mis. `/data/data/com.termux/files/usr/bin` untuk integrasi Termux), atau gunakan **Remote Ollama** + cloud provider bila perangkat terbatas.
- APK release di CI ditandatangani debug key agar mudah dipasang; gunakan keystore sendiri untuk distribusi publik.

## AI lokal on-device (llama.cpp)

Open Chat AI men-embed [llama.cpp](https://github.com/ggml-org/llama.cpp) (pin tag `b10919`, submodule `app/src/main/cpp/llama.cpp`) via JNI/NDK — ABI `arm64-v8a` + `x86_64`. Tidak perlu aplikasi lain (Ollama pun tidak): buka **Models** → unduh GGUF → **Use** → pilih provider **On-device** di model selector.

- Rekomendasi: RAM <3GB → model 0.5B; 3–4GB → 1B; 4–8GB → 1.5–2B; >8GB → 3B
- KV cache direset otomatis tiap generasi; BOS (`add_special`) hanya pada prompt pertama
- Model tersimpan di storage privat aplikasi (`filesDir/models`)

## Attribution & Lisensi

Proyek ini **MIT License** — lihat [LICENSE](LICENSE).

- [OpenCode](https://github.com/sst/opencode) — konsep agent engine & integrasi server mode (opsional). Open Chat AI adalah klien GUI independen, bukan wrapper CLI OpenCode.
- [Ollama](https://github.com/ollama/ollama) — backend model lokal/remote via HTTP API.
- Jetpack Compose, Material 3, OkHttp, Markwon, DataStore, kotlinx.serialization — lisensi masing-masing (Apache-2.0/MIT).
