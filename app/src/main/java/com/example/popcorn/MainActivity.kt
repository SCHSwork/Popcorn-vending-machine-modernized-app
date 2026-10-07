package com.example.popcorn

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.HorizontalScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView

// ---------- red and white palette
private val RED = Color.parseColor("#D90429")
private val DEEP = Color.parseColor("#8D0A1F")
private val CREAM = Color.parseColor("#FFF6EE")
private val INK = Color.parseColor("#2B0A10")
private val MUTED = Color.parseColor("#8A5A60")
private val PINK = Color.parseColor("#F6D2D7")

// controller status bits (register 0) and error bits (register 5)
private val FLAG_NAMES = arrayOf("not ready", "fault", "last cup ok", "paid", "cups in tube", "making a cup",
    "cup at sensor", "heating", "fan running", "feed motor running", "arm moving")
private val ERR_NAMES = arrayOf("general fault", "heater did not reach temperature", "fan fault", "feed (kernel) fault",
    "communication fault", "out of cups", "cup did not drop", "fan speed mismatch")

/** Striped shop awning with scalloped edge, drawn across the top of the screen. */
class Awning(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = RED }
    override fun onDraw(c: Canvas) {
        val n = 10
        val sw = width / n.toFloat()
        val bodyH = height - sw / 2f
        line.strokeWidth = resources.displayMetrics.density * 1.5f
        for (i in 0 until n) {
            val red = i % 2 == 0
            p.color = if (red) RED else Color.WHITE
            c.drawRect(i * sw, 0f, (i + 1) * sw, bodyH, p)
            c.drawCircle(i * sw + sw / 2f, bodyH, sw / 2f, p)
            if (!red) c.drawArc(RectF(i * sw, bodyH - sw / 2f, (i + 1) * sw, bodyH + sw / 2f), 0f, 180f, false, line)
        }
        p.color = RED
        c.drawRect(0f, 0f, width.toFloat(), resources.displayMetrics.density * 6f, p)
    }
}

/** Slowly rising popcorn puffs behind everything. */
class PopRain(ctx: Context) : View(ctx) {
    private val n = 12
    private val rnd = java.util.Random(7)
    private val xs = FloatArray(n) { rnd.nextFloat() }
    private val sp = FloatArray(n) { 0.02f + rnd.nextFloat() * 0.03f }
    private val off = FloatArray(n) { rnd.nextFloat() }
    private val rs = FloatArray(n) { 0.7f + rnd.nextFloat() * 0.9f }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.parseColor("#44D90429") }
    private val t0 = System.currentTimeMillis()
    private var running = false
    private val tick = object : Runnable {
        override fun run() { if (running) { invalidate(); postDelayed(this, 60) } }
    }
    fun setRunning(b: Boolean) {
        if (b == running) return
        running = b
        visibility = if (b) VISIBLE else GONE
        if (b) post(tick)
    }
    override fun onDraw(c: Canvas) {
        val t = (System.currentTimeMillis() - t0) / 1000f
        val d = resources.displayMetrics.density
        edge.strokeWidth = d * 1.5f
        for (i in 0 until n) {
            val prog = (t * sp[i] + off[i]) % 1f
            val y = height * (1f - prog)
            val x = width * xs[i] + Math.sin((t * 0.8f + i).toDouble()).toFloat() * 14f * d
            puff(c, x, y, 14f * d * rs[i])
        }
    }
    private fun puff(c: Canvas, x: Float, y: Float, r: Float) {
        val dx = floatArrayOf(0f, -0.8f, 0.8f, -0.35f, 0.4f)
        val dy = floatArrayOf(0f, 0.3f, 0.3f, -0.7f, -0.65f)
        for (k in 0 until 5) c.drawCircle(x + dx[k] * r, y + dy[k] * r, r * 0.7f, edge)
        for (k in 0 until 5) c.drawCircle(x + dx[k] * r, y + dy[k] * r, r * 0.7f, fill)
    }
}

/** The screen is a hot pan. Kernels fall in from the top, shake as the pan heats, and pop one by one as the cup progresses;
 *  popped corn bounces off the walls and piles up. When the cup is done the whole pan of popcorn falls out of the bottom of the screen. */
class PopMeter(ctx: Context) : View(ctx) {
    var onShown: ((Int, Float) -> Unit)? = null
    private var target = 0f
    private var virt = 0f
    private var shown = 0f
    private var rate = 0f                 // percent per second, learned from the polled values
    private var lastChange = 0L
    private var heatT = 0f                // 0..1 pan heat (from the real plate temperature)
    private var heat = 0f
    private var pulse = 0f
    private var lastMilestone = 0
    private var lastT = 0L
    private val rnd = java.util.Random()

    // flying popcorn
    private class P(var x: Float, var y: Float, var vx: Float, var vy: Float, var sc: Float, var rot: Float, var vr: Float, var spr: Int, var hits: Int = 0, var age: Float = 0f)
    private val parts = ArrayList<P>()
    // pop flashes
    private class F(var x: Float, var y: Float, var age: Float)
    private val flashes = ArrayList<F>()

