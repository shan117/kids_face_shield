package com.shantanu.shield.data

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * Property/fuzz tests for the profile/session codec: generated round-trips + "never crash on garbage".
 * Clean fields are alphanumeric (the codec's delimiters are `|` `\n` `,` `;`), so round-trips are exact;
 * `customAllowed` entries are non-blank (blank entries are filtered on decode).
 */
class KidProfileCodecPropertyTest {

    private val rnd = Random(20260615)
    private val alnum = (('a'..'z') + ('A'..'Z') + ('0'..'9')).joinToString("")

    private fun clean(maxLen: Int): String {
        val n = rnd.nextInt(0, maxLen + 1)
        return buildString { repeat(n) { append(alnum[rnd.nextInt(alnum.length)]) } }
    }

    private fun pkg(): String {
        val n = rnd.nextInt(1, 12)   // non-blank
        return buildString { repeat(n) { append(alnum[rnd.nextInt(alnum.length)]) } }
    }

    private fun randomStr(maxLen: Int = 40): String {
        val n = rnd.nextInt(0, maxLen + 1)
        // include the codec's delimiters + control chars to stress parsing
        val pool = alnum + "|,;\n\t" + ""
        return buildString { repeat(n) { append(pool[rnd.nextInt(pool.length)]) } }
    }

    private fun profile() = KidProfile(
        id = clean(6),
        name = clean(16),
        avatarColor = rnd.nextLong(),
        dailyLimitMinutes = rnd.nextInt(0, 600),
        allowedPreset = rnd.nextInt(0, 4),
        customAllowed = List(rnd.nextInt(0, 4)) { pkg() }.toSet(),
        usedMs = rnd.nextLong(0, 100_000_000),
        extensionsMs = rnd.nextLong(0, 100_000_000),
        lastResetDate = clean(10),
    )

    private fun session() =
        ProfileSession(clean(5), rnd.nextLong(0, 1_000_000_000), rnd.nextLong(0, 1_000_000_000))

    @Test
    fun `profiles round-trip for arbitrary clean lists`() {
        repeat(600) {
            val profiles = List(rnd.nextInt(0, 4)) { profile() }
            assertEquals(profiles, KidProfileCodec.decode(KidProfileCodec.encode(profiles)))
        }
    }

    @Test
    fun `sessions round-trip for arbitrary clean lists`() {
        repeat(600) {
            val sessions = List(rnd.nextInt(0, 6)) { session() }
            assertEquals(sessions, KidProfileCodec.decodeSessions(KidProfileCodec.encodeSessions(sessions)))
        }
    }

    @Test
    fun `decode never throws on garbage input`() {
        repeat(2000) {
            KidProfileCodec.decode(randomStr())          // must not throw
            KidProfileCodec.decodeSessions(randomStr())
        }
    }
}
