package ru.uralgamebuilding.ovnezey.engine

import java.util.zip.CRC32

/** Содержимое сохранения. [scenarioId] и [scenarioVersion] равны null для сохранений старого формата v1. */
data class SaveData(
    val state: GameState,
    val scenarioId: String?,
    val scenarioVersion: Int?,
    val savedAtMillis: Long = 0L,
)

enum class InvalidReason { EMPTY, UNRECOGNIZED, CORRUPTED, NEWER_FORMAT }

sealed interface DecodeResult {
    /** [migratedFromVersion] не null, если сохранение было в старом формате и преобразовано. */
    data class Ok(val data: SaveData, val migratedFromVersion: Int? = null) : DecodeResult
    data class Invalid(val reason: InvalidReason, val detail: String? = null) : DecodeResult
}

/**
 * Формат сохранения v2: текст в UTF-8, строки `ключ=значение`, последняя строка `crc32=` проверяет целостность.
 * Неизвестные ключи читаются и игнорируются, поэтому в v2 можно добавлять необязательные поля.
 */
object SaveFormat {
    const val CURRENT_VERSION = 2
    private const val HEADER = "OVNSAVE"
    private val ID = Regex("[A-Za-z0-9_]+")

    fun encode(data: SaveData): String {
        val s = data.state
        val body = buildString {
            append("$HEADER $CURRENT_VERSION\n")
            append("scenario=${data.scenarioId.orEmpty()}\n")
            append("version=${data.scenarioVersion ?: 0}\n")
            append("saved=${data.savedAtMillis}\n")
            append("scene=${s.scene}\n")
            append("index=${s.index}\n")
            append("location=${s.location.orEmpty()}\n")
            append("flags=${s.flags.sorted().joinToString(",")}\n")
            append("params=${s.params.toSortedMap().entries.joinToString(",") { "${it.key}:${it.value}" }}\n")
            append("inventory=${s.inventory.sorted().joinToString(",")}\n")
        }
        return body + "crc32=${crc(body)}\n"
    }

    fun decode(text: String?): DecodeResult {
        if (text == null || text.isBlank()) return DecodeResult.Invalid(InvalidReason.EMPTY)
        val trimmed = text.removePrefix("\uFEFF")
        if (trimmed.startsWith("v1|")) return decodeLegacy(trimmed.trim())
        if (!trimmed.startsWith("$HEADER ")) return DecodeResult.Invalid(InvalidReason.UNRECOGNIZED)
        return decodeV2(trimmed)
    }

    private fun decodeLegacy(text: String): DecodeResult {
        val state = GameState.decode(text) ?: return DecodeResult.Invalid(InvalidReason.CORRUPTED, "v1")
        return DecodeResult.Ok(SaveData(state, null, null, 0L), migratedFromVersion = 1)
    }

    private fun decodeV2(text: String): DecodeResult {
        val lines = text.replace("\r\n", "\n").split("\n").dropLastWhile { it.isEmpty() }
        val version = lines.first().removePrefix("$HEADER ").trim().toIntOrNull()
            ?: return DecodeResult.Invalid(InvalidReason.UNRECOGNIZED)
        if (version > CURRENT_VERSION) return DecodeResult.Invalid(InvalidReason.NEWER_FORMAT, version.toString())
        if (version < CURRENT_VERSION) return DecodeResult.Invalid(InvalidReason.UNRECOGNIZED)

        val crcLine = lines.last()
        if (!crcLine.startsWith("crc32=")) return corrupted("нет контрольной суммы")
        val body = lines.dropLast(1).joinToString("") { it + "\n" }
        if (crcLine.removePrefix("crc32=") != crc(body)) return corrupted("контрольная сумма не совпала")

        val fields = HashMap<String, String>()
        for (line in lines.drop(1).dropLast(1)) {
            val eq = line.indexOf('=')
            if (eq <= 0) return corrupted("строка без ключа: $line")
            fields[line.substring(0, eq)] = line.substring(eq + 1)
        }
        return try {
            val scenario = fields["scenario"]?.takeIf { ID.matches(it) } ?: return corrupted("scenario")
            val scenarioVersion = fields["version"]?.toIntOrNull()?.takeIf { it >= 0 } ?: return corrupted("version")
            val saved = (fields["saved"] ?: "0").toLongOrNull()?.takeIf { it >= 0 } ?: return corrupted("saved")
            val scene = fields["scene"]?.takeIf { ID.matches(it) } ?: return corrupted("scene")
            val index = fields["index"]?.toIntOrNull()?.takeIf { it >= 0 } ?: return corrupted("index")
            val location = (fields["location"] ?: "").takeIf { it.isEmpty() || ID.matches(it) } ?: return corrupted("location")
            val flags = parseIds(fields["flags"]) ?: return corrupted("flags")
            val inventory = parseIds(fields["inventory"]) ?: return corrupted("inventory")
            val params = parseParams(fields["params"]) ?: return corrupted("params")
            DecodeResult.Ok(
                SaveData(
                    GameState(scene, index, flags, params, inventory, location.ifEmpty { null }),
                    scenario,
                    scenarioVersion,
                    saved,
                ),
            )
        } catch (e: NumberFormatException) {
            corrupted("число")
        }
    }

    private fun parseIds(text: String?): Set<String>? {
        if (text.isNullOrEmpty()) return emptySet()
        val parts = text.split(",")
        return if (parts.all { ID.matches(it) }) parts.toSet() else null
    }

    private fun parseParams(text: String?): Map<String, Int>? {
        if (text.isNullOrEmpty()) return emptyMap()
        val result = LinkedHashMap<String, Int>()
        for (pair in text.split(",")) {
            val colon = pair.indexOf(':')
            if (colon <= 0) return null
            val name = pair.substring(0, colon)
            val value = pair.substring(colon + 1).toIntOrNull() ?: return null
            if (!ID.matches(name)) return null
            result[name] = value
        }
        return result
    }

    private fun corrupted(detail: String) = DecodeResult.Invalid(InvalidReason.CORRUPTED, detail)

    private fun crc(body: String): String {
        val crc = CRC32()
        crc.update(body.toByteArray(Charsets.UTF_8))
        return "%08X".format(crc.value)
    }
}

/** Запись в старом формате v1. Используется только в тестах миграции. */
object LegacySaveV1 {
    fun encode(state: GameState): String = "v1|${state.scene}|${state.index}|${state.flags.sorted().joinToString(",")}"
}
