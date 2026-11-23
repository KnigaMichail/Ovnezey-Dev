package ru.uralgamebuilding.ovnezey

import android.content.Context
import ru.uralgamebuilding.ovnezey.engine.IncompatibleReason
import ru.uralgamebuilding.ovnezey.engine.InvalidReason
import ru.uralgamebuilding.ovnezey.engine.LoadResult

/** Текст ошибки для игрока или null, если загрузка прошла успешно или слот пуст. */
fun LoadResult.errorText(context: Context): String? = when (this) {
    is LoadResult.Loaded, LoadResult.Empty -> null
    is LoadResult.Unreadable ->
        if (reason == InvalidReason.NEWER_FORMAT) context.getString(R.string.error_newer_format)
        else context.getString(R.string.error_unreadable)
    is LoadResult.Incompatible -> when (reason) {
        IncompatibleReason.VERSION_MISMATCH -> context.getString(R.string.error_version)
        IncompatibleReason.SCENARIO_MISMATCH -> context.getString(R.string.error_scenario)
        IncompatibleReason.INVALID_POSITION -> context.getString(R.string.error_position)
    }
}
