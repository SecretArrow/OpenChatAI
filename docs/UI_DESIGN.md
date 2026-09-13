# Sistem Desain — Material Design 3 (v1.10.0)

Dokumen ini merangkum sistem desain antarmuka Open Chat AI yang mengikuti spesifikasi [Material Design 3 (M3)](https://m3.material.io/). Semua layar memakai kontrak tema yang sama; dokumen ini menjadi acuan ketika menambah atau mengubah UI.

## Struktur file tema

| File | Isi |
|---|---|
| `ui/theme/Color.kt` | Palet tonal ungu/teal/peach + skema M3 lengkap terang & gelap + warna terminal |
| `ui/theme/Theme.kt` | `OpenChatTheme()`, pilihan skema (dynamic/brand), `AppShapes` (shape scale) |
| `ui/theme/Type.kt` | `AppTypography` — 15 gaya tipografi M3 |
| `ui/theme/Motion.kt` | `AppMotion` — token durasi & kurva easing M3 |

## Skema warna

Semua warna UI wajib diambil dari `MaterialTheme.colorScheme.*` — dilarang menulis literal `Color(0x…)` di file layar. Skema lengkap mencakup:

- **Aksen**: `primary/onPrimary` + `primaryContainer/onPrimaryContainer`, pasangan yang sama untuk `secondary` (teal) dan `tertiary` (peach)
- **Surface**: `surface`, `surfaceVariant/onSurfaceVariant`, tangga elevasi `surfaceContainerLowest → surfaceContainerLow → surfaceContainer → surfaceContainerHigh → surfaceContainerHighest`, plus `surfaceDim/surfaceBright`
- **Garis & aksi sekunder**: `outline`, `outlineVariant`
- **Status**: `error/onError` + `errorContainer/onErrorContainer`
- **Balik arah**: `inverseSurface/inverseOnSurface/inversePrimary`, `scrim`

Pedoman penggunaan yang dipakai di seluruh aplikasi:

| Kebutuhan | Role |
|---|---|
| Latar layar | `background` / `surface` |
| Kartu, pill, input | `surfaceContainer` … `surfaceContainerHigh` |
| Bubble chat user | `primaryContainer` + teks `onPrimaryContainer` |
| Bubble chat AI | `surfaceContainerHigh` + teks `onSurface` |
| Status sukses/terhubung | `secondary` / `secondaryContainer` |
| Status proses/penantian | `tertiary` / `tertiaryContainer` |
| Galat/banner gagal | `errorContainer` + `onErrorContainer` + ikon `WarningAmber` |
| Teks sekunder | `onSurfaceVariant` |
| Ikon/baris pemisah | `outlineVariant` |

**Pengecualian**: area emulator terminal (`TerminalOutputSurface`) tetap memakai palet `Terminal*` (`TerminalBackground/Foreground/Green/Yellow/Red`) karena warna ANSI emulator adalah bagian dari fungsionalitas, bukan dekorasi. Seluruh chrome di sekelilingnya (toolbar, banner, chip) tetap M3.

### Dynamic color (Material You)

Di Android 12+ (API 31) tema memakai `dynamicLightColorScheme`/`dynamicDarkColorScheme` — warna mengikuti wallpaper perangkat, tetap menghormati mode terang/gelap yang dipilih user (`ThemeMode.SYSTEM/LIGHT/DARK`). Di bawah Android 12, skema brand ungu (`LightColors`/`DarkColors`) dipakai agar tampilan konsisten.

## Bentuk (shape scale)

`AppShapes` di `Theme.kt`:

| Slot | Radius | Contoh pemakaian |
|---|---|---|
| `extraSmall` | 8 dp | detail kecil, permukaan dalam dialog |
| `small` | 12 dp | badge, chip kontainer |
| `medium` | 16 dp | kartu |
| `large` | 20 dp | bubble chat |
| `extraLarge` | 28 dp | pil input chat, bottom sheet, pill status |

Gunakan `MaterialTheme.shapes.small/medium/large/extraLarge` — jangan hardcode `RoundedCornerShape` kecuali bentuk khusus yang tidak ada di skala (mis. panel bottom-only).

## Tipografi

`AppTypography` mendefinisikan 15 gaya M3 (`displayLarge` s.d. `labelSmall`) dengan ukuran/line-height sesuai spesifikasi. Pemetaan umum:

- Judul layar / app bar: `titleLarge`
- Judul kartu & baris: `titleMedium` (deskripsi pendek: `bodyMedium` di `supportingText`)
- Teks isi chat: `bodyLarge`
- Metadata (waktu, ukuran file): `labelMedium` dengan warna `onSurfaceVariant`
- Label tombol/chip: `labelLarge`

## Ikon (Material Symbols)

Ikon memakai set extended `androidx.compose.material.icons` (artefak `material-icons-extended`) dengan gaya **Rounded/Outlined** — bukan `Icons.Filled`:

- Ikon **Outlined** untuk keadaan tidak aktif / tindakan di daftar (mis. `Outlined.DeleteOutline`, `Outlined.FolderOpen`)
- Ikon **Rounded** untuk keadaan aktif / tombol utama (mis. `Rounded.Send`, `Rounded.Folder`)
- **Bottom navigation** mengikuti pola M3: tab tidak terpilih = outlined, tab terpilih = rounded (filled)

Pemetaan tab: Chat (`ChatBubbleOutline`/`ChatBubble`), History (`History`), Projects (`FolderOpen`/`Folder`), Models (`SmartToy`), Settings (`Settings`). Ikon khas lain: `Terminal` (terminal/agent), `Bolt` (agent actions), `SmartToy` (AI/model), `CheckCircle` (sukses/terhubung), `WarningAmber` (galat), `ContentCopy` (salin), `ExpandMore` (collapsible, berotasi saat terbuka).

## Motion

Semua animasi memakai token `AppMotion` (`Motion.kt`) agar gerakan konsisten:

- `standardTween()` — animasi umum: `animateContentSize` (expand kartu/seksi), fade in/out
- `emphasizedTween()` — transisi menonjol (sheet, elemen hero)
- `EmphasizedDecelerateEasing` — elemen masuk; `SHORT/MEDIUM/LONG_DURATION` = 150/300/500 ms

Pola yang dipakai: `AnimatedVisibility` (fade + expandVertically) untuk banner & indikator "New messages", `animateFloatAsState` untuk rotasi chevron collapsible, `rememberInfiniteTransition` untuk pulse StatusDot dan indikator typing.

## Komponen per layar

| Layar | Komponen M3 utama |
|---|---|
| Chat | `TopAppBar` + aksi ikon, bubble (lihat tabel warna), pil input `extraLarge` + `FilledIconButton` kirim, `ModalBottomSheet` (model/project selector) + `FilterChip`, `SuggestionChip` slash-command, banner status `surfaceContainerHigh`, kartu agent events collapsible |
| Settings | `Scaffold` + `TopAppBar`, seksi `OutlinedCard` berisi `ListItem` + `Switch`, `SingleChoiceSegmentedButtonRow` (mode tema, kepadatan chat), `HorizontalDivider` |
| MCP / Skills | `OutlinedCard` per entri, `FilterChip` status, dialog form `shapes.extraLarge` |
| Models / Runtime | Kartu item + ikon tonal, `AssistChip` metadata, `LinearProgressIndicator` (track `surfaceContainerHighest`), blok galat `errorContainer` |
| Workspace / Linux setup | `ElevatedCard` opsi, `CenterAlignedTopAppBar`, kartu langkah ber-ikon tonal, progres determinate |
| History / Sessions drawer | `ListItem`, `ModalDrawerSheet`, item aktif `primaryContainer`, empty state ikon besar + `headlineSmall` |
| Projects | `OutlinedCard` proyek + chip "Active", aksi via `DropdownMenuItem` ber-ikon |
| Terminal | Chrome M3 (`surfaceContainer` + ikon `Terminal` tonal), emulator tetap palet terminal |

## Prinsip untuk kontributor

1. **Hanya role colorScheme** — tidak ada literal warna; kontras foreground/background dijamin skema.
2. **Ikon dari set extended** Rounded/Outlined, nama eksplisit di import; jangan pakai `Icons.Filled`.
3. **Komponen dari `androidx.compose.material3`** — tidak ada komponen Material 2 (`androidx.compose.material`).
4. **Shape & tipografi dari tema** — konsistensi skala mengutamakan hardcode.
5. **Animasi lewat `AppMotion`** — durasi/easing tidak ditulis manual.
6. **Visual-only untuk refactor** — perubahan UI tidak boleh mengubah kontrak ViewModel, callback, atau routing.
