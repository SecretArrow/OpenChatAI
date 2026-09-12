# Build, CI/CD & Release

Prinsip proyek ini: **tidak ada build lokal** — semua kompilasi, test, dan rilis lewat GitHub Actions.

## Workflow

| Workflow | File | Trigger | Hasil |
|---|---|---|---|
| **Android CI** | `.github/workflows/android-ci.yml` | push / PR ke `main` | Build debug + release, artifact `OpenChatAI-APKs`, timeout 50 menit |
| **Release** | `.github/workflows/release.yml` | tag `v*` | APK release + **runtime packs per-ABI** + pembaruan `runtime/manifest.json` (auto-commit ke `main`) + GitHub Release |
| **Auto Fix** | workflow autofix | Android CI gagal | Issue ringkasan error + (opsional) AI auto-fix & push patch |

## Alur rilis

1. Commit fitur ke `main` → tunggu **Android CI hijau**.
2. Buat tag: `git tag vX.Y.Z && git push origin vX.Y.Z`.
3. Workflow Release otomatis:
   - build & sign APK (debug key),
   - unzip `lib/<abi>/libopenchai_llama.so` dari APK → zip jadi `runtime-llama-cpu-<abi>.zip`,
   - hitung sha256/size tiap aset → tulis `runtime/manifest.json` → commit & push ke `main`,
   - publikasikan GitHub Release berisi APK + runtime packs.
4. Aplikasi yang sudah terpasang menarik manifest itu dari `raw.githubusercontent.com/.../main/runtime/manifest.json` untuk menawarkan pembaruan pack (auto-install hanya untuk pack yang sudah terpasang).

## Versi

- `versionCode` (integer, bertambah tiap rilis) dan `versionName` di `app/build.gradle.kts`.
- Riwayat versi ringkas ada di [README](../README.md#riwayat-versi-singkat).

## Secrets & keamanan

| Secret | Dipakai untuk |
|---|---|
| `GITHUB_TOKEN` (bawaan GitHub) | upload artifact/aset release & auto-commit `runtime/manifest.json` ke `main` (`persist-credentials: true`) |
| `AI_API_KEY` (+ opsional vars `AI_BASE_URL`, `AI_MODEL`) | workflow Auto Fix — AI memperbaiki CI yang gagal |

- API key pengguna **tidak pernah** disimpan di repo — hanya di EncryptedSharedPreferences perangkat.
- Tidak ada keystore produksi di CI; APK rilis ditandatangani debug key (lihat [INSTALL.md](INSTALL.md#signing)).

## Build environment CI

- JDK 17, Android SDK 34, NDK (via `android-actions/setup-android`).
- Checkout dengan `submodules: recursive` (llama.cpp pin `b10919`).
- CMake build `libopenchai_llama.so` untuk `arm64-v8a` + `x86_64` (ggml statis, tanpa example/server/curl).
- `packaging { jniLibs { keepDebugSymbols += "**/libproot.so" } }` agar proot statis tidak di-strip.
