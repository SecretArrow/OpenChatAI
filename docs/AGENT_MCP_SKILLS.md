# Agent Engine, MCP, Skills & Plugins

## Agent Engine

Arsitektur modular: `GUI → AgentOrchestrator → Engine`. Dua engine tersedia:

1. **Built-in agent** (bawaan): tool loop ter-sandbox.
2. **OpenCode engine** (opsional): klien HTTP untuk server OpenCode (`opencode serve`); bila server tidak tersedia, otomatis fallback ke built-in.

Aktivitas agent tampil manusiawi di chat (`✓ Analyzing project`, `⏳ Installing dependencies`) — bukan raw CLI output.

### Mode izin (CLI-grade, v1.6.0)

| Mode | Baca file | Edit file | Jalankan command |
|---|---|---|---|
| **Ask** | Izin dulu | Izin dulu | Izin dulu |
| **Plan** | Ya | Tidak | Tidak |
| **Auto Read-Edit** | Ya | Ya | Izin dulu |
| **YOLO** | Ya | Ya | Ya (tetap melalui blocklist) |

### Workspace

- Workspace **wajib di-setup** sebelum agent bekerja — dipilih lewat SAF (storage perangkat atau penyedia dokumen).
- Operasi file (`list_files`, `read_file`, `write_file`, `delete_file`, `search`) dibatasi root workspace.
- Dukungan **`AGENTS.md`** per project: instruksi konvensi project dibaca otomatis dan disuntikkan ke konteks agent.
- Maksimum iterasi tool loop dapat diatur di Settings.

### run_command

- Saat Linux embedded READY: dieksekusi **di dalam sandbox Ubuntu** (proot) dengan **workspace Android di-bind ke `/home/user/workspace`** — cwd = folder project asli, sehingga scaffold/build berdampak pada file nyata (lihat [LINUX_ENV.md](LINUX_ENV.md)).
- Workspace SAF: didukung bila foldernya di storage utama (path fisik dapat di-bind); selain itu agent diarahkan memakai file tools dengan pesan yang jelas.
- Selain itu: fallback ke shell Android dengan blocklist perintah berbahaya (`rm -rf /`, fork bomb, dll.).
- Output command tersimpan pada detail langkah — tampil via **View execution details** (collapsible), tidak memenuhi chat.

## MCP (Model Context Protocol)

- Transport: **streamable HTTP**, **SSE legacy**, dan **stdio**.
- Tambah server dari **Settings → MCP servers**: isi URL (HTTP/SSE) atau command (stdio), lalu test koneksi — status health dan jumlah tools ditampilkan.
- Tools MCP otomatis diekspos ke agent sebagai `mcp_<server>_<tool>` dan muncul sebagai aktivitas manusiawi di chat saat dipakai.

## Skills

- 6 skill bawaan: React SPA, FastAPI, conventional commits, code review, Android Gradle fixer, explainer.
- Skill terdekat dipilih **otomatis** berdasarkan isi tugas, lalu instruksinya disuntikkan ke system prompt agent.
- Buat skill sendiri dari **Settings → Skills**: nama, deskripsi, trigger, instruksi.

## Plugins

Plugin = paket kombinasi:

- satu/lebih **server MCP** (tools tambahan),
- satu/lebih **skill** (instruksi tambahan),
- satu **provider kustom** (AI tambahan).

Cocok untuk "paket kemampuan" — mis. plugin QA (MCP browser + skill code review) atau plugin data (MCP Postgres + skill SQL).

## Slash commands

Di kolom chat: `/fix`, `/test`, `/commit`, `/explain`, `/review` — satu ketukan mengisi prompt lengkap siap kirim.

## Agent actions menu (v1.9.0)

Ikon **tools (kunci inggris)** di header chat membuka menu satu pintu: **Fix, Test, Build, Run, Debug, Explain, Review, Commit** — memilih salah satu otomatis mengaktifkan mode engine AGENT lalu mengirim prompt terkait. Chat tidak dipenuhi tombol; semua aksi lanjutan cukup dari satu ikon.
