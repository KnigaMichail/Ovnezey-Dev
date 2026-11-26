package ru.uralgamebuilding.ovnezey

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import ru.uralgamebuilding.ovnezey.engine.MinigameKind
import ru.uralgamebuilding.ovnezey.engine.ShowKind
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Полноэкранные мини-игры и экранные вставки. Всё нарисовано кодом на логической сетке 320x180 с целым
 * масштабом и без сглаживания, пока нет готовых спрайтов. Правила (число тапов, время) приходят из сценария;
 * режим «упрощённые мини-игры» ослабляет пороги. Итог сообщается один раз через [onFinished].
 */
class EffectView @JvmOverloads constructor(context: Context) : View(context) {
    /** `true` успех, `false` провал. Для вставок всегда `true` (игрок нажал, чтобы продолжить). */
    var onFinished: ((Boolean) -> Unit)? = null

    private enum class Mode { NONE, WAKE, BADGE, CRATE, FIGHT, WIRES, CITY, TERMINAL }

    private var mode = Mode.NONE
    private var active = false
    private var finished = false
    private var startMs = 0L
    private var label = ""
    private var count = 1
    private var seconds = 0
    private var easy = false

    private var progress = 0f
    private var lastMs = 0L
    private var taps = 0
    private var tapMs = 0L
    private var doneMs = 0L
    private var shake = 0f
    private var failed = false

    private class Wire(val color: Int, var leftY: Float, var rightY: Float) { var connected = false }
    private var wires: List<Wire> = emptyList()
    private var dragWire = -1
    private var dragX = 0f
    private var dragY = 0f

    private class Building(val x: Float, val w: Float, val h: Float, val layer: Int, val seed: Int)
    private class Person(val x0: Float, val y: Float, val speed: Float, val delay: Long, val near: Boolean)
    private class Neon(val x: Float, val y: Float, val w: Float, val h: Float, val color: Int, val period: Long, val phase: Long)
    private val buildings = ArrayList<Building>()
    private val people = ArrayList<Person>()
    private val neons = ArrayList<Neon>()

    private val paint = Paint().apply { isAntiAlias = false }
    private val textPaint = Paint().apply { isAntiAlias = false }
    private var unit = 1
    private var ox = 0
    private var oy = 0
    private var canvasRef: Canvas? = null

    val isRunning: Boolean get() = active

    init {
        isClickable = true
        isFocusable = false
    }

    fun stop() {
        active = false
        mode = Mode.NONE
        finished = true
    }

    fun startMinigame(kind: MinigameKind, count: Int, seconds: Int, label: String, easyMode: Boolean) {
        reset()
        this.label = label
        this.easy = easyMode
        this.count = if (easyMode && kind != MinigameKind.WIRES && kind != MinigameKind.BADGE) max(2, (count * 0.7f).toInt()) else count
        this.seconds = if (easyMode) (seconds * 1.5f).toInt() else seconds
        mode = when (kind) {
            MinigameKind.WAKE -> Mode.WAKE
            MinigameKind.BADGE -> Mode.BADGE
            MinigameKind.CRATE -> Mode.CRATE
            MinigameKind.FIGHT -> Mode.FIGHT
            MinigameKind.WIRES -> Mode.WIRES.also { buildWires(count) }
        }
        begin()
    }

    fun startShow(kind: ShowKind, text: String) {
        reset()
        label = text
        mode = if (kind == ShowKind.CITY) Mode.CITY.also { buildCity() } else Mode.TERMINAL
        begin()
    }

    private fun reset() {
        progress = 0f; taps = 0; tapMs = 0L; doneMs = 0L; shake = 0f; failed = false
        dragWire = -1; wires = emptyList(); finished = false
    }

    private fun begin() {
        startMs = SystemClock.uptimeMillis()
        lastMs = startMs
        active = true
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        unit = max(1, min(w / 320, h / 180))
        ox = (w - 320 * unit) / 2
        oy = (h - 180 * unit) / 2
    }

    // ------------------------------------------------------------------ рисование

