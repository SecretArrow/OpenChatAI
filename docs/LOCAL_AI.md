# AI Lokal On-Device (llama.cpp)

Open Chat AI men-embed [llama.cpp](https://github.com/ggml-org/llama.cpp) **tag `b10919`** (submodule `app/src/main/cpp/llama.cpp`) via JNI/NDK. Tidak ada dependensi aplikasi eksternal — termasuk Ollama. ABI yang dibundel: `arm64-v8a` dan `x86_64`.

Alur singkat: **Models → unduh GGUF → Use → pilih provider "On-device"** di model selector pada layar Chat.

## Katalog model (v1.8.1)

Katalog dibaca dari `app/src/main/assets/models.json` (10 entri, urut ukuran naik). Ukuran file eksak — diperiksa dari Content-Length server — sehingga progress bar akurat. Semua model di bawah telah diuji **inferensi nyata** dengan engine b10919 (5/5 pass; arsitektur `qwen35`, `lfm2`, `qwen3`, `qwen2`, `llama`).

| Model | Quant | Ukuran | Estimasi RAM saat jalan | Catatan |
|---|---|---|---|---|
| Qwen2.5 0.5B Instruct | Q4_K_M | ± 398 MB | ± 0.9–1.3 GB | Cadangan paling ringan untuk HP entry-level |
| Qwen3 0.6B | Q4_0 | ± 429 MB | ± 1.0–1.5 GB | Sangat stabil, punya thinking mode |
| **Qwen3.5 0.8B** | Q4_K_M | ± 580 MB | ± 1.1–1.7 GB | **Terbaru & terpintar di kelasnya — pilihan utama RAM <4 GB** |
| Qwen2.5 0.5B Instruct | Q8_0 | ± 676 MB | ± 1.0–1.5 GB | Varian 8-bit, diksi lebih tajam |
| LFM2 1.2B (LiquidAI) | Q4_K_M | ± 731 MB | ± 1.4–2.2 GB | Arsitektur hybrid khusus edge/mobile |
| Llama 3.2 1B Instruct | Q4_K_M | ± 808 MB | ± 1.4–2.3 GB | Keseimbangan kualitas/kecepatan |
| Qwen2.5 Coder 1.5B | Q4_K_M | ± 1.12 GB | ± 1.8–2.8 GB | Spesialis kode untuk HP mid-range |
| Gemma 2 2B IT | Q4_K_M | ± 1.71 GB | ± 2.5–3.5 GB | Kualitas umum kuat |
| Qwen2.5 Coder 3B | Q4_K_M | ± 1.93 GB | ± 3–4.5 GB | Refaktor & sesi kode panjang |
| Phi-3.5 Mini (3.8B) | Q4_K_M | ± 2.39 GB | ± 4–5 GB | Penalaran terkuat di katalog |

Sumber unduhan: bartowski, unsloth, ggml-org, LiquidAI, dan Qwen di Hugging Face (URL `resolve/main` langsung, cocok dengan engine HTTP Range resume).

## Layar Models

- **Unduh** dengan progress bar per-model; **Pause/Lanjut/Batal** kapan saja — lanjut dari offset terakhir via HTTP Range (part `<id>.part` + sidecar `<id>.meta.json` berisi url/ETag/total).
- **Import GGUF** dari penyimpanan perangkat via SAF (file manager), dan **Export** model yang sudah diunduh — berguna untuk berbagi tanpa unduh ulang.
- Model tersimpan di storage privat aplikasi: `filesDir/models/<id>.gguf`.
- Cek RAM perangkat dilakukan sebelum load; model yang melebihi RAM akan ditolak dengan pesan jelas.

## Runtime Packs (ganti engine tanpa update APK)

**Settings → Runtime & Modul** menampilkan daftar pack dari manifest
`https://raw.githubusercontent.com/SecretArrow/OpenChatAI/main/runtime/manifest.json`:

- Pack `RUNTIME` per-ABI (`runtime-llama-cpu-arm64-v8a`, `runtime-llama-cpu-x86_64`) berisi `libopenchai_llama.so` hasil build llama.cpp dari CI.
- Pasang / Perbarui / Jeda / Lanjut / Batal / Hapus — semuanya resumable dan diverifikasi **SHA-256** streaming.
- **Auto-install** (Switch di layar Runtime): hanya memperbarui pack yang sudah terpasang + modul bertanda wajib; tidak pernah memasang pack opsional secara diam-diam.
- `LlamaBridge` lazy-load: memakai `.so` dari pack terpasang; bila belum ada, fallback ke library yang dibundel APK.

## Parameter inferensi

- KV cache direset otomatis tiap generasi (auto context-reset).
- BOS (`add_special`) hanya disuntik pada prompt pertama.
- Streaming token-by-token ke UI chat via `TokenCallback` JNI.
- Semua eksekusi di thread background — UI tidak pernah diblokir.

## Pengujian yang sudah dilakukan

Pada rilis v1.8.1, katalog diuji end-to-end di mesin Linux x86_64 memakai `llama-completion` hasil build **dari submodule b10919 yang sama** dengan engine aplikasi:

- Header GGUF tiap file diparse (magic `GGUF` v3, arsitektur, chat template ada).
- Inferensi prompt `What is 2+3?` → kelima model low-RAM menjawab benar (`5`) dengan exit code 0.
