package ru.uralgamebuilding.ovnezey.engine

/** Хранилище текстовых записей по ключу. Запись должна быть атомарной: после сбоя читается либо старое, либо новое содержимое. */
interface SaveStore {
    fun read(key: String): String?
    fun write(key: String, text: String)
    fun delete(key: String)
}

class InMemorySaveStore : SaveStore {
    val data = LinkedHashMap<String, String>()
    override fun read(key: String): String? = data[key]
    override fun write(key: String, text: String) { data[key] = text }
    override fun delete(key: String) { data.remove(key) }
}

enum class IncompatibleReason { SCENARIO_MISMATCH, VERSION_MISMATCH, INVALID_POSITION }

sealed interface LoadResult {
    data class Loaded(val runner: StoryRunner, val migrated: Boolean) : LoadResult
    data object Empty : LoadResult
    data class Unreadable(val reason: InvalidReason, val detail: String? = null) : LoadResult
    data class Incompatible(val reason: IncompatibleReason) : LoadResult
}

sealed interface SlotStatus {
    data object Empty : SlotStatus

    /** [location] это идентификатор локации, название берётся из сюжета в нужном языке. */
    data class Filled(
        val savedAtMillis: Long,
        val location: String?,
        val scenarioId: String?,
        val scenarioVersion: Int?,
        val migrated: Boolean,
    ) : SlotStatus

    data class Broken(val reason: InvalidReason) : SlotStatus
}

data class SlotSummary(val key: String, val status: SlotStatus)

/** Пять ручных слотов и одно автосохранение. Автосохранение никогда не пишет в ручные слоты. */
class SaveSlots(private val store: SaveStore, private val clock: () -> Long = { System.currentTimeMillis() }) {

    fun saveManual(slot: Int, state: GameState, story: Story) = write(manualKey(slot), state, story)

    fun saveAuto(state: GameState, story: Story) = write(AUTO_KEY, state, story)

    fun summary(key: String): SlotSummary {
        requireValidKey(key)
        val text = store.read(key) ?: return SlotSummary(key, SlotStatus.Empty)
        return SlotSummary(
            key,
            when (val result = SaveFormat.decode(text)) {
                is DecodeResult.Ok -> SlotStatus.Filled(
                    result.data.savedAtMillis,
                    result.data.state.location,
                    result.data.scenarioId,
                    result.data.scenarioVersion,
                    result.migratedFromVersion != null,
                )
                is DecodeResult.Invalid ->
                    if (result.reason == InvalidReason.EMPTY) SlotStatus.Empty else SlotStatus.Broken(result.reason)
            },
        )
    }

    fun manualSummaries(): List<SlotSummary> = (1..MANUAL_SLOTS).map { summary(manualKey(it)) }

    /** Загружает запись. Повреждённая или несовместимая запись не удаляется и не заменяется. */
    fun load(key: String, story: Story, language: Language = Language.DEFAULT): LoadResult {
        requireValidKey(key)
        val text = store.read(key) ?: return LoadResult.Empty
        return when (val result = SaveFormat.decode(text)) {
            is DecodeResult.Ok -> StoryRunner.restore(story, result.data, result.migratedFromVersion != null, language)
            is DecodeResult.Invalid ->
                if (result.reason == InvalidReason.EMPTY) LoadResult.Empty else LoadResult.Unreadable(result.reason, result.detail)
        }
    }

    private fun write(key: String, state: GameState, story: Story) {
        val data = SaveData(state, story.scenarioId, story.scenarioVersion, clock())
        store.write(key, SaveFormat.encode(data))
    }

    private fun requireValidKey(key: String) {
        require(key == AUTO_KEY || (1..MANUAL_SLOTS).any { manualKey(it) == key }) { "неизвестный слот: $key" }
    }

    companion object {
        const val MANUAL_SLOTS = 5
        const val AUTO_KEY = "auto"

        fun manualKey(slot: Int): String {
            require(slot in 1..MANUAL_SLOTS) { "номер слота должен быть от 1 до $MANUAL_SLOTS, получено $slot" }
            return "slot$slot"
        }
    }
}