    private fun fx(v: Float) = ox + v * unit
    private fun fy(v: Float) = oy + v * unit

    private fun box(x: Float, y: Float, w: Float, h: Float, color: Int) {
        paint.color = color
        canvasRef?.drawRect(fx(x), fy(y), fx(x + w), fy(y + h), paint)
    }

    private fun lerp(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt(),
        )
    }

    /** Текст по центру логического экрана с переносом по словам; возвращает высоту в логических пикселях. */
    private fun text(s: String, cy: Float, mult: Int, color: Int, alpha: Int = 255, maxW: Float = 296f): Float {
        val c = canvasRef ?: return 0f
        val px = PixelUi.metrics(context).textPx * mult
        textPaint.typeface = PixelUi.typeface(context)
        textPaint.textSize = px.toFloat()
        textPaint.color = color
        textPaint.alpha = alpha
        val limit = maxW * unit
        val lines = ArrayList<String>()
        for (para in s.split("\n")) {
            var cur = ""
            for (word in para.split(" ")) {
                val trial = if (cur.isEmpty()) word else "$cur $word"
                if (textPaint.measureText(trial) <= limit || cur.isEmpty()) cur = trial else { lines += cur; cur = word }
            }
            lines += cur
        }
        val lineH = px * 1.5f
        var y = fy(cy) - lineH * (lines.size - 1) / 2f + px / 2f
        for (line in lines) {
            c.drawText(line, fx(160f) - textPaint.measureText(line) / 2f, y, textPaint)
            y += lineH
        }
        return lineH * lines.size / unit
    }

    private fun hint(t: Long) {
        if ((t / 500) % 2 == 0L) {
            for (i in 0..3) box(156f + i, 168f + i, 8f - 2 * i, 1f, PixelUi.ACCENT)
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvasRef = canvas
        canvas.drawColor(Color.BLACK)
        if (!active && mode == Mode.NONE) return
        val now = SystemClock.uptimeMillis()
        val t = now - startMs
        val dt = (now - lastMs) / 1000f
        lastMs = now
        when (mode) {
            Mode.WAKE -> drawWake(t, dt)
            Mode.BADGE -> drawBadge(t)
            Mode.CRATE -> drawCrate(t, dt)
            Mode.FIGHT -> drawFight(t, dt)
            Mode.WIRES -> drawWires(t)
            Mode.CITY -> drawCity(t)
            Mode.TERMINAL -> drawTerminal(t)
            Mode.NONE -> {}
        }
        if (active) postInvalidateOnAnimation()
    }

    private fun end(success: Boolean) {
        if (finished) return
        finished = true
        active = false
        post { onFinished?.invoke(success) }
    }

    // ---- Проснуться
    private fun drawWake(t: Long, dt: Float) {
        if (doneMs == 0L) {
            progress = max(0f, progress - dt * (if (easy) 0.07f else 0.12f))
        }
        val open = progress
        val c1 = Color.rgb((10 + 60 * open).toInt(), (12 + 70 * open).toInt(), (20 + 90 * open).toInt())
        box(0f, 0f, 320f, 180f, c1)
        val half = open * 32f
        if (half >= 1f) {
            var dy = -half
            while (dy < half) {
                val k = sqrt(max(0f, 1f - (dy / half) * (dy / half)))
                val w = 120f * k
                box(160f - w / 2, 88f + dy, w, 2f, 0xFFDCE6F0.toInt())
                val r = 15f * min(1f, open * 1.6f)
                if (abs(dy) < r) {
                    val iw = 2f * sqrt(max(0f, r * r - dy * dy))
                    box(160f - iw / 2, 88f + dy, iw, 2f, 0xFF304868.toInt())
                    if (abs(dy) < r * 0.5f) {
                        val pw = iw * 0.45f
                        box(160f - pw / 2, 88f + dy, pw, 2f, Color.BLACK)
                    }
                }
                dy += 2f
            }
        }
        val blink = if ((t / 450) % 2 == 0L) 255 else 120
        text(label, 150f, 1, PixelUi.ACCENT, blink)
        box(80f, 168f, 160f, 4f, 0xFF202030.toInt())
        box(80f, 168f, 160f * open, 4f, PixelUi.ACCENT)
        if (open >= 1f && doneMs == 0L) doneMs = SystemClock.uptimeMillis()
        if (doneMs != 0L) {
            val k = ((SystemClock.uptimeMillis() - doneMs) / 450f).coerceIn(0f, 1f)
            box(0f, 0f, 320f, 180f, Color.argb((255 * k).toInt(), 255, 255, 255))
            if (k >= 1f) end(true)
        }
    }

    // ---- Пропуск
    private fun drawBadge(t: Long) {
        box(0f, 0f, 320f, 180f, 0xFF14161C.toInt())
        box(96f, 24f, 128f, 128f, 0xFF2A2D38.toInt())
        box(100f, 28f, 120f, 120f, 0xFF3A3E4C.toInt())
        val screenOk = tapMs != 0L && SystemClock.uptimeMillis() - tapMs > 380
        box(112f, 40f, 96f, 32f, if (screenOk) 0xFF1E6B3A.toInt() else 0xFF0E2A1A.toInt())
        if (screenOk) text("OK", 56f, 1, Color.WHITE) else if ((t / 400) % 2 == 0L) box(150f, 52f, 20f, 3f, 0xFF3ADA7A.toInt())
        box(124f, 92f, 72f, 10f, 0xFF0A0A10.toInt())
        box(126f, 94f, 68f, 2f, if (screenOk) 0xFF3ADA7A.toInt() else 0xFFB02020.toInt())
        var cardY = 150f + (if (tapMs == 0L) sin(t / 300.0).toFloat() * 2f else 0f)
        if (tapMs != 0L) {
            val k = ((SystemClock.uptimeMillis() - tapMs) / 350f).coerceIn(0f, 1f)
            cardY = 150f + (84f - 150f) * k
        }
        box(138f, cardY, 44f, 28f, 0xFFD8DCE8.toInt())
        box(142f, cardY + 6f, 10f, 8f, 0xFFC8A838.toInt())
        box(142f, cardY + 20f, 30f, 2f, 0xFF5A6078.toInt())
        text(label, 12f, 1, PixelUi.ACCENT)
        if (screenOk && SystemClock.uptimeMillis() - tapMs > 800) end(true)
    }

    // ---- Контейнер
    private fun drawCrate(t: Long, dt: Float) {
        box(0f, 0f, 320f, 180f, 0xFF16181E.toInt())
        shake = max(0f, shake - dt * 5f)
        val sx = if (shake > 0f) sin(t / 25.0).toFloat() * 3f * shake else 0f
        val burst = doneMs != 0L
        val k = if (burst) ((SystemClock.uptimeMillis() - doneMs) / 500f).coerceIn(0f, 1f) else 0f
        // ооз
        val oozeLen = 6f + 14f * ((taps.toFloat() / count) + 0.2f)
        for (i in 0..4) {
            val x = 118f + i * 20f + sx
            val len = oozeLen * (0.6f + 0.4f * sin(t / 600.0 + i).toFloat().let { (it + 1f) / 2f })
            box(x, 134f, 3f, len, 0xFF1C2A1A.toInt())
            box(x - 1f, 134f + len, 5f, 3f, 0xFF2E4A24.toInt())
        }
        box(106f + sx, 60f, 108f, 76f, 0xFF4A4036.toInt())
        for (i in 0..3) box(106f + sx, 60f + i * 19f, 108f, 2f, 0xFF2E2820.toInt())
        for (i in 0..1) for (j in 0..1) box(110f + sx + i * 96f, 64f + j * 66f, 3f, 3f, 0xFF8A8070.toInt())
        // трещины
        val rnd = Random(11)
        for (i in 0 until taps) {
            val cx = 120f + rnd.nextInt(80) + sx
            val cy = 66f + rnd.nextInt(60)
            box(cx, cy, 2f, 10f, Color.BLACK)
            box(cx + 2f, cy + 4f, 8f, 2f, Color.BLACK)
        }
        val lidY = 50f - k * 70f
        box(102f + sx, lidY, 116f, 12f, 0xFF5C5044.toInt())
        box(102f + sx, lidY, 116f, 2f, 0xFF8A7C68.toInt())
        text(label, 20f, 1, PixelUi.ACCENT)
        box(80f, 168f, 160f, 4f, 0xFF202030.toInt())
        box(80f, 168f, 160f * (taps.toFloat() / count).coerceIn(0f, 1f), 4f, PixelUi.ACCENT)
        if (burst && k >= 1f) end(true)
    }

    // ---- Отбиться
    private fun drawFight(t: Long, dt: Float) {
        val total = seconds * 1000L
        val left = (total - t).coerceAtLeast(0)
        val danger = 1f - left.toFloat() / total
        box(0f, 0f, 320f, 180f, lerp(0xFF16141A.toInt(), 0xFF3A1414.toInt(), danger))
        shake = max(0f, shake - dt * 6f)
        val sx = sin(t / 30.0).toFloat() * (1f + 4f * danger + 3f * shake)
        box(108f + sx, 24f, 104f, 130f, 0xFF4E3A2A.toInt())
        box(112f + sx, 28f, 96f, 122f, 0xFF6A5038.toInt())
        for (i in 0..3) box(112f + sx, 28f + i * 30f, 96f, 2f, 0xFF3A2A1C.toInt())
        box(190f + sx, 88f, 6f, 10f, 0xFFB8B8C0.toInt())
        if (shake > 0.2f) box(130f + (taps % 5) * 10f, 50f + (taps % 3) * 20f, 14f, 14f, Color.argb((200 * shake).toInt(), 255, 220, 120))
        box(0f, 0f, 320f * (left.toFloat() / total), 5f, 0xFFD03030.toInt())
        text(label, 14f, 1, PixelUi.ACCENT)
        box(80f, 168f, 160f, 4f, 0xFF202030.toInt())
        val p = (taps.toFloat() / count).coerceIn(0f, 1f)
        box(80f, 168f, 160f * p, 4f, 0xFF3ADA7A.toInt())
        if (doneMs == 0L) {
            if (taps >= count) { doneMs = SystemClock.uptimeMillis(); failed = false }
            else if (left == 0L) { doneMs = SystemClock.uptimeMillis(); failed = true }
        } else {
            val k = ((SystemClock.uptimeMillis() - doneMs) / 350f).coerceIn(0f, 1f)
            val c = if (failed) Color.argb((255 * k).toInt(), 160, 0, 0) else Color.argb((255 * k).toInt(), 255, 255, 255)
            box(0f, 0f, 320f, 180f, c)
            if (k >= 1f) end(!failed)
        }
    }

    // ---- Провода
    private val wirePalette = intArrayOf(0xFFE03C3C.toInt(), 0xFF3C8CE0.toInt(), 0xFFE0C83C.toInt(), 0xFF3CC878.toInt(), 0xFFB45CE0.toInt(), 0xFFE08C3C.toInt())

    private fun buildWires(n: Int) {
        val rnd = Random(SystemClock.uptimeMillis())
        val rights = (0 until n).shuffled(rnd)
        val step = 120f / n
        wires = (0 until n).map { i -> Wire(wirePalette[i], 36f + step * (i + 0.5f), 36f + step * (rights.indexOf(i) + 0.5f)) }
    }

    private fun drawWires(t: Long) {
        box(0f, 0f, 320f, 180f, 0xFF14161C.toInt())
        box(40f, 24f, 240f, 136f, 0xFF2A2D38.toInt())
        box(44f, 28f, 232f, 128f, 0xFF1C1E26.toInt())
        text(label, 12f, 1, PixelUi.ACCENT)
        val c = canvasRef ?: return
        paint.strokeWidth = 3f * unit
        paint.strokeCap = Paint.Cap.SQUARE
        for ((i, w) in wires.withIndex()) {
            box(60f, w.leftY - 5f, 10f, 10f, w.color)
            box(250f, w.rightY - 5f, 10f, 10f, w.color)
            if (w.connected) {
                paint.color = w.color
                c.drawLine(fx(70f), fy(w.leftY), fx(250f), fy(w.rightY), paint)
            } else if (i == dragWire) {
                paint.color = w.color
                c.drawLine(fx(70f), fy(w.leftY), dragX, dragY, paint)
            }
        }
        paint.strokeWidth = 0f
        if (wires.isNotEmpty() && wires.all { it.connected }) {
            if (doneMs == 0L) doneMs = SystemClock.uptimeMillis()
            val k = ((SystemClock.uptimeMillis() - doneMs) / 450f).coerceIn(0f, 1f)
            if ((t / 60) % 2 == 0L) box(150f, 70f, 20f, 20f, 0xFFFFF0A0.toInt())
            if (k >= 1f) end(true)
        }
    }

    // ---- Город
    private fun buildCity() {
        buildings.clear(); people.clear(); neons.clear()
        val rnd = Random(7)
        for (layer in 0..2) {
            var x = -8f
            while (x < 330f) {
                val w = 14f + rnd.nextInt(18)
                buildings += Building(x, w, 38f + layer * 18f + rnd.nextInt(30), layer, rnd.nextInt(1_000_000))
                x += w + rnd.nextInt(3)
            }
        }
        val neonColors = intArrayOf(0xFFFF3C8C.toInt(), 0xFF3CE0FF.toInt(), 0xFFFFD23C.toInt(), 0xFF7CFF5C.toInt(), 0xFFB45CFF.toInt())
        for (i in 0..8) {
            neons += Neon(14f + i * 36f + rnd.nextInt(10), 52f + rnd.nextInt(50), 14f + rnd.nextInt(14), 6f + rnd.nextInt(6), neonColors[i % neonColors.size], 500L + rnd.nextInt(900), rnd.nextInt(1000).toLong())
        }
        for (i in 0 until 26) {
            val near = i % 2 == 0
            people += Person(rnd.nextInt(320).toFloat(), if (near) 160f else 148f, (if (rnd.nextBoolean()) 1f else -1f) * (4f + rnd.nextInt(8)), 300L + i * 130L, near)
        }
    }

    private fun drawCity(t: Long) {
        for (y in 0 until 140 step 4) box(0f, y.toFloat(), 320f, 4f, lerp(0xFF04050C.toInt(), 0xFF1E2750.toInt(), y / 140f))
        for (i in 0..5) box(((i * 70f) + t / 80f) % 360f - 30f, 10f + i * 7f, 50f, 2f, 0xFF2A3A70.toInt())
        for (b in buildings) {
            val base = 140f
            val color = when (b.layer) { 0 -> 0xFF0E1226.toInt(); 1 -> 0xFF151A34.toInt(); else -> 0xFF1D2342.toInt() }
            box(b.x, base - b.h, b.w, b.h, color)
            var wy = base - b.h + 4f
            var row = 0
            while (wy < base - 4f) {
                var wx = b.x + 3f
                var col = 0
                while (wx < b.x + b.w - 3f) {
                    val hsh = (b.seed + row * 31 + col * 17) and 0xFFFF
                    val blink = hsh % 13 == 0
                    val lit = if (blink) ((t / 700) + hsh) % 2 == 0L else hsh % 3 != 0
                    if (lit) box(wx, wy, 2f, 2f, if (b.layer == 2) 0xFFE8D890.toInt() else 0xFF7A7048.toInt())
                    wx += 5f; col++
                }
                wy += 6f; row++
            }
        }
        for (n in neons) {
            val on = ((t + n.phase) / n.period) % 4 != 0L
            val c = if (on) n.color else lerp(n.color, Color.BLACK, 0.8f)
            box(n.x - 1f, n.y - 1f, n.w + 2f, n.h + 2f, 0xFF05060C.toInt())
            box(n.x, n.y, n.w, n.h, c)
            if (on) {
                box(n.x + 2f, n.y + 2f, n.w * 0.3f, n.h - 4f, Color.WHITE)
                box(n.x + n.w * 0.55f, n.y + 2f, n.w * 0.3f, 2f, Color.WHITE)
            }
        }
        box(0f, 140f, 320f, 40f, 0xFF0A0B14.toInt())
        box(0f, 140f, 320f, 2f, 0xFF2A3A70.toInt())
        for (p in people) {
            if (t < p.delay) continue
            val a = ((t - p.delay) / 700f).coerceIn(0f, 1f)
            val x = ((p.x0 + p.speed * t / 1000f) % 340f + 340f) % 340f - 10f
            val h = if (p.near) 10f else 7f
            val w = if (p.near) 4f else 3f
            val c = Color.argb((255 * a).toInt(), 4, 4, 10)
            box(x, p.y - h, w, h, c)
            box(x, p.y - h - w, w, w, c)
        }
        val ta = ((t - 2500) / 1000f).coerceIn(0f, 1f)
        if (ta > 0f) {
            box(40f, 54f, 240f, 40f, Color.argb((170 * ta).toInt(), 0, 0, 0))
            text(label, 74f, 2, PixelUi.ACCENT, (255 * ta).toInt())
        }
        if (t > 4200) hint(t)
    }

    // ---- Терминал с ошибкой
    private fun drawTerminal(t: Long) {
        val on = (t / 450) % 2 == 0L
        box(0f, 0f, 320f, 180f, if (on) 0xFF9E1414.toInt() else 0xFF5A0A0A.toInt())
        box(12f, 12f, 296f, 156f, 0xFF1A0606.toInt())
        for (y in 12 until 168 step 4) box(12f, y.toFloat(), 296f, 1f, 0xFF2A0A0A.toInt())
        box(142f, 34f, 36f, 4f, 0xFFFF4040.toInt())
        for (i in 0..14) box(160f - 2f - i * 1.2f, 38f + i * 2f, 4f + i * 2.4f, 2f, if (on) 0xFFFF4040.toInt() else 0xFF8A2020.toInt())
        box(158f, 50f, 4f, 14f, Color.BLACK)
        box(158f, 68f, 4f, 4f, Color.BLACK)
        text(label, 112f, 1, if (on) 0xFFFF6060.toInt() else 0xFFC03030.toInt())
        if (t > 600) hint(t)
    }

    // ------------------------------------------------------------------ ввод

    private fun logicalX(x: Float) = (x - ox) / unit
    private fun logicalY(y: Float) = (y - oy) / unit

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!active) return true
        val now = SystemClock.uptimeMillis()
        when (mode) {
            Mode.WIRES -> handleWireTouch(event)
            else -> if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                when (mode) {
                    Mode.WAKE -> if (doneMs == 0L) progress = min(1f, progress + 1f / count)
                    Mode.BADGE -> if (tapMs == 0L) tapMs = now
                    Mode.CRATE -> if (doneMs == 0L) {
                        taps++; shake = 1f
                        if (taps >= count) doneMs = now
                    }
                    Mode.FIGHT -> if (doneMs == 0L) { taps++; shake = 1f }
                    Mode.CITY -> if (now - startMs > 4200) end(true)
                    Mode.TERMINAL -> if (now - startMs > 600) end(true)
                    else -> {}
                }
            }
        }
        return true
    }

    private fun handleWireTouch(event: MotionEvent) {
        val lx = logicalX(event.x)
        val ly = logicalY(event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragWire = wires.indexOfFirst { !it.connected && abs(lx - 65f) < 14f && abs(ly - it.leftY) < 12f }
                dragX = event.x; dragY = event.y
            }
            MotionEvent.ACTION_MOVE -> { dragX = event.x; dragY = event.y }
            MotionEvent.ACTION_UP -> {
                val w = wires.getOrNull(dragWire)
                if (w != null && abs(lx - 255f) < 14f && abs(ly - w.rightY) < 12f) w.connected = true
                dragWire = -1
            }
            MotionEvent.ACTION_CANCEL -> dragWire = -1
        }
    }
}