    // kernels
    private val NK = 150
    private val kx = FloatArray(NK); private val kyT = FloatArray(NK); private val kyC = FloatArray(NK)
    private val kvx = FloatArray(NK); private val kav = FloatArray(NK)
    private val kPre = FloatArray(NK)       // seconds left in the swell-up just before a kernel bursts
    private class G(var x: Float, var y: Float, var rot: Float, var idx: Int, var age: Float)
    private val ghosts = ArrayList<G>()    // the kernel that fades out as its popcorn grows
    private val kvy = FloatArray(NK); private val kAng = FloatArray(NK); private val kt = FloatArray(NK)
    private val kAlive = BooleanArray(NK) { true }
    private var kw = 0
    private var dropped = false
    private var dropP = 0f
    private var dropT = 0f                // seconds since the kernels were dropped
    private var popped = 0                // kernels that have really popped; the pile is built only from these
    private var pileShown = 0f
    private var capBmp: Bitmap? = null         // the lumpy top edge of the pile, built the same way as the body
    private var capTop = 0f
    private var tile: Bitmap? = null        // a seamless block of densely packed popcorn, repeated to fill the pile
    private var tileH = 0
    private var kSprites: Array<Bitmap>? = null
    private var kSize = 0
    private val kPaints: Array<Paint> = Array<Paint>(5) { i ->
        val f = i / 4f                    // 0 = fresh golden kernel, 4 = well cooked and darker
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = android.graphics.LightingColorFilter(Color.rgb((255 - 65 * f).toInt(), (255 - 115 * f).toInt(), (255 - 140 * f).toInt()), 0)
        }
    }

    // drain-out at the end
    private var draining = false
    private var drainT = 0f
    private var pourAcc = 0f
    private var drainDone: (() -> Unit)? = null

    // popcorn sprites, built once from the screen width
    private var sprites: Array<Bitmap>? = null
    private var sprSize = 0
    private val bp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val base = Paint(Paint.ANTI_ALIAS_FLAG)
    private val panP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val kern = Paint(Paint.ANTI_ALIAS_FLAG)
    private val kHi = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 255, 235, 190) }
    private val spot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#33A8741E") }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
    private val glowF = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFF3C8") }
    private val frame = object : Runnable {
        override fun run() { if (isShown) { invalidate(); postOnAnimation(this) } }
    }

    fun reset() {
        target = 0f; virt = 0f; shown = 0f; rate = 0f; lastChange = 0L; lastMilestone = 0; pulse = 0f
        heat = 0f; heatT = 0f; parts.clear(); flashes.clear(); lastT = 0L; kw = 0
        dropped = false; dropP = 0f; dropT = 0f; popped = 0; pileShown = 0f; ghosts.clear(); for (q in 0 until NK) kPre[q] = 0f; draining = false; drainT = 0f; pourAcc = 0f; drainDone = null
    }

    /** Plate temperature from the controller, as 0..1 of a ~190 C cooking plate. */
    fun setHeat(tempC: Int) { heatT = (tempC / 185f).coerceIn(0f, 1f) }

    /** The machine is feeding kernels: let them fall in from the top. */
    fun dropKernels() {
        if (dropped || draining) return
        dropped = true; dropP = (shown / 100f).coerceIn(0f, 0.9f)
        for (i in 0 until NK) {                     // kernels pour in from the hopper at top centre, then bounce and roll outward
            kx[i] = width * (0.5f + (rnd.nextFloat() - 0.5f) * 0.36f)
            kyC[i] = -(rnd.nextFloat() * 0.8f * height + 20f)
            kvx[i] = (rnd.nextFloat() - 0.5f) * 140f; kvy[i] = rnd.nextFloat() * 200f; kav[i] = (rnd.nextFloat() - 0.5f) * 360f
        }
    }

    /** The cup is done: everything falls out of the bottom of the screen, then `done` runs. */
    fun drain(done: () -> Unit) {
        if (draining) return
        draining = true; drainT = 0f; drainDone = done
    }

    /** Called with each polled progress value; learns the speed so the fill keeps moving between polls. */
    fun setProgress(v: Float) {
        val now = System.nanoTime()
        if (v != target) {
            if (lastChange != 0L) {
                val dt = (now - lastChange) / 1e9f
                if (dt > 0.05f && v > target) rate = if (rate == 0f) (v - target) / dt else 0.5f * rate + 0.5f * (v - target) / dt
            }
            lastChange = now
            if (v < target) { virt = v; shown = v; lastMilestone = (v / 25f).toInt() * 25 }
            target = v
        }
    }

    override fun onVisibilityChanged(v: View, vis: Int) {
        super.onVisibilityChanged(v, vis)
        removeCallbacks(frame)
        if (vis == VISIBLE) { lastT = 0L; postOnAnimation(frame) }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); removeCallbacks(frame); postOnAnimation(frame) }
    override fun onDetachedFromWindow() { removeCallbacks(frame); super.onDetachedFromWindow() }

    private fun mix(a: Int, b: Int, f: Float): Int {
        val t = f.coerceIn(0f, 1f)
        return Color.rgb((Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt())
    }

    private fun loadAsset(name: String): Bitmap? = try {
        context.assets.open(name).use { android.graphics.BitmapFactory.decodeStream(it) }
    } catch (e: Throwable) { null }

    /** Shrinks in halves so the picture stays smooth instead of aliasing. */
    private fun shrink(b: Bitmap, tw: Int, th: Int): Bitmap {
        var cur = b
        while (cur.width / 2 >= tw && cur.height / 2 >= th) cur = Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
        return Bitmap.createScaledBitmap(cur, tw, th, true)
    }

    /** The shaded popcorn and kernel pictures bundled in the app (made by gen_popcorn_art.py). Null if they are missing. */
    private fun loadPieces(sz: Int): Array<Bitmap>? {
        val out = ArrayList<Bitmap>()
        for (i in 0 until 12) { val b = loadAsset("pc$i.png") ?: return null; out.add(shrink(b, sz, sz)) }
        return out.toTypedArray()
    }
    private fun loadKernels(sz: Int): Array<Bitmap>? {
        val out = ArrayList<Bitmap>()
        for (i in 0 until 4) { val b = loadAsset("k$i.png") ?: return null; out.add(shrink(b, sz, (sz * b.height / b.width.toFloat()).toInt())) }
        return out.toTypedArray()
    }

    /** Fallback if the pictures are missing: ten procedurally drawn popped-corn pieces. lumpy cream lobes with soft shading, an outline, and (on most) a golden hull at the base. */
    private fun buildSprites(sz: Int): Array<Bitmap> = Array(10) { i ->
        val bmp = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val rr = java.util.Random(100L + i * 13L)
        val c0 = sz / 2f
        val n = 6 + rr.nextInt(4)
        val lx = FloatArray(n + 1); val ly = FloatArray(n + 1); val lr = FloatArray(n + 1)
        lx[0] = c0; ly[0] = c0; lr[0] = sz * 0.25f
        for (k in 1..n) {
            val ang = (2.0 * Math.PI * k / n + rr.nextDouble() * 0.5)
            val dist = sz * (0.17f + 0.08f * rr.nextFloat())
            lx[k] = c0 + Math.cos(ang).toFloat() * dist; ly[k] = c0 + Math.sin(ang).toFloat() * dist * 0.92f
            lr[k] = sz * (0.15f + 0.08f * rr.nextFloat())
        }
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.argb(70, 120, 80, 20)                                   // soft shadow
        for (k in 0..n) cv.drawCircle(lx[k] + sz * 0.025f, ly[k] + sz * 0.045f, lr[k] * 1.08f, p)
        p.color = Color.parseColor("#D2B278")                                   // outline
        for (k in 0..n) cv.drawCircle(lx[k], ly[k], lr[k] * 1.07f, p)
        for (k in 0..n) {                                                       // body: each lobe lit from the upper left
            val tint = if (rr.nextInt(4) == 0) Color.parseColor("#FBE3A0") else Color.parseColor("#FFEFC6")
            p.shader = android.graphics.RadialGradient(lx[k] - lr[k] * 0.35f, ly[k] - lr[k] * 0.4f, lr[k] * 1.25f,
                intArrayOf(Color.WHITE, Color.parseColor("#FFF8E6"), tint), floatArrayOf(0f, 0.45f, 1f), android.graphics.Shader.TileMode.CLAMP)
            cv.drawCircle(lx[k], ly[k], lr[k], p)
        }
        p.shader = null
        p.color = Color.argb(60, 190, 140, 50)                                  // creases between lobes
        p.style = Paint.Style.STROKE; p.strokeWidth = sz * 0.012f
        for (k in 1..n) cv.drawArc(RectF(lx[k] - lr[k] * 0.6f, ly[k] - lr[k] * 0.6f, lx[k] + lr[k] * 0.6f, ly[k] + lr[k] * 0.6f), 200f, 110f, false, p)
        p.style = Paint.Style.FILL
        if (i % 3 != 0) {                                                       // golden hull bit at the base
            val hx = c0 + sz * (rr.nextFloat() - 0.5f) * 0.12f; val hy = c0 + sz * 0.27f
            p.color = Color.parseColor("#8A5712"); cv.drawOval(RectF(hx - sz * 0.07f, hy - sz * 0.045f, hx + sz * 0.07f, hy + sz * 0.05f), p)
            p.color = Color.parseColor("#D49A2A"); cv.drawOval(RectF(hx - sz * 0.055f, hy - sz * 0.04f, hx + sz * 0.04f, hy + sz * 0.02f), p)
        }
        bmp
    }

    /** Four unpopped corn kernels: a rounded crown tapering to a pointed tip, golden with a bright highlight, a dimple and a darker rim. */
    private fun buildKernels(sz: Int): Array<Bitmap> = Array(4) { i ->
        val w = sz.toFloat(); val h = sz * 1.25f
        val bmp = Bitmap.createBitmap(sz, h.toInt(), Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val rr = java.util.Random(7L + i * 31L)
        val cw = w * (0.46f + 0.05f * rr.nextFloat())               // half width of the crown
        val top = h * 0.08f; val tip = h * 0.95f; val mid = h * 0.30f
        val path = Path().apply {
            moveTo(w / 2f, tip)
            cubicTo(w / 2f - cw * 0.55f, h * 0.72f, w / 2f - cw, h * 0.5f, w / 2f - cw, mid)       // left side, up to the crown
            cubicTo(w / 2f - cw, top, w / 2f - cw * 0.4f, top - h * 0.02f, w / 2f, top)
            cubicTo(w / 2f + cw * 0.4f, top - h * 0.02f, w / 2f + cw, top, w / 2f + cw, mid)
            cubicTo(w / 2f + cw, h * 0.5f, w / 2f + cw * 0.55f, h * 0.72f, w / 2f, tip)
            close()
        }
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.argb(70, 80, 40, 0)                                                      // soft shadow
        cv.save(); cv.translate(w * 0.03f, h * 0.025f); cv.drawPath(path, p); cv.restore()
        p.shader = android.graphics.LinearGradient(0f, top, 0f, tip, intArrayOf(Color.parseColor("#FFD84A"), Color.parseColor("#F2A81C"), Color.parseColor("#C9780E")),
            floatArrayOf(0f, 0.55f, 1f), android.graphics.Shader.TileMode.CLAMP)
        cv.drawPath(path, p)
        p.shader = android.graphics.RadialGradient(w * 0.38f, h * 0.24f, w * 0.38f, Color.argb(190, 255, 250, 215), Color.argb(0, 255, 250, 215), android.graphics.Shader.TileMode.CLAMP)
        cv.drawPath(path, p)                                                                     // glossy highlight
        p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = w * 0.07f; p.color = Color.parseColor("#9A5A08")
        cv.drawPath(path, p)                                                                     // rim
        p.strokeWidth = w * 0.05f; p.color = Color.argb(120, 130, 70, 5); p.strokeCap = Paint.Cap.ROUND
        cv.drawArc(RectF(w * 0.30f, h * 0.10f, w * 0.70f, h * 0.30f), 20f, 140f, false, p)    // the dimple on the crown
        bmp
    }

    /** Packs real popcorn pieces edge to edge into one block that tiles top-to-bottom, with dark gaps between the pieces for depth. */
    private fun buildTile(w: Int, th: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, th, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.drawColor(Color.parseColor("#A87A2E"))
        val rr = java.util.Random(55L)
        val dx = sprSize * 0.46f; val dy = sprSize * 0.27f
        val rows = Math.ceil((th / dy).toDouble()).toInt()
        val cols = Math.ceil((w / dx).toDouble()).toInt() + 2
        for (r in 0 until rows) for (i in 0 until cols) {
            val x = (i - 0.5f) * dx + (if (r % 2 == 0) 0f else dx / 2f) + (rr.nextFloat() - 0.5f) * dx * 0.5f
            val y = r * dy + (rr.nextFloat() - 0.5f) * dy * 0.5f
            val sc = 0.95f + 0.25f * rr.nextFloat(); val rot = rr.nextFloat() * 360f; val idx = rr.nextInt(10)
            sprite(cv, idx, x, y, sc, rot)
            if (y < sprSize) sprite(cv, idx, x, y + th, sc, rot)            // wrap so the block repeats without a seam
            if (y > th - sprSize) sprite(cv, idx, x, y - th, sc, rot)
        }
        return bmp
    }

    /** The top few rows of the pile, laid out exactly like the body block but with gaps along the top so the edge is lumpy. */
    private fun buildCap(w: Int): Bitmap {
        val dx = sprSize * 0.46f; val dy = sprSize * 0.27f
        val rows = 8
        capTop = sprSize * 0.7f
        val lastY = capTop + (rows - 1) * dy
        val hgt = (lastY + sprSize * 0.6f).toInt()          // just enough room for the last row of pieces
        val bmp = Bitmap.createBitmap(w, hgt, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val rr = java.util.Random(77L)
        val fill = floatArrayOf(0.5f, 0.78f, 0.94f, 1f, 1f, 1f, 1f, 1f)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#A87A2E") }
        cv.drawRect(0f, capTop + dy * 2.2f, w.toFloat(), lastY + dy * 0.2f, bg)      // dark gaps from under the lumpy rows down to the last row only; below that the body shows through
        val cols = Math.ceil((w / dx).toDouble()).toInt() + 2
        for (r in 0 until rows) for (i in 0 until cols) {
            val x = (i - 0.5f) * dx + (if (r % 2 == 0) 0f else dx / 2f) + (rr.nextFloat() - 0.5f) * dx * 0.5f
            val y = capTop + r * dy + (rr.nextFloat() - 0.5f) * dy * 0.5f
            val sc = 0.95f + 0.25f * rr.nextFloat(); val rot = rr.nextFloat() * 360f; val idx = rr.nextInt(12)
            if (rr.nextFloat() <= fill[r]) sprite(cv, idx, x, y, sc, rot)
        }
        return bmp
    }

    private fun kernel(c: Canvas, idx: Int, x: Float, y: Float, rot: Float, level: Int, sc: Float = 1f, alpha: Int = 255) {
        val ks = kSprites ?: return
        val b = ks[idx % ks.size]
        val pp = kPaints[level.coerceIn(0, 4)]
        pp.alpha = alpha
        c.save(); c.translate(x, y); c.rotate(rot); c.scale(sc, sc)
        c.drawBitmap(b, -b.width / 2f, -b.height / 2f, pp)
        c.restore()
        pp.alpha = 255
    }

    private fun sprite(c: Canvas, idx: Int, x: Float, y: Float, sc: Float, rot: Float) {
        val s = sprites ?: return
        val b = s[idx % s.size]
        c.save(); c.translate(x, y); c.rotate(rot); c.scale(sc, sc)
        c.drawBitmap(b, -b.width / 2f, -b.height / 2f, bp)
        c.restore()
    }

    /** Launch a piece upward; `power` 0..1 is how high it flies (1 = to the top of the pan). */
    private fun spawn(x: Float, y: Float, power: Float, g: Float, w: Float, h: Float) {
        if (parts.size > 48) return
        val vy = -Math.sqrt((2f * g * h * power).toDouble()).toFloat()
        val vx = (rnd.nextFloat() - 0.5f) * w * 1.6f
        parts.add(P(x, y, vx, vy, 0.8f + rnd.nextFloat() * 0.35f, rnd.nextFloat() * 360f, (rnd.nextFloat() - 0.5f) * 700f, rnd.nextInt(12)))
    }

    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastT == 0L) 0.016f else ((now - lastT) / 1e9f).coerceAtMost(0.1f)
        lastT = now
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val d = resources.displayMetrics.density
        val t = now / 1e9f
        val pr = w * 0.07f
        val g = h * 3.2f      // gravity scaled to the screen: a full-height pop takes about 0.8 s to peak
        val wall = 7f * d
        val floorH = 44f * d
        val floorTop = h - floorH
        if (kSprites == null || kSize != (14f * d).toInt()) { kSize = (14f * d).toInt().coerceAtLeast(8); kSprites = loadKernels(kSize) ?: buildKernels(kSize) }
        if (sprites == null || sprSize != (pr * 2.6f).toInt()) { sprSize = (pr * 2.6f).toInt().coerceAtLeast(24); sprites = loadPieces(sprSize) ?: buildSprites(sprSize); tile = null; capBmp = null }
        if ((capBmp?.width ?: -1) != width) capBmp = buildCap(width)
        val tileW = tile?.width ?: -1
        if (tileW != width) { tileH = (sprSize * 2.2f).toInt(); tile = buildTile(width, tileH) }

        // glide: run ahead at the learned speed (never more than ~1.5 polls past the real value), then ease onto it
        virt += rate * dt
        val cap = if (target >= 100f) 100f else minOf(99.5f, target + rate * 1.5f)
        if (virt > cap) virt = cap
        if (virt < target - 1f) virt = target - 1f
        shown += (virt - shown) * (1f - Math.exp((-6.0 * dt).toDouble()).toFloat())
        rate *= Math.exp((-0.15 * dt).toDouble()).toFloat()
        val p = (shown / 100f).coerceIn(0f, 1f)
        heat += (maxOf(heatT, minOf(1f, 0.15f + p * 1.4f)) - heat) * (1f - Math.exp((-1.5 * dt).toDouble()).toFloat())

        // fallback: if the feed signal was missed, drop the kernels once the bar is well along
        if (!dropped && !draining && shown >= 25f) dropKernels()
        val eff = if (dropped) ((p - dropP) / (1f - dropP + 0.0001f)).coerceIn(0f, 1f) else 0f
        if (dropped && !draining) dropT += dt

        val m = (shown / 25f).toInt() * 25
        if (m > lastMilestone && m <= 100) { lastMilestone = m; pulse = 1f }
        pulse = (pulse - dt * 2.2f).coerceAtLeast(0f)

        // drain: the floor opens like a trap door and the popcorn pours out of the bottom of the screen
        var poff = 0f; var door = 0f
        val pileTop0 = floorTop - floorTop * pileShown
        if (draining) {
            drainT += dt
            door = (drainT / 0.35f).coerceIn(0f, 1f)
            val tp = (drainT - 0.2f).coerceAtLeast(0f)
            poff = 0.5f * g * 0.5f * tp * tp
            if (drainT > 0.15f && pileTop0 + poff < h + sprSize && parts.size < 90) {        // loose pieces stream out through the opening
                pourAcc += 70f * dt
                while (pourAcc >= 1f) {
                    pourAcc -= 1f
                    val fx = w * (0.5f + (rnd.nextFloat() - 0.5f) * 0.9f)
                    parts.add(P(fx, floorTop - rnd.nextFloat() * sprSize * 0.6f, (rnd.nextFloat() - 0.5f) * w * 0.15f, h * (0.25f + 0.4f * rnd.nextFloat()),
                        0.8f + rnd.nextFloat() * 0.35f, rnd.nextFloat() * 360f, (rnd.nextFloat() - 0.5f) * 500f, rnd.nextInt(12), 9))
                }
            }
            if (pileTop0 + poff > h + sprSize * 1.2f && parts.isEmpty()) { val cb = drainDone; drainDone = null; draining = false; cb?.invoke() }
            else if (drainT > 2.8f) { val cb = drainDone; drainDone = null; draining = false; parts.clear(); cb?.invoke() }
        }
        pileShown += ((popped / (NK * 0.95f)).coerceAtMost(1f) - pileShown) * (1f - Math.exp((-4.0 * dt).toDouble()).toFloat())
        val pile = pileShown
        val level = floorTop - floorTop * pile

        if (kw != width) {
            kw = width
            for (i in 0 until NK) {
                kx[i] = wall + 6f * d + rnd.nextFloat() * (w - 2f * wall - 12f * d)
                kyT[i] = floorTop - 5f * d - rnd.nextFloat() * 26f * d
                kt[i] = 0.03f + 0.92f * rnd.nextFloat()
                kAng[i] = rnd.nextFloat() * 180f
                kAlive[i] = true
                kyC[i] = kyT[i]
            }
        }

        c.save()

        // the inside of the pan: dark, so the cream popcorn and golden kernels stand out
        base.shader = android.graphics.LinearGradient(0f, 0f, 0f, floorTop, Color.parseColor("#3A0610"), Color.parseColor("#6E0F1E"), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, floorTop, base)
        base.shader = null

        // glow rising off the hot floor
        val hot = Color.parseColor("#FF4B2B")
        if (heat > 0.05f) {
            glowP.shader = android.graphics.LinearGradient(0f, floorTop - 150f * d, 0f, floorTop,
                Color.argb(0, 255, 120, 60), Color.argb((90 * heat).toInt(), 255, 90, 40), android.graphics.Shader.TileMode.CLAMP)
            c.drawRect(0f, floorTop - 150f * d, w, floorTop, glowP)
        }

        // the pile of popped corn: a warm mass with real-looking pieces over it
        if (pile > 0.02f) {
            c.save(); c.translate(0f, poff)
            val tb = tile
            if (tb != null) {                                           // the body of the pile: packed popcorn, fixed to the floor so it doesn't slide as the pile grows
                c.save()
                c.clipRect(0f, level + sprSize * 0.27f * 4f, w, floorTop)
                var yy = floorTop - tileH
                while (yy + tileH > level) { c.drawBitmap(tb, 0f, yy, null); yy -= tileH }
                val shadeFrom = level + sprSize * 2.2f                    // starts below the lumpy top, so the two blend without a seam
                base.shader = android.graphics.LinearGradient(0f, shadeFrom, 0f, floorTop, Color.argb(0, 60, 30, 0), Color.argb(110, 60, 30, 0), android.graphics.Shader.TileMode.CLAMP)
                c.drawRect(0f, shadeFrom, w, floorTop, base)             // deeper corn is a little darker
                c.restore()
            }
            val cb = capBmp
            if (cb != null) {                                           // the lumpy top of the pile (clipped so it never pokes below the floor)
                c.save(); c.clipRect(0f, 0f, w, floorTop)
                c.drawBitmap(cb, 0f, level - capTop, null)
                c.restore()
                if (draining && floorTop - level > sprSize * 3f) {          // ragged bottom edge of the pile as it drops through the opening
                    c.save(); c.scale(1f, -1f, 0f, floorTop); c.drawBitmap(cb, 0f, floorTop - capTop, null); c.restore()
                }
            }
            c.restore()
        }

        // the pan floor: two doors hinged at the sides swing down and open
        panP.shader = android.graphics.LinearGradient(0f, floorTop, 0f, h, mix(Color.parseColor("#6A6A72"), hot, heat), mix(Color.parseColor("#2E2E34"), Color.parseColor("#A02012"), heat), android.graphics.Shader.TileMode.CLAMP)
        if (door <= 0f) {
            c.drawRect(0f, floorTop, w, h, panP)
            panP.shader = null; panP.color = Color.argb(70, 255, 255, 255)
            c.drawRect(0f, floorTop, w, floorTop + 3f * d, panP)
        } else {
            val hy = floorTop + floorH * 0.5f
            c.save(); c.rotate(door * 88f, 0f, hy); c.drawRect(0f, floorTop, w / 2f, h, panP); c.restore()
            c.save(); c.rotate(-door * 88f, w, hy); c.drawRect(w / 2f, floorTop, w, h, panP); c.restore()
            panP.shader = null
        }

        // kernels: real physics. They fall, bounce, collide with each other and the walls, pile up and jostle as the pan heats, then pop.
        if (dropped && !draining) {
            val rK = kSize * 0.42f
            val gk = g * 0.6f
            val sub = 2; val hs = Math.min(dt, 0.033f) / sub
            for (step in 0 until sub) {
                for (i in 0 until NK) {
                    if (!kAlive[i]) continue
                    if (kPre[i] > 0f) { kvx[i] = 0f; kvy[i] = 0f; kav[i] = 0f; continue }     // a kernel about to burst holds still and swells
                    kvy[i] += gk * hs
                    val sp2 = kvx[i] * kvx[i] + kvy[i] * kvy[i]; val vmax = h * 0.9f   // no kernel is ever allowed to shoot off
                    if (sp2 > vmax * vmax) { val k = vmax / Math.sqrt(sp2.toDouble()).toFloat(); kvx[i] *= k; kvy[i] *= k }
                    kav[i] = kav[i].coerceIn(-720f, 720f)
                    kx[i] += kvx[i] * hs; kyC[i] += kvy[i] * hs; kAng[i] += kav[i] * hs
                    if (kx[i] < wall + rK) { kx[i] = wall + rK; kvx[i] = Math.abs(kvx[i]) * 0.5f }
                    if (kx[i] > w - wall - rK) { kx[i] = w - wall - rK; kvx[i] = -Math.abs(kvx[i]) * 0.5f }
                    val fy = floorTop - rK * 0.9f
                    if (kyC[i] > fy) {
                        kyC[i] = fy
                        kvy[i] = if (kvy[i] > 60f * d) -kvy[i] * 0.35f else 0f
                        kvx[i] *= 0.97f; kav[i] = kvx[i] / rK * 57f
                    }
                }
                for (i in 0 until NK) {                                  // kernel against kernel
                    if (!kAlive[i]) continue
                    for (j in i + 1 until NK) {
                        if (!kAlive[j]) continue
                        val ddx = kx[j] - kx[i]; val ddy = kyC[j] - kyC[i]
                        val rr2 = (2f * rK * 0.92f)
                        if (Math.abs(ddx) > rr2 || Math.abs(ddy) > rr2) continue
                        val dd2 = ddx * ddx + ddy * ddy
                        if (dd2 >= rr2 * rr2 || dd2 < 0.0001f) continue
                        val dd = Math.sqrt(dd2.toDouble()).toFloat()
                        val nx = ddx / dd; val ny = ddy / dd
                        val push = (rr2 - dd) * 0.5f
                        kx[i] -= nx * push; kyC[i] -= ny * push; kx[j] += nx * push; kyC[j] += ny * push
                        val rv = (kvx[j] - kvx[i]) * nx + (kvy[j] - kvy[i]) * ny      // closing speed along the contact
                        if (rv < 0f) {
                            val jn = -(1f + 0.3f) * rv * 0.5f
                            kvx[i] -= jn * nx; kvy[i] -= jn * ny; kvx[j] += jn * nx; kvy[j] += jn * ny
                            val tx = -ny; val ty = nx                                   // a little spin from the rub
                            val tv = (kvx[j] - kvx[i]) * tx + (kvy[j] - kvy[i]) * ty
                            kav[i] += tv * 0.4f; kav[j] += tv * 0.4f
                        }
                    }
                }
            }
            for (i in 0 until NK) {
                if (!kAlive[i]) continue
                val near = (1f - (kt[i] - eff) * 8f).coerceIn(0f, 1f)                 // kernels about to pop jump harder
                if (dropT > 0.8f && rnd.nextFloat() < (0.25f + 2.5f * heat) * dt * (1f + 2f * near)) {   // the heat makes them hop and jostle
                    val hop = kSize * (0.4f + 1.1f * rnd.nextFloat()) * (1f + 1.5f * near)          // hop height in kernel sizes, so it scales with the screen
                    kvy[i] -= Math.sqrt((2f * gk * hop).toDouble()).toFloat()
                    kvx[i] += (rnd.nextFloat() - 0.5f) * kSize * 8f
                    kav[i] += (rnd.nextFloat() - 0.5f) * 300f
                }
                if (kPre[i] == 0f && eff >= kt[i] && dropT > 1.8f) kPre[i] = 0.2f          // start the swell
                if (kPre[i] > 0f) {
                    kPre[i] -= dt
                    val f = (1f - kPre[i] / 0.2f).coerceIn(0f, 1f)
                    val sh = (1.5f + 3.5f * f) * d
                    kernel(c, i, kx[i] + Math.sin((t * 70f + i).toDouble()).toFloat() * sh, kyC[i] + Math.cos((t * 63f + i).toDouble()).toFloat() * sh * 0.6f,
                        kAng[i], 0, 1f + 0.5f * f * f, 255)                                // swelling, hot, shaking harder
                    if (kPre[i] <= 0f) {                                                   // BURST
                        kPre[i] = 0f; kAlive[i] = false; popped++
                        val before = parts.size
                        spawn(kx[i], kyC[i], 0.25f + 0.7f * rnd.nextFloat(), g, w, level.coerceAtLeast(h * 0.3f))
                        if (parts.size > before) { val q = parts[parts.size - 1]; q.rot = kAng[i]; q.age = 0f }
                        ghosts.add(G(kx[i], kyC[i], kAng[i], i, 0f))
                        if (flashes.size < 24) flashes.add(F(kx[i], kyC[i], 0f))
                        for (k in 0 until 2) if (parts.size < 70)                           // two bits of hull fly off
                            parts.add(P(kx[i], kyC[i], (rnd.nextFloat() - 0.5f) * w * 0.7f, -(0.15f + 0.3f * rnd.nextFloat()) * h, 0.7f + 0.6f * rnd.nextFloat(),
                                rnd.nextFloat() * 360f, (rnd.nextFloat() - 0.5f) * 900f, -1, 2))
                    }
                    continue
                }
                val lvl = if (dropT < 1f) 0 else ((heat * 0.6f + eff * 0.5f) * 4f + 0.5f).toInt()
                kernel(c, i, kx[i], kyC[i], kAng[i], lvl)
            }
            if (popped > 3 && eff < 0.98f && rnd.nextFloat() < 3f * eff * dt)
                spawn(w * (0.08f + 0.84f * rnd.nextFloat()), level, 0.2f + 0.6f * rnd.nextFloat(), g, w, level.coerceAtLeast(h * 0.3f))
        }
        c.restore()

        if (pulse >= 0.99f && !draining && popped > 3) for (i in 0 until 14) spawn(w * (i + 0.5f) / 14f, level, 0.5f + 0.45f * rnd.nextFloat(), g, w, level.coerceAtLeast(h * 0.3f))

        // the kernel that has just burst fades out while its popcorn swells from the same spot
        val gi = ghosts.iterator()
        while (gi.hasNext()) {
            val gh = gi.next(); gh.age += dt
            if (gh.age > 0.12f) { gi.remove(); continue }
            kernel(c, gh.idx, gh.x, gh.y, gh.rot, 0, 1f + 0.5f, (255 * (1f - gh.age / 0.12f)).toInt())
        }

        // pop flashes
        val fi = flashes.iterator()
        while (fi.hasNext()) {
            val f = fi.next(); f.age += dt
            if (f.age > 0.28f) { fi.remove(); continue }
            val k = f.age / 0.28f
            glowF.alpha = (170 * (1f - k) * (1f - k)).toInt()
            c.drawCircle(f.x, f.y, (5f + 15f * k) * d, glowF)
            ring.alpha = (200 * (1f - k)).toInt(); ring.strokeWidth = 2.2f * d
            c.drawCircle(f.x, f.y, (4f + 20f * k) * d, ring)
        }

        // flying corn: bounces off the walls, the ceiling and the pile; while draining it just falls away
        val it = parts.iterator()
        while (it.hasNext()) {
            val q = it.next()
            q.vy += g * dt
            q.x += q.vx * dt; q.y += q.vy * dt; q.rot += q.vr * dt
            val half = sprSize * 0.3f * q.sc
            if (!draining) {
                if (q.x < wall + half) { q.x = wall + half; q.vx = Math.abs(q.vx) * 0.9f }
                if (q.x > w - wall - half) { q.x = w - wall - half; q.vx = -Math.abs(q.vx) * 0.9f }
                if (q.y < wall + half) { q.y = wall + half; q.vy = Math.abs(q.vy) * 0.6f }
                if (q.vy > 0 && q.y > level - half * 0.3f) {
                    q.hits++
                    if (q.hits > 2 || q.vy < h * 0.35f || level >= floorTop) { it.remove(); continue }
                    q.y = level - half * 0.3f; q.vy = -q.vy * 0.5f; q.vx *= 0.7f; q.vr *= 0.5f
                }
            } else if (q.y > h + sprSize) { it.remove(); continue }
            q.age += dt
            if (q.spr < 0) {                                           // a bit of hull
                kern.color = Color.parseColor("#9A5A12")
                c.save(); c.translate(q.x, q.y); c.rotate(q.rot)
                c.drawOval(RectF(-kSize * 0.2f * q.sc, -kSize * 0.11f * q.sc, kSize * 0.2f * q.sc, kSize * 0.11f * q.sc), kern)
                c.restore()
            } else {
                val a = Math.min(1f, q.age / 0.24f) - 1f                // swells from kernel size with a small overshoot, like a real pop
                val back = 1f + 2.70158f * a * a * a + 1.70158f * a * a
                sprite(c, q.spr, q.x, q.y, q.sc * (0.3f + 0.7f * back), q.rot)
            }
        }

        // pan walls: dark metal that heats up
        c.save()
        box.color = mix(Color.parseColor("#4C4C54"), Color.parseColor("#FF6A3D"), heat)
        box.strokeWidth = wall * 1.6f
        c.drawRoundRect(RectF(wall * 0.8f, wall * 0.8f, w - wall * 0.8f, h - wall * 0.8f), 14f * d, 14f * d, box)
        c.restore()
        onShown?.invoke(Math.round(shown), pulse)
    }
}

