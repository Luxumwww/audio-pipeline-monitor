package com.audioprobe.audio

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Guards against a bug class that JVM unit tests cannot see.
 *
 * `Regex("""mCodecConfig:\s*\{(.*)}""")` compiles fine on the desktop JVM, but Android's
 * regex engine rejects the pattern because of the unescaped closing brace. Because these
 * patterns are `val`s, the failure lands in the owning object's `<clinit>` and ART then
 * refuses to ever retry it:
 *
 * ```
 * Rejecting re-init on previously-failed class com.audioprobe.audio.BluetoothParser:
 *   java.lang.ExceptionInInitializerError
 *   at void com.audioprobe.audio.BluetoothParser.<clinit>()
 * ```
 *
 * The symptom surfaces far away from the cause - the parser simply never runs and the
 * feature silently disappears - so the rule is enforced mechanically: in the parser
 * sources, `{` and `}` may only appear escaped, inside a character class, or as part of
 * a `{n}` / `{n,}` / `{n,m}` quantifier.
 */
class RegexPortabilityTest {

    private val quantifier = Regex("""^\{\d+(,\d*)?}""")

    @Test
    fun `no parser regex contains a bare brace`() {
        val root = File("src/main/java/com/audioprobe")
        assumeTrue("source tree not visible from ${File(".").absolutePath}", root.isDirectory)

        val offenders = ArrayList<String>()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                regexLiterals(file.readText()).forEach { pattern ->
                    if (hasBareBrace(pattern)) offenders += "${file.name}: $pattern"
                }
            }

        assertTrue(
            "Regex literals that Android's engine may reject:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `the guard actually detects the pattern that shipped broken`() {
        assertTrue(hasBareBrace("""mCodecConfig:\s*\{(.*)}"""))
        assertTrue(hasBareBrace("""a}b"""))

        // Escaped, inside a character class, or a real quantifier are all fine.
        assertTrue(!hasBareBrace("""FormatInfo\{[^}]*channelMask=(\w+)\}"""))
        assertTrue(!hasBareBrace("""[0-9a-fA-F]{1,8}"""))
        assertTrue(!hasBareBrace("""\d{2,4}"""))
    }

    /** Pulls the body out of every `Regex("""...""")` occurrence in [source]. */
    private fun regexLiterals(source: String): List<String> {
        val results = ArrayList<String>()
        var index = 0
        while (true) {
            val start = source.indexOf("Regex(\"\"\"", index)
            if (start < 0) break
            val bodyStart = start + "Regex(\"\"\"".length
            val end = source.indexOf("\"\"\"", bodyStart)
            if (end < 0) break
            results += source.substring(bodyStart, end)
            index = end
        }
        return results
    }

    private fun hasBareBrace(pattern: String): Boolean {
        var i = 0
        var inClass = false
        while (i < pattern.length) {
            when {
                pattern[i] == '\\' -> i++ // skip the escaped character
                pattern[i] == '[' && !inClass -> inClass = true
                pattern[i] == ']' && inClass -> inClass = false
                !inClass && (pattern[i] == '{' || pattern[i] == '}') -> {
                    val match = quantifier.find(pattern.substring(i))
                        ?: return true
                    i += match.value.length - 1
                }
            }
            i++
        }
        return false
    }
}
