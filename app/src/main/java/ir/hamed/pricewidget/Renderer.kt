package ir.hamed.pricewidget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** کل ظاهر ویجت روی یک بوم (Canvas) کشیده می‌شود تا هر طرح ایرانی ممکن باشد */
class Renderer(private val W: Float, private val H: Float, private val d: Data) {

    class Wx(val temp: Int?, val code: Int, val isDay: Boolean?)

    class Data(ctx: Context) {
        val cal: Calendar = Calendar.getInstance()
        val prices: JSONObject
        val weather: List<Wx>

        init {
            val p = ctx.getSharedPreferences("data", Context.MODE_PRIVATE)
            prices = runCatching { JSONObject(p.getString("prices", "") ?: "") }
                .getOrDefault(JSONObject())
            val arr = runCatching { JSONArray(p.getString("weather", "") ?: "") }.getOrNull()
            weather = Config.CITIES.indices.map { i ->
                val cur = arr?.optJSONObject(i)?.optJSONObject("current")
                if (cur == null) Wx(null, -1, null) else Wx(
                    if (cur.has("temperature_2m")) cur.optDouble("temperature_2m").roundToInt() else null,
                    cur.optInt("weather_code", -1),
                    if (cur.has("is_day")) cur.optInt("is_day") == 1 else null
                )
            }
        }

        /** روز یا شب بودن بر اساس شاهدشهر (اگر داده نبود، از ساعت گوشی) */
        val isDay: Boolean
            get() = weather.firstOrNull()?.isDay ?: (cal.get(Calendar.HOUR_OF_DAY) in 6..17)
    }

    companion object {
        private val BOLD: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
        private val NORMAL: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)

