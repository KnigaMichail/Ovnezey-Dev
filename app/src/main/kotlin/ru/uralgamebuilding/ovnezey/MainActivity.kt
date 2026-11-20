package ru.uralgamebuilding.ovnezey

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import ru.uralgamebuilding.ovnezey.engine.ScriptParser
import ru.uralgamebuilding.ovnezey.engine.Side
import ru.uralgamebuilding.ovnezey.engine.Step
import ru.uralgamebuilding.ovnezey.engine.Story
import ru.uralgamebuilding.ovnezey.engine.StoryParseException
import ru.uralgamebuilding.ovnezey.engine.StoryRunner
import java.io.IOException

/**
 * Единственный экран: рассказчик и диалоги, персонажи слева и справа, выбор кнопками.
 * Вся логика сюжета в модуле engine, здесь только отображение и сохранение.
 */
class MainActivity : Activity() {
    private lateinit var runner: StoryRunner
    private lateinit var speakerView: TextView
    private lateinit var textView: TextView
    private lateinit var choicesView: LinearLayout
    private lateinit var portraitLeft: ImageView
    private lateinit var portraitRight: ImageView
    private lateinit var story: Story

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_novel)
        speakerView = findViewById(R.id.speaker)
        textView = findViewById(R.id.text)
        choicesView = findViewById(R.id.choices)
        portraitLeft = findViewById(R.id.portraitLeft)
        portraitRight = findViewById(R.id.portraitRight)

        try {
            story = assets.open(STORY_ASSET).bufferedReader(Charsets.UTF_8).use { ScriptParser.parse(it.readText()) }
        } catch (e: StoryParseException) {
            speakerView.text = ""
            textView.text = getString(R.string.story_error, e.message)
            return
        }
        runner = StoryRunner.fromSave(story, prefs.getString(KEY_STATE, null))

        findViewById<View>(R.id.root).setOnClickListener {
            if (runner.step is Step.Say) {
                runner.advance()
                afterStateChange()
            }
        }
        render()
    }

    override fun onPause() {
        super.onPause()
        if (::runner.isInitialized) saveState()
    }

    @Suppress("DEPRECATION")
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    private fun afterStateChange() {
        saveState()
        render()
    }

    private fun saveState() {
        prefs.edit().putString(KEY_STATE, runner.state.encode()).apply()
    }

    private fun render() {
        choicesView.removeAllViews()
        choicesView.visibility = View.GONE
        when (val step = runner.step) {
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
        if (active != null && step.speaker != null) {
            val bitmap = loadPortrait(step.speaker!!)
            if (bitmap != null) {
                active.setImageDrawable(bitmap)
                active.visibility = View.VISIBLE
            }
        }
        // Говорящий виден полностью, второй персонаж приглушён; в речи рассказчика приглушены оба.
        portraitLeft.alpha = if (active === portraitLeft) 1f else 0.5f
        portraitRight.alpha = if (active === portraitRight) 1f else 0.5f
    }

    private fun showChoices(step: Step.Choose) {
        choicesView.visibility = View.VISIBLE
        step.options.forEachIndexed { index, option ->
            choicesView.addView(Button(this).apply {
                text = option.text
                setOnClickListener {
                    runner.choose(index)
                    afterStateChange()
                }
            })
        }
    }

    private fun showEnd() {
        speakerView.text = ""
        textView.text = ""
        choicesView.visibility = View.VISIBLE
        choicesView.addView(Button(this).apply {
            setText(R.string.restart)
            setOnClickListener {
                runner = StoryRunner(story)
                portraitLeft.visibility = View.INVISIBLE
                portraitRight.visibility = View.INVISIBLE
                afterStateChange()
            }
        })
    }

    /** Портрет персонажа ищется в assets/portraits/<имя>.png; пока файла нет, показывается только имя. */
    private fun loadPortrait(name: String): BitmapDrawable? = try {
        assets.open("portraits/$name.png").use { stream ->
            BitmapDrawable(resources, BitmapFactory.decodeStream(stream)).apply {
                // пиксель-арт масштабируется без сглаживания
                setFilterBitmap(false)
            }
        }
    } catch (e: IOException) {
        null
    }

    private companion object {
        const val PREFS = "ovnezey"
        const val KEY_STATE = "state"
        const val STORY_ASSET = "story/demo.ovn"
    }
}