/** Animated pictogram for the error / notice screen. kind: 0 wait, 1 out of cups, 2 cup stuck, 3 heater, 4 fan, 5 kernels, 6 link, 7 general. */
class FaultArt(ctx: Context) : View(ctx) {
    var kind = 7
        set(v) { field = v; invalidate() }
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val soft = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sign = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = RED; textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-black", Typeface.NORMAL) }
    private val frame = object : Runnable { override fun run() { if (isShown) { invalidate(); postOnAnimation(this) } } }
    override fun onVisibilityChanged(v: View, vis: Int) { super.onVisibilityChanged(v, vis); removeCallbacks(frame); if (vis == VISIBLE) postOnAnimation(frame) }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); removeCallbacks(frame); postOnAnimation(frame) }
    override fun onDetachedFromWindow() { removeCallbacks(frame); super.onDetachedFromWindow() }

    private fun sinf(x: Float) = Math.sin(x.toDouble()).toFloat()
    private fun cosf(x: Float) = Math.cos(x.toDouble()).toFloat()

    override fun onDraw(c: Canvas) {
        val s = Math.min(width, height).toFloat()
        if (s <= 0f) return
        val t = (System.nanoTime() / 1e9f)
        val cx = width / 2f; val cy = height / 2f
        c.save(); c.translate(cx - s / 2f, cy - s / 2f)
        // pulsing halo and two expanding rings
        soft.style = Paint.Style.FILL; soft.color = Color.argb(34, 255, 255, 255)
        c.drawCircle(s / 2f, s / 2f, s * 0.42f * (1f + 0.04f * sinf(t * 2.2f)), soft)
        soft.style = Paint.Style.STROKE; soft.strokeWidth = s * 0.012f
        for (k in 0 until 2) {
            val f = ((t * 0.5f + k * 0.5f) % 1f)
            soft.color = Color.argb((90 * (1f - f)).toInt(), 255, 255, 255)
            c.drawCircle(s / 2f, s / 2f, s * (0.42f + 0.08f * f), soft)
        }
        line.strokeWidth = s * 0.045f
        when (kind) {
            0 -> {                                   // spinner of dots
                for (i in 0 until 10) {
                    val a = (t * 2.4f - i * 0.5f)
                    val x = s / 2f + cosf(a) * s * 0.2f; val y = s / 2f + sinf(a) * s * 0.2f
                    white.alpha = (255 * (1f - i / 10f)).toInt()
                    c.drawCircle(x, y, s * (0.045f - i * 0.002f), white)
                }
                white.alpha = 255
            }
            1 -> {                                   // empty cup, dashed outline, bobbing
                val bob = sinf(t * 2.4f) * s * 0.02f
                val p = Path().apply {
                    moveTo(s * 0.32f, s * 0.30f + bob); lineTo(s * 0.68f, s * 0.30f + bob)
                    lineTo(s * 0.62f, s * 0.70f + bob); lineTo(s * 0.38f, s * 0.70f + bob); close()
                }
                line.pathEffect = android.graphics.DashPathEffect(floatArrayOf(s * 0.05f, s * 0.04f), t * s * 0.08f)
                c.drawPath(p, line); line.pathEffect = null
                soft.style = Paint.Style.FILL; soft.color = Color.argb(60, 255, 255, 255); c.drawPath(p, soft)
                line.strokeWidth = s * 0.04f
                c.drawLine(s * 0.38f, s * 0.45f + bob, s * 0.62f, s * 0.45f + bob, line)           // an empty rim line
                for (k in 0 until 3) {                                                                // little question-free sparkles
                    val a = t * 1.5f + k * 2.1f
                    white.alpha = (160 + 90 * sinf(a)).toInt()
                    c.drawCircle(s * (0.5f + 0.27f * cosf(a * 0.6f + k)), s * (0.32f + 0.06f * k) + sinf(a) * s * 0.02f, s * 0.014f, white)
                }
                white.alpha = 255
            }
            2 -> {                                   // cup stuck in the tube, shaking
                val shake = sinf(t * 18f) * 4f
                c.drawLine(s * 0.26f, s * 0.28f, s * 0.74f, s * 0.28f, line)                         // the tube mouth
                c.drawLine(s * 0.26f, s * 0.40f, s * 0.74f, s * 0.40f, line)
                c.save(); c.rotate(shake, s / 2f, s * 0.5f)
                val p = Path().apply {
                    moveTo(s * 0.34f, s * 0.34f); lineTo(s * 0.66f, s * 0.34f)
                    lineTo(s * 0.61f, s * 0.72f); lineTo(s * 0.39f, s * 0.72f); close()
                }
                soft.style = Paint.Style.FILL; soft.color = Color.WHITE; c.drawPath(p, soft)
                soft.color = Color.argb(255, 217, 4, 41); c.drawRect(s * 0.355f, s * 0.46f, s * 0.645f, s * 0.54f, soft)
                c.restore()
            }
            3 -> {                                   // thermometer with a rising and falling column and heat waves
                val lvl = 0.5f + 0.5f * sinf(t * 1.6f)
                line.strokeWidth = s * 0.04f
                c.drawRoundRect(RectF(s * 0.44f, s * 0.2f, s * 0.56f, s * 0.64f), s * 0.06f, s * 0.06f, line)
                soft.style = Paint.Style.FILL; soft.color = Color.WHITE
                c.drawCircle(s * 0.5f, s * 0.7f, s * 0.1f, soft)
                val top = s * 0.58f - lvl * s * 0.32f
                c.drawRoundRect(RectF(s * 0.475f, top, s * 0.525f, s * 0.7f), s * 0.025f, s * 0.025f, soft)
                for (k in 0 until 3) {                                                                // heat waves
                    val f = ((t * 0.7f + k / 3f) % 1f)
                    line.alpha = (200 * (1f - f)).toInt()
                    val y = s * 0.62f - f * s * 0.36f; val x = s * (0.7f + 0.06f * k)
                    val p = Path(); p.moveTo(x, y); p.cubicTo(x + s * 0.03f, y - s * 0.04f, x - s * 0.03f, y - s * 0.07f, x, y - s * 0.11f)
                    c.drawPath(p, line)
                }
                line.alpha = 255
            }
            4 -> {                                   // spinning fan
                c.save(); c.rotate(t * 220f, s / 2f, s / 2f)
                soft.style = Paint.Style.FILL; soft.color = Color.WHITE
                for (k in 0 until 3) {
                    c.save(); c.rotate(k * 120f, s / 2f, s / 2f)
                    c.drawOval(RectF(s * 0.46f, s * 0.22f, s * 0.62f, s * 0.48f), soft)
                    c.restore()
                }
                c.restore()
                soft.color = Color.argb(255, 217, 4, 41); c.drawCircle(s / 2f, s / 2f, s * 0.05f, soft)
                c.drawCircle(s / 2f, s / 2f, s * 0.32f, line)
            }
            5 -> {                                   // hopper with kernels that will not fall
                val p = Path().apply { moveTo(s * 0.28f, s * 0.26f); lineTo(s * 0.72f, s * 0.26f); lineTo(s * 0.56f, s * 0.58f); lineTo(s * 0.44f, s * 0.58f); close() }
                c.drawPath(p, line)
                soft.style = Paint.Style.FILL; soft.color = Color.WHITE
                for (k in 0 until 7) {
                    val x = s * (0.36f + 0.28f * ((k * 37) % 10) / 10f); val y = s * (0.31f + 0.15f * ((k * 53) % 10) / 10f)
                    c.drawOval(RectF(x - s * 0.028f, y + sinf(t * 14f + k) * s * 0.006f - s * 0.02f, x + s * 0.028f, y + sinf(t * 14f + k) * s * 0.006f + s * 0.02f), soft)
                }
                line.pathEffect = android.graphics.DashPathEffect(floatArrayOf(s * 0.03f, s * 0.04f), -t * s * 0.1f)
                c.drawLine(s * 0.5f, s * 0.62f, s * 0.5f, s * 0.78f, line); line.pathEffect = null
            }
            6 -> {                                   // plug and socket moving apart with a spark
                val gap = s * (0.06f + 0.05f * (0.5f + 0.5f * sinf(t * 3f)))
                c.drawRoundRect(RectF(s * 0.14f, s * 0.42f, s * 0.42f - gap, s * 0.58f), s * 0.04f, s * 0.04f, line)
                c.drawRoundRect(RectF(s * 0.58f + gap, s * 0.42f, s * 0.86f, s * 0.58f), s * 0.04f, s * 0.04f, line)
                c.drawLine(s * 0.42f - gap, s * 0.46f, s * 0.48f - gap, s * 0.46f, line); c.drawLine(s * 0.42f - gap, s * 0.54f, s * 0.48f - gap, s * 0.54f, line)
                if (sinf(t * 9f) > 0f) {
                    val z = Path(); z.moveTo(s * 0.47f, s * 0.36f); z.lineTo(s * 0.52f, s * 0.46f); z.lineTo(s * 0.48f, s * 0.5f); z.lineTo(s * 0.54f, s * 0.62f)
                    c.drawPath(z, line)
                }
            }
            8 -> {                                   // a CLOSED sign swinging on two ropes
                c.save(); c.rotate(sinf(t * 1.5f) * 5f, s / 2f, s * 0.18f)
                line.strokeWidth = s * 0.02f
                c.drawLine(s * 0.5f, s * 0.18f, s * 0.33f, s * 0.38f, line); c.drawLine(s * 0.5f, s * 0.18f, s * 0.67f, s * 0.38f, line)
                soft.style = Paint.Style.FILL; soft.color = Color.WHITE
                c.drawRoundRect(RectF(s * 0.2f, s * 0.38f, s * 0.8f, s * 0.7f), s * 0.05f, s * 0.05f, soft)
                sign.textSize = s * 0.13f; c.drawText("CLOSED", s / 2f, s * 0.535f, sign)
                sign.textSize = s * 0.05f; c.drawText("back soon", s / 2f, s * 0.625f, sign)
                c.restore()
            }
            else -> {                                // warning triangle that gently wobbles
                c.save(); c.rotate(sinf(t * 2.6f) * 4f, s / 2f, s * 0.56f)
                val p = Path().apply { moveTo(s * 0.5f, s * 0.22f); lineTo(s * 0.78f, s * 0.7f); lineTo(s * 0.22f, s * 0.7f); close() }
                c.drawPath(p, line)
                c.drawLine(s * 0.5f, s * 0.38f, s * 0.5f, s * 0.54f, line)
                c.drawCircle(s * 0.5f, s * 0.61f, s * 0.018f, white)
                c.restore()
            }
        }
        c.restore()
    }
}

