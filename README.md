# Open Chat AI

> **"Saya cukup chat dengan AI, lalu AI mengerjakan project saya."**

**Open Chat AI** adalah aplikasi Android **AI Coding Agent** dengan pengalaman utama seperti aplikasi chat AI modern — bukan IDE, bukan CLI wrapper. Chat adalah pusat pengalaman; agent engine bekerja di background untuk membaca, membuat, mengubah, dan mengelola project Anda. Sejak v1.8, aplikasi juga membawa **Lingkungan Pengembangan Linux tertanam** (proot + Ubuntu) sehingga `apt`, `node`, `npm`, `python3`, dan `git` berjalan nyata di dalam aplikasi — tanpa root, tanpa VM.

![CI](https://github.com/SecretArrow/OpenChatAI/actions/workflows/android-ci.yml/badge.svg)

**Versi terbaru:** v1.9.0 (versionCode 11) · paket `com.openchatai.app` · minSdk 26 · ABI `arm64-v8a` + `x86_64`

## Fitur

### Chat (fokus utama)
- Streaming AI response, Markdown, code blocks + syntax layout, copy response/code
- **Model selector di header chat**: pilih provider + model tanpa membuka Settings, lengkap status koneksi per provider + **tombol Test** untuk menguji model satu klik (hasil/error detail ditampilkan)
- **Agent actions menu (satu tombol ⚡/tools)**: Fix, Test, Build, Run, Debug, Explain, Review, Commit — chat tetap bersih
- **Auto-scroll pintar**: mengikuti output terbaru hanya saat user di bagian bawah; scroll ke atas menghentikan paksaan, muncul chip "↓ New messages" untuk kembali
- **Agent events collapsible**: langkah agent ringkas satu baris; "View execution details" membuka stdout/stderr penuh
- Regenerate, Retry, Stop generation, Edit message, lanjut percakapan
- Riwayat percakapan lokal, percakapan per-project, layar History dengan pencarian
- **Export chat**: bagikan percakapan sebagai Markdown lewat share sheet Android
- **Workspace selalu tampil** di header; workspace aktif & model terpilih dipulihkan otomatis saat app dibuka ulang (tanpa restart kedua)

### AI Provider
- **On-device (tanpa aplikasi lain!)**: llama.cpp embedded via JNI — 10 model GGUF di katalog (Qwen3.5 0.8B, LFM2 1.2B, Qwen3 0.6B, Llama 3.2 1B, Qwen2.5 0.5B/1.5B Coder, Gemma 2, Phi-3.5 Mini) diunduh langsung dari dalam aplikasi (layar **Models**), inferensi 100% di perangkat, streaming, pause/resume unduhan, import/export file GGUF via SAF → lihat [docs/LOCAL_AI.md](docs/LOCAL_AI.md)
- **Ollama** (`http://127.0.0.1:11434`) & **Remote Ollama** — dengan **auto-deteksi saat app dibuka**: status Connected / Not running / Connection error selalu terlihat, tombol **Start Ollama** (menjalankan `ollama serve` di sandbox Linux bila terpasang), Retry, dan daftar model **yang benar-benar dikembalikan Ollama** (mis. `qwen3:0.6b` langsung muncul di selector) → lihat [docs/PROVIDERS.md](docs/PROVIDERS.md)
- **Cloud**: OpenAI, Anthropic (Claude), Google (Gemini), **Poolside**, Custom OpenAI-compatible
- Daftar model per provider ditarik dinamis dari base URL masing-masing; connection status + model selector dari chat
- Semua perubahan konfigurasi (API key, base URL, dll.) memberi **Toast/Dialog konfirmasi** eksplisit

### MCP (Model Context Protocol)
- Client MCP lengkap: transport **streamable HTTP** + **SSE legacy** + **stdio**
- Tambah server MCP apa pun dari **Settings → MCP servers** (URL / command), test koneksi, status health + jumlah tools
- Tools MCP otomatis tersedia ke agent sebagai `mcp_<server>_<tool>` dan tampil sebagai aktivitas manusiawi di chat

### Skills & Plugins
- 6 skill bawaan (React SPA, FastAPI, conventional commits, code review, Android Gradle fixer, explainer)
- Skill cocok dipilih otomatis berdasarkan isi tugas lalu disuntikkan ke system prompt agent
- Buat skill sendiri dari **Settings → Skills** (nama, deskripsi, trigger, instruksi)
- Plugin = kombinasi server MCP (tools) + skill (instruksi) + provider kustom (AI)

### Agent Engine (CLI-grade)
- Arsitektur modular: `GUI → AgentOrchestrator → Engine`
- **Mode izin**: Ask, Plan, Auto Read-Edit, YOLO — YOLO benar-benar auto-approve semua tool (create/modify/delete file, npm/node/test/build) dengan tetap dibatasi workspace + blocklist command berbahaya
- **Workspace aktif = satu sumber kebenaran**: agent mengetahui path workspace, file tools & `run_command` berjalan di dalamnya; **command sandbox Linux di-bind langsung ke folder workspace Android** (`-b <workspace>:/home/user/workspace`), sehingga `npm create vite`, `npm install`, `npm run build` benar-benar menyentuh file project
- **Workspace wajib ter-setup** (SAF atau app-dir) + dukungan `AGENTS.md` per project
- **Built-in agent**: tool loop ter-sandbox — `list_files`, `read_file`, `write_file`, `delete_file`, `search`, `run_command` — progress ditampilkan sebagai kartu aktivitas collapsible di chat
- **OpenCode engine** (opsional): hubungkan ke server OpenCode (`opencode serve`) via HTTP; bila tidak tersedia, otomatis fallback
- `run_command` dieksekusi di **sandbox Linux tertanam** bila siap (lihat di bawah), fallback ke shell Android

### Terminal (Activity layar penuh, terpisah)
- Toggle ikon **$** di header chat membuka **TerminalActivity** terpisah — chat tidak berkurang ruangnya; kembali ke chat, state tetap
- Terminal otomatis memakai **cwd = workspace aktif** (bind ke sandbox Linux bila READY, path asli bila legacy shell)
- Multi-session, command history, copy/paste, selection, clear, ANSI, font size, background process + stdin REPL, process manager
- **Quick commands**: chip `ls`, `git status`, `node -v`, `python3 --version`, dll.
- Detail lengkap: [docs/LINUX_ENV.md](docs/LINUX_ENV.md)

### Lingkungan Linux Embedded (proot + Ubuntu)
- **Saat Linux siap**: shell `bash -li` nyata di dalam Ubuntu (proot) — `bash`, `ls`, `cd`, `mkdir`, `rm`, `grep`, `sed`, `awk`, `tar`, `ps`, `kill`, dll. berjalan normal
- **apt sungguhan**: `apt update`, `apt install nodejs npm`, `apt search/list` — paket diunduh dari mirror Ubuntu
- **Node.js + npm + npx** dan **Python 3 + pip3** nyata (`npm init/install/run start/dev`, `node server.js`, `python3 -m venv`, dll.)
- Dev tools satu perintah: `apt install git curl wget openssh-client make gcc g++ pkg-config`
- **Background process**: `node server.js &` tetap hidup saat terminal ditutup; Process Manager (PID, port, durasi, stop/restart, auto-restart, stdin REPL); `ps`/`kill`/`killall` dari dalam sandbox
- **Cron**: `crontab` kompatibel sintaks cron + scheduler internal in-app sebagai fallback (log `var/log/openchai-cron.log` di dalam rootfs)
- **Workspace persisten**: folder workspace aktif di-bind ke sandbox (`/home/user/workspace`) dan bertahan antar sesi & restart aplikasi
- Setup wizard dengan cek kelayakan (ABI, RAM ≥ ~2.5 GB, storage) + progress unduhan rootfs (resumable, SHA-256 terverifikasi)

### Runtime Packs (engine AI modular)
- Layar **Settings → Runtime & Modul**: pasang/hapus runtime llama.cpp per-ABI dari manifest (unduhan resumable + pause/resume + SHA-256)
- **Auto-install** opsional untuk modul yang dibutuhkan; modul/library tambahan bisa dipasang manual
- Manifest: `runtime/manifest.json` di branch `main` — dipublikasikan otomatis oleh workflow Release

### Keamanan
- Sandbox Linux userspace (proot): proses guest tidak menyentuh partisi sistem Android, data aplikasi lain, atau Keystore
- Workspace sandbox: operasi file agent dibatasi direktori project; command agent melewati lapisan eksekusi aman + blocklist berbahaya (`rm -rf /`, fork bomb, dll.)
- API key disimpan via **EncryptedSharedPreferences** (Android Keystore)

## Dokumentasi

| Dokumen | Isi |
|---|---|
| [docs/INSTALL.md](docs/INSTALL.md) | Instalasi, persyaratan perangkat, catatan upgrade & aplikasi terpisah |
| [docs/LOCAL_AI.md](docs/LOCAL_AI.md) | AI on-device: katalog model, pause/resume, import/export, runtime packs |
| [docs/LINUX_ENV.md](docs/LINUX_ENV.md) | Lingkungan Linux embedded: setup, apt, Node/Python, background process, cron, sandbox |
| [docs/PROVIDERS.md](docs/PROVIDERS.md) | Semua provider AI, model list dinamis, penyimpanan API key |
| [docs/AGENT_MCP_SKILLS.md](docs/AGENT_MCP_SKILLS.md) | Agent engine, mode izin, workspace & AGENTS.md, MCP, skills & plugins |
| [docs/BUILD_CI.md](docs/BUILD_CI.md) | CI/CD GitHub Actions, auto-fix, proses release & signing |

## Build

CI/CD penuh via **GitHub Actions** — tidak perlu build lokal:

| Workflow | Trigger | Hasil |
|---|---|---|
| `Android CI` | push/PR ke `main` | APK debug + release (artifact `OpenChatAI-APKs`) |
| `Release` | tag `v*` | GitHub Release: `OpenChatAI-release.apk` + runtime packs per-ABI + update manifest |
| `Auto Fix` | CI gagal | Issue berisi ringkasan error + (opsional) AI auto-fix & push patch |

Untuk auto-fix AI, tambahkan repo secret `AI_API_KEY` (+ opsional vars `AI_BASE_URL`, `AI_MODEL`, format OpenAI-compatible). Detail: [docs/BUILD_CI.md](docs/BUILD_CI.md).

## Arsitektur

```text
Open Chat AI GUI (Compose, chat-first)
        │
        ▼
  AgentOrchestrator ──► AiProvider (On-device / Ollama / OpenAI / Anthropic /
        │                Google / Poolside / Custom)
        │
        ├──► OpenCodeRuntime (client HTTP server OpenCode, opsional)
        ├──► BuiltInAgent (tool loop sandboxed, mode izin)
        │        └── CommandRunner ──► LinuxEnvManager (proot, bila siap)
        │                                    └── fallback: shell Android
        ▼
  TerminalHost / ProcessSupervisor ──► proot + Ubuntu rootfs · Files · Shell · Git
```

Struktur modul (single-module, paket modular):

```text
com.openchatai.app/        MainActivity, AppContainer (DI), AgentOrchestrator
com.openchatai.app.ui/     chat, models, runtime, linux, terminal, settings, history, ...
com.openchatai.app.ai/     Ollama/OpenAI/Anthropic/Google/Poolside/Custom providers
com.openchatai.app.core/   model, ai, agent, settings, data, runtime, terminal
com.openchai.core.llm/     LlamaBridge (JNI), LlamaEngine, ModelManager, RuntimeManager
com.openchai.core.linux/   LinuxEnvManager (proot+rootfs), LinuxShell,
                           LinuxProcessSupervisor, LinuxCron
com.openchai.core.mcp/     client MCP (HTTP streamable / SSE / stdio)
com.openchai.core.skills/  skill engine + skill bawaan
com.openchai.runtime/      AndroidProcessManager, ShellEnvironment
com.openchai.terminal/     TerminalManager, Ansi
```

## AI lokal on-device (llama.cpp)

Open Chat AI men-embed [llama.cpp](https://github.com/ggml-org/llama.cpp) (pin tag `b10919`, submodule `app/src/main/cpp/llama.cpp`) via JNI/NDK — ABI `arm64-v8a` + `x86_64`. Tidak perlu aplikasi lain (Ollama pun tidak): buka **Models** → unduh GGUF → **Use** → pilih provider **On-device** di model selector.

- Katalog v1.8.1 (10 model, urut ukuran): mulai dari Qwen2.5 0.5B Q4_K_M (± 400 MB) hingga Phi-3.5 Mini Q4_K_M (± 2.4 GB); ukuran file katalog eksak (terverifikasi dari Content-Length) sehingga progress bar akurat
- **Rekomendasi RAM <4 GB**: Qwen3.5 0.8B Q4_K_M (terbaru & terpintar di kelasnya), LFM2 1.2B (khusus edge/mobile), Qwen3 0.6B, Llama 3.2 1B, Qwen2.5 0.5B
- Semua model katalog telah diuji inferensi nyata dengan engine b10919 (5/5 pass) — arsitektur `qwen35`, `lfm2`, `qwen3`, `qwen2`, dan `llama` didukung
- KV cache direset otomatis tiap generasi; BOS (`add_special`) hanya pada prompt pertama
- Model tersimpan di storage privat aplikasi (`filesDir/models`); engine bisa diganti/dihapus lewat **Runtime & Modul**

## Catatan runtime

- Terminal berjalan di **Ubuntu userspace (proot)** setelah setup Linux; sebelum itu, fallback ke shell Android (`/system/bin/sh`, utilitas toybox).
- `node`, `npm`, `python3`, `pip3`, `git`, dll. dipasang **di dalam rootfs** via `apt` — bukan dibundel APK demi ukuran & lisensi. Rootfs diunduh dari `cdimage.ubuntu.com` (ubuntu-base 22.04/20.04, ± 28 MB, SHA-256 terverifikasi) saat setup pertama.
- APK release di CI ditandatangani debug key agar mudah dipasang; gunakan keystore sendiri untuk distribusi publik.
- `targetSdk` sengaja 28 (pola Termux/UserLAnd) agar `execve()` binary di app-data sah pada Android 10+.

## Riwayat versi singkat

| Versi | Sorotan |
|---|---|
| v1.4.0 | Import/export model GGUF (SAF), model list dinamis per base URL |
| v1.5.0 | Pause/resume unduhan model (HTTP Range), provider Poolside, perbaikan SecureStore |
| v1.6.0 | Agent CLI-grade: mode izin (Ask/Plan/Auto Read-Edit/YOLO), workspace SAF wajib, AGENTS.md |
| v1.7.0 | Runtime packs: pasang/hapus runtime & modul, manifest, auto-install |
| v1.8.0 | **Lingkungan Linux embedded** (proot + Ubuntu, apt/Node/Python), rename paket `com.openchatai.app`, Toast konfirmasi konfigurasi |
| v1.8.1 | Katalog model low-RAM (Qwen3.5 0.8B, LFM2 1.2B, Qwen3 0.6B, Qwen2.5 0.5B Q4_K_M), sizeBytes eksak, semua model teruji inferensi |
| v1.9.0 | **Audit & perbaikan root-cause**: chat langsung muncul setelah create workspace (race lifecycle diperbaiki), command agent & terminal ter-bind ke workspace aktif, Ollama auto-detect + Start + Test model, model selector di header, agent actions satu tombol, auto-scroll + "New messages", **Terminal sebagai Activity terpisah**, agent events collapsible |

## Attribution & Lisensi

Proyek ini **MIT License** — lihat [LICENSE](LICENSE).

- [llama.cpp](https://github.com/ggml-org/llama.cpp) — engine inferensi GGUF on-device (MIT).
- [Ubuntu base images](https://cdimage.ubuntu.com/ubuntu-base/) — rootfs untuk lingkungan Linux embedded.
- [proot](https://github.com/proot-me/proot) — userspace sandbox Linux tanpa root.
- [OpenCode](https://github.com/sst/opencode) — konsep agent engine & integrasi server mode (opsional). Open Chat AI adalah klien GUI independen, bukan wrapper CLI OpenCode.
- [Ollama](https://github.com/ollama/ollama) — backend model lokal/remote via HTTP API.
- Jetpack Compose, Material 3, OkHttp, Markwon, DataStore, kotlinx.serialization, commons-compress — lisensi masing-masing (Apache-2.0/MIT).
