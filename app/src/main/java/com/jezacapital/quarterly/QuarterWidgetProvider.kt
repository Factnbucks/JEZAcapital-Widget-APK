package com.jezacapital.quarterly

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.widget.RemoteViews
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * JEZAcapital - 90-minute Quarterly Theory + Gold Bias Checklist widget (New York time).
 *
 * Trading day starts 18:00 NY. Four 6-hour sessions, each with four 90-minute quarters:
 *   ASIA 18:00-00:00 | LONDON 00:00-06:00 | NEW YORK AM 06:00-12:00 | NEW YORK PM 12:00-18:00
 */
class QuarterWidgetProvider : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AppWidgetManager.ACTION_APPWIDGET_UPDATE, ACTION_REFRESH, Intent.ACTION_TIME_CHANGED -> {
                renderAll(context)          // draw instantly from cached data
                scheduleNext(context)
                val pending = goAsync()
                Thread {
                    try {
                        BiasEngine.refresh(context)   // download candles + recalculate
                    } catch (e: Throwable) {
                        // keep showing the last good data
                    }
                    try {
                        renderAll(context)
                    } finally {
                        pending.finish()
                    }
                }.start()
            }
            else -> super.onReceive(context, intent)
        }
    }

    override fun onDisabled(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(refreshIntent(context))
    }

    // ---------------------------------------------------------------- state

    private data class State(
        val session: String,
        val sessionStartMin: Int,
        val quarter: Int,
        val remainingMs: Long
    )

    private fun currentState(): State {
        val now = ZonedDateTime.now(NY)
        val minuteOfDay = now.hour * 60 + now.minute
        val shifted = (minuteOfDay - DAY_START + 1440) % 1440
        val sessionIdx = shifted / 360
        val intoSession = shifted % 360
        val quarter = intoSession / 90
        val quarterStart = now.truncatedTo(ChronoUnit.MINUTES)
            .minusMinutes((intoSession % 90).toLong())
        val remaining = Duration.between(now, quarterStart.plusMinutes(90)).toMillis()
        return State(
            session = SESSIONS[sessionIdx],
            sessionStartMin = (DAY_START + sessionIdx * 360) % 1440,
            quarter = quarter,
            remainingMs = remaining
        )
    }

    // ---------------------------------------------------------------- views

    private fun renderAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, QuarterWidgetProvider::class.java))
        if (ids.isEmpty()) return
        manager.updateAppWidget(ids, buildViews(context, currentState(), BiasEngine.load(context)))
    }

    private fun buildViews(context: Context, s: State, bias: Bias?): RemoteViews {
        val v = RemoteViews(context.packageName, R.layout.widget_quarterly)

        v.setTextViewText(R.id.tv_session, s.session)
        v.setTextViewText(R.id.tv_quarter_label, "Q${s.quarter + 1} ends in")

        v.setChronometerCountDown(R.id.ch_remaining, true)
        v.setChronometer(R.id.ch_remaining, SystemClock.elapsedRealtime() + s.remainingMs, null, true)

        QUARTER_IDS.forEachIndexed { i, id ->
            val start = (s.sessionStartMin + i * 90) % 1440
            v.setTextViewText(id, String.format(Locale.US, "Q%d\n%02d:%02d", i + 1, start / 60, start % 60))
            val active = i == s.quarter
            v.setInt(id, "setBackgroundResource",
                if (active) R.drawable.bg_quarter_active else R.drawable.bg_quarter)
            v.setTextColor(id, if (active) COLOR_ACTIVE_TEXT else COLOR_IDLE_TEXT)
        }

        bindBias(v, bias, BiasEngine.lastError(context))

        v.setOnClickPendingIntent(R.id.root, refreshIntent(context))
        return v
    }

    private fun bindBias(v: RemoteViews, b: Bias?, err: String?) {
        setRow(v, R.id.b_h4, R.id.s_h4, b?.h4 ?: 0, "")
        setRow(v, R.id.b_h1, R.id.s_h1, b?.h1 ?: 0, "")
        setRow(v, R.id.b_now, R.id.s_now, b?.now ?: 0, "")
        setRow(v, R.id.b_adx, R.id.s_adx, b?.adxDir ?: 0, if (b != null && !b.adxStrong) " weak" else "")

        if (b == null) {
            v.setTextViewText(R.id.b_score, "BUY -")
            v.setTextViewText(R.id.s_score, "SELL -")
            v.setTextColor(R.id.b_score, COLOR_OFF)
            v.setTextColor(R.id.s_score, COLOR_OFF)
            v.setTextViewText(R.id.tv_adx_detail, "")
            v.setTextViewText(R.id.tv_status, "Fetching gold data..." + (if (err != null) "  (error: $err)" else ""))
            return
        }

        v.setTextViewText(R.id.b_score, "BUY ${b.buyCount}")
        v.setTextViewText(R.id.s_score, "SELL ${b.sellCount}")
        v.setTextColor(R.id.b_score, if (b.buyCount > b.sellCount) COLOR_BUY else COLOR_OFF)
        v.setTextColor(R.id.s_score, if (b.sellCount > b.buyCount) COLOR_SELL else COLOR_OFF)

        v.setTextViewText(
            R.id.tv_adx_detail,
            String.format(Locale.US, "+DI %.1f   -DI %.1f   ADX %.1f", b.plusDi, b.minusDi, b.adx)
        )

        val nowMs = System.currentTimeMillis()
        val barAgeMin = (nowMs - b.lastBarSec * 1000) / 60000
        val upd = TIME_FMT.format(Instant.ofEpochMilli(b.fetchedMs).atZone(NY))
        val tag = when {
            barAgeMin > 30 -> "  \u2022  market closed"
            err != null && nowMs - b.fetchedMs > 3 * 60_000 -> "  \u2022  error: $err"
            nowMs - b.fetchedMs > 5 * 60_000 -> "  \u2022  stale"
            else -> ""
        }
        v.setTextViewText(
            R.id.tv_status,
            String.format(Locale.US, "Gold %,.1f  \u2022  updated %s NY%s", b.price, upd, tag)
        )
    }

    private fun setRow(v: RemoteViews, buyId: Int, sellId: Int, dir: Int, suffix: String) {
        val buyOn = dir > 0
        val sellOn = dir < 0
        v.setTextViewText(buyId, (if (buyOn) "\u25CF BUY" else "\u25CB BUY") + (if (buyOn) suffix else ""))
        v.setTextColor(buyId, if (buyOn) COLOR_BUY else COLOR_OFF)
        v.setTextViewText(sellId, (if (sellOn) "\u25CF SELL" else "\u25CB SELL") + (if (sellOn) suffix else ""))
        v.setTextColor(sellId, if (sellOn) COLOR_SELL else COLOR_OFF)
    }

    // ---------------------------------------------------------------- alarm

    private fun refreshIntent(context: Context): PendingIntent {
        val intent = Intent(context, QuarterWidgetProvider::class.java).setAction(ACTION_REFRESH)
        return PendingIntent.getBroadcast(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Refresh every minute (new data) and exactly at the next quarter boundary. */
    private fun scheduleNext(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)
        val s = currentState()
        val delay = maxOf(1000L, minOf(REFRESH_MS, s.remainingMs + 500))
        val trigger = System.currentTimeMillis() + delay
        val pi = refreshIntent(context)
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (canExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC, trigger, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC, trigger, pi)
        }
    }

    companion object {
        private const val ACTION_REFRESH = "com.jezacapital.quarterly.REFRESH"
        private const val REFRESH_MS = 60_000L
        private val NY: ZoneId = ZoneId.of("America/New_York")
        private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        private const val DAY_START = 18 * 60
        private val SESSIONS = listOf("ASIA", "LONDON", "NEW YORK AM", "NEW YORK PM")
        private val QUARTER_IDS = listOf(R.id.q1, R.id.q2, R.id.q3, R.id.q4)
        private val COLOR_ACTIVE_TEXT = Color.parseColor("#0B1F14")
        private val COLOR_IDLE_TEXT = Color.parseColor("#A5D6A7")
        private val COLOR_BUY = Color.parseColor("#00E676")
        private val COLOR_SELL = Color.parseColor("#FF5252")
        private val COLOR_OFF = Color.parseColor("#4F7A60")
    }
}
