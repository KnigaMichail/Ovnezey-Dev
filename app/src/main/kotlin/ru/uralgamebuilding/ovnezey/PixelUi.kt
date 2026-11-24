package ru.uralgamebuilding.ovnezey

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

class DialogAction(val label: String, val run: () -> Unit)

/**
 * Пиксельное оформление: шрифт Press Start 2P, плоские кнопки с рамкой, диалоги поверх экрана.
 * Размеры привязаны к логической сетке 320x180: масштаб равен целому числу физических пикселей на логический.
 */
object PixelUi {
    val BG = 0xFF101018.toInt()
    val TEXT = 0xFFFFFFFF.toInt()
    val ACCENT = 0xFFFFD24D.toInt()
    val MUTED = 0xFF9A9AB4.toInt()
    val BORDER = 0xFF4A4A6A.toInt()
    val BUTTON = 0xFF18182A.toInt()
    val BUTTON_PRESSED = 0xFF2A2A44.toInt()
    private val SCRIM = 0xB0000000.toInt()

    private var cachedTypeface: Typeface? = null

    class Metrics(val scale: Int, val textPx: Int, val pad: Int, val stroke: Int)

    fun metrics(context: Context): Metrics {
        val dm = context.resources.displayMetrics
        val scale = max(1, min(dm.widthPixels, dm.heightPixels) / 180)
        // Шрифт нарисован на сетке 8x8: размер кратен 8, чтобы пиксели были ровными.
        val textPx = max(8, (scale / 2) * 8)
        return Metrics(scale, textPx, textPx, max(2, scale / 2))
    }

    fun typeface(context: Context): Typeface {
        cachedTypeface?.let { return it }
        val face = try {
            Typeface.createFromAsset(context.assets, "fonts/PressStart2P.ttf")
        } catch (e: RuntimeException) {
            Typeface.MONOSPACE
        }
        cachedTypeface = face
        return face
    }

    /** [sizeSteps] кратно 1: 1 это основной размер, 2 заголовок. */
    fun styleText(context: Context, view: TextView, sizeSteps: Int = 1, color: Int = TEXT) {
        view.typeface = typeface(context)
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, (metrics(context).textPx * sizeSteps).toFloat())
        view.setTextColor(color)
        view.paint.isAntiAlias = false
    }

    fun frame(fill: Int, border: Int, stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        setStroke(stroke, border)
    }

    fun button(context: Context, text: String, onClick: () -> Unit): Button {
        val m = metrics(context)
        return Button(context).apply {
            this.text = text
            isAllCaps = false
            stateListAnimator = null
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            gravity = Gravity.CENTER
            setPadding(m.pad, m.pad, m.pad, m.pad)
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), frame(BUTTON_PRESSED, ACCENT, m.stroke))
                addState(intArrayOf(), frame(BUTTON, BORDER, m.stroke))
            }
            styleText(context, this)
            setOnClickListener { onClick() }
        }
    }

    fun setEnabledLook(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else 0.4f
    }

    fun label(context: Context, text: String, sizeSteps: Int = 1, color: Int = TEXT): TextView =
        TextView(context).apply {
            this.text = text
            styleText(context, this, sizeSteps, color)
        }

    fun spacing(context: Context, steps: Int = 1): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(1, metrics(context).pad * steps)
    }

    fun matchWidth(context: Context, bottomMarginSteps: Int = 1) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = metrics(context).pad / 2 * bottomMarginSteps
        }

    /**
     * Диалог в виде слоя поверх содержимого экрана. Системные диалоги не используются: они выводят
     * системные панели из полноэкранного режима. Возвращает слой, чтобы его можно было убрать.
     */
    fun showDialog(
        activity: Activity,
        title: String?,
        message: String?,
        actions: List<DialogAction>,
        onClose: () -> Unit,
        content: View? = null,
    ): View {
        val m = metrics(activity)
        val dm = activity.resources.displayMetrics
        val root = FrameLayout(activity).apply {
            setBackgroundColor(SCRIM)
            isClickable = true
            isFocusable = true
        }
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = frame(BG, ACCENT, m.stroke)
            setPadding(m.pad * 2, m.pad * 2, m.pad * 2, m.pad * 2)
        }
        if (title != null) {
            panel.addView(label(activity, title, color = ACCENT), matchWidth(activity, 2))
        }
        if (message != null) {
            panel.addView(label(activity, message), matchWidth(activity, 2))
        }
        if (content != null) {
            panel.addView(content, matchWidth(activity, 2))
        }
        for (action in actions) {
            panel.addView(button(activity, action.label) {
                (root.parent as? ViewGroup)?.removeView(root)
                onClose()
                action.run()
            }, matchWidth(activity))
        }
        val width = min((dm.widthPixels * 0.8f).toInt(), m.textPx * 36)
        root.addView(panel, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        activity.findViewById<ViewGroup>(android.R.id.content).addView(
            root,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        return root
    }
}
