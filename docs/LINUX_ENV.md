# Lingkungan Linux Embedded (proot + Ubuntu)

Sejak v1.8.0, Open Chat AI membawa **Lingkungan Pengembangan Linux** lengkap yang berjalan userspace di dalam aplikasi — tanpa root, tanpa VM, tanpa ISO. Implementasinya: binary **proot statis** dibundel di APK (`libproot.so` di `jniLibs`), dan **rootfs Ubuntu resmi** (ubuntu-base) diunduh saat setup pertama. Semua command benar-benar dieksekusi — tidak ada mock.

```text
Android (host) ──► Open Chat AI ──► proot ──► Ubuntu rootfs (userspace)
                                                ├── bash, coreutils, grep, sed, awk, tar, ...
                                                ├── apt (update/install/search/list)
                                                ├── node / npm / npx   (apt install nodejs npm)
                                                ├── python3 / pip3
                                                └── git, curl, wget, ssh, make, gcc, g++, ...
```

## Setup pertama

1. Buka **Settings → Linux Environment** (atau toggle `Terminal ▼` di Chat lalu ikuti ajakan setup).
2. Aplikasi melakukan **cek kelayakan**: ABI (`arm64-v8a`/`x86_64`), RAM total ≥ ± 2.5 GB, storage kosong cukup.
3. Rootfs diunduh dari `cdimage.ubuntu.com` (`ubuntu-base-22.04` atau `20.04`, ± 28 MB) — unduhan **resumable** dan diverifikasi **SHA-256** terhadap manifest beku di kode.
4. Rootfs diekstrak (zip-slip guard + symlink `/bin → usr/bin` ditangani), lalu **bootstrap**: `apt-get update` di dalam rootfs.
5. Status menjadi **READY** — terminal otomatis memakai `bash -li` di dalam sandbox.

Workspace `/home/user/workspace` dibuat saat setup dan **persisten** — file, project, dan paket yang dipasang bertahan lintas sesi dan restart aplikasi.

## Yang bisa dilakukan setelah READY

- **Shell lengkap**: `bash`, `ls`, `cd`, `mkdir`, `rm`, `cp`, `mv`, `grep`, `sed`, `awk`, `tar`, `find`, `ps`, `kill`, `killall`, dll.
- **apt sungguhan**: `apt update`, `apt install nodejs npm`, `apt search <paket>`, `apt list --installed` — paket diunduh dari mirror Ubuntu ke rootfs.
- **Node.js & npm**: `node run.js`, `npm init`, `npm install`, `npm run start/dev`, `npx <tool>`.
- **Python 3 & pip3**: skrip, venv (`python3 -m venv`), `pip3 install`.
- **Dev tools**: `apt install git curl wget openssh-client make gcc g++ pkg-config`.
- **Background process**: `node server.js &` tetap hidup walau terminal disembunyikan atau kembali ke Chat; kelola dari **Process Manager** (PID, port terdeteksi, durasi, output, stop/restart, auto-restart opsional, kirim stdin untuk REPL `python3 -i` / `node`).

## Cron

- `crontab` di dalam rootfs memakai **sintaks cron standar** (5 field).
- Bila daemon cron dibatasi Android, scheduler internal in-app menjalankan entri yang sama (tick 60 detik, mendukung `*`, `*/n`, rentang, daftar koma, OR dom/dow) — hasil dieksekusi lewat runner yang sama dan dicatat di `var/log/openchai-cron.log` (rotasi otomatis 256 KB → 128 KB).
- Cron hanya aktif saat lingkungan berstatus READY dan berhenti saat lingkungan dihancurkan.

## Terminal

Ikon **$** di header chat membuka **TerminalActivity** terpisah (layar penuh) — chat tidak pernah kehilangan ruang; kembali ke chat, state percakapan tetap. Terminal otomatis berada di **cwd = workspace aktif**.

Fitur terminal: ANSI color, command history, copy/paste, text selection, clear, search, auto-scroll, font size, **Ctrl+C** (sinyal ke proses depan), **Ctrl+D** (EOF), Tab completion dari bash, resize mengikuti jendela.

## Integrasi AI → Terminal

Tool agent `run_command` otomatis diarahkan ke sandbox Linux saat status READY:

- AI bisa menjalankan `npm install`, `python3 script.py`, `git status`, dll. — **folder workspace Android di-bind langsung ke rootfs** (`proot -b <workspace>:/home/user/workspace`), jadi cwd di dalam sandbox ADALAH folder project asli: file yang dibuat `npm create vite` = file yang dibaca `read_file` = file yang terlihat di layar Files perangkat.
- Workspace SAF pada storage utama (`primary:...`) juga di-bind bila path fisiknya dapat diturunkan; SAF non-primary memakai file tools saja dengan pesan error yang jelas.
- stdout, stderr, dan exit code dikembalikan ke agent untuk dievaluasi.
- Bila Linux belum siap (atau tidak memenuhi syarat perangkat), `run_command` fallback ke shell Android secara transparan (cwd = path workspace asli).

## Keamanan & batasan

- **Sandbox userspace (proot)**: proses guest dipetakan ke UID aplikasi; akses ke partisi sistem Android, data aplikasi lain, dan Keystore tidak mungkin dari dalam rootfs.
- Tidak ada eskalasi root; proot hanyalah penerjemah path/syscall userspace.
- Command dari agent tetap melewati lapisan eksekusi aman + blocklist berbahaya.
- `targetSdk` sengaja 28 (pola Termux/UserLAnd): Android 10+ memblokir `execve()` binary di app-data untuk targetSdk ≥ 29.

## Manajemen resource

- Semua eksekusi asinkron (Dispatchers.IO) — UI tidak pernah macet.
- Supervisor proses: revision counter per sesi, restart same-id, prune proses EXITED/STOPPED/FAILED > 30 menit.
- Saat aplikasi dihancurkan (teardown), seluruh proses di sandbox dihentikan rapi; output di-stream berbatas agar memori aman.
- Output cron & proses lama dipangkas otomatis (rotasi log).

## Troubleshooting

| Gejala | Penyebab & solusi |
|---|---|
| Setup menolak: "RAM tidak cukup" | RAM total < 2560 MB. Tutup aplikasi lain atau gunakan perangkat ≥ 3 GB. |
| Setup menolak: "ABI tidak didukung" | Perangkat 32-bit. Gunakan arm64-v8a / x86_64. |
| Unduhan rootfs gagal di tengah | Jalankan ulang — unduhan lanjut dari offset terakhir (resumable). |
| `apt install` gagal jaringan | Cek koneksi; ulangi `apt update`. Mirror Ubuntu harus terjangkau dari jaringan Anda. |
| Terminal bilang fallback shell Android | Linux belum READY — buka layar Linux Environment dan selesaikan setup. |
| `Ctrl+C` tidak memotong proses | Proses berjalan sebagai background job — gunakan `kill %1` / `kill <PID>` atau Process Manager. |
