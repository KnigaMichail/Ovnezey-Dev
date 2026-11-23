package ru.uralgamebuilding.ovnezey.engine

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileSaveStoreTest {
    private val dir: File = Files.createTempDirectory("ovn-saves").toFile()
    private val store = FileSaveStore(File(dir, "nested/saves"))

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun missingKeyReadsAsNull() {
        assertNull(store.read("slot1"))
    }

    @Test
    fun writeThenReadRoundTripsUnicode() {
        store.write("slot1", "Привет, мир\nline2")
        assertEquals("Привет, мир\nline2", store.read("slot1"))
    }

    @Test
    fun overwriteReplacesContentAndLeavesNoTemporaryFile() {
        store.write("slot1", "one")
        store.write("slot1", "two")
        assertEquals("two", store.read("slot1"))
        val names = File(dir, "nested/saves").list()!!.toSet()
        assertEquals(setOf("slot1.ovnsave"), names)
    }

    @Test
    fun staleTemporaryFileDoesNotAffectReads() {
        store.write("slot1", "good")
        File(dir, "nested/saves/slot1.tmp").writeText("half-written garbage")
        assertEquals("good", store.read("slot1"))
        store.write("slot1", "newer")
        assertEquals("newer", store.read("slot1"))
    }

    @Test
    fun deleteRemovesOnlyThatKey() {
        store.write("a", "1")
        store.write("b", "2")
        store.delete("a")
        assertNull(store.read("a"))
        assertEquals("2", store.read("b"))
        store.delete("missing")
    }

    @Test
    fun unsafeKeysAreRejected() {
        assertFailsWith<IllegalArgumentException> { store.write("../escape", "x") }
        assertFailsWith<IllegalArgumentException> { store.read("a/b") }
        assertTrue(File(dir, "escape.ovnsave").exists().not())
    }

    @Test
    fun worksTogetherWithSaveSlots() {
        val story = ScriptParser.parse("@scenario demo 1\n@scene start\n> A\n@end")
        val slots = SaveSlots(store)
        slots.saveManual(4, StoryRunner(story).state, story)
        slots.saveAuto(StoryRunner(story).state, story)
        assertTrue(slots.load("slot4", story) is LoadResult.Loaded)
        assertTrue(slots.load("auto", story) is LoadResult.Loaded)
    }
}
