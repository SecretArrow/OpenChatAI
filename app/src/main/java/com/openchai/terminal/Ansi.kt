package com.openchai.terminal

/**
 * Pembersih kode escape ANSI agar output terminal aman ditampilkan
 * sebagai teks biasa (Text Compose) tanpa artefak.
 */
object Ansi {

    /** ESC [ params intermediate final — contoh: ESC[31m, ESC[2J, ESC[?25h. */
    private val CSI = Regex("\u001B\\[[0-9;?=><!]*[ -/]*[@-~]")

    /** ESC ] ... BEL atau ESC ] ... ESC \ (OSC — judul jendela, hyperlink, dsb). */
    private val OSC = Regex("\u001B\\][^\u0007]*(?:\u0007|\u001B\\\\)")

    /** Karakter kontrol tunggal, kecuali \n (0x0A) dan \t (0x09). */
    private val CONTROL = Regex("[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]")

    /** Buang semua escape sequence & karakter kontrol dari [s]. */
    fun strip(s: String): String {
        if (s.indexOf('\u001B') < 0 && !CONTROL.containsMatchIn(s)) return s
        var out = OSC.replace(s, "")
        out = CSI.replace(out, "")
        out = CONTROL.replace(out, "")
        // ESC nyangkut (sequence terpotong di batas buffer) juga dibuang.
        out = out.replace("\u001B", "")
        return out
    }
}
