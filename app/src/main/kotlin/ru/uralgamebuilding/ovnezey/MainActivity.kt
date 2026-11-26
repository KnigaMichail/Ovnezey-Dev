package ru.uralgamebuilding.ovnezey

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import ru.uralgamebuilding.ovnezey.engine.GameState
import ru.uralgamebuilding.ovnezey.engine.LoadResult
import ru.uralgamebuilding.ovnezey.engine.RejectReason
import ru.uralgamebuilding.ovnezey.engine.SaveSlots
import ru.uralgamebuilding.ovnezey.engine.Side
import ru.uralgamebuilding.ovnezey.engine.Step
import android.view.ViewGroup
import ru.uralgamebuilding.ovnezey.engine.StoryRunner
import ru.uralgamebuilding.ovnezey.engine.UseResult
import java.io.IOException
import java.util.Locale

/**
 * Игровой экран: иллюстрация локации, рассказчик и диалоги, персонажи слева и справа, выбор кнопками,
 * осмотр с областями нажатия и рюкзак. Вся логика сюжета в модуле engine, здесь только отображение
 * и вызовы сохранения. Партию задаёт меню или экран слотов через [Services.runner].
 */
class MainActivity : BaseActivity() {
    private var runner: StoryRunner? = null
    private lateinit var scene: SceneView
    private lateinit var speakerView: TextView
    private lateinit var textView: TextView
    private lateinit var choicesView: LinearLayout
    private lateinit var portraitLeft: ImageView
    private lateinit var portraitRight: ImageView
    private lateinit var menuButton: Button
    private lateinit var backpackButton: Button
    private lateinit var textPanel: View
    private lateinit var effect: EffectView

    /** Позиция сюжета, для которой запущена мини-игра или вставка: чтобы не перезапускать их при каждой перерисовке. */
    private var effectKey: String? = null

