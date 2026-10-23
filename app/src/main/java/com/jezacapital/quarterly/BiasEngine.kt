package com.jezacapital.quarterly

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** ---- Settings you may want to change ---- */
object BiasConfig {
    const val SYMBOL = "GC=F"        // COMEX gold futures (Yahoo Finance) - has real volume for VWAP
    const val ADX_LEN = 14           // same as ta.dmi(14, 14)
    const val ADX_STRONG = 20.0      // ADX below this is shown as "weak"
}

data class Bar(val t: Long, val o: Double, val h: Double, val l: Double, val c: Double, val v: Double)

/** dir values: +1 = BUY, -1 = SELL, 0 = neutral */
data class Bias(
    val h4: Int,
    val h1: Int,
    val now: Int,
    val adxDir: Int,
    val adxStrong: Boolean,
    val plusDi: Double,
    val minusDi: Double,
    val adx: Double,
    val price: Double,
    val lastBarSec: Long,
    val fetchedMs: Long
) {
    val buyCount: Int get() = listOf(h4, h1, now, adxDir).count { it > 0 }
    val sellCount: Int get() = listOf(h4, h1, now, adxDir).count { it < 0 }

    fun toJson(): String = JSONObject().apply {
        put("h4", h4); put("h1", h1); put("now", now)
        put("adxDir", adxDir); put("adxStrong", adxStrong)
        put("plusDi", plusDi); put("minusDi", minusDi); put("adx", adx)
        put("price", price); put("lastBarSec", lastBarSec); put("fetchedMs", fetchedMs)
    }.toString()

    companion object {
        fun fromJson(s: String): Bias? = try {
            val o = JSONObject(s)
            Bias(
                o.getInt("h4"), o.getInt("h1"), o.getInt("now"),
                o.getInt("adxDir"), o.getBoolean("adxStrong"),
                o.getDouble("plusDi"), o.getDouble("minusDi"), o.getDouble("adx"),
                o.getDouble("price"), o.getLong("lastBarSec"), o.getLong("fetchedMs")
            )
        } catch (e: Exception) {
            null
        }
    }
}

object BiasEngine {
    private val NY: ZoneId = ZoneId.of("America/New_York")
    private const val PREFS = "bias_cache"
    private const val KEY = "bias_json"
    private const val KEY_ERR = "last_error"

