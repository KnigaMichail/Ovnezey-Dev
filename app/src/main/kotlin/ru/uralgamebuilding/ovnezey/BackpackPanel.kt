package ru.uralgamebuilding.ovnezey

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import ru.uralgamebuilding.ovnezey.engine.ItemView

/** Содержимое окна рюкзака: сетка иконок, название и описание выбранного предмета, кнопка «Использовать». */
object BackpackPanel {
    fun build(
        activity: Activity,
        items: List<ItemView>,
        canUse: Boolean,
        onUse: (ItemView) -> Unit,
    ): View {
        val m = PixelUi.metrics(activity)
        val cellSize = m.scale * 24
        val iconSize = m.scale * 16
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val grid = GridLayout(activity).apply { columnCount = COLUMNS }
        val name = PixelUi.label(activity, "", color = PixelUi.ACCENT)
        val description = PixelUi.label(activity, "", color = PixelUi.MUTED)
        var selected: ItemView? = null
        val cells = ArrayList<Pair<ItemView, View>>()

        val useButton = PixelUi.button(activity, activity.getString(R.string.item_use)) {
            selected?.let(onUse)
        }

        fun select(item: ItemView) {
            selected = item
            name.text = item.name
            description.text = item.description
            for ((cellItem, cell) in cells) {
                val active = cellItem.id == item.id
                cell.background = PixelUi.frame(
                    if (active) PixelUi.BUTTON_PRESSED else PixelUi.BUTTON,
                    if (active) PixelUi.ACCENT else PixelUi.BORDER,
                    m.stroke,
                )
            }
            PixelUi.setEnabledLook(useButton, canUse)
        }

        for (item in items) {
            val cell = FrameLayout(activity)
            val icon = AssetImages.item(activity, item.id)
            if (icon != null) {
                cell.addView(
                    ImageView(activity).apply {
                        setImageDrawable(icon)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        contentDescription = item.name
                    },
                    FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER),
                )
            } else {
                // Иконки ещё нет: показываем первые буквы названия.
                cell.addView(
                    PixelUi.label(activity, item.name.take(2).uppercase()).apply { gravity = Gravity.CENTER },
                    FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
                )
            }
            cell.setOnClickListener { select(item) }
            cells += item to cell
            grid.addView(cell, GridLayout.LayoutParams().apply {
                width = cellSize
                height = cellSize
                setMargins(m.stroke, m.stroke, m.stroke, m.stroke)
            })
        }

        column.addView(grid, PixelUi.matchWidth(activity, 2))
        column.addView(name, PixelUi.matchWidth(activity))
        column.addView(description, PixelUi.matchWidth(activity, 2))
        column.addView(useButton, PixelUi.matchWidth(activity))
        items.firstOrNull()?.let { select(it) }
        return column
    }

    private const val COLUMNS = 4
}