    /** Предмет, выбранный в рюкзаке для применения на область. Не сохраняется: это состояние интерфейса. */
    private var selectedItem: String? = null
    private var shownLocation: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_novel)
        scene = findViewById(R.id.scene)
        speakerView = findViewById(R.id.speaker)
        textView = findViewById(R.id.text)
        choicesView = findViewById(R.id.choices)
        portraitLeft = findViewById(R.id.portraitLeft)
        portraitRight = findViewById(R.id.portraitRight)
        textPanel = findViewById(R.id.textPanel)

        PixelUi.styleText(this, speakerView, color = PixelUi.ACCENT)
        PixelUi.styleText(this, textView)
        val m = PixelUi.metrics(this)
        val root = findViewById<FrameLayout>(R.id.root)
        effect = EffectView(this).apply { visibility = View.GONE }
        root.addView(effect, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        menuButton = PixelUi.button(this, getString(R.string.game_menu)) { showPauseMenu() }
        root.addView(
            menuButton,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
                .apply { setMargins(m.pad, m.pad, m.pad, m.pad) },
        )
        backpackButton = PixelUi.button(this, getString(R.string.backpack)) { showBackpack() }
        root.addView(
            backpackButton,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START)
                .apply { setMargins(m.pad, m.pad, m.pad, m.pad) },
        )
        root.setOnClickListener { onTap() }
        scene.onSpotTap = { id -> onHotspot(id) }
        scene.onEmptyTap = { clearSelection() }
        scene.onDebugPoint = { x, y ->
            Toast.makeText(this, String.format(Locale.US, "x=%.2f  y=%.2f", x, y), Toast.LENGTH_SHORT).show()
        }

        val story = services.story
        if (story == null) {
            speakerView.text = ""
            textView.text = getString(R.string.story_error, services.storyError)
            backpackButton.visibility = View.GONE
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
        selectedItem = null
        shownLocation = null
        portraitLeft.visibility = View.INVISIBLE
        portraitRight.visibility = View.INVISIBLE
        render()
    }

    override fun onResume() {
        super.onResume()
        scene.debug = services.settings.debugHotspots
        render()
    }

    override fun onPause() {
        super.onPause()
        // Мини-игра по времени не должна идти в фоне: при возвращении она начнётся заново с той же позиции.
        effect.stop()
        effectKey = null
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
        val location = r.state.location
        if (location != shownLocation) {
            scene.setLocationImage(location?.let { AssetImages.location(this, it) })
            shownLocation = location
        }
        if (selectedItem != null && r.inventoryItems.none { it.id == selectedItem }) selectedItem = null
        backpackButton.visibility = if (r.inventoryItems.isEmpty()) View.GONE else View.VISIBLE

        choicesView.removeAllViews()
        choicesView.visibility = View.GONE
        scene.setSpots(emptyList())
        val step = r.step
        val effectStep = step is Step.Minigame || step is Step.Show
        if (!effectStep) {
            effect.stop()
            effect.visibility = View.GONE
            effectKey = null
        }
        when (step) {
            is Step.Say -> showSay(step)
            is Step.Choose -> showChoices(step)
            is Step.Explore -> showExplore(step)
            is Step.Minigame -> showEffect { it.startMinigame(step.kind, step.count, step.seconds, step.label, services.settings.easyMinigames) }
            is Step.Show -> showEffect { it.startShow(step.kind, step.text) }
            Step.End -> showEnd()
        }
        // Пустая панель текста не рисуется: иначе она закрывает нижнюю часть сцены и области осмотра.
        textPanel.visibility = if (effectStep || (speakerView.text.isEmpty() && textView.text.isEmpty())) View.GONE else View.VISIBLE
        if (effectStep) {
            portraitLeft.visibility = View.INVISIBLE
            portraitRight.visibility = View.INVISIBLE
            backpackButton.visibility = View.GONE
        }
    }

    /** Показывает полноэкранный эффект и запускает его один раз для текущей позиции сюжета. */
    private fun showEffect(start: (EffectView) -> Unit) {
        val r = runner ?: return
        speakerView.text = ""
        textView.text = ""
        effect.visibility = View.VISIBLE
        val key = "${r.state.scene}#${r.state.index}"
        if (effectKey == key && effect.isRunning) return
        effectKey = key
        effect.onFinished = { success ->
            val current = runner
            if (current != null && "${current.state.scene}#${current.state.index}" == key) {
                val before = current.state
                when (current.step) {
                    is Step.Minigame -> current.finishMinigame(success)
                    is Step.Show -> current.advance()
                    else -> {}
                }
                effectKey = null
                afterChange(before)
            }
        }
        start(effect)
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
            val drawable = AssetImages.portrait(this, id)
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

    private fun showExplore(step: Step.Explore) {
        speakerView.text = ""
        val name = runner?.inventoryItems?.firstOrNull { it.id == selectedItem }?.name
        textView.text = if (name != null) getString(R.string.item_selected, name) else ""
        scene.setSpots(step.hotspots.map { SceneView.Spot(it.id, it.label, it.x, it.y, it.w, it.h) })
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
                selectedItem = null
                shownLocation = null
                portraitLeft.visibility = View.INVISIBLE
                portraitRight.visibility = View.INVISIBLE
                render()
            },
            PixelUi.matchWidth(this),
        )
    }

    /** Нажатие на область: переход в её сцену или применение выбранного предмета. */
    private fun onHotspot(id: String) {
        val r = runner ?: return
        if (hasOverlay() || r.step !is Step.Explore) return
        val before = r.state
        val item = selectedItem
        if (item == null) {
            r.tap(id)
            afterChange(before)
            return
        }
        when (val result = r.useItem(item, id)) {
            UseResult.Used, UseResult.Reaction -> {
                selectedItem = null
                afterChange(before)
            }
            is UseResult.Rejected -> {
                if (result.reason == RejectReason.NO_EFFECT) {
                    // Состояние не изменилось, предмет остаётся выбранным: можно попробовать другую область.
                    textView.text = getString(R.string.item_no_effect)
                } else {
                    selectedItem = null
                    render()
                }
            }
        }
    }

    private fun clearSelection() {
        if (selectedItem == null) return
        selectedItem = null
        render()
    }

    private fun showBackpack() {
        val r = runner ?: return
        if (hasOverlay()) return
        val items = r.inventoryItems
        if (items.isEmpty()) return
        val content = BackpackPanel.build(this, items, canUse = r.step is Step.Explore) { item ->
            dismissOverlay()
            selectedItem = item.id
            render()
        }
        showDialog(getString(R.string.backpack), null, listOf(DialogAction(getString(R.string.close)) {}), content)
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
}