    fun load(context: Context): Bias? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)?.let { Bias.fromJson(it) }

    /** Last problem seen while downloading/calculating (null when everything worked). */
    fun lastError(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ERR, null)

    /** Downloads fresh candles, recalculates the checklist and stores it. Blocking - call off the main thread. */
    fun refresh(context: Context) {
        var r5: FetchResult? = null
        var r60: FetchResult? = null
        val t1 = Thread { r5 = fetch("5m", "5d") }
        val t2 = Thread { r60 = fetch("60m", "1mo") }
        t1.start()
        t2.start()
        val deadline = System.currentTimeMillis() + 8500
        t1.join(maxOf(1L, deadline - System.currentTimeMillis()))
        t2.join(maxOf(1L, deadline - System.currentTimeMillis()))

        val a = r5
        val b = r60
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val bars5 = a?.bars
        val bars60 = b?.bars
        if (bars5 == null || bars60 == null) {
            val msg = StringBuilder()
            if (bars5 == null) msg.append("5m: ").append(a?.error ?: "timeout").append("  ")
            if (bars60 == null) msg.append("1h: ").append(b?.error ?: "timeout")
            prefs.edit().putString(KEY_ERR, msg.toString().trim()).apply()
            return
        }
        val bias = compute(bars5, bars60)
        if (bias == null) {
            prefs.edit().putString(KEY_ERR, "not enough data to calculate").apply()
            return
        }
        prefs.edit().putString(KEY, bias.toJson()).remove(KEY_ERR).apply()
    }

    // ------------------------------------------------------------ network

    private class FetchResult(val bars: List<Bar>?, val error: String?)

    private fun fetch(interval: String, range: String): FetchResult {
        var error = "unknown"
        for (host in listOf("query1", "query2")) {
            try {
                val url = URL("https://$host.finance.yahoo.com/v8/finance/chart/${BiasConfig.SYMBOL}?interval=$interval&range=$range")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 4000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
                conn.setRequestProperty("Accept", "application/json")
                try {
                    val code = conn.responseCode
                    if (code != 200) {
                        error = "HTTP $code"
                        continue
                    }
                    val bars = parse(conn.inputStream.bufferedReader().use { it.readText() })
                    if (bars != null) return FetchResult(bars, null)
                    error = "bad data"
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                error = e.javaClass.simpleName
            }
        }
        return FetchResult(null, error)
    }

    private fun parse(body: String): List<Bar>? {
        val res = JSONObject(body).getJSONObject("chart").getJSONArray("result").getJSONObject(0)
        val ts = res.getJSONArray("timestamp")
        val q = res.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0)
        val o = q.getJSONArray("open")
        val h = q.getJSONArray("high")
        val l = q.getJSONArray("low")
        val c = q.getJSONArray("close")
        val v = q.getJSONArray("volume")
        val out = ArrayList<Bar>(ts.length())
        for (i in 0 until ts.length()) {
            val close = c.optDouble(i, Double.NaN)
            val high = h.optDouble(i, Double.NaN)
            val low = l.optDouble(i, Double.NaN)
            if (close.isNaN() || high.isNaN() || low.isNaN()) continue
            out.add(Bar(ts.getLong(i), o.optDouble(i, close), high, low, close, v.optDouble(i, 0.0)))
        }
        return if (out.size > 60) out else null
    }

    // ------------------------------------------------------------ anchors (New York trading day starts 18:00)

    private fun dayStart(sec: Long): Long {
        val z = Instant.ofEpochSecond(sec).atZone(NY)
        var start = z.withHour(18).withMinute(0).withSecond(0).withNano(0)
        if (start.isAfter(z)) start = start.minusDays(1)
        return start.toEpochSecond()
    }

    /** Trading week starts Sunday 18:00 New York. */
    private fun weekStart(sec: Long): Long {
        val d = Instant.ofEpochSecond(dayStart(sec)).atZone(NY)
        return d.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY)).toEpochSecond()
    }

    // ------------------------------------------------------------ maths

    private fun vwap(bars: List<Bar>, fromSec: Long): Double {
        var pv = 0.0
        var vol = 0.0
        var pSum = 0.0
        var n = 0
        for (b in bars) {
            if (b.t < fromSec) continue
            val tp = (b.h + b.l + b.c) / 3.0
            val w = if (b.v > 0) b.v else 0.0
            pv += tp * w
            vol += w
            pSum += tp
            n++
        }
        return if (vol > 0) pv / vol else if (n > 0) pSum / n else Double.NaN
    }

    /** 1-hour bars -> 4-hour bars (18:00, 22:00, 02:00, 06:00, 10:00, 14:00 New York). */
    private fun to4h(bars: List<Bar>): List<Bar> {
        val out = ArrayList<Bar>()
        var curKey = Long.MIN_VALUE
        var o = 0.0
        var h = 0.0
        var l = 0.0
        var c = 0.0
        var v = 0.0
        for (b in bars) {
            val ds = dayStart(b.t)
            val key = ds + ((b.t - ds) / 14400L) * 14400L
            if (key != curKey) {
                if (curKey != Long.MIN_VALUE) out.add(Bar(curKey, o, h, l, c, v))
                curKey = key
                o = b.o; h = b.h; l = b.l; c = b.c; v = b.v
            } else {
                h = max(h, b.h)
                l = min(l, b.l)
                c = b.c
                v += b.v
            }
        }
        if (curKey != Long.MIN_VALUE) out.add(Bar(curKey, o, h, l, c, v))
        return out
    }

    private class Dmi(val plus: Double, val minus: Double, val adx: Double)

    /** Same maths as TradingView ta.dmi(len, len): Wilder (RMA) smoothing. */
    private fun dmi(bars: List<Bar>, len: Int): Dmi? {
        val n = bars.size
        if (n < len * 3) return null
        val tr = DoubleArray(n)
        val pdm = DoubleArray(n)
        val mdm = DoubleArray(n)
        for (i in 1 until n) {
            val up = bars[i].h - bars[i - 1].h
            val dn = bars[i - 1].l - bars[i].l
            pdm[i] = if (up > dn && up > 0) up else 0.0
            mdm[i] = if (dn > up && dn > 0) dn else 0.0
            val pc = bars[i - 1].c
            tr[i] = max(bars[i].h - bars[i].l, max(abs(bars[i].h - pc), abs(bars[i].l - pc)))
        }

        fun rma(src: DoubleArray, from: Int): DoubleArray {
            val out = DoubleArray(n)
            var sum = 0.0
            for (i in from until from + len) sum += src[i]
            out[from + len - 1] = sum / len
            for (i in from + len until n) out[i] = (out[i - 1] * (len - 1) + src[i]) / len
            return out
        }

        val sTr = rma(tr, 1)
        val sP = rma(pdm, 1)
        val sM = rma(mdm, 1)
        val dx = DoubleArray(n)
        val first = len
        for (i in first until n) {
            val pdi = 100.0 * sP[i] / sTr[i]
            val mdi = 100.0 * sM[i] / sTr[i]
            val s = pdi + mdi
            dx[i] = if (s == 0.0) 0.0 else 100.0 * abs(pdi - mdi) / s
        }
        val adx = rma(dx, first)
        val i = n - 1
        return Dmi(100.0 * sP[i] / sTr[i], 100.0 * sM[i] / sTr[i], adx[i])
    }

    private fun compute(b5: List<Bar>, b60: List<Bar>): Bias? {
        val last = b5.last()
        val price = last.c

        val vH4 = vwap(to4h(b60), weekStart(last.t))      // H4 VWAP  (weekly anchor)
        val vH1 = vwap(b60, dayStart(last.t))              // H1 VWAP  (daily anchor)
        val vNow = vwap(b5, dayStart(last.t))              // 5m VWAP  (daily anchor)
        val d = dmi(b5, BiasConfig.ADX_LEN) ?: return null

        val all = listOf(vH4, vH1, vNow, d.plus, d.minus, d.adx, price)
        if (all.any { it.isNaN() || it.isInfinite() }) return null

        fun side(v: Double): Int = if (price > v) 1 else if (price < v) -1 else 0
        val adxDir = if (d.plus > d.minus) 1 else if (d.plus < d.minus) -1 else 0

        return Bias(
            h4 = side(vH4), h1 = side(vH1), now = side(vNow),
            adxDir = adxDir, adxStrong = d.adx >= BiasConfig.ADX_STRONG,
            plusDi = d.plus, minusDi = d.minus, adx = d.adx,
            price = price, lastBarSec = last.t, fetchedMs = System.currentTimeMillis()
        )
    }
}
