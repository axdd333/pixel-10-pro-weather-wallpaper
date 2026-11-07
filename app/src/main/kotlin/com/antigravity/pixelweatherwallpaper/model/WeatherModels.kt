package com.antigravity.pixelweatherwallpaper.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * Primary weather conditions modeled after Apple iOS 17/18 Weather Live Wallpaper.
 */
enum class WeatherCondition(
    val label: String,
    val subtitle: String,
    val iconGlyph: String,
    val baseCloudCover: Float,
    val basePrecipitation: Float,
    val isSnow: Boolean = false,
    val isStorm: Boolean = false,
    val baseFog: Float = 0f
) {
    CLEAR_DAY(
        label = "Sunny",
        subtitle = "Radiant solar corona, warm lens flares & gentle cirrus drift",
        iconGlyph = "☀",
        baseCloudCover = 0.12f,
        basePrecipitation = 0.0f
    ),
    CLEAR_NIGHT(
        label = "Clear Night",
        subtitle = "Twinkling starfield, lunar glow, shooting stars & aurora wisps",
        iconGlyph = "☾",
        baseCloudCover = 0.10f,
        basePrecipitation = 0.0f
    ),
    PARTLY_CLOUDY(
        label = "Partly Cloudy",
        subtitle = "Volumetric multi-layered cumulus with crepuscular silver linings",
        iconGlyph = "⛅",
        baseCloudCover = 0.52f,
        basePrecipitation = 0.0f
    ),
    OVERCAST_FOG(
        label = "Overcast & Mist",
        subtitle = "Dense atmospheric stratus layers with rolling valley mist",
        iconGlyph = "🌫",
        baseCloudCover = 0.90f,
        basePrecipitation = 0.0f,
        baseFog = 0.78f
    ),
    RAIN(
        label = "Rain",
        subtitle = "Depth-layered raindrops, clock-shelf splashes & glass droplets",
        iconGlyph = "🌧",
        baseCloudCover = 0.85f,
        basePrecipitation = 0.58f
    ),
    THUNDERSTORM(
        label = "Thunderstorm",
        subtitle = " gale-force downpour, branching lightning & internal cloud illumination",
        iconGlyph = "⛈",
        baseCloudCover = 0.98f,
        basePrecipitation = 0.96f,
        isStorm = true
    ),
    SNOW(
        label = "Snowfall",
        subtitle = "Turbulent 3D parallax snowflakes, soft frost vignette & crystalline bokeh",
        iconGlyph = "❄",
        baseCloudCover = 0.82f,
        basePrecipitation = 0.65f,
        isSnow = true
    ),
    GOLDEN_HOUR(
        label = "Golden Hour",
        subtitle = "Horizon-low sun, warm amber-rose atmospheric scattering & long rays",
        iconGlyph = "🌅",
        baseCloudCover = 0.32f,
        basePrecipitation = 0.0f
    );
}

/**
 * Time-of-day phases for sky gradient & celestial positioning.
 */
enum class TimeOfDayPhase(val displayName: String) {
    NIGHT("Deep Night"),
    DAWN("Dawn"),
    GOLDEN_MORNING("Morning Golden Hour"),
    MIDDAY("High Noon"),
    GOLDEN_EVENING("Sunset Golden Hour"),
    DUSK("Twilight")
}

/**
 * Snapshot of either real-time Open-Meteo weather or interactive studio override.
 */
