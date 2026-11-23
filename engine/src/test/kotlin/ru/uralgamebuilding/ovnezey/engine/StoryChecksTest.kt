package ru.uralgamebuilding.ovnezey.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StoryChecksTest {
    @Test
    fun reportsEveryTextWithoutTranslation() {
        val story = ScriptParser.parse(
            """
            @character a Имя
            @scene start
            @location l Место
            > Без перевода
            Имя: С переводом
            |en With translation
            @choice
            * Вариант -> start
            """.trimIndent()
        )
        val missing = StoryChecks.missingTranslations(story)
        assertEquals(4, missing.size)
        assertTrue(missing.any { it.contains("Без перевода") })
        assertTrue(missing.any { it.contains("вариант") })
        assertTrue(missing.any { it.contains("персонаж a") })
        assertTrue(missing.any { it.contains("локация l") })
    }

    @Test
    fun completeStoryHasNoMissingTranslations() {
        val story = ScriptParser.parse("@scene start\n> Привет\n|en Hello\n@end")
        assertTrue(StoryChecks.missingTranslations(story).isEmpty())
    }

    @Test
    fun bundledDemoStoryIsValidAndFullyTranslated() {
        val file = listOf("../app/src/main/assets/story/demo.ovn", "app/src/main/assets/story/demo.ovn")
            .map { File(it) }.firstOrNull { it.isFile }
        org.junit.Assume.assumeTrue("demo.ovn не найден относительно рабочей папки", file != null)
        val story = ScriptParser.parse(file!!.readText(Charsets.UTF_8))
        assertEquals(emptyList(), StoryChecks.missingTranslations(story))
    }
}
