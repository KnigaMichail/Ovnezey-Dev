package ru.uralgamebuilding.ovnezey.engine

enum class CompareOp(val symbol: String) { GE(">="), LE("<="), EQ("=="), NE("!="), GT(">"), LT("<") }

/** Условие показа варианта или перехода. Синтаксис в .ovn: `flag`, `!flag`, `trust>=2`, `has:key`, `!has:key`, части через `&`. */
sealed interface Condition {
    data class Flag(val name: String, val negate: Boolean = false) : Condition
    data class Param(val name: String, val op: CompareOp, val value: Int) : Condition
    data class HasItem(val item: String, val negate: Boolean = false) : Condition
    data class All(val parts: List<Condition>) : Condition
}

fun Condition.test(state: GameState): Boolean = when (this) {
    is Condition.Flag -> (name in state.flags) != negate
    is Condition.HasItem -> (item in state.inventory) != negate
    is Condition.Param -> {
        val v = state.param(name)
        when (op) {
            CompareOp.GE -> v >= value
            CompareOp.LE -> v <= value
            CompareOp.EQ -> v == value
            CompareOp.NE -> v != value
            CompareOp.GT -> v > value
            CompareOp.LT -> v < value
        }
    }
    is Condition.All -> parts.all { it.test(state) }
}

object ConditionParser {
    private const val ID = "[A-Za-z0-9_]+"
    private val ITEM = Regex("^(!?)has:($ID)$")
    private val PARAM = Regex("^($ID)\\s*(>=|<=|==|!=|>|<)\\s*(-?\\d+)$")
    private val FLAG = Regex("^(!?)($ID)$")

    /** Возвращает null, если текст не является корректным условием. */
    fun parse(text: String): Condition? {
        val parts = text.split("&").map { it.trim() }
        if (parts.any { it.isEmpty() }) return null
        val parsed = parts.map { parseOne(it) ?: return null }
        return if (parsed.size == 1) parsed[0] else Condition.All(parsed)
    }

    private fun parseOne(text: String): Condition? {
        ITEM.matchEntire(text)?.let { return Condition.HasItem(it.groupValues[2], it.groupValues[1] == "!") }
        PARAM.matchEntire(text)?.let { m ->
            val value = m.groupValues[3].toIntOrNull() ?: return null
            val op = CompareOp.entries.first { it.symbol == m.groupValues[2] }
            return Condition.Param(m.groupValues[1], op, value)
        }
        FLAG.matchEntire(text)?.let { return Condition.Flag(it.groupValues[2], it.groupValues[1] == "!") }
        return null
    }
}