data class WeatherState(
    val condition: WeatherCondition = WeatherCondition.CLEAR_DAY,
    val temperatureCelsius: Float = 21.5f,
    val highCelsius: Float = 24.0f,
    val lowCelsius: Float = 14.0f,
    val feelsLikeCelsius: Float = 21.0f,
    val humidityPercent: Int = 54,
    val windSpeedKmh: Float = 14.2f,
    val windDirectionDegrees: Float = 235f,
    val uvIndex: Float = 6.2f,
    val visibilityKm: Float = 16.0f,
    val pressureHpa: Int = 1015,
    val cloudCoverFraction: Float = condition.baseCloudCover,
    val precipitationIntensity: Float = condition.basePrecipitation,
    val fogDensity: Float = condition.baseFog,
    val solarElevation: Float = 0.65f, // -1.0 (midnight nadir) to +1.0 (solar noon zenith)
    val solarAzimuthNormalized: Float = 0.5f, // 0.0 (east) to 1.0 (west)
    val moonPhase: Float = 0.72f, // 0 = new, 0.5 = full, 1 = new
    val locationName: String = "Cupertino",
    val latitude: Double = 37.3230,
    val longitude: Double = -122.0322,
    val lastUpdatedEpochMs: Long = System.currentTimeMillis(),
    val isLiveMode: Boolean = true,
    val hourlyForecast: List<HourlyForecastPoint> = defaultHourlyForecast()
) {
    val timePhase: TimeOfDayPhase
        get() = when {
            condition == WeatherCondition.GOLDEN_HOUR -> TimeOfDayPhase.GOLDEN_EVENING
            condition == WeatherCondition.CLEAR_NIGHT -> TimeOfDayPhase.NIGHT
            solarElevation < -0.18f -> TimeOfDayPhase.NIGHT
            solarElevation < -0.02f -> TimeOfDayPhase.DUSK
            solarElevation < 0.22f -> TimeOfDayPhase.GOLDEN_EVENING
            else -> TimeOfDayPhase.MIDDAY
        }

    fun formatTemperature(useFahrenheit: Boolean): String {
        val value = if (useFahrenheit) (temperatureCelsius * 9f / 5f + 32f) else temperatureCelsius
        return "${value.roundToInt()}°"
    }

    fun formatHighLow(useFahrenheit: Boolean): String {
        val h = if (useFahrenheit) (highCelsius * 9f / 5f + 32f) else highCelsius
        val l = if (useFahrenheit) (lowCelsius * 9f / 5f + 32f) else lowCelsius
        return "H:${h.roundToInt()}°  L:${l.roundToInt()}°"
    }

    companion object {
        fun defaultHourlyForecast(): List<HourlyForecastPoint> = listOf(
            HourlyForecastPoint("Now", 22, "☀", 0),
            HourlyForecastPoint("1PM", 23, "⛅", 10),
            HourlyForecastPoint("2PM", 24, "⛅", 15),
            HourlyForecastPoint("3PM", 23, "🌧", 60),
            HourlyForecastPoint("4PM", 20, "⛈", 85),
            HourlyForecastPoint("5PM", 19, "🌅", 20),
            HourlyForecastPoint("6PM", 17, "☾", 5)
        )

        /**
         * Maps WMO Weather interpretation codes (WW) from Open-Meteo to our rich WeatherCondition.
         */
        fun fromWmoCode(wmoCode: Int, isDay: Boolean, solarElevation: Float): WeatherCondition {
            if (!isDay && wmoCode in 0..2) {
                return WeatherCondition.CLEAR_NIGHT
            }
            if (isDay && solarElevation in -0.04f..0.22f && wmoCode in 0..3) {
                return WeatherCondition.GOLDEN_HOUR
            }
            return when (wmoCode) {
                0, 1 -> if (isDay) WeatherCondition.CLEAR_DAY else WeatherCondition.CLEAR_NIGHT
                2 -> WeatherCondition.PARTLY_CLOUDY
                3, 45, 48 -> WeatherCondition.OVERCAST_FOG
                51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> WeatherCondition.RAIN
                71, 73, 75, 77, 85, 86 -> WeatherCondition.SNOW
                95, 96, 99 -> WeatherCondition.THUNDERSTORM
                else -> if (isDay) WeatherCondition.PARTLY_CLOUDY else WeatherCondition.CLEAR_NIGHT
            }
        }

        /**
         * Approximates solar elevation (-1..+1) from latitude, day of year, and local fractional hour (0..24).
         */
        fun computeSolarElevation(latitudeDeg: Double, dayOfYear: Int, localHour: Float): Float {
            val latRad = latitudeDeg * PI / 180.0
            // Solar declination angle in radians
            val declination = 23.45 * (PI / 180.0) * sin(2.0 * PI * (284 + dayOfYear) / 365.0)
            // Hour angle: 0 at solar noon (12.0)
            val hourAngle = ((localHour - 12.0f) * 15.0) * (PI / 180.0)
            val sinElevation = sin(latRad) * sin(declination) +
                cos(latRad) * cos(declination) * cos(hourAngle)
            return sinElevation.toFloat().coerceIn(-1f, 1f)
        }

        /**
         * Computes lunar synodic phase (0.0 = New Moon, 0.5 = Full Moon, 1.0 = New Moon).
         */
        fun computeMoonPhase(epochMillis: Long): Float {
            val synodicMonthMs = 29.530588853 * 24.0 * 3600.0 * 1000.0
            // Known new moon reference: Jan 6, 2000 18:14 UTC (947182440000L)
            val diff = (epochMillis - 947182440000L).toDouble()
            val phase = ((diff % synodicMonthMs) + synodicMonthMs) % synodicMonthMs / synodicMonthMs
            return phase.toFloat()
        }

        /**
         * Computes the fractional lunar illumination [0.0..1.0] from synodic phase [0.0..1.0].
         */
        fun computeMoonIlluminationFraction(moonPhase: Float): Float {
            return (0.5f * (1f - cos(moonPhase * 2f * PI.toFloat()))).coerceIn(0f, 1f)
        }

        /**
         * Returns the human-readable 8-phase astronomical name for the given synodic phase [0..1].
         */
        fun moonPhaseName(moonPhase: Float): String {
            val p = ((moonPhase % 1f) + 1f) % 1f
            return when {
                p < 0.03f || p > 0.97f -> "New Moon"
                p < 0.22f -> "Waxing Crescent"
                p < 0.28f -> "First Quarter"
                p < 0.47f -> "Waxing Gibbous"
                p < 0.53f -> "Full Moon"
                p < 0.72f -> "Waning Gibbous"
                p < 0.78f -> "Third Quarter"
                else -> "Waning Crescent"
            }
        }
    }
}

