package ru.uralgamebuilding.ovnezey.engine

import kotlin.math.max
import kotlin.math.min

/**
 * Шкала быстрых тапов («Очнуться»): каждый тап поднимает прогресс на 1/[needed], со временем он спадает
 * со скоростью [decayPerSecond]. Завершение определяется в момент тапа, а не при следующей перерисовке:
 * иначе спад между тапом и проверкой не даёт шкале дойти до конца.
 */
class TapMeter(private val needed: Int, private val decayPerSecond: Float) {
    init {
        require(needed >= 1) { "нужен хотя бы один тап" }
    }

    var progress = 0f
        private set

    var completed = false
        private set

    fun tap() {
        if (completed) return
        progress = min(1f, progress + 1f / needed)
        if (progress >= 1f - EPSILON) {
            progress = 1f
            completed = true
        }
    }

    fun update(deltaSeconds: Float) {
        if (completed) return
        progress = max(0f, progress - deltaSeconds * decayPerSecond)
    }

    private companion object {
        const val EPSILON = 1e-4f
    }
}
