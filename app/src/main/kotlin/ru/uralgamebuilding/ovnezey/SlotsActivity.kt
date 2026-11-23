package ru.uralgamebuilding.ovnezey

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import ru.uralgamebuilding.ovnezey.engine.LoadResult
import ru.uralgamebuilding.ovnezey.engine.SaveSlots
import ru.uralgamebuilding.ovnezey.engine.SlotStatus
import ru.uralgamebuilding.ovnezey.engine.SlotSummary
import java.text.DateFormat
import java.util.Date

/** Экран слотов: режим сохранения (пять ручных слотов) или загрузки (автосохранение и пять ручных). */
class SlotsActivity : BaseActivity() {
    private lateinit var list: LinearLayout
    private val saveMode get() = intent.getStringExtra(EXTRA_MODE) == MODE_SAVE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { setBackgroundColor(PixelUi.BG) }
        val m = PixelUi.metrics(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(m.pad * 2, m.pad * 2, m.pad * 2, m.pad * 2)
        }
        column.addView(
            PixelUi.label(this, getString(if (saveMode) R.string.save_title else R.string.load_title), sizeSteps = 2, color = PixelUi.ACCENT),
            PixelUi.matchWidth(this, 2),
        )
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        column.addView(PixelUi.button(this, getString(R.string.back)) { finish() }, PixelUi.matchWidth(this))
        val width = (resources.displayMetrics.widthPixels * 0.85f).toInt()
        root.addView(column, FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        fillList()
    }

    private fun fillList() {
        list.removeAllViews()
        if (services.story == null) return
        val summaries = ArrayList<SlotSummary>()
        if (!saveMode) summaries += services.slots.summary(SaveSlots.AUTO_KEY)
        summaries += services.slots.manualSummaries()
        for (summary in summaries) {
            val button = PixelUi.button(this, describe(summary)) { onSlotTapped(summary) }
            button.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            PixelUi.setEnabledLook(button, saveMode || summary.status !is SlotStatus.Empty)
            list.addView(button, PixelUi.matchWidth(this))
        }
    }

    private fun slotTitle(key: String): String =
        if (key == SaveSlots.AUTO_KEY) getString(R.string.slot_auto)
        else getString(R.string.slot_name, key.removePrefix("slot").toInt())

    private fun describe(summary: SlotSummary): String {
        val title = slotTitle(summary.key)
        return when (val status = summary.status) {
            SlotStatus.Empty -> "$title\n${getString(R.string.slot_empty)}"
            is SlotStatus.Broken -> "$title\n${getString(R.string.slot_broken)}"
            is SlotStatus.Filled -> {
                val language = services.settings.language
                val lines = ArrayList<String>()
                lines += title
                if (status.savedAtMillis > 0) {
                    val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, LocaleHelper.localeOf(language))
                    lines += format.format(Date(status.savedAtMillis))
                }
                services.story?.locationTitle(status.location, language)?.let { lines += it }
                lines.joinToString("\n")
            }
        }
    }

    private fun onSlotTapped(summary: SlotSummary) {
        if (saveMode) {
            val slot = summary.key.removePrefix("slot").toInt()
            if (summary.status is SlotStatus.Empty) saveTo(slot)
            else showDialog(
                null,
                getString(R.string.confirm_overwrite, slot),
                listOf(DialogAction(getString(R.string.yes)) { saveTo(slot) }, DialogAction(getString(R.string.no)) {}),
            )
        } else {
            loadFrom(summary.key)
        }
    }

    private fun saveTo(slot: Int) {
        val runner = services.runner
        val story = services.story
        if (runner == null || story == null) {
            finish()
            return
        }
        try {
            services.slots.saveManual(slot, runner.state, story)
        } catch (e: java.io.IOException) {
            showDialog(getString(R.string.error_title), getString(R.string.save_failed), listOf(DialogAction(getString(R.string.ok)) {}))
            return
        }
        Toast.makeText(this, R.string.slot_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun loadFrom(key: String) {
        when (val result = services.load(key)) {
            is LoadResult.Loaded -> {
                services.runner = result.runner
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                finish()
            }
            LoadResult.Empty -> Unit
            else -> showDialog(getString(R.string.error_title), result.errorText(this), listOf(DialogAction(getString(R.string.ok)) {}))
        }
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_SAVE = "save"
        const val MODE_LOAD = "load"
    }
}
