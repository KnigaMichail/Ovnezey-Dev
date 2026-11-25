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
    fun reportsMissingTranslationsOfHotspotsAndItems() {
        val story = ScriptParser.parse(
            "@item a\nname: Имя\ndesc: Описание\n@scene start\n@explore\n* h 0 0 0.5 0.5 Область -> start",
        )
        val missing = StoryChecks.missingTranslations(story)
        assertTrue(missing.any { it.contains("область «Область»") })
        assertTrue(missing.any { it.contains("предмет a: название") })
        assertTrue(missing.any { it.contains("предмет a: описание") })
    }

    @Test
    fun findsUndeclaredItemsInUse() {
        val story = ScriptParser.parse(
            "@item known\nname: K\n@scene start\n@give known\n@give ghost\n@take phantom\n" +
                "@if has:spirit -> two\n> x\n@end\n@scene two\n@end",
        )
        assertEquals(setOf("ghost", "phantom", "spirit"), StoryChecks.undefinedItems(story))
    }

    @Test
    fun bundledDemoStoryIsValidAndFullyTranslated() {
        val file = listOf("src/test/resources/demo.ovn", "engine/src/test/resources/demo.ovn")
            .map { File(it) }.firstOrNull { it.isFile }
        org.junit.Assume.assumeTrue("demo.ovn не найден относительно рабочей папки", file != null)
        val story = ScriptParser.parse(file!!.readText(Charsets.UTF_8))
        assertEquals(emptyList(), StoryChecks.missingTranslations(story))
        assertEquals(emptySet(), StoryChecks.undefinedItems(story))
    }
}
