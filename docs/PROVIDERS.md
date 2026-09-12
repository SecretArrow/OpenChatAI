# Provider AI

Open Chat AI mendukung tujuh jenis provider. Pilih lewat **model selector di layar Chat**; konfigurasi di **Settings → AI Providers**. Setiap kali konfigurasi disimpan (API key, base URL, model default, dll.), aplikasi menampilkan **Toast/Dialog konfirmasi** sehingga jelas bahwa konfigurasi telah berubah.

| Provider | Jenis | Butuh key? | Keterangan |
|---|---|---|---|
| **On-device** | Lokal (embedded llama.cpp) | Tidak | Model GGUF dari layar Models; inferensi 100% di perangkat |
| **Ollama** | Lokal HTTP | Tidak | `http://127.0.0.1:11434` |
| **Remote Ollama** | LAN/remote HTTP | Tidak | mis. `http://192.168.1.100:11434` |
| **OpenAI** | Cloud | Ya | `api.openai.com` |
| **Anthropic** | Cloud | Ya | Claude |
| **Google** | Cloud | Ya | Gemini |
| **Poolside** | Cloud/self-host | Ya | Endpoint kompatibel OpenAI |
| **Custom** | Cloud/self-host | Ya | Base URL OpenAI-compatible apa pun |

## Model list dinamis per base URL

Layar provider menampilkan **daftar model yang benar-benar dimiliki endpoint** — ditarik dari base URL masing-masing (`GET /v1/models` untuk OpenAI-compatible, `/api/tags` untuk Ollama, endpoint model list untuk Anthropic/Google). Jadi:

- Ollama lokal menampilkan model yang ada di mesin Anda saja.
- Endpoint Poolside/custom menampilkan model milik endpoint tersebut, bukan daftar hardcoded.
- Bila endpoint tidak bisa dihubungi, status koneksi ditampilkan jelas + fallback ke input manual.

## Penyimpanan API key

- API key disimpan di **EncryptedSharedPreferences** (kunci di Android Keystore) — tidak pernah ditulis plaintext, tidak pernah dikirim ke mana pun selain endpoint provider terkait.
- Mengganti atau menghapus key memicu konfirmasi visual (Toast/Dialog) yang menyatakan konfigurasi berubah.
- Key **tidak pernah masuk** ke repo, log, atau file ekspor chat.

## On-device vs Ollama

- **On-device** = engine llama.cpp tertanam di APK (lihat [LOCAL_AI.md](LOCAL_AI.md)). Tanpa server, tanpa aplikasi lain, streaming langsung dari JNI.
- **Ollama** = butuh Ollama berjalan di perangkat yang sama (via terminal Termux, dsb.) atau mesin lain di jaringan. Pilih ini bila ingin memakai model yang lebih besar dari RAM ponsel dengan menaruh Ollama di PC/server.

## Ollama + Linux embedded

Sejak v1.8.0, Anda bisa menjalankan Ollama **di dalam sandbox Linux bawaan**: unduh binary Ollama (mis. `wget https://ollama.com/download/ollama-linux-amd64.tgz` lalu ekstrak ke `/usr/local/bin` via Terminal), jalankan `ollama serve` sebagai **background process** dari Process Manager, lalu pakai provider Ollama `http://127.0.0.1:11434` seperti biasa. Installer resmi berbasis systemd tidak berlaku di sandbox (tanpa systemd) — pakai cara manual di atas. Ini opsi lanjutan; untuk sebagian besar perangkat, provider **On-device** lebih ringan.
