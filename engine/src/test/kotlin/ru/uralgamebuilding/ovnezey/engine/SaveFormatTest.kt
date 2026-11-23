package ru.uralgamebuilding.ovnezey.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SaveFormatTest {
    private val full = SaveData(
        GameState(
            "chapter_one", 7,
            flags = setOf("b_flag", "a_flag"),
            params = mapOf("trust" to -2, "money" to 40),
            inventory = setOf("key", "lamp"),
            location = "street",
        ),
        scenarioId = "ovnezey",
        scenarioVersion = 3,
        savedAtMillis = 1_760_000_000_000L,
    )

    private fun ok(text: String?) = (SaveFormat.decode(text) as DecodeResult.Ok)

    private fun reason(text: String?) = (SaveFormat.decode(text) as DecodeResult.Invalid).reason

    @Test
    fun roundTripPreservesEverything() {
        val decoded = ok(SaveFormat.encode(full))
        assertEquals(full, decoded.data)
        assertEquals(null, decoded.migratedFromVersion)
    }

    @Test
    fun emptyCollectionsAndNoLocationRoundTrip() {
        val minimal = SaveData(GameState("start", 0), "story", 1, 0L)
        assertEquals(minimal, ok(SaveFormat.encode(minimal)).data)
    }

    @Test
    fun encodingIsDeterministic() {
        val a = SaveFormat.encode(full)
        val b = SaveFormat.encode(
            full.copy(state = full.state.copy(flags = setOf("a_flag", "b_flag"), inventory = setOf("lamp", "key"), params = mapOf("money" to 40, "trust" to -2))),
        )
        assertEquals(a, b)
        assertTrue(a.startsWith("OVNSAVE 2\n"))
        assertTrue(a.lines().filter { it.isNotEmpty() }.last().startsWith("crc32="))
    }

    @Test
    fun anyChangedByteIsDetected() {
        val text = SaveFormat.encode(full)
        val tampered = text.replace("index=7", "index=8")
        assertEquals(InvalidReason.CORRUPTED, reason(tampered))
        val flipped = text.replace("trust:-2", "trust:-3")
        assertEquals(InvalidReason.CORRUPTED, reason(flipped))
    }

    @Test
    fun truncatedFilesAreDetected() {
        val text = SaveFormat.encode(full)
        for (cut in listOf(10, text.length / 2, text.length - 5)) {
            assertEquals(InvalidReason.CORRUPTED, reason(text.substring(0, cut)), "обрезано до $cut")
        }
        assertEquals(InvalidReason.CORRUPTED, reason(text.substringBefore("crc32=")))
    }

    @Test
    fun windowsLineEndingsAreAccepted() {
        assertEquals(full, ok(SaveFormat.encode(full).replace("\n", "\r\n")).data)
    }

    @Test
    fun emptyAndUnknownInputs() {
        assertEquals(InvalidReason.EMPTY, reason(null))
        assertEquals(InvalidReason.EMPTY, reason("  \n"))
        assertEquals(InvalidReason.UNRECOGNIZED, reason("garbage"))
        assertEquals(InvalidReason.UNRECOGNIZED, reason("OVNSAVE x\n"))
        assertEquals(InvalidReason.UNRECOGNIZED, reason("OVNSAVE 1\ncrc32=00000000\n"))
    }

    @Test
    fun newerFormatIsReportedNotParsed() {
        val result = SaveFormat.decode("OVNSAVE 9\nwhatever\n")
        assertIs<DecodeResult.Invalid>(result)
        assertEquals(InvalidReason.NEWER_FORMAT, result.reason)
        assertEquals("9", result.detail)
    }

    @Test
    fun unknownKeysAreIgnoredWhenChecksumIsValid() {
        val base = SaveFormat.encode(full).substringBefore("crc32=")
        val extended = base + "future_field=42\n"
        val crc = java.util.zip.CRC32().also { it.update(extended.toByteArray()) }.value
        val text = extended + "crc32=%08X\n".format(crc)
        assertEquals(full, ok(text).data)
    }

    @Test
    fun invalidFieldValuesAreCorrupted() {
        fun withField(field: String, value: String): String {
            val body = SaveFormat.encode(full).substringBefore("crc32=")
                .lines().joinToString("\n") { if (it.startsWith("$field=")) "$field=$value" else it }
            val crc = java.util.zip.CRC32().also { it.update(body.toByteArray()) }.value
            return body + "crc32=%08X\n".format(crc)
        }
        assertEquals(InvalidReason.CORRUPTED, reason(withField("scene", "bad scene")))
        assertEquals(InvalidReason.CORRUPTED, reason(withField("index", "-1")))
        assertEquals(InvalidReason.CORRUPTED, reason(withField("flags", "a,,b")))
        assertEquals(InvalidReason.CORRUPTED, reason(withField("params", "x:notnumber")))
        assertEquals(InvalidReason.CORRUPTED, reason(withField("params", "novalue")))
        assertEquals(InvalidReason.CORRUPTED, reason(withField("inventory", "bad item")))
        assertEquals(InvalidReason.CORRUPTED, reason(withField("location", "bad loc")))
        assertEquals(InvalidReason.CORRUPTED, reason(withField("saved", "-5")))
    }

    @Test
    fun legacyV1IsMigrated() {
        val legacy = LegacySaveV1.encode(GameState("start", 2, setOf("b", "a")))
        assertEquals("v1|start|2|a,b", legacy)
        val result = ok(legacy)
        assertEquals(1, result.migratedFromVersion)
        assertEquals(GameState("start", 2, setOf("a", "b")), result.data.state)
        assertEquals(null, result.data.scenarioId)
        assertEquals(null, result.data.scenarioVersion)
    }

    @Test
    fun brokenLegacyIsCorrupted() {
        assertEquals(InvalidReason.CORRUPTED, reason("v1|bad scene|0|"))
        assertEquals(InvalidReason.CORRUPTED, reason("v1|start|x|"))
    }
}
