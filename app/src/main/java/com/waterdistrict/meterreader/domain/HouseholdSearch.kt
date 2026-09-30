package com.waterdistrict.meterreader.domain

import java.text.Normalizer
import java.util.Locale

/**
 * How a reader finds a household on their route: by name, account number or
 * meter number, whichever they have in front of them.
 *
 * Every word typed has to appear somewhere in the household's name or numbers,
 * so "dela cruz" and "cruz juan" both find Juan Dela Cruz, and "710013" finds
 * meter MTR-710013. Case and accents don't matter — "pena" finds Peña.
 *
 * A household whose meter or account number is exactly what was typed comes
 * first; the rest are A–Z by name, like every other list in the system.
 */
object HouseholdSearch {

    /** The fewest characters worth searching on — one letter matches most of a route. */
    const val MIN_QUERY_LENGTH = 2

    data class Candidate<T>(
        val item: T,
        val name: String,
        val meterNo: String,
        val accountNo: String,
        val officeAccountNo: String,
    )

    fun <T> search(query: String, candidates: List<Candidate<T>>): List<T> {
        val words = fold(query).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty() || fold(query).replace(" ", "").length < MIN_QUERY_LENGTH) return emptyList()
        val typed = code(query)

        return candidates
            .filter { c ->
                val haystack = fold("${c.name} ${c.meterNo} ${c.accountNo} ${c.officeAccountNo}")
                words.all { it in haystack }
            }
            .sortedWith(
                compareBy<Candidate<T>> { c ->
                    // An exact number is the household the reader is standing at.
                    if (typed.isNotEmpty() && (code(c.meterNo) == typed || code(c.accountNo) == typed ||
                            code(c.officeAccountNo) == typed)) 0 else 1
                }.thenBy { fold(it.name) }
            )
            .map { it.item }
    }

    /** Lower case, accents off, one space between words. */
    private fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
            .trim()

    /** A number with its punctuation dropped, so "MTR 710013" equals "MTR-710013". */
    private fun code(text: String): String = fold(text).replace(Regex("[^a-z0-9]"), "")
}