        fun render(ctx: Context, wDp: Int, hDp: Int): Bitmap {
            val dm = ctx.resources.displayMetrics
            var w = wDp * dm.density
            var h = hDp * dm.density
            val maxPix = 1_200_000f
            if (w * h > maxPix) {
                val s = sqrt(maxPix / (w * h))
                w *= s; h *= s
            }
            val bmp = Bitmap.createBitmap(
                w.toInt().coerceAtLeast(240), h.toInt().coerceAtLeast(240), Bitmap.Config.ARGB_8888
            )
            Renderer(bmp.width.toFloat(), bmp.height.toFloat(), Data(ctx)).draw(Canvas(bmp))
            return bmp
        }
    }

    // ---------------------------------------------------------------- رنگ‌ها
    private val GOLD = 0xFFE8C766.toInt()
    private val TURQ = 0xFF3CCFC0.toInt()
    private val WHITE = 0xFFFFFFFF.toInt()
    private val SOFT = 0xFFB8C7F0.toInt()
    private val GREEN = 0xFF3DDC84.toInt()
    private val RED = 0xFFFF5A5F.toInt()
    private val ROSE = 0xFFEF5B7B.toInt()
    private val CARD = 0xB00A1744.toInt()

    private val u = W / 100f
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)

    private enum class Sky { CLEAR, PARTLY, CLOUDY, FOG, RAIN, SNOW, STORM }

    private fun skyOf(code: Int): Sky = when (code) {
        0, 1 -> Sky.CLEAR
        2 -> Sky.PARTLY
        3 -> Sky.CLOUDY
        45, 48 -> Sky.FOG
        in 51..67, in 80..82 -> Sky.RAIN
        in 71..77, 85, 86 -> Sky.SNOW
        in 95..99 -> Sky.STORM
        else -> Sky.CLEAR
    }

    private fun skyColors(s: Sky, day: Boolean): Pair<Int, Int> = when (s) {
        Sky.CLEAR, Sky.PARTLY ->
            if (day) Pair(0xFF3E9BE0.toInt(), 0xFFBFE4F8.toInt()) else Pair(0xFF050A24.toInt(), 0xFF1B2B66.toInt())
        Sky.CLOUDY ->
            if (day) Pair(0xFF7B93AB.toInt(), 0xFFC7D2DC.toInt()) else Pair(0xFF121A33.toInt(), 0xFF2D3757.toInt())
        Sky.FOG ->
            if (day) Pair(0xFF9FB1BF.toInt(), 0xFFDCE4EA.toInt()) else Pair(0xFF222B40.toInt(), 0xFF4A546C.toInt())
        Sky.RAIN ->
            if (day) Pair(0xFF506479.toInt(), 0xFF9CB0C0.toInt()) else Pair(0xFF0C1226.toInt(), 0xFF283250.toInt())
        Sky.STORM ->
            if (day) Pair(0xFF3B4A5C.toInt(), 0xFF7D8DA0.toInt()) else Pair(0xFF070B1A.toInt(), 0xFF1D2540.toInt())
        Sky.SNOW ->
            if (day) Pair(0xFFA7B8C9.toInt(), 0xFFEAF0F5.toInt()) else Pair(0xFF1A2442.toInt(), 0xFF4B5A7D.toInt())
    }

    // ---------------------------------------------------------------- ابزارها
    private fun txt(
        c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int,
        bold: Boolean = false, align: Paint.Align = Paint.Align.CENTER,
        maxW: Float = 0f, shadow: Boolean = false
    ) {
        tp.typeface = if (bold) BOLD else NORMAL
        tp.color = color
        tp.textAlign = align
        tp.textSize = size
        if (maxW > 0f) {
            while (tp.measureText(s) > maxW && tp.textSize > 6f) tp.textSize -= 0.5f
        }
        if (shadow) tp.setShadowLayer(.6f * u, 0f, .25f * u, 0xAA000000.toInt())
        c.drawText(s, x, y, tp)
        if (shadow) tp.clearShadowLayer()
    }

    private fun mw(s: String, size: Float, bold: Boolean = false): Float {
        tp.typeface = if (bold) BOLD else NORMAL
        tp.textSize = size
        return tp.measureText(s)
    }

    private fun star8(c: Canvas, cx: Float, cy: Float, r: Float, p: Paint) {
        val path = Path()
        for (k in 0 until 16) {
            val a = Math.PI * k / 8.0 - Math.PI / 2
            val rr = if (k % 2 == 0) r else r * 0.62f
            val x = cx + (rr * cos(a)).toFloat()
            val y = cy + (rr * sin(a)).toFloat()
            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        c.drawPath(path, p)
    }

    private fun diamond(c: Canvas, x: Float, y: Float, s: Float, color: Int) {
        val p = Path()
        p.moveTo(x, y - s); p.lineTo(x + s * .7f, y); p.lineTo(x, y + s); p.lineTo(x - s * .7f, y)
        p.close()
        fill.color = color
        c.drawPath(p, fill)
    }

    private fun heart(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        val p = Path()
        p.moveTo(cx, cy + s * .9f)
        p.cubicTo(cx - s * 1.3f, cy, cx - s * .9f, cy - s * .9f, cx, cy - s * .35f)
        p.cubicTo(cx + s * .9f, cy - s * .9f, cx + s * 1.3f, cy, cx, cy + s * .9f)
        p.close()
        fill.color = color
        c.drawPath(p, fill)
    }

    private fun card(c: Canvas, r: RectF) {
        val rad = 2.2f * u
        fill.color = CARD
        c.drawRoundRect(r, rad, rad, fill)
        line.color = GOLD; line.strokeWidth = .32f * u
        c.drawRoundRect(r, rad, rad, line)
        val s = .85f * u
        diamond(c, r.left + .6f * u, r.top + .6f * u, s, GOLD)
        diamond(c, r.right - .6f * u, r.top + .6f * u, s, GOLD)
        diamond(c, r.left + .6f * u, r.bottom - .6f * u, s, GOLD)
        diamond(c, r.right - .6f * u, r.bottom - .6f * u, s, GOLD)
    }

    private fun fmtTemp(t: Int?): String {
        if (t == null) return "—"
        return (if (t < 0) "\u200E−" else "") + Fa.d(abs(t).toString()) + "°"
    }

    private fun greeting(): String {
        val h = d.cal.get(Calendar.HOUR_OF_DAY)
        return when {
            h in 4..10 -> "صبح بخیر"
            h in 11..14 -> "ظهر بخیر"
            h in 15..17 -> "عصر بخیر"
            else -> "شب بخیر"
        }
    }

    // ---------------------------------------------------------------- ترکیب کلی
    fun draw(c: Canvas) {
        background(c)
        val pad = 4.4f * u
        dates(c, RectF(pad, .19f * H, W - pad, .29f * H))
        scene(c, RectF(pad, .30f * H, W - pad, .545f * H))
        prices(c, RectF(pad, .555f * H, W - pad, .675f * H))
        weather(c, RectF(pad, .685f * H, W - pad, .83f * H))
        poem(c, RectF(pad, .84f * H, W - pad, .965f * H))
    }

    // ---------------------------------------------------------------- پس‌زمینه و قاب
    private fun background(c: Canvas) {
        val rr = RectF(0f, 0f, W, H)
        fill.shader = LinearGradient(
            0f, 0f, W * .6f, H,
            intArrayOf(0xFF081433.toInt(), 0xFF132C72.toInt(), 0xFF081433.toInt()),
            floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRoundRect(rr, 7 * u, 7 * u, fill)
        fill.shader = null

        c.save()
        c.clipPath(Path().apply { addRoundRect(rr, 7 * u, 7 * u, Path.Direction.CW) })
        // درخشش پشت ساعت
        fill.shader = RadialGradient(
            W / 2, .10f * H, W * .55f, 0x38E8C766, 0x00E8C766, Shader.TileMode.CLAMP
        )
        c.drawCircle(W / 2, .10f * H, W * .55f, fill)
        fill.shader = null
        // نقش ستاره‌ی هشت‌پر
        line.color = 0x2AE8C766; line.strokeWidth = .22f * u
        val s = 13f * u
        var row = 0
        var y = s / 2
        while (y < H + s) {
            var x = if (row % 2 == 0) s / 2 else s
            while (x < W + s) {
                star8(c, x, y, s * .38f, line)
                x += s
            }
            y += s * .9f
            row++
        }
        c.restore()

        // قاب طلایی دوگانه
        line.color = GOLD; line.strokeWidth = .7f * u
        c.drawRoundRect(RectF(.9f * u, .9f * u, W - .9f * u, H - .9f * u), 6.2f * u, 6.2f * u, line)
        line.color = 0x66E8C766; line.strokeWidth = .25f * u
        c.drawRoundRect(RectF(2.1f * u, 2.1f * u, W - 2.1f * u, H - 2.1f * u), 5.2f * u, 5.2f * u, line)
        fill.color = GOLD
        star8(c, 4f * u, 4f * u, 1.7f * u, fill)
        star8(c, W - 4f * u, 4f * u, 1.7f * u, fill)
        star8(c, 4f * u, H - 4f * u, 1.7f * u, fill)
        star8(c, W - 4f * u, H - 4f * u, 1.7f * u, fill)

        // تزئین دو طرف ساعت
        val y0 = .10f * H
        line.color = 0x99E8C766.toInt(); line.strokeWidth = .3f * u
        c.drawLine(8f * u, y0, 17f * u, y0, line)
        c.drawLine(W - 17f * u, y0, W - 8f * u, y0, line)
        fill.color = GOLD
        star8(c, 18.8f * u, y0, 1.6f * u, fill)
        star8(c, W - 18.8f * u, y0, 1.6f * u, fill)
        // خط زیر ساعت
        val ys = .182f * H
        c.drawLine(8f * u, ys, W / 2 - 3f * u, ys, line)
        c.drawLine(W / 2 + 3f * u, ys, W - 8f * u, ys, line)
        star8(c, W / 2, ys, 1.5f * u, fill)
    }

    // ---------------------------------------------------------------- سه تاریخ
    private fun dates(c: Canvas, r: RectF) {
        val cal = d.cal
        val gy = cal.get(Calendar.YEAR)
        val gm = cal.get(Calendar.MONTH)
        val gd = cal.get(Calendar.DAY_OF_MONTH)
        val (jy, jm, jd) = Fa.toJalali(gy, gm + 1, gd)
        val items = listOf(
            Triple("شمسی", "$jd ${Fa.MONTHS[jm - 1]}", "$jy"),
            Triple("شاهنشاهی", "$jd ${Fa.MONTHS[jm - 1]}", "${jy + 1180}"),
            Triple("میلادی", "$gd ${Fa.GMONTHS[gm]}", "$gy"),
        )
        val gap = 2f * u
        val cw = (r.width() - 2 * gap) / 3
        items.forEachIndexed { i, (title, l1, l2) ->
            val right = r.right - i * (cw + gap)
            val rc = RectF(right - cw, r.top, right, r.bottom)
            card(c, rc)
            val h = rc.height()
            val cx = rc.centerX()
            txt(c, title, cx, rc.top + .24f * h, .17f * h, TURQ, true)
            txt(c, Fa.d(l1), cx, rc.top + .55f * h, .28f * h, WHITE, true, maxW = cw * .9f)
            txt(c, Fa.d(l2), cx, rc.top + .84f * h, .22f * h, GOLD)
        }
    }

    // ---------------------------------------------------------------- تصویر وسط (هوای شاهدشهر + روز و شب)
    private fun cloud(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        fill.color = color
        c.drawCircle(cx - s * .45f, cy + s * .05f, s * .28f, fill)
        c.drawCircle(cx - s * .10f, cy - s * .12f, s * .36f, fill)
        c.drawCircle(cx + s * .28f, cy, s * .30f, fill)
        c.drawCircle(cx + s * .55f, cy + s * .10f, s * .20f, fill)
        c.drawRoundRect(RectF(cx - s * .7f, cy + s * .05f, cx + s * .75f, cy + s * .30f), s * .12f, s * .12f, fill)
    }

    private fun sun(c: Canvas, cx: Float, cy: Float, rad: Float) {
        fill.shader = RadialGradient(cx, cy, rad * 2.6f, 0x88FFE08A.toInt(), 0x00FFE08A, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, rad * 2.6f, fill)
        fill.shader = null
        line.color = 0xCCFFD54A.toInt(); line.strokeWidth = .45f * u
        for (k in 0 until 12) {
            val a = Math.PI * 2 * k / 12
            c.drawLine(
                cx + (rad * 1.25f * cos(a)).toFloat(), cy + (rad * 1.25f * sin(a)).toFloat(),
                cx + (rad * 1.65f * cos(a)).toFloat(), cy + (rad * 1.65f * sin(a)).toFloat(), line
            )
        }
        fill.color = 0xFFFFD54A.toInt()
        c.drawCircle(cx, cy, rad, fill)
    }

    private fun moon(c: Canvas, cx: Float, cy: Float, rad: Float) {
        fill.shader = RadialGradient(cx, cy, rad * 2.4f, 0x55F5F0D0, 0x00F5F0D0, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, rad * 2.4f, fill)
        fill.shader = null
        val a = Path().apply { addCircle(cx, cy, rad, Path.Direction.CW) }
        val b = Path().apply { addCircle(cx + rad * .55f, cy - rad * .2f, rad * .9f, Path.Direction.CW) }
        a.op(b, Path.Op.DIFFERENCE)
        fill.color = 0xFFF8F1C9.toInt()
        c.drawPath(a, fill)
    }

    private fun cypress(c: Canvas, cx: Float, baseY: Float, hgt: Float, color: Int) {
        val wid = hgt * .17f
        val p = Path()
        p.moveTo(cx, baseY - hgt)
        p.quadTo(cx + wid * 1.6f, baseY - hgt * .42f, cx, baseY)
        p.quadTo(cx - wid * 1.6f, baseY - hgt * .42f, cx, baseY - hgt)
        p.close()
        fill.color = color
        c.drawPath(p, fill)
    }

    private fun scene(c: Canvas, r: RectF) {
        val wx0 = d.weather[0]
        val sky = skyOf(wx0.code)
        val day = d.isDay
        val w = r.width()
        val h = r.height()
        val rnd = Random(11)

        c.save()
        c.clipPath(Path().apply { addRoundRect(r, 2.6f * u, 2.6f * u, Path.Direction.CW) })

        val (top, bot) = skyColors(sky, day)
        fill.shader = LinearGradient(r.left, r.top, r.left, r.bottom, top, bot, Shader.TileMode.CLAMP)
        c.drawRect(r, fill)
        fill.shader = null

        // ستاره‌ها در شب صاف
        if (!day && (sky == Sky.CLEAR || sky == Sky.PARTLY)) {
            for (i in 0 until 26) {
                val sx = r.left + rnd.nextFloat() * w
                val sy = r.top + rnd.nextFloat() * h * .6f
                fill.color = ((0x55 + rnd.nextInt(0xAA)) shl 24) or 0xFFFFFF
                c.drawCircle(sx, sy, (.25f + rnd.nextFloat() * .4f) * u, fill)
            }
        }
        // خورشید یا ماه
        if (sky == Sky.CLEAR || sky == Sky.PARTLY) {
            val cx = r.left + w * .22f
            val cy = r.top + h * .32f
            if (day) sun(c, cx, cy, h * .15f) else moon(c, cx, cy, h * .14f)
        }
        // ابرها
        val cloudColor = when (sky) {
            Sky.RAIN, Sky.STORM -> if (day) 0xFF8796A5.toInt() else 0xFF39425A.toInt()
            Sky.SNOW -> if (day) 0xFFDDE5EC.toInt() else 0xFF6A7690.toInt()
            Sky.FOG -> 0xCCDDDDDD.toInt()
            else -> if (day) 0xFFFFFFFF.toInt() else 0xFF8A93B5.toInt()
        }
        when (sky) {
            Sky.PARTLY -> {
                cloud(c, r.left + w * .55f, r.top + h * .34f, h * .42f, cloudColor)
                cloud(c, r.left + w * .82f, r.top + h * .22f, h * .28f, cloudColor)
            }
            Sky.CLOUDY, Sky.RAIN, Sky.SNOW, Sky.STORM, Sky.FOG -> {
                cloud(c, r.left + w * .25f, r.top + h * .26f, h * .46f, cloudColor)
                cloud(c, r.left + w * .60f, r.top + h * .20f, h * .52f, cloudColor)
                cloud(c, r.left + w * .88f, r.top + h * .30f, h * .40f, cloudColor)
            }
            else -> {}
        }
        // باران
        if (sky == Sky.RAIN || sky == Sky.STORM) {
            line.color = 0xAACDE6FF.toInt(); line.strokeWidth = .28f * u
            for (i in 0 until 46) {
                val x = r.left + rnd.nextFloat() * w
                val y = r.top + h * .25f + rnd.nextFloat() * h * .6f
                c.drawLine(x, y, x - .9f * u, y + 3.2f * u, line)
            }
        }
        if (sky == Sky.STORM) {
            val bx = r.left + w * .55f
            val by = r.top + h * .38f
            val p = Path()
            p.moveTo(bx, by); p.lineTo(bx - 2f * u, by + 5f * u); p.lineTo(bx + .5f * u, by + 5f * u)
            p.lineTo(bx - 1.5f * u, by + 10.5f * u); p.lineTo(bx + 3f * u, by + 3.5f * u)
            p.lineTo(bx + .5f * u, by + 3.5f * u); p.lineTo(bx + 2.2f * u, by)
            p.close()
            fill.color = 0xFFFFE066.toInt()
            c.drawPath(p, fill)
        }
        // برف
        if (sky == Sky.SNOW) {
            fill.color = 0xE6FFFFFF.toInt()
            for (i in 0 until 40) {
                val x = r.left + rnd.nextFloat() * w
                val y = r.top + h * .2f + rnd.nextFloat() * h * .7f
                c.drawCircle(x, y, (.35f + rnd.nextFloat() * .45f) * u, fill)
            }
        }
        // مه
        if (sky == Sky.FOG) {
            fill.color = 0x55FFFFFF
            for (i in 0 until 3) {
                val y = r.top + h * (.45f + i * .14f)
                c.drawRoundRect(RectF(r.left - 2 * u, y, r.right + 2 * u, y + h * .07f), 3f * u, 3f * u, fill)
            }
        }
        // تپه‌ها
        val snow = sky == Sky.SNOW
        val hill1 = if (snow) 0xFFC9D6E2.toInt() else if (day) 0xFF3A8F5C.toInt() else 0xFF123B3C.toInt()
        val hill2 = if (snow) 0xFFEAF1F7.toInt() else if (day) 0xFF2D7A4C.toInt() else 0xFF0B2A2D.toInt()
        val tree = if (snow) 0xFF2E5B4A.toInt() else if (day) 0xFF1F5E3A.toInt() else 0xFF07201F.toInt()
        val p1 = Path()
        p1.moveTo(r.left, r.bottom); p1.lineTo(r.left, r.top + h * .70f)
        p1.cubicTo(r.left + w * .30f, r.top + h * .55f, r.left + w * .55f, r.top + h * .80f, r.right, r.top + h * .62f)
        p1.lineTo(r.right, r.bottom); p1.close()
        fill.color = hill1; c.drawPath(p1, fill)
        val p2 = Path()
        p2.moveTo(r.left, r.bottom); p2.lineTo(r.left, r.top + h * .84f)
        p2.cubicTo(r.left + w * .35f, r.top + h * .74f, r.left + w * .70f, r.top + h * .92f, r.right, r.top + h * .80f)
        p2.lineTo(r.right, r.bottom); p2.close()
        fill.color = hill2; c.drawPath(p2, fill)
        val base = r.top + h * .90f
        cypress(c, r.left + w * .91f, base, h * .46f, tree)
        cypress(c, r.left + w * .84f, base, h * .33f, tree)
        cypress(c, r.left + w * .07f, base, h * .36f, tree)
        cypress(c, r.left + w * .14f, base, h * .25f, tree)
        c.restore()

        // قاب صحنه
        line.color = GOLD; line.strokeWidth = .45f * u
        c.drawRoundRect(r, 2.6f * u, 2.6f * u, line)
        diamond(c, r.left, r.top, 1.1f * u, GOLD)
        diamond(c, r.right, r.top, 1.1f * u, GOLD)
        diamond(c, r.left, r.bottom, 1.1f * u, GOLD)
        diamond(c, r.right, r.bottom, 1.1f * u, GOLD)

        // متن‌ها روی صحنه
        val dow = Fa.weekday(d.cal.get(Calendar.DAY_OF_WEEK))
        txt(c, "$dow · ${greeting()}", r.right - 3f * u, r.top + .16f * h, .095f * h, WHITE,
            true, Paint.Align.RIGHT, shadow = true)
        val info = Config.CITIES[0].name + " · " + fmtTemp(wx0.temp) + " · " +
                (if (wx0.code >= 0) Fa.weatherText(wx0.code) else "—")
        val fs = .095f * h
        val pw = mw(info, fs, true) + 5f * u
        val ph = fs * 1.9f
        val py = r.bottom - .07f * h - ph
        fill.color = 0x9906122E.toInt()
        c.drawRoundRect(RectF(r.centerX() - pw / 2, py, r.centerX() + pw / 2, py + ph), ph / 2, ph / 2, fill)
        txt(c, info, r.centerX(), py + ph * .66f, fs, WHITE, true)
    }

    // ---------------------------------------------------------------- قیمت‌ها
    private fun arrow(c: Canvas, cx: Float, cy: Float, s: Float, up: Boolean, color: Int) {
        val p = Path()
        if (up) {
            p.moveTo(cx, cy - s / 2); p.lineTo(cx + s / 2, cy + s / 2); p.lineTo(cx - s / 2, cy + s / 2)
        } else {
            p.moveTo(cx, cy + s / 2); p.lineTo(cx + s / 2, cy - s / 2); p.lineTo(cx - s / 2, cy - s / 2)
        }
        p.close()
        fill.color = color
        c.drawPath(p, fill)
    }

    private fun prices(c: Canvas, r: RectF) {
        val defs = listOf("usd" to "دلار", "usdt" to "تتر", "gold18" to "طلا ۱۸ (گرم)")
        val gap = 2f * u
        val cw = (r.width() - 2 * gap) / 3
        val approxArr = d.prices.optJSONArray("approx")
        val pctObj = d.prices.optJSONObject("pct")
        defs.forEachIndexed { i, (k, label) ->
            val right = r.right - i * (cw + gap)
            val rc = RectF(right - cw, r.top, right, r.bottom)
            card(c, rc)
            val h = rc.height()
            val cx = rc.centerX()
            val v = d.prices.optLong(k, 0L)
            val approx = approxArr != null && (0 until approxArr.length()).any { approxArr.optString(it) == k }
            txt(c, label, cx, rc.top + .22f * h, .15f * h, GOLD, true)
            val value = if (v > 0) (if (approx) "~" else "") + Fa.money(v) else "—"
            txt(c, value, cx, rc.top + .55f * h, .26f * h, WHITE, true, maxW = cw * .92f)

            if (pctObj != null && pctObj.has(k)) {
                val pct = pctObj.optDouble(k)
                val flat = abs(pct) < 0.005
                val col = if (flat) SOFT else if (pct > 0) GREEN else RED
                val ps = .17f * h
                val t = Fa.d(String.format(Locale.US, "%.2f", abs(pct)).replace('.', '٫')) + "٪"
                val tw = mw(t, ps, true)
                val a = .15f * h
                val total = tw + if (flat) 0f else a + .8f * u
                var x = cx - total / 2f
                val yb = rc.top + .87f * h
                if (!flat) {
                    arrow(c, x + a / 2, yb - ps * .35f, a, pct > 0, col)
                    x += a + .8f * u
                }
                txt(c, t, x, yb, ps, col, true, Paint.Align.LEFT)
            }
        }
    }

    // ---------------------------------------------------------------- آب‌وهوای چهار شهر
    private fun weather(c: Canvas, r: RectF) {
        val gap = 2f * u
        val cw = (r.width() - gap) / 2
        val ch = (r.height() - gap) / 2
        for (i in 0 until 4) {
            val col = i % 2
            val row = i / 2
            val right = r.right - col * (cw + gap)
            val top = r.top + row * (ch + gap)
            val rc = RectF(right - cw, top, right, top + ch)
            card(c, rc)
            val wx = d.weather[i]
            val h = ch
            txt(c, Config.CITIES[i].name, rc.right - 2.2f * u, rc.top + .45f * h, .30f * h, WHITE,
                true, Paint.Align.RIGHT, maxW = cw * .5f)
            txt(c, if (wx.code >= 0) Fa.weatherText(wx.code) else "—", rc.right - 2.2f * u, rc.top + .80f * h,
                .21f * h, SOFT, false, Paint.Align.RIGHT, maxW = cw * .5f)
            txt(c, Fa.weatherEmoji(wx.code, wx.isDay ?: d.isDay), rc.left + 2.2f * u, rc.top + .70f * h,
                .50f * h, WHITE, false, Paint.Align.LEFT)
            txt(c, fmtTemp(wx.temp), rc.left + 2.2f * u + .62f * h, rc.top + .68f * h, .40f * h, GOLD,
                true, Paint.Align.LEFT, maxW = cw * .30f)
        }
    }

    // ---------------------------------------------------------------- بیت روز
    private fun poem(c: Canvas, r: RectF) {
        card(c, r)
        val p = Config.POEMS[d.cal.get(Calendar.DAY_OF_YEAR) % Config.POEMS.size]
        val h = r.height()
        val cx = r.centerX()
        heart(c, cx, r.top + .15f * h, .075f * h, ROSE)
        txt(c, p.l1, cx, r.top + .43f * h, .19f * h, WHITE, true, maxW = r.width() * .9f)
        txt(c, p.l2, cx, r.top + .69f * h, .19f * h, WHITE, true, maxW = r.width() * .9f)
        txt(c, "— ${p.poet}", cx, r.top + .92f * h, .13f * h, GOLD)
    }
}
