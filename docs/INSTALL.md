# Instalasi & Persyaratan

## Unduh

1. Buka halaman [Releases](https://github.com/SecretArrow/OpenChatAI/releases) repo ini.
2. Pada rilis terbaru (v1.8.1), unduh **`OpenChatAI-release.apk`**.
3. Pasang APK (izinkan "Install from unknown sources" bila diminta).

Rilis juga berisi **runtime packs** (`runtime-llama-cpu-<abi>.zip`) — ini opsional dan lebih mudah dipasang langsung dari dalam aplikasi: **Settings → Runtime & Modul → Pasang** (unduhan resumable + verifikasi SHA-256 otomatis).

## Persyaratan perangkat

| Komponen | Minimum | Catatan |
|---|---|---|
| Android | 9.0 (API 26) | `minSdk 26` |
| ABI | `arm64-v8a` | `x86_64` didukung (emulator/Chromebook) |
| RAM untuk chat + AI lokal | 2–3 GB | 0.5B–0.8B Q4 butuh ± 1–1.7 GB saat inferensi |
| RAM untuk Linux embedded | ± 2.5 GB total | batas internal `MIN_RAM_MB = 2560` |
| Storage Linux embedded | ± 1.5 GB kosong | rootfs ± 28 MB terkompresi, ± 400–700 MB terpasang + ruang paket apt |
| Storage model GGUF | 0.4–2.4 GB per model | sesuai pilihan di layar Models |

## Catatan penting: paket baru sejak v1.8.0

Sejak v1.8.0, `applicationId` berubah dari `com.openchai.app` menjadi **`com.openchatai.app`** (karena itu juga `targetSdk` diset 28 mengikuti pola Termux/UserLAnd agar eksekusi binary di app-data sah).

Konsekuensinya:

- **v1.8.x terpasang sebagai aplikasi terpisah**, bukan upgrade in-place, dari versi 1.7.0 ke bawah. Ikon lama dan baru bisa berdampingan.
- Riwayat chat, project, model GGUF, dan rootfs yang tersimpan di versi lama **tidak otomatis dipindah** — unduh ulang model/rootfs di aplikasi baru bila perlu.
- Hapus instalasi lama (`com.openchai.app`) setelah migrasi agar tidak bingung.

## Signing

APK rilis dari CI ditandatangani **debug key** agar mudah dipasang untuk pengujian. Untuk distribusi publik (Play Store atau mirror), build dengan keystore Anda sendiri: letakkan keystore + `keystore.properties` lalu sesuaikan signingConfig di `app/build.gradle.kts` sebelum membuat tag rilis.

## Verifikasi hasil unduhan

- **Runtime packs** dan **rootfs** diverifikasi SHA-256 secara streaming saat diunduh; bila checksum gagal, unduhan ditolak dan bisa diulang (part dihapus otomatis).
- **Model GGUF** dari katalog diverifikasi ukurannya (progress bar menghitung total byte eksak dari Content-Length); unduhan bisa dijeda/lanjutkan kapan saja (HTTP Range).
