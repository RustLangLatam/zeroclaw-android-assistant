package org.rustlanglatam.zeroclaw.android

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.BatteryManager
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import okhttp3.Request
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * Reads the data shown by the home-screen widgets ([Widget]) from the phone: next alarm, calendar, battery and weather.
 *
 * Weather is the only request that leaves the device: the location, rounded to about 10 km, is sent to Open-Meteo (no
 * account, no key) at most every 30 minutes.
 */
class PhoneWidgets(private val context: Context) {

    /**
     * Current weather in °C. [day] is whether it is daytime at the location; [rain] is the highest precipitation
     * probability over the next 12 hours, in percent.
     */
    data class Weather(val now: Int, val max: Int, val min: Int, val code: Int, val day: Boolean = true, val rain: Int = 0)

    private val hm = DateTimeFormatter.ofPattern("HH:mm")

    /** Returns the next alarm-clock alarm as its time and a localized day label, or `null` when there is none. */
    fun alarm(): Pair<String, String>? {
        val next = context.getSystemService(AlarmManager::class.java).nextAlarmClock ?: return null
        // Only clock-app alarms count: Xiaomi's calendar sets an alarm clock at midnight, and this app's HIGH reminder
        // calls are alarm clocks too.
        val from = next.showIntent?.creatorPackage.orEmpty()
        if ("calendar" in from || from == context.packageName) return null
        val at = Instant.ofEpochMilli(next.triggerTime).atZone(ZoneId.systemDefault()).toLocalDateTime()
        return at.format(hm) to day(at.toLocalDate())
    }

    /**
     * Returns up to [max] upcoming events from now to the end of tomorrow as (time, title, day label), or `null` without
     * calendar permission.
     */
    fun agenda(max: Int = 3): List<Triple<String, String, String>>? {
        val now = LocalDateTime.now()
        return PhoneMcp.events(context, now, LocalDate.now().plusDays(2).atStartOfDay())
            ?.take(max)
            ?.map { (title, begin, _) -> Triple(begin.format(hm), title, day(begin.toLocalDate())) }
    }

    /** Returns the battery percentage and whether a charger is plugged in. */
    fun battery(): Pair<Int, Boolean> {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = status?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = status?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val plugged = (status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        return (if (level < 0) 0 else level * 100 / scale) to plugged
    }

    fun locationPermitted() = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Fetches the weather in the background when location is permitted and the last fetch is older than 30 minutes. */
    fun refreshWeather() {
        if (!locationPermitted() || System.currentTimeMillis() - fetchedAt < 30 * 60_000L) return
        fetchedAt = System.currentTimeMillis()
        val manager = context.getSystemService(LocationManager::class.java)
        // With location services off there is no position; [noPlace] lets the widget say so instead of a placeholder.
        noPlace = !manager.isLocationEnabled
        if (noPlace) { fetchedAt = 0; return }
        val known = runCatching {
            manager.getProviders(true).mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.time }
        }.getOrNull()
        if (known != null) { fetch(known); return }
        // No last-known location: queries every enabled provider; the first fix wins, and if none answers the next
        // refresh retries.
        val providers = runCatching { manager.getProviders(true).filter { it != LocationManager.PASSIVE_PROVIDER } }.getOrDefault(emptyList())
        if (providers.isEmpty()) { fetchedAt = 0; return }
        val answered = java.util.concurrent.atomic.AtomicInteger()
        val fetching = java.util.concurrent.atomic.AtomicBoolean()
        providers.forEach { provider ->
            runCatching {
                manager.getCurrentLocation(provider, null, context.mainExecutor) { place ->
                    val n = answered.incrementAndGet()
                    // Only the first fix fetches; later fixes arriving before the response do not fetch again.
                    if (place != null && fetching.compareAndSet(false, true)) fetch(place) else if (n == providers.size && !fetching.get()) fetchedAt = 0
                }
            }
        }
    }

    private fun fetch(place: Location) { thread {
        // One decimal (about 10 km) is enough for a forecast without revealing the owner's address.
        val lat = "%.1f".format(Locale.US, place.latitude)
        val lon = "%.1f".format(Locale.US, place.longitude)
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code,is_day" +
            "&daily=temperature_2m_max,temperature_2m_min&hourly=precipitation_probability&forecast_hours=12&timezone=auto&forecast_days=1"
        runCatching {
            val json = http.newCall(Request.Builder().url(url).build()).execute().use { JSONObject(it.body!!.string()) }
            val current = json.getJSONObject("current")
            val daily = json.getJSONObject("daily")
            weather = Weather(
                current.getDouble("temperature_2m").roundToInt(),
                daily.getJSONArray("temperature_2m_max").getDouble(0).roundToInt(),
                daily.getJSONArray("temperature_2m_min").getDouble(0).roundToInt(),
                current.getInt("weather_code"),
                current.optInt("is_day", 1) == 1,
                json.optJSONObject("hourly")?.optJSONArray("precipitation_probability")?.let { a -> (0 until a.length()).maxOfOrNull { a.optInt(it, 0) } } ?: 0,
            )
            // Persisted with its timestamp so the last reading can still be reported offline.
            weather?.let { w -> context.getSharedPreferences("weather", Context.MODE_PRIVATE).edit()
                .putString("w", "${w.now},${w.max},${w.min},${w.code},${w.day},${w.rain}").putLong("at", System.currentTimeMillis()).apply() }
            Ui.refresh()
        }.onFailure {
            fetchedAt = 0
            Log.w(AssistantService.TAG, "weather: ${it.message}")
        }
    } }

    private fun day(date: LocalDate) = when (date) {
        LocalDate.now() -> today()
        LocalDate.now().plusDays(1) -> t(R.string.phonewidgets_tomorrow)
        else -> date.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Language.locale)
    }

    companion object {
        /** Returns the last persisted weather and its fetch time, or `null` before the first fetch. */
        fun last(context: Context): Pair<Weather, Long>? {
            val prefs = context.getSharedPreferences("weather", Context.MODE_PRIVATE)
            val p = prefs.getString("w", null)?.split(',') ?: return null
            return runCatching { Weather(p[0].toInt(), p[1].toInt(), p[2].toInt(), p[3].toInt(), p[4].toBoolean(), p[5].toInt()) to prefs.getLong("at", 0) }.getOrNull()
        }

        /** Compose state: true when location services are off, so no weather can be fetched. */
        var noPlace by androidx.compose.runtime.mutableStateOf(false)

        /** Returns the localized "today" label that [day] produces, for callers to compare against. */
        fun today() = t(R.string.phonewidgets_today)

        var weather by mutableStateOf<Weather?>(null)
        @Volatile private var fetchedAt = 0L

        private val http = AssistantService.http.newBuilder().callTimeout(10, TimeUnit.SECONDS).build()
    }
}