class Check(ctx: Context) : View(ctx) {
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = RED }
    private val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        c.drawCircle(w / 2f, w / 2f, w / 2f, bg)
        fg.strokeWidth = w * 0.09f
        val p = Path()
        p.moveTo(w * 0.28f, w * 0.52f); p.lineTo(w * 0.44f, w * 0.68f); p.lineTo(w * 0.74f, w * 0.34f)
        c.drawPath(p, fg)
    }
}

/** One controller register the service menu may change. asFound is what the machine had in the Oct 2026 logs. */
class Reg(val label: String, val addr: Int, val asFound: Int, val min: Int, val max: Int, val step: Int, val unit: String)

class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private var link: Link? = null
    @Volatile private var ctl: Controller? = null
    @Volatile private var running = true
    @Volatile private var lastError = ""
    @Volatile private var lastRegs: IntArray? = null
    private var offline = 0
    private var state = "NOTICE"          // READY, MAKING, DONE, NOTICE
    private var flavor = 'B'
    private var sawOuting = false
    private var makeStart = 0L
    private var tailStart = 0L            // when the controller first reported 97%+ this cup (start of the slow finish)
    private var sold = "-"
    private var testUsed = false

    private lateinit var root: FrameLayout
    private lateinit var rain: PopRain
    private lateinit var readyV: View
    private lateinit var makingV: View
    private lateinit var doneV: View
    private lateinit var noticeV: View
    private lateinit var cardA: View
    private lateinit var cardB: View
    private lateinit var picA: ImageView
    private lateinit var picB: ImageView
    private lateinit var nameA: TextView
    private lateinit var nameB: TextView
    private lateinit var headline: TextView
    private lateinit var subline: TextView
    private lateinit var thanks: TextView
    private lateinit var hotTv: TextView
    private lateinit var makingTitle: TextView
    private lateinit var phase: TextView
    private lateinit var meter: PopMeter
    private lateinit var check: Check
    private lateinit var nArt: FaultArt
    private lateinit var nTitle: TextView
    private lateinit var nSub: TextView
    private lateinit var nCode: TextView
    private lateinit var foot: TextView

    private var settingsV: View? = null
    private var liveUpdate: (() -> Unit)? = null
    private val liveRefresh = object : Runnable {
        override fun run() {
            liveUpdate?.invoke()
            if (settingsV != null && on("autoClose", true) && System.currentTimeMillis() - lastTouch > 5 * 60 * 1000L) {
                closeSettings(); return
            }
            if (settingsV != null) ui.postDelayed(this, 1000)
        }
    }

    // attract mode (videos from the machine's popcron/adv folder, like the vendor app)
    private var attract: FrameLayout? = null
    private var attractVv: VideoView? = null
    private var vidFiles: List<java.io.File> = emptyList()
    private var vidIdx = 0
    private var vidErrors = 0
    private var lastTouch = System.currentTimeMillis()
    private val idleCheck = object : Runnable {
        override fun run() {
            if (running) { maybeAttract(); ui.postDelayed(this, 2000) }
        }
    }

    private val heatOff = Runnable { sendCoil(20, false, "Heater auto-off") }
    private val contOff = Runnable { sendCoil(28, false, "Continuous test auto-off") }

    // ---------- small view helpers
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun lp(w: Int = -2, h: Int = -2, top: Int = 0) = LinearLayout.LayoutParams(w, h).apply { topMargin = dp(top) }
    private fun box(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }
    private fun oval(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    private fun col() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
    private fun tv(t: String, sp: Float, bold: Boolean = false, color: Int = Color.WHITE) = TextView(this).apply {
        text = t; textSize = sp; setTextColor(color); gravity = Gravity.CENTER
        if (bold) setTypeface(null, Typeface.BOLD)
    }
    private fun heavy(v: TextView) { v.typeface = Typeface.create("sans-serif-black", Typeface.NORMAL) }
    private fun pref(k: String, d: String) = prefs.getString(k, d) ?: d
    private fun on(k: String, d: Boolean) = pref(k, if (d) "yes" else "no") == "yes"
    private fun toast(t: String) { Toast.makeText(this, t, Toast.LENGTH_LONG).show() }

    private fun pillButton(t: String, bg: Int, fg: Int, click: () -> Unit): TextView =
        tv(t, 16f, true, fg).apply {
            background = box(bg, 24); setPadding(dp(20), dp(10), dp(20), dp(10)); setOnClickListener { click() }
        }

    private fun roundBtn(t: String, click: () -> Unit): TextView =
        tv(t, 26f, true, Color.WHITE).apply { background = oval(RED); setOnClickListener { click() } }

    private fun flavorCard(f: Char): View {
        val name = tv("", 28f, true, Color.WHITE).apply { gravity = Gravity.START }
        heavy(name)
        val pic = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; visibility = View.GONE }
        if (f == 'A') { nameA = name; picA = pic } else { nameB = name; picB = pic }
        val tag = tv("FREE", 14f, true, RED).apply { background = box(Color.WHITE, 12); setPadding(dp(12), dp(1), dp(12), dp(1)) }
        val tap = tv("Tap to start", 16f, false, Color.parseColor("#FFE3E6")).apply { gravity = Gravity.START }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tag, LinearLayout.LayoutParams(-2, -2))
            addView(name, lp(top = 8))
            addView(tap, lp(top = 2))
        }
        val go = tv("GO", 22f, true, RED).apply { background = oval(Color.WHITE) }
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(RED, DEEP)).apply { cornerRadius = dp(28).toFloat() }
            setPadding(dp(24), dp(22), dp(24), dp(22))
            elevation = dp(8).toFloat()
            addView(pic, lp(dp(84), dp(84)).apply { rightMargin = dp(16) })
            addView(left, LinearLayout.LayoutParams(0, -2, 1f))
            addView(go, LinearLayout.LayoutParams(dp(68), dp(68)))
            setOnClickListener { startMake(f) }
            setOnTouchListener { v, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(80).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                }
                false
            }
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = getSharedPreferences("popcorn", Context.MODE_PRIVATE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        takeOver()

        root = FrameLayout(this).apply { setBackgroundColor(CREAM) }
        rain = PopRain(this)
        root.addView(rain, FrameLayout.LayoutParams(-1, -1))

        val title = tv("FRESH POPCORN", 22f, true, RED).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL; letterSpacing = 0.08f }
        val hold = Runnable { if (settingsV == null && state != "MAKING") askPin() }
        title.setOnTouchListener { _, e ->                       // hold the title 1.5 s for the service menu
            when (e.action) {
                MotionEvent.ACTION_DOWN -> ui.postDelayed(hold, 1500)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> ui.removeCallbacks(hold)
            }
            true
        }
        val pill = tv("100% FREE", 16f, true, Color.WHITE).apply { background = box(RED, 20); setPadding(dp(16), dp(6), dp(16), dp(6)) }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, dp(44), 1f)); addView(pill)
        }

        val sw = minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels).toFloat()          // size the badge from the real screen width so it always fits
        val free = tv("FREE", 76f, true, Color.WHITE).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sw * 0.115f)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(RED, DEEP)).apply { cornerRadius = sw * 0.06f }
            setPadding((sw * 0.046f).toInt(), 0, (sw * 0.046f).toInt(), (sw * 0.006f).toInt()); rotation = -3f; elevation = dp(10).toFloat()
        }
        heavy(free)
        // fit the badge to half the width it has, once per layout size (measured, not repeated, so it can't shrink away)
        var fitRoom = 0
        free.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val room = (v.parent as? View)?.width ?: 0
            if (room > 0 && room != fitRoom) {
                fitRoom = room
                val tvv = v as TextView
                tvv.post {
                    val tp = android.graphics.Paint(tvv.paint); tp.textSize = 100f
                    val per = tp.measureText("FREE") / 100f + 0.8f            // text width plus 0.4 text-heights of padding each side
                    val ns = (room * 0.5f / per).coerceIn(24f, room * 0.2f)
                    tvv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, ns)
                    tvv.setPadding((ns * 0.4f).toInt(), 0, (ns * 0.4f).toInt(), (ns * 0.05f).toInt())
                }
            }
        }
        for (p in arrayOf("scaleX", "scaleY"))
            ObjectAnimator.ofFloat(free, p, 1f, 1.04f).apply { duration = 1200; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE; start() }
        headline = tv("", 27f, true, INK); heavy(headline)
        subline = tv("", 17f, false, MUTED)
        hotTv = tv("", 14f, false, MUTED)
        cardA = flavorCard('A'); cardB = flavorCard('B')
        readyV = col().apply {
            addView(free, lp(-2, -2, 0))
            addView(headline, lp(top = 26))
            addView(subline, lp(top = 8))
            addView(cardA, lp(-1, -2, 28)); addView(cardB, lp(-1, -2, 18))
            addView(hotTv, lp(top = 16))
        }

        makingTitle = tv("", 26f, true, INK); heavy(makingTitle)
        makingTitle.background = box(Color.WHITE, 24); makingTitle.setPadding(dp(22), dp(8), dp(22), dp(8))
        meter = PopMeter(this); meter.visibility = View.GONE
        root.addView(meter, 1, FrameLayout.LayoutParams(-1, -1))     // full screen, just above the floating popcorn
        phase = tv("Warming up...", 24f, true, RED)
        phase.background = box(Color.WHITE, 24); phase.setPadding(dp(22), dp(8), dp(22), dp(8))
        val pctTv = tv("0%", 84f, true, RED); heavy(pctTv)
        pctTv.background = box(Color.WHITE, 36); pctTv.setPadding(dp(34), dp(6), dp(34), dp(10))
        meter.onShown = { n, pulse ->
            val t = "$n%"
            if (pctTv.text.toString() != t) pctTv.text = t
            pctTv.scaleX = 1f + 0.16f * pulse; pctTv.scaleY = pctTv.scaleX
        }
        makingV = col().apply {
            addView(makingTitle); addView(pctTv, lp(top = 40)); addView(phase, lp(top = 40))
        }

        check = Check(this)
        thanks = tv("", 20f, false, MUTED)
        doneV = col().apply {
            addView(check, LinearLayout.LayoutParams(dp(150), dp(150)))
            addView(tv("Enjoy your free popcorn!", 30f, true, INK).also { heavy(it) }, lp(top = 22))
            addView(thanks, lp(top = 12))
        }

        nArt = FaultArt(this)
        nTitle = tv("", 28f, true, Color.WHITE); heavy(nTitle); nTitle.gravity = Gravity.CENTER
        nSub = tv("", 18f, false, Color.parseColor("#FFE3E8")); nSub.gravity = Gravity.CENTER
        nCode = tv("", 13f, false, Color.WHITE); nCode.gravity = Gravity.CENTER
        nCode.background = box(Color.argb(60, 255, 255, 255), 16); nCode.setPadding(dp(16), dp(6), dp(16), dp(6))
        noticeV = col().apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(RED, DEEP)).apply { cornerRadius = dp(28).toFloat() }
            setPadding(dp(20), dp(20), dp(20), dp(20))
            addView(nArt, LinearLayout.LayoutParams(dp(230), dp(230)))
            addView(nTitle, lp(top = 8)); addView(nSub, lp(top = 10)); addView(nCode, lp(top = 22))
        }

        foot = tv("", 12f, false, MUTED)
        val content = FrameLayout(this)
        for (v in listOf(readyV, makingV, doneV)) content.addView(v, FrameLayout.LayoutParams(-1, -1))
        content.addView(noticeV, FrameLayout.LayoutParams(-1, -1).apply { topMargin = dp(6); bottomMargin = dp(6) })
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(Awning(this@MainActivity), LinearLayout.LayoutParams(-1, dp(76)))
            addView(header, lp(-1, -2, 6).apply { leftMargin = dp(20); rightMargin = dp(20) })
            addView(content, LinearLayout.LayoutParams(-1, 0, 1f).apply { leftMargin = dp(20); rightMargin = dp(20) })
            addView(foot, lp(-1, -2).apply { bottomMargin = dp(10) })
        }
        root.addView(page, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        applyTexts()
        notice(0, "Starting up...", "Connecting to the machine", "")
        if (Build.VERSION.SDK_INT >= 23 && on("attractOn", true) &&
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 7)
        ui.postDelayed(idleCheck, 2000)
        Thread { pollLoop() }.apply { isDaemon = true; start() }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        lastTouch = System.currentTimeMillis()
        return super.dispatchTouchEvent(ev)
    }

    override fun onWindowFocusChanged(f: Boolean) {
        super.onWindowFocusChanged(f)
        if (f) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    override fun onBackPressed() { if (settingsV != null) closeSettings() }   // kiosk: back only closes the service menu

    override fun onDestroy() { running = false; closeLink(); super.onDestroy() }

    /** Keeps the old vendor app out of the way: bring this app to the front, then stop the vendor's background processes.
     *  Runs right away and again after 10, 25 and 45 seconds, because the vendor app starts itself a while after boot. */
    private fun takeOver() {
        if (!on("killVendor", true)) return
        val pkg = pref("vendorPkg", "com.yuchen.popcorn")
        for (sec in intArrayOf(0, 10, 25, 45)) ui.postDelayed({
            try {
                if (sec > 0) startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                (getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).killBackgroundProcesses(pkg)
            } catch (e: Throwable) { }
        }, sec * 1000L)
    }

    private fun loadPic(base: String): android.graphics.Bitmap? {
        val dir = java.io.File(Environment.getExternalStorageDirectory(), "popcron/res")
        for (ext in arrayOf("bmp", "png", "jpg")) {
            val f = java.io.File(dir, "$base.$ext")
            if (f.exists()) { val bm = BitmapFactory.decodeFile(f.absolutePath); if (bm != null) return bm }
        }
        return null
    }

    private fun applyTexts() {
        nameA.text = pref("nameA", "Amish Baby Corn"); nameB.text = pref("nameB", "Orville Corn")
        cardA.visibility = if (on("enableA", true)) View.VISIBLE else View.GONE
        cardB.visibility = if (on("enableB", true)) View.VISIBLE else View.GONE
        headline.text = pref("headline", "Fresh hot popcorn, made just for you")
        subline.text = pref("subline", "Pick a flavor. No coins, no cards, no catch.")
        thanks.text = pref("thanks", "Please take your cup.\nCome back anytime, it's always free.")
        val hot = pref("hotLine", "").trim(); val loc = pref("localName", "").trim()
        val parts = ArrayList<String>()
        if (loc.isNotEmpty()) parts.add(loc)
        if (hot.isNotEmpty()) parts.add("Questions? Call $hot")
        hotTv.text = parts.joinToString("   |   ")
        hotTv.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
        val ba = loadPic("a"); picA.setImageBitmap(ba); picA.visibility = if (ba != null) View.VISIBLE else View.GONE
        val bb = loadPic("b"); picB.setImageBitmap(bb); picB.visibility = if (bb != null) View.VISIBLE else View.GONE
        rain.setRunning(on("anim", true))
        foot.visibility = if (on("footer", true)) View.VISIBLE else View.GONE
    }

    private fun show(v: View) {
        if (v !== readyV) stopAttract()
        for (x in listOf(readyV, makingV, doneV, noticeV)) x.visibility = if (x === v) View.VISIBLE else View.GONE
        if (::meter.isInitialized) meter.visibility = if (v === makingV) View.VISIBLE else View.GONE
    }

    private fun notice(kind: Int, t: String, sub: String, code: String) {
        state = "NOTICE"; nArt.kind = kind; nTitle.text = t; nSub.text = sub; nCode.text = code
        nCode.visibility = if (code.isEmpty()) View.GONE else View.VISIBLE
        show(noticeV)
    }

    private fun problem(err: Int) {
        val names = ArrayList<String>()
        for (i in ERR_NAMES.indices) if (((err shr i) and 1) == 1) names.add(ERR_NAMES[i])
        val code = "E%02X".format(err)
        val ctx = if (state == "MAKING") "while making " + (if (flavor == 'A') nameA else nameB).text else "while idle"
        val detail = "Service code: $code\n" + names.joinToString(", ")
        when {
            (err and 0x20) != 0 -> { notice(1, "Out of cups!", "A refill is on the way. Thanks for waiting!", ""); logFault(code, "Out of cups", ctx) }
            (err and 0x40) != 0 -> { notice(2, "A cup got stuck", "We're sorting it out. Back in a moment!", detail); logFault(code, "Cup did not drop", ctx) }
            (err and 0x02) != 0 -> { notice(3, "The pan is too shy", "It's not getting hot enough. Taking a quick break.", detail); logFault(code, "Heater did not reach temperature", ctx) }
            (err and 0x84) != 0 -> { notice(4, "Fan trouble", "The fan needs a breather. Back soon!", detail); logFault(code, "Fan fault", ctx) }
            (err and 0x08) != 0 -> { notice(5, "Kernel jam", "The kernels aren't coming through. Taking a quick break.", detail); logFault(code, "Feed (kernel) fault", ctx) }
            (err and 0x10) != 0 -> { notice(6, "Lost the connection", "The control board stopped answering.", detail); logFault(code, "Communication fault", ctx) }
            else -> { notice(7, "Taking a quick break", "This machine is temporarily out of service.", detail); logFault(code, names.joinToString(", ").ifEmpty { "General fault" }, ctx) }
        }
    }

    // ---------- fault history: the last 40 faults, kept in the app's settings (start time, end time, code, text, context)
    private var activeFault = ""

    private fun faultLines(): MutableList<String> =
        pref("faultLog", "").split('\n').filter { it.isNotBlank() }.toMutableList()

    private fun logFault(code: String, text: String, ctx: String) {
        if (activeFault == code) return
        if (activeFault.isNotEmpty()) endFault()          // a different fault replaced the previous one
        activeFault = code
        val lines = faultLines()
        lines.add(System.currentTimeMillis().toString() + "|0|" + code + "|" + text.replace('|', '/').replace('\n', ' ') + "|" + ctx)
        while (lines.size > 40) lines.removeAt(0)
        prefs.edit().putString("faultLog", lines.joinToString("\n")).apply()
    }

    /** Called when the controller reports no fault again: closes the open entry with the time it ended. */
    private fun endFault() {
        if (activeFault.isEmpty()) return
        activeFault = ""
        val lines = faultLines()
        if (lines.isEmpty()) return
        val p = lines[lines.size - 1].split('|')
        if (p.size >= 5 && p[1] == "0") {
            lines[lines.size - 1] = p[0] + "|" + System.currentTimeMillis() + "|" + p[2] + "|" + p[3] + "|" + p[4]
            prefs.edit().putString("faultLog", lines.joinToString("\n")).apply()
        }
    }

    private fun faultSummary(line: String): String {
        val p = line.split('|')
        if (p.size < 5) return line
        val start = p[0].toLongOrNull() ?: return line
        val end = p[1].toLongOrNull() ?: 0L
        val whenTxt = java.text.SimpleDateFormat("EEE MMM d, h:mm a", java.util.Locale.getDefault()).format(java.util.Date(start))
        val lasted = if (end > 0L) {
            val sec = ((end - start) / 1000L).toInt()
            if (sec < 90) "lasted $sec s" else "lasted ${sec / 60} min ${sec % 60} s"
        } else "still open or never cleared"
        return whenTxt + "   " + p[3] + "\n" + lasted + "   |   " + p[4] + "   |   " + p[2]
    }

    // ---------- attract mode
    private fun maybeAttract() {
        if (attract != null || settingsV != null || state != "READY" || !on("attractOn", true)) return
        val secs = (pref("attractSecs", "60").toIntOrNull() ?: 60).coerceIn(10, 3600)
        if (System.currentTimeMillis() - lastTouch < secs * 1000L) return
        val dir = java.io.File(pref("advDir", Environment.getExternalStorageDirectory().absolutePath + "/popcron/adv"))
        val files = dir.listFiles(java.io.FileFilter { f -> f.isFile && f.name.lowercase().endsWith(".mp4") })?.sortedBy { it.name } ?: return
        if (files.isEmpty()) return
        startAttract(files)
    }

    private fun startAttract(files: List<java.io.File>) {
        vidFiles = files; vidIdx = 0; vidErrors = 0
        val vv = VideoView(this)
        val label = tv("Touch to start - it's FREE!", 24f, true, Color.WHITE).apply {
            background = box(RED, 30); setPadding(dp(28), dp(12), dp(28), dp(12))
        }
        val ov = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(vv, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
            addView(label, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(60) })
            setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_DOWN) stopAttract(); true }
        }
        vv.setOnCompletionListener { playNext(vv) }
        vv.setOnErrorListener { _, _, _ ->
            vidErrors++
            if (vidErrors > vidFiles.size) stopAttract() else playNext(vv)
            true
        }
        vv.setOnPreparedListener { mp -> if (!on("attractSound", false)) mp.setVolume(0f, 0f) }
        root.addView(ov, FrameLayout.LayoutParams(-1, -1))
        attract = ov; attractVv = vv
        playNext(vv)
    }

    private fun playNext(vv: VideoView) {
        if (attract == null || vidFiles.isEmpty()) return
        vv.setVideoPath(vidFiles[vidIdx % vidFiles.size].absolutePath)
        vidIdx++
        vv.start()
    }

    private fun stopAttract() {
        try { attractVv?.stopPlayback() } catch (e: Throwable) { }
        attract?.let { root.removeView(it) }
        attract = null; attractVv = null
        lastTouch = System.currentTimeMillis()
    }

    // ---------- controller link
    private fun openLink(): Controller {
        val l: Link = if (pref("link", "serial") == "sim") TcpLink("10.0.2.2", 7777)
                      else SerialLink(pref("path", "/dev/ttyS1"), pref("baud", "115200").toInt())
        l.open(); link = l
        return Controller(l)
    }

    private fun closeLink() { try { link?.close() } catch (e: Throwable) { }; link = null; ctl = null }

    private fun pollLoop() {
        while (running) {
            var regs: IntArray? = null
            try {
                if (ctl == null) ctl = openLink()
                regs = ctl?.readAll()
            } catch (e: Throwable) { lastError = e.toString(); closeLink() }
            ui.post { onRegs(regs) }
            try { Thread.sleep(1300) } catch (e: InterruptedException) { }
        }
    }

    private fun onRegs(r: IntArray?) {
        lastRegs = r
        if (r == null) {
            offline++
            if (offline >= 3 && state != "MAKING") { notice(6, "Starting up...", "Waiting for the machine controller", "LINK: " + lastError); logFault("LINK", "No answer from the controller", "while idle") }
            return
        }
        offline = 0
        val flags = r[0]; val err = r[5]
        sold = "${r[6]} / ${r[7]}"
        foot.text = "controller OK   |   cups made A / B: $sold"
        when (state) {
            "MAKING" -> {
                if (err != 0) { problem(err); return }
                if ((flags and 0x20) != 0) sawOuting = true
                val nowMs = System.currentTimeMillis()
                if (r[1] >= 97 && tailStart == 0L) tailStart = nowMs
                if ((flags and 0x200) != 0) meter.dropKernels()
                meter.setProgress(shownPct(r[1], nowMs)); meter.setHeat(r[3])
                phase.text = when {
                    r[1] >= 97 -> "Finishing up..."
                    (flags and 0x400) != 0 -> "Dropping your cup..."
                    (flags and 0x200) != 0 -> "Adding the kernels..."
                    (flags and 0x80) != 0 && r[1] < 20 -> "Heating up..."
                    r[1] < 20 -> "Warming up..."
                    r[1] < 50 -> "Popping!"
                    r[1] < 80 -> "Pop pop pop!"
                    else -> "Filling your cup..."
                }
                if (sawOuting && (flags and 0x04) != 0 && r[1] >= 99) complete()
                else if (System.currentTimeMillis() - makeStart > 6 * 60_000) { notice(7, "Taking a quick break", "This machine is temporarily out of service.", "Service code: TIMEOUT"); logFault("TIMEOUT", "Cup took longer than 6 minutes", "while making " + (if (flavor == 'A') nameA else nameB).text) }
            }
            "DONE" -> { }
            else -> {
                if (on("lockout", false)) {                          // service lockout: out of service for customers
                    if (state != "LOCKED") { notice(8, "Temporarily out of service", "We'll be back soon. Thanks for your patience!", ""); state = "LOCKED" }
                }
                else if (err != 0) problem(err)
                else if ((flags and 1) != 0) notice(0, "One moment...", "The machine is busy", "")
                else if (state != "READY") { endFault(); state = "READY"; show(readyV) }
            }
        }
    }

    private fun startMake(f: Char) {
        if (state != "READY" || settingsV != null) return
        flavor = f; sawOuting = false; tailStart = 0L; makeStart = System.currentTimeMillis(); state = "MAKING"
        makingTitle.text = "Making your " + (if (f == 'A') nameA else nameB).text
        meter.reset(); phase.text = "Warming up..."; show(makingV)
        Thread {
            var ok = false
            try {
                val c = ctl; val addr = if (f == 'A') 0x10 else 0x11      // same pair the vendor app uses (16 / 17)
                if (c != null) { ok = c.coil(addr, true); if (!ok) ok = c.coil(addr, true) }
            } catch (e: Throwable) { lastError = e.toString() }
            if (!ok) ui.post { notice(7, "Taking a quick break", "Could not start. Please try again.", "Service code: START"); logFault("START", "Could not start a cup (no reply)", "while starting a cup") }
        }.start()
    }

    /** Learned timing: the controller sits at 97-99% for a while at the end. Reserve that share of the bar for it and
     *  walk through it over the time the last cups took, so the bar reaches 100% when the finish command arrives. */
    private fun tailSecs() = (pref("tailSecs$flavor", "40").toFloatOrNull() ?: 40f).coerceIn(3f, 120f)
    private fun cycleSecs() = (pref("cycleSecs$flavor", "180").toFloatOrNull() ?: 180f).coerceIn(30f, 600f)
    private fun shownPct(raw: Int, nowMs: Long): Float {
        val tailPct = (tailSecs() / cycleSecs() * 100f).coerceIn(4f, 40f)
        val mainPct = 100f - tailPct
        if (raw < 97 || tailStart == 0L) return raw * mainPct / 97f
        val x = (nowMs - tailStart) / 1000f / tailSecs()                       // 0..1 = the expected tail; later = creep
        val f = if (x < 1f) x * 0.97f else 0.97f + 0.025f * (1f - Math.exp((-(x - 1f) * 2f).toDouble()).toFloat())
        return mainPct + tailPct * f
    }

    private fun learnTiming() {
        val now = System.currentTimeMillis()
        val total = (now - makeStart) / 1000f
        val tail = if (tailStart > 0L) (now - tailStart) / 1000f else 0f
        // Per flavor, a running average over about the last 8 cups (only three numbers are stored, never a per-cup history).
        // Short enough to follow a settings change within a few cups, long enough that one odd cup barely moves it.
        // A cup more than 50% off the average (a jam, a long wait) is ignored once there are a few cups to compare with.
        val n = (pref("timingN$flavor", "0").toIntOrNull() ?: 0).coerceAtLeast(0)
        val a = 1f / minOf(n + 1, 8)
        val ed = prefs.edit()
        var used = false
        val odd = n >= 3 && Math.abs(total - cycleSecs()) > 0.5f * cycleSecs()
        if (!odd && total in 30f..600f) { ed.putString("cycleSecs$flavor", (if (n == 0) total else (1f - a) * cycleSecs() + a * total).toString()); used = true }
        if (!odd && tail in 2f..120f) { ed.putString("tailSecs$flavor", (if (n == 0) tail else (1f - a) * tailSecs() + a * tail).toString()); used = true }
        if (used) ed.putString("timingN$flavor", minOf(n + 1, 8).toString())
        ed.apply()
    }

    private fun complete() {
        learnTiming()
        state = "DONE"
        val ack = if (flavor == 'A') 0x0B else 0x0C                       // same "finished" message the vendor app sends
        Thread { try { ctl?.coil(ack, false) } catch (e: Throwable) { } }.start()
        makingV.visibility = View.INVISIBLE                                // text out of the way while the popcorn falls out of the pan
        meter.setProgress(100f)
        meter.drain {
            if (state == "DONE") {
                show(doneV)
                check.scaleX = 0.3f; check.scaleY = 0.3f
                check.animate().scaleX(1f).scaleY(1f).setDuration(500).setInterpolator(OvershootInterpolator()).start()
                val secs = (pref("doneSecs", "7").toIntOrNull() ?: 7).coerceIn(2, 60)
                ui.postDelayed({ if (state == "DONE") state = "NOTICE" }, secs * 1000L)   // next poll returns to READY
            }
        }
    }

    // ---------- service menu: access and password
    private fun expectedPin(): Int = lastRegs?.get(0x13) ?: (pref("pin", "0000").toIntOrNull() ?: 0)

    private fun askPin() {
        val e = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD; hint = "Service password" }
        AlertDialog.Builder(this).setTitle("Service menu").setView(e)
            .setPositiveButton("OK") { _, _ -> if (e.text.toString().toIntOrNull() == expectedPin()) openSettings() else toast("Wrong password") }
            .setNegativeButton("Cancel", null).show()
    }

    private fun changePwdDialog() {
        fun field(h: String) = EditText(this).apply { hint = h; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD }
        val o = field("Current password"); val n = field("New password"); val c = field("Repeat new password")
        val lay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(0))
            addView(o); addView(n); addView(c)
        }
        AlertDialog.Builder(this).setTitle("Change service password").setView(lay)
            .setPositiveButton("Change") { _, _ ->
                val nv = n.text.toString().toIntOrNull()
                if (o.text.toString().toIntOrNull() != expectedPin()) toast("Current password is wrong")
                else if (nv == null || nv < 0 || nv > 65535 || n.text.toString() != c.text.toString())
                    toast("New password must be a number from 0 to 65535, entered twice")
                else Thread {
                    val ok = try { ctl?.writeReg(0x13, nv) ?: false } catch (e: Throwable) { false }
                    val back = if (ok) ctl?.readAll() else null
                    val stuck = back != null && back[0x13] == nv
                    ui.post {
                        if (stuck) { prefs.edit().putString("pin", nv.toString()).apply(); toast("Password changed") }
                        else toast("The controller did not accept the new password. Nothing was changed.")
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun closeSettings() {
        settingsV?.let { root.removeView(it) }
        settingsV = null; liveUpdate = null
        ui.removeCallbacks(liveRefresh)
        if (testUsed) { testUsed = false; stopAll() }
    }

    private fun saveSettings(texts: List<Pair<String, EditText>>, switches: List<Pair<String, Switch>>) {
        val ed = prefs.edit()
        for ((k, e) in texts) ed.putString(k, e.text.toString().trim())
        for ((k, s) in switches) ed.putString(k, if (s.isChecked) "yes" else "no")
        ed.apply()
        applyTexts(); closeLink()
    }

    // ---------- writing registers
    private fun describe(r: Reg, v: Int): String = when (r.unit) {
        "ds" -> "%.1f s".format(v / 10.0)
        "rpm" -> "$v rpm"
        "C" -> "$v C"
        "feed" -> (if (r.asFound != 0) v * 100 / r.asFound else 0).toString() + "% of as found"
        else -> ""
    }

    private fun confirmWrites(list: List<Triple<Reg, Int, Int>>) {
        if (list.isEmpty()) { toast("No changes to apply"); return }
        if (state == "MAKING") { toast("Wait until the cup is finished"); return }
        val msg = StringBuilder("These settings will be written to the controller:\n\n")
        for ((t, o, n) in list) msg.append(t.label).append(":  ").append(o).append("  ->  ").append(n).append("\n")
        msg.append("\nThe app reads each value back afterwards and tells you if it did not stick. Keep hands clear of the machine.")
        AlertDialog.Builder(this).setTitle("Apply to machine?").setMessage(msg.toString())
            .setPositiveButton("Apply") { _, _ -> writeNow(list.map { Pair(it.first.addr, it.third) }) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun writeNow(writes: List<Pair<Int, Int>>) {
        Thread {
            val c = ctl
            var msg: String
            if (c == null) {
                msg = "Not connected to the controller"
            } else {
                var bad = ""
                for ((addr, v) in writes) {
                    val ok = try { c.writeReg(addr, v) } catch (e: Throwable) { false }
                    if (!ok) { bad += "  0x%02X (no reply)".format(addr); continue }
                    Thread.sleep(300)
                    val back = c.readAll()
                    if (back == null || back[addr] != v) bad += "  0x%02X (did not stick)".format(addr)
                }
                if (writes.any { it.first != 0x13 }) prefs.edit().putString("timingNA", "0").putString("timingNB", "0").apply()   // settings changed: relearn the cup timing quickly
                msg = if (bad.isEmpty()) "Saved to the machine" else "Problem:$bad"
            }
            ui.post { toast(msg) }
        }.start()
    }

    // ---------- tests (the vendor app's Test screen)
    private fun sendCoil(addr: Int, on: Boolean, label: String) {
        testUsed = true
        Thread {
            val ok = try { ctl?.coil(addr, on) ?: false } catch (e: Throwable) { false }
            ui.post { toast(if (ok) "$label: command accepted" else "$label: no reply from the controller") }
        }.start()
        if (addr == 20 && on) { ui.removeCallbacks(heatOff); ui.postDelayed(heatOff, 120_000L) }
        if (addr == 28 && on) { ui.removeCallbacks(contOff); ui.postDelayed(contOff, 600_000L) }
    }

    private fun confirmTest(label: String, addr: Int, on: Boolean, warn: String) {
        if (state == "MAKING") { toast("Wait until the cup is finished"); return }
        AlertDialog.Builder(this).setTitle(label).setMessage(warn + "\n\nKeep hands and tools out of the machine.")
            .setPositiveButton("Run") { _, _ -> sendCoil(addr, on, label) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun stopAll() {
        ui.removeCallbacks(heatOff); ui.removeCallbacks(contOff)
        Thread {
            val c = ctl
            var ok = true
            for (a in intArrayOf(20, 19, 28)) {
                val r = try { c?.coil(a, false) ?: false } catch (e: Throwable) { false }
                ok = r && ok
            }
            ui.post {
                toast(if (ok) "Heater, fan and continuous test stopped"
                      else "Stop had no reply. If anything is still running, switch the machine off at its power switch.")
            }
        }.start()
    }

    // ---------- service menu: the screen itself
    private fun openSettings() {
        if (settingsV != null) return
        stopAttract()
        val texts = ArrayList<Pair<String, EditText>>()
        val switches = ArrayList<Pair<String, Switch>>()
        val live = ArrayList<() -> Unit>()
        val edits = HashMap<Int, EditText>()
        val nA = pref("nameA", "Amish Baby Corn")
        val nB = pref("nameB", "Orville Corn")

        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(40)) }

        val secViews = LinkedHashMap<String, View>()
        fun section(t: String) {
            val h = tv(t.uppercase(), 13f, true, RED).apply { gravity = Gravity.START; letterSpacing = 0.12f }
            secViews[t] = h
            form.addView(h, lp(-1, -2, 22))
        }
        fun group(): LinearLayout {
            val g = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(16).toFloat(); setStroke(dp(1), PINK) }
                setPadding(dp(14), dp(8), dp(14), dp(12))
            }
            form.addView(g, lp(-1, -2, 8))
            return g
        }
        fun note(g: LinearLayout, t: String) {
            g.addView(tv(t, 12f, false, MUTED).apply { gravity = Gravity.START }, lp(-1, -2, 8))
        }
        fun field(g: LinearLayout, label: String, key: String, def: String, number: Boolean = false) {
            g.addView(tv(label, 13f, false, MUTED).apply { gravity = Gravity.START }, lp(-1, -2, 8))
            val e = EditText(this).apply {
                setText(pref(key, def)); setTextColor(INK); textSize = 16f
                if (number) inputType = InputType.TYPE_CLASS_NUMBER
            }
            g.addView(e, lp(-1, -2)); texts.add(Pair(key, e))
        }
        fun toggle(g: LinearLayout, label: String, key: String, def: Boolean) {
            val s = Switch(this).apply { text = label; isChecked = on(key, def); setTextColor(INK); textSize = 16f }
            g.addView(s, lp(-1, -2, 8)); switches.add(Pair(key, s))
        }
        fun info(g: LinearLayout, label: String, f: (IntArray?) -> String) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val v = tv("-", 15f, true, RED).apply { gravity = Gravity.END }
            row.addView(tv(label, 15f, false, INK).apply { gravity = Gravity.START }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(v, LinearLayout.LayoutParams(0, -2, 1.5f))
            g.addView(row, lp(-1, -2, 8))
            live.add { v.text = f(lastRegs) }
        }
        fun regRow(g: LinearLayout, r: Reg) {
            val cur = lastRegs?.get(r.addr) ?: r.asFound
            val e = EditText(this).apply {
                setText(cur.toString()); inputType = InputType.TYPE_CLASS_NUMBER
                setTextColor(INK); textSize = 20f; gravity = Gravity.CENTER
            }
            val hint = tv("", 12f, false, MUTED).apply { gravity = Gravity.START }
            fun upd() {
                val v = e.text.toString().toIntOrNull()
                hint.text = "as found ${r.asFound}   |   allowed ${r.min} to ${r.max}" + (if (v != null) "   |   " + describe(r, v) else "")
            }
            upd()
            e.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { upd() }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) { }
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { }
            })
            fun bump(d: Int) {
                val v = (e.text.toString().toIntOrNull() ?: r.asFound) + d * r.step
                e.setText(v.coerceIn(r.min, r.max).toString())
            }
            g.addView(tv(r.label, 13f, false, MUTED).apply { gravity = Gravity.START }, lp(-1, -2, 12))
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(roundBtn("-") { bump(-1) }, LinearLayout.LayoutParams(dp(52), dp(52)))
            row.addView(e, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(roundBtn("+") { bump(1) }, LinearLayout.LayoutParams(dp(52), dp(52)))
            g.addView(row, lp(-1, -2, 4))
            g.addView(hint, lp(-1, -2, 2))
            edits[r.addr] = e
        }
        fun changes(list: List<Reg>, toAsFound: Boolean): List<Triple<Reg, Int, Int>> {
            val out = ArrayList<Triple<Reg, Int, Int>>()
            val cur = lastRegs
            for (r in list) {
                val old = cur?.get(r.addr) ?: r.asFound
                val nv = if (toAsFound) r.asFound else (edits[r.addr]?.text?.toString()?.toIntOrNull() ?: old)
                if (nv < r.min || nv > r.max) { toast(r.label + ": allowed " + r.min + " to " + r.max); return emptyList() }
                if (nv != old) out.add(Triple(r, old, nv))
            }
            return out
        }
        fun regGroup(title: String, help: String, list: List<Reg>) {
            section(title)
            val g = group()
            if (help.isNotEmpty()) note(g, help)
            for (r in list) regRow(g, r)
            g.addView(pillButton("Apply to machine", RED, Color.WHITE) { confirmWrites(changes(list, false)) }, lp(-1, -2, 16))
            g.addView(pillButton("Restore as-found values", PINK, RED) { confirmWrites(changes(list, true)) }, lp(-1, -2, 10))
        }
        fun testBtn(g: LinearLayout, label: String, addr: Int, turnOn: Boolean, warn: String, confirm: Boolean) {
            g.addView(pillButton(label, PINK, RED) {
                if (confirm) confirmTest(label, addr, turnOn, warn) else sendCoil(addr, turnOn, label)
            }, lp(-1, -2, 8))
        }

        // ----- work status (the vendor app's "work status" screen)
        // ----- service lockout
        section("Service lockout")
        val lg = group()
        note(lg, "Out of service mode. Customers see a 'Temporarily out of service' screen and the flavor buttons stop working. You can still open this menu by holding the title. A cup already being made finishes first. The setting is kept when the machine is switched off and on.")
        val lockHolder = arrayOfNulls<TextView>(1)
        fun lockLook() {
            val lk = on("lockout", false)
            lockHolder[0]?.text = if (lk) "Lockout is ON  -  tap to turn OFF" else "Lockout is OFF  -  tap to turn ON"
            lockHolder[0]?.background = box(if (lk) DEEP else RED, 24)
        }
        val lockBtn = pillButton("", RED, Color.WHITE) {
            val lk = !on("lockout", false)
            prefs.edit().putString("lockout", if (lk) "yes" else "no").apply()
            if (!lk && state == "LOCKED") state = "NOTICE"                 // the next poll returns to the home screen
            lockLook()
            toast(if (lk) "Lockout ON: customers will see 'out of service' when you close this menu" else "Lockout OFF: the machine is open again")
        }
        lockHolder[0] = lockBtn
        lockLook()
        lg.addView(lockBtn, lp(-1, -2, 8))

        section("Work status")
        var g = group()
        info(g, "Controller link") { r -> if (r == null) "no reply" else "connected" }
        info(g, "Heater plate temperature") { r -> if (r == null) "-" else "${r[3]} C" }
        info(g, "Fan speed") { r -> if (r == null) "-" else "${r[2]} rpm" }
        info(g, "Job progress") { r -> if (r == null) "-" else "${r[1]}%" }
        info(g, "Coins inserted now") { r -> if (r == null) "-" else "${r[4]}" }
        info(g, "Cups made: $nA") { r -> if (r == null) "-" else "${r[6]}" }
        info(g, "Cups made: $nB") { r -> if (r == null) "-" else "${r[7]}" }
        info(g, "Active flags") { r ->
            if (r == null) "-" else {
                val on = ArrayList<String>()
                for (i in FLAG_NAMES.indices) if (((r[0] shr i) and 1) == 1) on.add(FLAG_NAMES[i])
                if (on.isEmpty()) "idle" else on.joinToString(", ")
            }
        }
        info(g, "Active errors") { r ->
            if (r == null) "-" else {
                val on = ArrayList<String>()
                for (i in ERR_NAMES.indices) if (((r[5] shr i) and 1) == 1) on.add(ERR_NAMES[i])
                if (on.isEmpty()) "none" else on.joinToString(", ")
            }
        }
        info(g, "Raw flags / errors") { r -> if (r == null) "-" else "%04X / %02X".format(r[0], r[5]) }
        info(g, "Last link error") { _ -> if (lastError.isEmpty()) "none" else lastError.take(40) }

        // ----- fault history
        section("Fault history")
        g = group()
        val flog = faultLines()
        note(g, if (flog.isEmpty()) "No faults recorded yet. Newest faults will show here, with when they happened, how long they lasted and what the machine was doing."
                else "Newest first. The last 40 faults are kept (${flog.size} recorded).")
        for (line in flog.reversed().take(25)) {
            g.addView(tv(faultSummary(line), 14f, false, INK).apply {
                background = box(CREAM, 14); setPadding(dp(14), dp(10), dp(14), dp(10))
            }, lp(-1, -2, 8))
        }
        if (flog.isNotEmpty()) g.addView(pillButton("Clear fault history", PINK, RED) {
            AlertDialog.Builder(this).setTitle("Clear fault history?").setMessage("This removes all recorded faults.")
                .setPositiveButton("Clear") { _, _ -> prefs.edit().remove("faultLog").apply(); toast("Fault history cleared"); closeSettings(); openSettings() }
                .setNegativeButton("Cancel", null).show()
        }, lp(-1, -2, 12))

        // ----- flavors
        section("Flavors")
        g = group()
        toggle(g, "Show the $nA button", "enableA", true)
        field(g, "Name of flavor A (NAME_A)", "nameA", "Amish Baby Corn")
        toggle(g, "Show the $nB button", "enableB", true)
        field(g, "Name of flavor B (NAME_B)", "nameB", "Orville Corn")
        note(g, "Flavor pictures: put a.bmp (or .png / .jpg) and b.bmp in the machine's popcron/res folder, like the vendor app.")

        // ----- registers, grouped like the vendor app's settings screens
        regGroup("Feeding (stuff amount)",
            "The vendor app calls these 'A/B feeding setting' (the kernels dropped per cup). Higher = more kernels, in my reading. Use the dispense test further down to check a change.",
            listOf(Reg("Feeding amount: $nA", 0x0C, 20, 5, 60, 1, "feed"), Reg("Feeding amount: $nB", 0x0D, 20, 5, 60, 1, "feed")))
        regGroup("Time", "Units are tenths of a second, as in the vendor app.",
            listOf(Reg("Making time: $nA", 0x10, 1200, 600, 1800, 10, "ds"), Reg("Making time: $nB", 0x11, 1200, 600, 1800, 10, "ds"),
                Reg("Boiler preheat time", 0x12, 200, 100, 400, 10, "ds")))
        regGroup("Fan speed", "Making fan speed per flavor.",
            listOf(Reg("Making fan speed: $nA", 0x0E, 6200, 4400, 8000, 100, "rpm"), Reg("Making fan speed: $nB", 0x0F, 6200, 4400, 8000, 100, "rpm")))
        regGroup("Blow-out", "How the finished corn is blown into the cup.",
            listOf(Reg("Blow-out fan speed", 0x17, 18000, 12600, 23400, 200, "rpm"), Reg("Blow-out time", 0x18, 35, 10, 100, 5, "ds")))
        regGroup("Temperature", "Allowed range is limited to what I judged safe. The real plate temperature is shown under Work status.",
            listOf(Reg("Preset temperature", 0x08, 140, 100, 180, 5, "C"), Reg("Feeding (stuff) temperature", 0x09, 140, 100, 180, 5, "C")))
        regGroup("Price and machine", "Price 0 = free. A price above 0 may make the controller wait for payment.",
            listOf(Reg("Price: $nA", 0x19, 0, 0, 9999, 1, ""), Reg("Price: $nB", 0x1A, 0, 0, 9999, 1, ""),
                Reg("Machine number", 0x1B, 0, 0, 65535, 1, ""), Reg("Manufacture date (YYMM)", 0x1C, 2604, 0, 9912, 1, "")))
        regGroup("Payment multipliers (not used while free)", "",
            listOf(Reg("Bill acceptor setting", 0x0A, 100, 1, 1000, 1, ""), Reg("Coin acceptor setting", 0x0B, 50, 1, 1000, 1, "")))

        // ----- tests (the vendor app's Test screen)
        section("Tests")
        g = group()
        note(g, "These move real parts. Only run them with the machine open and watched, hands clear. STOP ALL turns off the heater, fan and continuous test.")
        g.addView(pillButton("STOP ALL", DEEP, Color.WHITE) { stopAll() }, lp(-1, -2, 8))
        testBtn(g, "Dispense kernels only: $nA", 25, true, "Runs the feed motor once without making a cup.", true)
        testBtn(g, "Dispense kernels only: $nB", 26, true, "Runs the feed motor once without making a cup.", true)
        testBtn(g, "Drop a cup", 22, true, "Drops one cup.", true)
        testBtn(g, "Make a cup: $nA (test)", 16, true, "Starts a full cup of $nA.", true)
        testBtn(g, "Make a cup: $nB (test)", 17, true, "Starts a full cup of $nB.", true)
        testBtn(g, "Heater ON (auto-off after 2 minutes)", 20, true, "Heats the plate. This app switches it off after 2 minutes.", true)
        testBtn(g, "Heater OFF", 20, false, "", false)
        testBtn(g, "Fan start", 21, true, "Starts the fan.", true)
        testBtn(g, "Fan faster", 23, true, "Speeds the fan up.", true)
        testBtn(g, "Fan slower", 24, true, "Slows the fan down.", true)
        testBtn(g, "Fan stop", 19, false, "", false)
        testBtn(g, "Continuous making ON (auto-off after 10 minutes)", 28, true, "Keeps making cups until stopped. Do not leave it unattended.", true)
        testBtn(g, "Continuous making OFF", 28, false, "", false)

        // ----- screen text and attract videos
        section("Screen")
        g = group()
        field(g, "Headline", "headline", "Fresh hot popcorn, made just for you")
        field(g, "Line under the headline", "subline", "Pick a flavor. No coins, no cards, no catch.")
        field(g, "Thank-you message", "thanks", "Please take your cup.\nCome back anytime, it's always free.")
        field(g, "Thank-you screen seconds (2 to 60)", "doneSecs", "7", true)
        field(g, "Location name (optional, shown on the ready screen)", "localName", "")
        field(g, "Hot line (optional, shown on the ready screen)", "hotLine", "")
        toggle(g, "Floating popcorn animation", "anim", true)
        toggle(g, "Show service line at the bottom of the screen", "footer", true)
        toggle(g, "Close the service menu after 5 minutes without a touch", "autoClose", true)

        section("Attract videos")
        g = group()
        note(g, "After the screen has been idle, videos from the folder below play full screen. A touch brings back the menu. The vendor app used popcron/adv.")
        toggle(g, "Play attract videos", "attractOn", true)
        toggle(g, "Play video sound", "attractSound", false)
        field(g, "Idle seconds before videos start (10 to 3600)", "attractSecs", "60", true)
        field(g, "Video folder (.mp4 files)", "advDir", Environment.getExternalStorageDirectory().absolutePath + "/popcron/adv")

        // ----- connection
        section("Connection")
        g = group()
        field(g, "Stop the old vendor popcorn app when this app opens: yes or no", "killVendor", "yes")
        field(g, "Vendor app package name", "vendorPkg", "com.yuchen.popcorn")
        field(g, "Link: serial (real machine) or sim (test simulator)", "link", "serial")
        field(g, "Serial port (control board)", "path", "/dev/ttyS1")
        field(g, "Baud rate", "baud", "115200", true)
        val ports = (java.io.File("/dev").listFiles()?.map { it.name }?.filter {
            it.startsWith("ttyS") || it.startsWith("ttyUSB") || it.startsWith("ttyACM") || it.startsWith("ttyAMA") || it.startsWith("ttyHS")
        }?.sorted() ?: emptyList()).joinToString(", ") { "/dev/$it" }
        note(g, "Ports this app can see: " + (if (ports.isEmpty()) "none visible (that is normal on a PC emulator)" else ports))
        field(g, "Register code (vendor cloud ID, kept for reference only)", "regCode", "")
        note(g, "MDB payment (bill acceptor, card reader): not supported in this app. Your machine has all of it switched off, and the machine is free.")
        note(g, "Connection changes take effect after Save.")

        // ----- password and maintenance
        section("Password")
        g = group()
        note(g, "The service password is stored in the controller, the same one the vendor app uses (default 0000).")
        g.addView(pillButton("Change service password", PINK, RED) { changePwdDialog() }, lp(-1, -2, 8))

        section("Maintenance")
        g = group()
        g.addView(pillButton("Reconnect to controller", PINK, RED) { closeLink(); toast("Reconnecting...") }, lp(-1, -2, 8))
        g.addView(pillButton("Save and restart this app", RED, Color.WHITE) {
            saveSettings(texts, switches); closeSettings(); recreate()
        }, lp(-1, -2, 10))
        g.addView(pillButton("Exit to Android", DEEP, Color.WHITE) {
            window.decorView.systemUiVisibility = 0; finish()
        }, lp(-1, -2, 10))

        // ----- assemble the screen
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(RED); setPadding(dp(16), dp(14), dp(12), dp(14))
            addView(tv("Service menu", 22f, true, Color.WHITE).apply { gravity = Gravity.START }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(pillButton("Save", Color.WHITE, RED) { saveSettings(texts, switches); closeSettings() })
            addView(pillButton("Close", DEEP, Color.WHITE) { closeSettings() }, lp().apply { leftMargin = dp(10) })
        }
        val sv = ScrollView(this).apply { addView(form) }
        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(10), dp(8), dp(10), dp(8)) }
        val jumps = listOf("Lockout" to "Service lockout", "Status" to "Work status", "Faults" to "Fault history",
            "Flavors" to "Flavors", "Feeding" to "Feeding (stuff amount)", "Time" to "Time", "Fan" to "Fan speed",
            "Blow-out" to "Blow-out", "Temp" to "Temperature", "Price" to "Price and machine",
            "Tests" to "Tests", "Screen" to "Screen", "Videos" to "Attract videos",
            "Connection" to "Connection", "Password" to "Password", "Maintenance" to "Maintenance")
        for ((label, key) in jumps) {
            chipRow.addView(pillButton(label, PINK, RED) {
                secViews[key]?.let { v -> sv.smoothScrollTo(0, maxOf(0, v.top - dp(8))) }
            }, lp().apply { rightMargin = dp(8) })
        }
        val chips = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; setBackgroundColor(Color.WHITE); addView(chipRow)
        }
        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(CREAM)
            addView(bar, lp(-1, -2)); addView(chips, lp(-1, -2)); addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        settingsV = overlay
        lastTouch = System.currentTimeMillis()
        liveUpdate = { for (f in live) f() }
        liveUpdate?.invoke()
        ui.postDelayed(liveRefresh, 1000)
    }
}
