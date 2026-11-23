package ru.uralgamebuilding.ovnezey

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import ru.uralgamebuilding.ovnezey.engine.LoadResult
import ru.uralgamebuilding.ovnezey.engine.SaveSlots
import ru.uralgamebuilding.ovnezey.engine.SlotStatus

/** Главное меню: Продолжить, Новая игра, Загрузить, Настройки. */
class MenuActivity : BaseActivity() {
    private lateinit var continueButton: Button
    private lateinit var newButton: Button
    private lateinit var loadButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { setBackgroundColor(PixelUi.BG) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val width = (resources.displayMetrics.widthPixels * 0.5f).toInt()

        column.addView(PixelUi.label(this, getString(R.string.app_name), sizeSteps = 2, color = PixelUi.ACCENT), PixelUi.matchWidth(this, 4))
        continueButton = PixelUi.button(this, getString(R.string.menu_continue)) { continueGame() }
        newButton = PixelUi.button(this, getString(R.string.menu_new_game)) { askNewGame() }
        loadButton = PixelUi.button(this, getString(R.string.menu_load)) {
            startActivity(Intent(this, SlotsActivity::class.java).putExtra(SlotsActivity.EXTRA_MODE, SlotsActivity.MODE_LOAD))
        }
        val settingsButton = PixelUi.button(this, getString(R.string.menu_settings)) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        listOf(continueButton, newButton, loadButton, settingsButton).forEach { column.addView(it, PixelUi.matchWidth(this)) }

        root.addView(column, FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        setContentView(root)

        services.storyError?.let { error ->
            column.addView(PixelUi.label(this, getString(R.string.story_error, error), color = PixelUi.MUTED), PixelUi.matchWidth(this))
        }
    }

    override fun onResume() {
        super.onResume()
        val playable = services.story != null
        val hasAutosave = playable && services.slots.summary(SaveSlots.AUTO_KEY).status !is SlotStatus.Empty
        PixelUi.setEnabledLook(continueButton, hasAutosave)
        PixelUi.setEnabledLook(newButton, playable)
        PixelUi.setEnabledLook(loadButton, playable)
    }

    override fun onBackPressed() {
        if (dismissOverlay()) return
        finish()
    }

    private fun continueGame() {
        when (val result = services.load(SaveSlots.AUTO_KEY)) {
            is LoadResult.Loaded -> {
                services.runner = result.runner
                startGame()
            }
            LoadResult.Empty -> Unit
            else -> showError(result.errorText(this))
        }
    }

    private fun askNewGame() {
        if (services.slots.summary(SaveSlots.AUTO_KEY).status is SlotStatus.Empty) {
            newGame()
            return
        }
        showDialog(
            null,
            getString(R.string.new_game_confirm),
            listOf(DialogAction(getString(R.string.yes)) { newGame() }, DialogAction(getString(R.string.no)) {}),
        )
    }

    private fun newGame() {
        try {
            services.startNew()
        } catch (e: java.io.IOException) {
            showError(getString(R.string.autosave_failed))
            return
        }
        startGame()
    }

    private fun startGame() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    private fun showError(text: String?) {
        showDialog(getString(R.string.error_title), text, listOf(DialogAction(getString(R.string.ok)) {}))
    }
}
