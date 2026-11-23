package ru.uralgamebuilding.ovnezey.engine

/**
 * Всё, что влияет на дальнейшее прохождение и сохраняется. Время суток, язык и настройки сюда не входят.
 * Отношения с персонажами хранятся как числовые параметры.
 */
data class GameState(
    val scene: String,
    val index: Int,
    val flags: Set<String> = emptySet(),
    val params: Map<String, Int> = emptyMap(),
    val inventory: Set<String> = emptySet(),
    val location: String? = null,
) {
    fun param(name: String): Int = params[name] ?: 0

    /** Значимое изменение для автосохранения: всё, кроме простого перехода к следующей реплике в той же сцене. */
    fun isSignificantChangeFrom(previous: GameState): Boolean =
        scene != previous.scene || flags != previous.flags || params != previous.params ||
            inventory != previous.inventory || location != previous.location

    companion object {
        private val ID = Regex("[A-Za-z0-9_]+")

        /** Разбор старого формата `v1|сцена|позиция|флаги`. Возвращает null для любой другой строки. */
        fun decode(text: String?): GameState? {
            if (text == null) return null
            val parts = text.split("|")
            if (parts.size != 4 || parts[0] != "v1") return null
            val scene = parts[1].takeIf { ID.matches(it) } ?: return null
            val index = parts[2].toIntOrNull()?.takeIf { it >= 0 } ?: return null
            val flags = if (parts[3].isEmpty()) emptySet() else parts[3].split(",").toSet()
            if (!flags.all { ID.matches(it) }) return null
            return GameState(scene, index, flags)
        }
    }
}
