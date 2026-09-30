package com.waterdistrict.meterreader

/**
 * The office every bill and the About screen name. Kept in one place so the
 * printed bill, the digital bill and About can't drift apart — the move to the
 * bus terminal was one edit here.
 */
object OfficeInfo {
    const val NAME = "South Wao Water System (MEEDO)"
    const val ADDRESS = "Bus Terminal Lobby, Brgy. Eastern, Wao, Lanao del Sur"
    const val TELEPHONE = "0985 762 5456"

    /**
     * [ADDRESS] for a screen. The spaces inside each part are non-breaking, so
     * a narrow screen wraps it between parts — never "Lanao del / Sur".
     */
    val addressForDisplay: String =
        ADDRESS.split(",").joinToString(", ") { it.trim().replace(' ', ' ') }

    /**
     * [ADDRESS] as lines no wider than [width], broken at its commas.
     *
     * A thermal printer wraps a long line wherever it runs out of room, which
     * cuts words in half ("Brgy. Easte / rn"). Breaking between the parts of
     * the address keeps each part whole; a part wider than a line on its own
     * is left for the printer to wrap.
     */
    fun addressLines(width: Int, address: String = ADDRESS): List<String> {
        val lines = mutableListOf<String>()
        var current = ""
        for (part in address.split(",").map { it.trim() }.filter { it.isNotEmpty() }) {
            val joined = if (current.isEmpty()) part else "$current, $part"
            if (joined.length <= width || current.isEmpty()) {
                current = joined
            } else {
                lines += current
                current = part
            }
        }
        if (current.isNotEmpty()) lines += current
        return lines
    }
}
