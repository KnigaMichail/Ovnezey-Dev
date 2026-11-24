package ru.uralgamebuilding.ovnezey

import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout

/** Настройки. Выбор языка меняет и интерфейс, и текст в игре. */
class SettingsActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { setBackgroundColor(PixelUi.BG) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(PixelUi.label(this, getString(R.string.settings_title), sizeSteps = 2, color = PixelUi.ACCENT), PixelUi.matchWidth(this, 3))
        column.addView(PixelUi.label(this, getString(R.string.settings_language), color = PixelUi.MUTED), PixelUi.matchWidth(this))

        val current = services.settings.languageChoice
        val options = listOf(
            LanguageChoice.AUTO to R.string.language_auto,
            LanguageChoice.RU to R.string.language_ru,
            LanguageChoice.EN to R.string.language_en,
        )
        for ((choice, label) in options) {
            val text = (if (choice == current) "> " else "  ") + getString(label)
            column.addView(
                PixelUi.button(this, text) {
                    if (services.settings.languageChoice != choice) {
                        services.settings.languageChoice = choice
                        recreate()
                    }
                }.also { it.gravity = Gravity.START or Gravity.CENTER_VERTICAL },
                PixelUi.matchWidth(this),
            )
        }
        column.addView(PixelUi.spacing(this))
        column.addView(PixelUi.label(this, getString(R.string.settings_hotspots), color = PixelUi.MUTED), PixelUi.matchWidth(this))
        val debugOn = services.settings.debugHotspots
        column.addView(
            PixelUi.button(this, getString(if (debugOn) R.string.on else R.string.off)) {
                services.settings.debugHotspots = !debugOn
                recreate()
            }.also { it.gravity = Gravity.START or Gravity.CENTER_VERTICAL },
            PixelUi.matchWidth(this),
        )
        column.addView(PixelUi.spacing(this))
        column.addView(PixelUi.button(this, getString(R.string.back)) { finish() }, PixelUi.matchWidth(this))

        val width = (resources.displayMetrics.widthPixels * 0.55f).toInt()
        root.addView(column, FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        setContentView(root)
    }
}
