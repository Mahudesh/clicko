package com.mahudesh.tvremote.tv

/**
 * Turns text into Android key presses, for TVs that can't take a whole text field at once.
 * Letters, digits, space and common punctuation; capitals and shifted symbols hold Shift.
 * Characters with no key (emoji, accents) are skipped.
 */
object KeyTyper {
    private const val SHIFT = 59

    private val plain: Map<Char, Int> = buildMap {
        ('a'..'z').forEachIndexed { i, c -> put(c, 29 + i) }
        ('0'..'9').forEachIndexed { i, c -> put(c, 7 + i) }
        put(' ', 62); put('\n', 66); put('\t', 61)
        put(',', 55); put('.', 56); put('`', 68); put('-', 69); put('=', 70)
        put('[', 71); put(']', 72); put('\\', 73); put(';', 74); put('\'', 75)
        put('/', 76); put('@', 77); put('*', 17); put('#', 18); put('+', 81)
    }

    // US layout shifted symbols.
    private val shifted: Map<Char, Int> = buildMap {
        ('A'..'Z').forEachIndexed { i, c -> put(c, 29 + i) }
        val digitsShifted = ")!@#\$%^&*("
        digitsShifted.forEachIndexed { i, c -> if (c !in plain) put(c, 7 + i) }
        put('_', 69); put('{', 71); put('}', 72); put('|', 73); put(':', 74)
        put('"', 75); put('?', 76); put('<', 55); put('>', 56); put('~', 68)
    }

    fun type(text: String, press: (code: Int, direction: KeyDirection) -> Unit) {
        for (c in text) {
            plain[c]?.let { press(it, KeyDirection.SHORT); return@let } ?: shifted[c]?.let { code ->
                press(SHIFT, KeyDirection.START_LONG)
                press(code, KeyDirection.SHORT)
                press(SHIFT, KeyDirection.END_LONG)
            }
        }
    }
}
