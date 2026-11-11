package dev.eigger.hassble.decode

/**
 * Parser für ELM327 ATMA-Ausgaben. Unterstützt 11- und 29-Bit-CAN-IDs,
 * optionale DLC-Angabe und Datenbytes mit/ohne Leerzeichen.
 */
object CanMonitorParser {
    data class Frame(val canId: String, val data: ByteArray)

    /**
     * Extrahiert vollständige CAN-Frames aus einem oder mehreren Monitor-Textblöcken.
     * Erwartete Beispiele:
     *   0C10A040 05 79 A2 4B 48 40
     *   0C10A0400579A24B4840
     *   7E8 8 03 41 0C 1A F8 00 00 00
     */
    fun parse(text: String): List<Frame> {
        val frames = mutableListOf<Frame>()
        for (line in text.split('\r', '\n', '>')) {
            val normalizedLine = line.trim().uppercase()
            if (normalizedLine.isEmpty()) continue
            val tokens = normalizedLine.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val id: String
            val tail: String
            if (tokens.size == 1) {
                val compact = tokens[0].removePrefix("0X")
                // Compact monitor output is unambiguous for extended (29-bit) IDs.
                if (compact.length <= 10 || compact.length % 2 != 0) continue
                id = compact.substring(0, 8)
                tail = compact.substring(8)
            } else {
                id = tokens.first().removePrefix("0X")
                tail = tokens.drop(1).joinToString(" ")
            }
            if (id.length !in 3..8 || id.any { !it.isDigit() && it !in 'A'..'F' }) continue

            val tokensAfterId = tail.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val hex = tokensAfterId.joinToString("") { it }
                .filter { it.isDigit() || it in 'A'..'F' }
            if (hex.length < 2 || hex.length % 2 != 0) continue
            val bytes = runCatching {
                ByteArray(hex.length / 2) { i ->
                    hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }
            }.getOrNull() ?: continue

            // DLC kann von ELM als einzelnes Token ("5") oder als Byte ("05") erscheinen.
            val dlc = tokensAfterId.firstOrNull()?.let { it.toIntOrNull(16) }
            val payload = if (dlc != null && dlc in 0..8 && bytes.size == tokensAfterId.drop(1).joinToString("").length / 2 + 1) {
                bytes.copyOfRange(1, bytes.size)
            } else if (bytes.isNotEmpty() &&
                (bytes[0].toInt() and 0xFF) in 0..8 &&
                bytes.size == (bytes[0].toInt() and 0xFF) + 1
            ) bytes.copyOfRange(1, bytes.size) else bytes
            if (payload.isNotEmpty()) frames += Frame(id, payload)
        }
        return frames
    }

    fun normalizeCanId(value: String): String? {
        val id = value.trim().removePrefix("0x").removePrefix("0X").uppercase()
        return id.takeIf { it.length in 3..8 && it.all { c -> c.isDigit() || c in 'A'..'F' } }
    }
}