data class HourlyForecastPoint(
    val label: String,
    val tempCelsius: Int,
    val glyph: String,
    val precipProbabilityPercent: Int
)

/**
 * Curated world cities for instant switching or fallback when GPS is disabled.
 */
data class PresetLocation(
    val name: String,
    val region: String,
    val latitude: Double,
    val longitude: Double,
    val defaultCondition: WeatherCondition,
    val defaultTempC: Float
) {
    companion object {
        val ALL = listOf(
            PresetLocation("Cupertino", "California", 37.3230, -122.0322, WeatherCondition.CLEAR_DAY, 22f),
            PresetLocation("New York", "United States", 40.7128, -74.0060, WeatherCondition.PARTLY_CLOUDY, 18f),
            PresetLocation("London", "United Kingdom", 51.5074, -0.1278, WeatherCondition.RAIN, 13f),
            PresetLocation("Tokyo", "Japan", 35.6762, 139.6503, WeatherCondition.CLEAR_NIGHT, 19f),
            PresetLocation("Reykjavik", "Iceland", 64.1466, -21.9426, WeatherCondition.SNOW, -2f),
            PresetLocation("Miami", "Florida", 25.7617, -80.1918, WeatherCondition.THUNDERSTORM, 27f),
            PresetLocation("San Francisco", "California", 37.7749, -122.4194, WeatherCondition.OVERCAST_FOG, 15f),
            PresetLocation("Santorini", "Greece", 36.3932, 25.4615, WeatherCondition.GOLDEN_HOUR, 24f)
        )
    }
}

/**
 * User-configurable live wallpaper settings persisted via SharedPreferences.
 */
data class WallpaperPreferences(
    val isLiveWeatherSync: Boolean = true,
    val selectedCondition: WeatherCondition = WeatherCondition.CLEAR_DAY,
    val selectedPresetCity: String = "Cupertino",
    val latitude: Double = 37.3230,
    val longitude: Double = -122.0322,
    val useFahrenheit: Boolean = true,
    val enableGyroParallax: Boolean = true,
    val parallaxIntensity: Float = 0.85f,
    val enableGlassDroplets: Boolean = true,
    val enableClockShelfSplashes: Boolean = true,
    val clockShelfYFraction: Float = 0.28f, // Pixel 10 Pro clock bottom shelf
    val enableTouchRipples: Boolean = true,
    val targetFrameRateMode: Int = 120, // 60 or 120 Hz for Pixel 10 Pro Smooth Display
    val customTimeSliderEnabled: Boolean = false,
    val customSolarElevation: Float = 0.65f,
    val particleDensityMultiplier: Float = 1.0f,
    val atmosphericBloomIntensity: Float = 0.90f,
    val showConstellationLines: Boolean = true,
    val showStarLabels: Boolean = true
)
