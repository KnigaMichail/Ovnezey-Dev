package ru.uralgamebuilding.ovnezey

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Иллюстрация локации и активные области. Картинка рисуется целым масштабом от логического размера 320x180
 * без сглаживания, по краям остаются чёрные поля. Области заданы в долях картинки.
 * Пока список областей пуст, вид не перехватывает нажатия, и тап проходит к родителю (листание текста).
 */
class SceneView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    class Spot(val id: String, val label: String, val x: Float, val y: Float, val w: Float, val h: Float)

    var onSpotTap: ((String) -> Unit)? = null
    var onEmptyTap: (() -> Unit)? = null

    /** Вызывается в режиме автора по долгому нажатию с координатами в долях картинки. */
    var onDebugPoint: ((Float, Float) -> Unit)? = null

    var debug: Boolean = false
        set(value) { field = value; invalidate() }

    private var locationBitmap: Bitmap? = null
    private var spots: List<Spot> = emptyList()
    private var pressedId: String? = null
    private var startId: String? = null
    private var downX = 0f
    private var downY = 0f
    private var longPressFired = false
    private val sceneRect = Rect()
    private val bitmapPaint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val fillPaint = Paint().apply { isAntiAlias = false }
    private val linePaint = Paint().apply { isAntiAlias = false; style = Paint.Style.STROKE }
    private val textPaint = Paint().apply { isAntiAlias = false }
    private val longPress = Runnable {
        longPressFired = true
        val nx = (downX - sceneRect.left) / sceneRect.width()
        val ny = (downY - sceneRect.top) / sceneRect.height()
        if (nx in 0f..1f && ny in 0f..1f) onDebugPoint?.invoke(nx, ny)
    }

    fun setLocationImage(bitmap: Bitmap?) {
        locationBitmap = bitmap
        invalidate()
    }

    fun setSpots(list: List<Spot>) {
        spots = list
        pressedId = null
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val scale = max(1, min(w / LOGICAL_W, h / LOGICAL_H))
        val sw = LOGICAL_W * scale
        val sh = LOGICAL_H * scale
        val left = (w - sw) / 2
        val top = (h - sh) / 2
        sceneRect.set(left, top, left + sw, top + sh)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val bitmap = locationBitmap
        if (bitmap != null) {
            canvas.drawBitmap(bitmap, null, sceneRect, bitmapPaint)
        } else {
            fillPaint.color = PLACEHOLDER
            canvas.drawRect(sceneRect, fillPaint)
        }
        val m = PixelUi.metrics(context)
        linePaint.strokeWidth = m.stroke.toFloat()
        textPaint.typeface = PixelUi.typeface(context)
        textPaint.textSize = m.textPx.toFloat()
        for (spot in spots) {
            val rect = rectOf(spot)
            val pressed = spot.id == pressedId
            if (pressed) {
                fillPaint.color = PRESSED_FILL
                canvas.drawRect(rect, fillPaint)
            }
            if (pressed || debug) {
                linePaint.color = if (pressed) PixelUi.ACCENT else DEBUG_LINE
                canvas.drawRect(rect, linePaint)
                drawLabel(canvas, spot.label, rect, if (pressed) PixelUi.ACCENT else DEBUG_LINE, m.textPx)
            }
        }
    }

    private fun drawLabel(canvas: Canvas, text: String, rect: Rect, color: Int, textPx: Int) {
        val width = textPaint.measureText(text).roundToInt()
        val pad = textPx / 4
        val left = rect.left.coerceIn(sceneRect.left, max(sceneRect.left, sceneRect.right - width - pad * 2))
        val top = max(sceneRect.top, rect.top - textPx - pad * 2)
        fillPaint.color = LABEL_BACKGROUND
        canvas.drawRect(left.toFloat(), top.toFloat(), (left + width + pad * 2).toFloat(), (top + textPx + pad * 2).toFloat(), fillPaint)
        textPaint.color = color
        canvas.drawText(text, (left + pad).toFloat(), (top + pad + textPx).toFloat() - textPx * 0.125f, textPaint)
    }

    private fun rectOf(spot: Spot): Rect {
        val l = sceneRect.left + (spot.x * sceneRect.width()).roundToInt()
        val t = sceneRect.top + (spot.y * sceneRect.height()).roundToInt()
        val r = sceneRect.left + ((spot.x + spot.w) * sceneRect.width()).roundToInt()
        val b = sceneRect.top + ((spot.y + spot.h) * sceneRect.height()).roundToInt()
        return Rect(l, t, r, b)
    }

    private fun spotAt(x: Float, y: Float): Spot? =
        spots.lastOrNull { rectOf(it).contains(x.toInt(), y.toInt()) }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (spots.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                longPressFired = false
                startId = spotAt(event.x, event.y)?.id
                pressedId = startId
                invalidate()
                if (debug) postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val slop = ViewConfiguration.get(context).scaledTouchSlop
                if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) removeCallbacks(longPress)
                val over = spotAt(event.x, event.y)?.id
                val shouldPress = if (over != null && over == startId) over else null
                if (shouldPress != pressedId) {
                    pressedId = shouldPress
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val hit = spotAt(event.x, event.y)
                val id = startId
                pressedId = null
                startId = null
                invalidate()
                if (!longPressFired) {
                    if (hit != null && hit.id == id) onSpotTap?.invoke(hit.id) else onEmptyTap?.invoke()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                pressedId = null
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    companion object {
        const val LOGICAL_W = 320
        const val LOGICAL_H = 180
        private val PLACEHOLDER = 0xFF1C1C2C.toInt()
        private val PRESSED_FILL = 0x66FFD24D
        private val DEBUG_LINE = 0xFF58E0A0.toInt()
        private val LABEL_BACKGROUND = 0xCC000000.toInt()
    }
}
