package ru.uralgamebuilding.ovnezey.engine

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Хранение в файлах. Запись идёт во временный файл с принудительной синхронизацией, затем атомарная замена:
 * при сбое питания или завершении процесса остаётся старый файл целиком или новый целиком.
 */
class FileSaveStore(private val dir: File) : SaveStore {
    init {
        dir.mkdirs()
    }

    override fun read(key: String): String? {
        val file = fileFor(key)
        return if (file.isFile) file.readText(Charsets.UTF_8) else null
    }

    override fun write(key: String, text: String) {
        val target = fileFor(key)
        val tmp = File(dir, "$key.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    override fun delete(key: String) {
        fileFor(key).delete()
    }

    private fun fileFor(key: String): File {
        require(KEY.matches(key)) { "недопустимый ключ: $key" }
        return File(dir, "$key.ovnsave")
    }

    private companion object {
        val KEY = Regex("[A-Za-z0-9_]+")
    }
}
