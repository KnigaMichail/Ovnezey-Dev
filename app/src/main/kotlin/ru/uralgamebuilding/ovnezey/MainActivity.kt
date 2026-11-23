package ru.uralgamebuilding.ovnezey

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import ru.uralgamebuilding.ovnezey.engine.GameState
import ru.uralgamebuilding.ovnezey.engine.LoadResult
import ru.uralgamebuilding.ovnezey.engine.SaveSlots
import ru.uralgamebuilding.ovnezey.engine.Side
import ru.uralgamebuilding.ovnezey.engine.Step
import ru.uralgamebuilding.ovnezey.engine.StoryRunner
import java.io.IOException

/**
 * Игровой экран: рассказчик и диалоги, персонажи слева и справа, выбор кнопками.
 * Вся логика сюжета в модуле engine, здесь только отображение и вызовы сохранения.
 * Партию задаёт меню или экран слотов через [Services.runner].
 */
class MainActivity : BaseActivity() {
    private var runner: StoryRunner? = null
    private lateinit var speakerView: TextView
    private lateinit var textView: TextView
    private lateinit var choicesView: LinearLayout
    private lateinit var portraitLeft: ImageView
    private lateinit var portraitRight: ImageView
    private lateinit var menuButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_novel)
        speakerView = findViewById(R.id.speaker)
        textView = findViewById(R.id.text)
        choicesView = findViewById(R.id.choices)
        portraitLeft = findViewById(R.id.portraitLeft)
        portraitRight = findViewById(R.id.portraitRight)

        PixelUi.styleText(this, speakerView, color = PixelUi.ACCENT)
        PixelUi.styleText(this, textView)
        val m = PixelUi.metrics(this)
        menuButton = PixelUi.button(this, getString(R.string.game_menu)) { showPauseMenu() }
        findViewById<android.widget.FrameLayout>(R.id.root).addView(
            menuButton,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP or android.view.Gravity.END,
            ).apply { setMargins(m.pad, m.pad, m.pad, m.pad) },
        )
        findViewById<View>(R.id.root).setOnClickListener { onTap() }

        val story = services.story
        if (story == null) {
            speakerView.text = ""
            textView.text = getString(R.string.story_error, services.storyError)
            return
        }
        runner = services.runner ?: restoreAutosave()
        if (runner == null) {
            finish()
            return
        }
        services.runner = runner
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        runner = services.runner
        portraitLeft.visibility = View.INVISIBLE
        portraitRight.visibility = View.INVISIBLE
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onPause() {
        super.onPause()
        autosave()
    }

    override fun onBackPressed() {
        if (dismissOverlay()) return
        showPauseMenu()
    }

    /** Процесс мог быть завершён системой: партия берётся из автосохранения. */
    private fun restoreAutosave(): StoryRunner? {
        val result = services.load(SaveSlots.AUTO_KEY)
        return (result as? LoadResult.Loaded)?.runner
    }

    private fun onTap() {
        val r = runner ?: return
        if (hasOverlay() || r.step !is Step.Say) return
        val before = r.state
        r.advance()
        afterChange(before)
    }

    private fun afterChange(before: GameState) {
        val r = runner ?: return
        if (r.state.isSignificantChangeFrom(before)) autosave()
        render()
    }

    private fun autosave() {
        val r = runner ?: return
        val story = services.story ?: return
        try {
            services.slots.saveAuto(r.state, story)
        } catch (e: IOException) {
            Toast.makeText(this, R.string.autosave_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun render() {
        val r = runner ?: return
        r.language = services.settings.language
        choicesView.removeAllViews()
        choicesView.visibility = View.GONE
        when (val step = r.step) {
            is Step.Say -> showSay(step)
            is Step.Choose -> showChoices(step)
            Step.End -> showEnd()
        }
    }

    private fun showSay(step: Step.Say) {
        speakerView.text = step.speaker.orEmpty()
        textView.text = step.text
        val active = when (step.side) {
            Side.LEFT -> portraitLeft
            Side.RIGHT -> portraitRight
            Side.NONE -> null
        }
        val id = step.speakerId
        if (active != null && id != null) {
            val drawable = loadPortrait(id)
            if (drawable != null) {
                active.setImageDrawable(drawable)
                active.visibility = View.VISIBLE
            }
        }
        // Говорящий виден полностью, второй персонаж приглушён; в речи рассказчика приглушены оба.
        portraitLeft.alpha = if (active === portraitLeft) 1f else 0.5f
        portraitRight.alpha = if (active === portraitRight) 1f else 0.5f
    }

    private fun showChoices(step: Step.Choose) {
        speakerView.text = ""
        textView.text = ""
        choicesView.visibility = View.VISIBLE
        step.options.forEachIndexed { index, option ->
            choicesView.addView(
                PixelUi.button(this, option.text) {
                    val r = runner ?: return@button
                    val before = r.state
                    r.choose(index)
                    afterChange(before)
                },
                PixelUi.matchWidth(this),
            )
        }
    }

    private fun showEnd() {
        speakerView.text = ""
        textView.text = ""
        choicesView.visibility = View.VISIBLE
        choicesView.addView(
            PixelUi.button(this, getString(R.string.restart)) {
                try {
                    runner = services.startNew()
                } catch (e: IOException) {
                    Toast.makeText(this, R.string.autosave_failed, Toast.LENGTH_SHORT).show()
                    return@button
                }
                portraitLeft.visibility = View.INVISIBLE
                portraitRight.visibility = View.INVISIBLE
                render()
            },
            PixelUi.matchWidth(this),
        )
    }

    private fun showPauseMenu() {
        if (hasOverlay()) return
        showDialog(
            getString(R.string.game_menu),
            null,
            listOf(
                DialogAction(getString(R.string.game_resume)) {},
                DialogAction(getString(R.string.game_save)) { openSlots(SlotsActivity.MODE_SAVE) },
                DialogAction(getString(R.string.game_load)) { openSlots(SlotsActivity.MODE_LOAD) },
                DialogAction(getString(R.string.menu_settings)) { startActivity(Intent(this, SettingsActivity::class.java)) },
                DialogAction(getString(R.string.game_to_main_menu)) { finish() },
            ),
        )
    }

    private fun openSlots(mode: String) {
        startActivity(Intent(this, SlotsActivity::class.java).putExtra(SlotsActivity.EXTRA_MODE, mode))
    }

    /** Портрет персонажа ищется в assets/portraits/<идентификатор>.png; пока файла нет, показывается только имя. */
    private fun loadPortrait(id: String): BitmapDrawable? = try {
        assets.open("portraits/$id.png").use { stream ->
            BitmapDrawable(resources, BitmapFactory.decodeStream(stream)).apply {
                // пиксель-арт масштабируется без сглаживания
                setFilterBitmap(false)
            }
        }
    } catch (e: IOException) {
        null
    }
}
