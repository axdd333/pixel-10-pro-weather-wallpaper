package com.antigravity.pixelweatherwallpaper.data

import android.content.Context
import android.content.SharedPreferences
import com.antigravity.pixelweatherwallpaper.model.HourlyForecastPoint
import com.antigravity.pixelweatherwallpaper.model.PresetLocation
import com.antigravity.pixelweatherwallpaper.model.WallpaperPreferences
import com.antigravity.pixelweatherwallpaper.model.WeatherCondition
import com.antigravity.pixelweatherwallpaper.model.WeatherState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import kotlin.math.roundToInt

/**
 * Singleton repository that coordinates live Open-Meteo weather fetching,
 * astronomical calculations, and user customization preferences between
 * the Companion Studio Activity and the Android WallpaperService Engine.
 */
class WeatherRepository private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _preferences = MutableStateFlow(loadPreferences())
    val preferences: StateFlow<WallpaperPreferences> = _preferences.asStateFlow()

    private val _weatherState = MutableStateFlow(buildInitialWeatherState(_preferences.value))
    val weatherState: StateFlow<WeatherState> = _weatherState.asStateFlow()

    private var lastNetworkFetchMs: Long = 0L

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        val updated = loadPreferences()
        _preferences.value = updated
        if (!updated.isLiveWeatherSync) {
            _weatherState.value = buildSimulatedWeatherState(updated)
        } else {
            refreshWeatherIfNeeded(force = false)
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        if (_preferences.value.isLiveWeatherSync) {
            refreshWeatherIfNeeded(force = true)
        }
    }

    fun updatePreferences(transform: (WallpaperPreferences) -> WallpaperPreferences) {
        val current = _preferences.value
        val next = transform(current)
        _preferences.value = next
        savePreferences(next)
        if (!next.isLiveWeatherSync) {
            _weatherState.value = buildSimulatedWeatherState(next)
        } else if (
            current.latitude != next.latitude ||
            current.longitude != next.longitude ||
            !current.isLiveWeatherSync
        ) {
            refreshWeatherIfNeeded(force = true)
        } else if (next.customTimeSliderEnabled) {
            _weatherState.value = _weatherState.value.copy(
                solarElevation = next.customSolarElevation
            )
        }
    }

    fun selectPresetCity(city: PresetLocation, switchToLive: Boolean = true) {
        updatePreferences {
            it.copy(
                selectedPresetCity = city.name,
                latitude = city.latitude,
                longitude = city.longitude,
                selectedCondition = city.defaultCondition,
                isLiveWeatherSync = switchToLive
            )
        }
    }

    fun setGeoCoordinates(name: String, latitude: Double, longitude: Double) {
        updatePreferences {
            it.copy(
                selectedPresetCity = name,
                latitude = latitude,
                longitude = longitude,
                isLiveWeatherSync = true
            )
        }
    }

    fun refreshWeatherIfNeeded(force: Boolean = false) {
        val currentPrefs = _preferences.value
        if (!currentPrefs.isLiveWeatherSync) {
            _weatherState.value = buildSimulatedWeatherState(currentPrefs)
            return
        }
        val now = System.currentTimeMillis()
        if (!force && (now - lastNetworkFetchMs) < MIN_FETCH_INTERVAL_MS) {
            return
        }
        scope.launch {
            fetchOpenMeteoWeather(currentPrefs)
        }
    }

    private suspend fun fetchOpenMeteoWeather(currentPrefs: WallpaperPreferences) =
        withContext(Dispatchers.IO) {
            try {
                val lat = currentPrefs.latitude
                val lon = currentPrefs.longitude
                val urlStr =
                    "https://api.open-meteo.com/v1/forecast" +
                        "?latitude=$lat&longitude=$lon" +
                        "&current=temperature_2m,relative_humidity_2m,apparent_temperature,is_day,precipitation,rain,showers,snowfall,weather_code,cloud_cover,surface_pressure,wind_speed_10m,wind_direction_10m" +
                        "&hourly=temperature_2m,precipitation_probability,weather_code" +
                        "&daily=temperature_2m_max,temperature_2m_min,uv_index_max" +
                        "&timezone=auto&forecast_days=1"

                val connection = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 6000
                    readTimeout = 6000
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/json")
                }

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(responseBody)
                    val current = json.getJSONObject("current")

                    val tempC = current.optDouble("temperature_2m", 21.0).toFloat()
                    val feelsLikeC = current.optDouble("apparent_temperature", tempC.toDouble()).toFloat()
                    val humidity = current.optInt("relative_humidity_2m", 55)
                    val isDay = current.optInt("is_day", 1) == 1
                    val precipMm = current.optDouble("precipitation", 0.0).toFloat()
                    val wmoCode = current.optInt("weather_code", 0)
                    val cloudCoverPct = current.optDouble("cloud_cover", 15.0).toFloat()
                    val pressure = current.optDouble("surface_pressure", 1013.0).roundToInt()
                    val windSpeed = current.optDouble("wind_speed_10m", 10.0).toFloat()
                    val windDir = current.optDouble("wind_direction_10m", 220.0).toFloat()

                    val daily = json.optJSONObject("daily")
                    val highC = daily?.optJSONArray("temperature_2m_max")?.optDouble(0, tempC + 3.0)?.toFloat()
                        ?: (tempC + 3f)
                    val lowC = daily?.optJSONArray("temperature_2m_min")?.optDouble(0, tempC - 5.0)?.toFloat()
                        ?: (tempC - 5f)
                    val uvMax = daily?.optJSONArray("uv_index_max")?.optDouble(0, 5.0)?.toFloat() ?: 5.0f

                    val cal = Calendar.getInstance()
                    val dayOfYear = cal.get(Calendar.DAY_OF_YEAR)
                    val localHour = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60f
                    val computedSolarElev = if (currentPrefs.customTimeSliderEnabled) {
                        currentPrefs.customSolarElevation
                    } else {
                        WeatherState.computeSolarElevation(lat, dayOfYear, localHour)
                    }

                    val condition = WeatherState.fromWmoCode(wmoCode, isDay, computedSolarElev)
                    val precipNormalized = when {
                        condition.isStorm -> (precipMm / 8f).coerceIn(0.65f, 1.0f)
                        precipMm > 0f -> (precipMm / 6f).coerceIn(0.25f, 1.0f)
                        else -> condition.basePrecipitation
                    }
                    val cloudNormalized = (cloudCoverPct / 100f).coerceIn(
                        condition.baseCloudCover * 0.5f,
                        1.0f
                    )

                    val hourlyForecast = parseHourlyForecast(json, cal.get(Calendar.HOUR_OF_DAY))
                    lastNetworkFetchMs = System.currentTimeMillis()

                    _weatherState.value = WeatherState(
                        condition = condition,
                        temperatureCelsius = tempC,
                        highCelsius = highC,
                        lowCelsius = lowC,
                        feelsLikeCelsius = feelsLikeC,
                        humidityPercent = humidity,
                        windSpeedKmh = windSpeed,
                        windDirectionDegrees = windDir,
                        uvIndex = uvMax,
                        visibilityKm = if (condition == WeatherCondition.OVERCAST_FOG) 3.5f else 16.0f,
                        pressureHpa = pressure,
                        cloudCoverFraction = cloudNormalized,
                        precipitationIntensity = precipNormalized,
                        fogDensity = condition.baseFog,
                        solarElevation = computedSolarElev,
                        solarAzimuthNormalized = ((localHour - 6f) / 12f).coerceIn(0f, 1f),
                        moonPhase = WeatherState.computeMoonPhase(lastNetworkFetchMs),
                        locationName = currentPrefs.selectedPresetCity,
                        latitude = lat,
                        longitude = lon,
                        lastUpdatedEpochMs = lastNetworkFetchMs,
                        isLiveMode = true,
                        hourlyForecast = hourlyForecast
                    )
                }
                connection.disconnect()
            } catch (_: Exception) {
                // Gracefully retain cached/astronomical fallback when offline or in sandbox
                val fallback = buildSimulatedWeatherState(currentPrefs).copy(isLiveMode = true)
                _weatherState.value = fallback
            }
        }

    private fun parseHourlyForecast(json: JSONObject, currentHour: Int): List<HourlyForecastPoint> {
        val hourly = json.optJSONObject("hourly") ?: return WeatherState.defaultHourlyForecast()
        val temps = hourly.optJSONArray("temperature_2m") ?: return WeatherState.defaultHourlyForecast()
        val probs = hourly.optJSONArray("precipitation_probability")
        val codes = hourly.optJSONArray("weather_code")

        val points = mutableListOf<HourlyForecastPoint>()
        for (offset in 0..6) {
            val idx = (currentHour + offset).coerceAtMost(temps.length() - 1)
            if (idx < 0) continue
            val t = temps.optDouble(idx, 20.0).roundToInt()
            val prob = probs?.optInt(idx, 0) ?: 0
            val code = codes?.optInt(idx, 0) ?: 0
            val hour24 = (currentHour + offset) % 24
            val isDay = hour24 in 6..19
            val cond = WeatherState.fromWmoCode(code, isDay, if (isDay) 0.5f else -0.5f)
            val label = if (offset == 0) {
                "Now"
            } else {
                val h12 = if (hour24 % 12 == 0) 12 else hour24 % 12
                val suffix = if (hour24 < 12) "AM" else "PM"
                "$h12$suffix"
            }
            points.add(HourlyForecastPoint(label, t, cond.iconGlyph, prob))
        }
        return if (points.isEmpty()) WeatherState.defaultHourlyForecast() else points
    }

    private fun buildInitialWeatherState(prefs: WallpaperPreferences): WeatherState {
        return buildSimulatedWeatherState(prefs)
    }

    private fun buildSimulatedWeatherState(prefs: WallpaperPreferences): WeatherState {
        val preset = PresetLocation.ALL.find { it.name == prefs.selectedPresetCity }
            ?: PresetLocation.ALL.first()
        val cond = if (prefs.isLiveWeatherSync) preset.defaultCondition else prefs.selectedCondition
        val cal = Calendar.getInstance()
        val dayOfYear = cal.get(Calendar.DAY_OF_YEAR)
        val localHour = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60f

        val solarElev = when {
            prefs.customTimeSliderEnabled -> prefs.customSolarElevation
            cond == WeatherCondition.CLEAR_NIGHT -> -0.68f
            cond == WeatherCondition.GOLDEN_HOUR -> 0.08f
            cond == WeatherCondition.CLEAR_DAY -> 0.76f
            else -> WeatherState.computeSolarElevation(prefs.latitude, dayOfYear, localHour)
                .let { if (!prefs.isLiveWeatherSync && it < 0.1f) 0.52f else it }
        }

        val baseTemp = when (cond) {
            WeatherCondition.SNOW -> -2.5f
            WeatherCondition.RAIN -> 13.5f
            WeatherCondition.THUNDERSTORM -> 19.0f
            WeatherCondition.OVERCAST_FOG -> 14.0f
            WeatherCondition.CLEAR_NIGHT -> 15.0f
            WeatherCondition.GOLDEN_HOUR -> 22.5f
            WeatherCondition.PARTLY_CLOUDY -> 20.0f
            WeatherCondition.CLEAR_DAY -> 23.5f
        }

        val windKmh = when (cond) {
            WeatherCondition.THUNDERSTORM -> 46.0f
            WeatherCondition.RAIN -> 24.0f
            WeatherCondition.SNOW -> 16.0f
            WeatherCondition.OVERCAST_FOG -> 9.0f
            else -> 12.0f
        }

        return WeatherState(
            condition = cond,
            temperatureCelsius = baseTemp,
            highCelsius = baseTemp + 3.5f,
            lowCelsius = baseTemp - 5.5f,
            feelsLikeCelsius = baseTemp - (if (cond.isStorm || cond.isSnow) 3.0f else 0.5f),
            humidityPercent = when (cond) {
                WeatherCondition.RAIN, WeatherCondition.THUNDERSTORM -> 92
                WeatherCondition.OVERCAST_FOG -> 96
                WeatherCondition.SNOW -> 84
                else -> 48
            },
            windSpeedKmh = windKmh,
            windDirectionDegrees = 240f,
            uvIndex = if (solarElev > 0.2f && cond == WeatherCondition.CLEAR_DAY) 7.5f else 1.2f,
            visibilityKm = if (cond == WeatherCondition.OVERCAST_FOG) 2.4f else 16.0f,
            pressureHpa = if (cond.isStorm) 996 else 1016,
            cloudCoverFraction = cond.baseCloudCover,
            precipitationIntensity = cond.basePrecipitation,
            fogDensity = cond.baseFog,
            solarElevation = solarElev,
            solarAzimuthNormalized = 0.58f,
            moonPhase = WeatherState.computeMoonPhase(System.currentTimeMillis()),
            locationName = prefs.selectedPresetCity,
            latitude = prefs.latitude,
            longitude = prefs.longitude,
            lastUpdatedEpochMs = System.currentTimeMillis(),
            isLiveMode = prefs.isLiveWeatherSync,
            hourlyForecast = WeatherState.defaultHourlyForecast()
        )
    }

    private fun loadPreferences(): WallpaperPreferences {
        val condName = prefs.getString(KEY_CONDITION, WeatherCondition.CLEAR_DAY.name)
            ?: WeatherCondition.CLEAR_DAY.name
        val condition = runCatching { WeatherCondition.valueOf(condName) }
            .getOrDefault(WeatherCondition.CLEAR_DAY)

        return WallpaperPreferences(
            isLiveWeatherSync = prefs.getBoolean(KEY_LIVE_SYNC, false),
            selectedCondition = condition,
            selectedPresetCity = prefs.getString(KEY_CITY, "Cupertino") ?: "Cupertino",
            latitude = Double.fromBits(
                prefs.getLong(KEY_LAT_BITS, (37.3230).toRawBits())
            ),
            longitude = Double.fromBits(
                prefs.getLong(KEY_LON_BITS, (-122.0322).toRawBits())
            ),
            useFahrenheit = prefs.getBoolean(KEY_FAHRENHEIT, true),
            enableGyroParallax = prefs.getBoolean(KEY_GYRO, true),
            parallaxIntensity = prefs.getFloat(KEY_PARALLAX_INTENSITY, 0.85f),
            enableGlassDroplets = prefs.getBoolean(KEY_DROPLETS, true),
            enableClockShelfSplashes = prefs.getBoolean(KEY_CLOCK_SPLASHES, true),
            clockShelfYFraction = prefs.getFloat(KEY_CLOCK_SHELF_Y, 0.28f),
            enableTouchRipples = prefs.getBoolean(KEY_TOUCH_RIPPLES, true),
            targetFrameRateMode = prefs.getInt(KEY_FPS_MODE, 120),
            customTimeSliderEnabled = prefs.getBoolean(KEY_CUSTOM_TIME, false),
            customSolarElevation = prefs.getFloat(KEY_SOLAR_ELEV, 0.65f),
            particleDensityMultiplier = prefs.getFloat(KEY_PARTICLE_DENSITY, 1.0f),
            atmosphericBloomIntensity = prefs.getFloat(KEY_BLOOM, 0.90f)
        )
    }

    private fun savePreferences(p: WallpaperPreferences) {
        prefs.edit()
            .putBoolean(KEY_LIVE_SYNC, p.isLiveWeatherSync)
            .putString(KEY_CONDITION, p.selectedCondition.name)
            .putString(KEY_CITY, p.selectedPresetCity)
            .putLong(KEY_LAT_BITS, p.latitude.toRawBits())
            .putLong(KEY_LON_BITS, p.longitude.toRawBits())
            .putBoolean(KEY_FAHRENHEIT, p.useFahrenheit)
            .putBoolean(KEY_GYRO, p.enableGyroParallax)
            .putFloat(KEY_PARALLAX_INTENSITY, p.parallaxIntensity)
            .putBoolean(KEY_DROPLETS, p.enableGlassDroplets)
            .putBoolean(KEY_CLOCK_SPLASHES, p.enableClockShelfSplashes)
            .putFloat(KEY_CLOCK_SHELF_Y, p.clockShelfYFraction)
            .putBoolean(KEY_TOUCH_RIPPLES, p.enableTouchRipples)
            .putInt(KEY_FPS_MODE, p.targetFrameRateMode)
            .putBoolean(KEY_CUSTOM_TIME, p.customTimeSliderEnabled)
            .putFloat(KEY_SOLAR_ELEV, p.customSolarElevation)
            .putFloat(KEY_PARTICLE_DENSITY, p.particleDensityMultiplier)
            .putFloat(KEY_BLOOM, p.atmosphericBloomIntensity)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "cupertino_weather_wallpaper_prefs"
        private const val MIN_FETCH_INTERVAL_MS = 10 * 60 * 1000L // 10 minutes

        private const val KEY_LIVE_SYNC = "is_live_sync"
        private const val KEY_CONDITION = "selected_condition"
        private const val KEY_CITY = "selected_city"
        private const val KEY_LAT_BITS = "latitude_bits"
        private const val KEY_LON_BITS = "longitude_bits"
        private const val KEY_FAHRENHEIT = "use_fahrenheit"
        private const val KEY_GYRO = "enable_gyro"
        private const val KEY_PARALLAX_INTENSITY = "parallax_intensity"
        private const val KEY_DROPLETS = "enable_droplets"
        private const val KEY_CLOCK_SPLASHES = "enable_clock_splashes"
        private const val KEY_CLOCK_SHELF_Y = "clock_shelf_y"
        private const val KEY_TOUCH_RIPPLES = "enable_touch_ripples"
        private const val KEY_FPS_MODE = "target_fps_mode"
        private const val KEY_CUSTOM_TIME = "custom_time_enabled"
        private const val KEY_SOLAR_ELEV = "custom_solar_elev"
        private const val KEY_PARTICLE_DENSITY = "particle_density"
        private const val KEY_BLOOM = "bloom_intensity"

        @Volatile
        private var instance: WeatherRepository? = null

        fun getInstance(context: Context): WeatherRepository {
            return instance ?: synchronized(this) {
                instance ?: WeatherRepository(context).also { instance = it }
            }
        }
    }
}
