package ru.uralgamebuilding.ovnezey

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import java.io.IOException

/** Загрузка пиксельных картинок из assets без масштабирования по плотности экрана и без сглаживания. */
object AssetImages {
    fun bitmap(context: Context, path: String): Bitmap? = try {
        context.assets.open(path).use { stream ->
            BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply { inScaled = false })
        }
    } catch (e: IOException) {
        null
    }

    fun drawable(context: Context, path: String): BitmapDrawable? = bitmap(context, path)?.let {
        BitmapDrawable(context.resources, it).apply { setFilterBitmap(false) }
    }

    fun portrait(context: Context, characterId: String) = drawable(context, "portraits/$characterId.png")

    fun location(context: Context, locationId: String) = bitmap(context, "locations/$locationId.png")

    fun item(context: Context, itemId: String) = drawable(context, "items/$itemId.png")
}
