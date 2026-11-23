package ru.uralgamebuilding.ovnezey

import android.content.Context
import ru.uralgamebuilding.ovnezey.engine.FileSaveStore
import ru.uralgamebuilding.ovnezey.engine.LoadResult
import ru.uralgamebuilding.ovnezey.engine.SaveSlots
import ru.uralgamebuilding.ovnezey.engine.ScriptParser
import ru.uralgamebuilding.ovnezey.engine.Story
import ru.uralgamebuilding.ovnezey.engine.StoryParseException
import ru.uralgamebuilding.ovnezey.engine.StoryRunner
import java.io.File

/** Общие объекты приложения: сюжет, слоты сохранений, настройки и текущая партия. */
class Services private constructor(context: Context) {
    val settings = AppSettings(context)
    val slots = SaveSlots(FileSaveStore(File(context.filesDir, "saves")))

    /** Разобранный сюжет или null, если сценарий содержит ошибку (тогда причина в [storyError]). */
    val story: Story?
    val storyError: String?

    /** Текущая партия. Задаётся меню и экраном слотов, читается игровым экраном. */
    @Volatile
    var runner: StoryRunner? = null

    init {
        var parsed: Story? = null
        var error: String? = null
        try {
            parsed = context.assets.open(STORY_ASSET).bufferedReader(Charsets.UTF_8).use { ScriptParser.parse(it.readText()) }
        } catch (e: StoryParseException) {
            error = e.message
        } catch (e: java.io.IOException) {
            error = e.message
        }
        story = parsed
        storyError = error
    }

    /** Начинает новую партию и сразу записывает автосохранение. */
    fun startNew(): StoryRunner {
        val s = checkNotNull(story) { "сюжет не загружен" }
        val r = StoryRunner(s, language = settings.language)
        runner = r
        slots.saveAuto(r.state, s)
        return r
    }

    fun load(key: String): LoadResult {
        val s = checkNotNull(story) { "сюжет не загружен" }
        return slots.load(key, s, settings.language)
    }

    companion object {
        const val STORY_ASSET = "story/demo.ovn"

        @Volatile
        private var instance: Services? = null

        fun get(context: Context): Services = instance ?: synchronized(this) {
            instance ?: Services(context.applicationContext ?: context).also { instance = it }
        }
    }
}
