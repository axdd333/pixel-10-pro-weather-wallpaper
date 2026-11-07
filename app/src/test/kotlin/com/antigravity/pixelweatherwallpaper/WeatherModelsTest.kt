package com.antigravity.pixelweatherwallpaper

import com.antigravity.pixelweatherwallpaper.model.TimeOfDayPhase
import com.antigravity.pixelweatherwallpaper.model.WeatherCondition
import com.antigravity.pixelweatherwallpaper.model.WeatherState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherModelsTest {

    @Test
    fun wmoCodeMapping_mapsClearAndStormAndSnowAccurately() {
        assertEquals(
            WeatherCondition.CLEAR_DAY,
            WeatherState.fromWmoCode(wmoCode = 0, isDay = true, solarElevation = 0.65f)
        )
        assertEquals(
            WeatherCondition.CLEAR_NIGHT,
            WeatherState.fromWmoCode(wmoCode = 0, isDay = false, solarElevation = -0.5f)
        )
        assertEquals(
            WeatherCondition.GOLDEN_HOUR,
            WeatherState.fromWmoCode(wmoCode = 1, isDay = true, solarElevation = 0.08f)
        )
        assertEquals(
            WeatherCondition.RAIN,
            WeatherState.fromWmoCode(wmoCode = 63, isDay = true, solarElevation = 0.4f)
        )
        assertEquals(
            WeatherCondition.SNOW,
            WeatherState.fromWmoCode(wmoCode = 75, isDay = true, solarElevation = 0.3f)
        )
        assertEquals(
            WeatherCondition.THUNDERSTORM,
            WeatherState.fromWmoCode(wmoCode = 95, isDay = false, solarElevation = -0.4f)
        )
    }

    @Test
    fun solarElevation_peaksNearNoonAndDropsAtMidnight() {
        val noonElev = WeatherState.computeSolarElevation(
            latitudeDeg = 37.3230,
            dayOfYear = 172,
            localHour = 12.0f
        )
        val midnightElev = WeatherState.computeSolarElevation(
            latitudeDeg = 37.3230,
            dayOfYear = 172,
            localHour = 0.0f
        )
        assertTrue("Noon solar elevation should be positive", noonElev > 0.5f)
        assertTrue("Midnight solar elevation should be negative", midnightElev < -0.2f)
    }

    @Test
    fun moonPhase_isNormalizedInZeroToOneRange() {
        val phase = WeatherState.computeMoonPhase(1762554000000L)
        assertTrue("Moon phase must be in [0, 1]", phase in 0.0f..1.0f)
    }

    @Test
    fun temperatureFormatting_supportsCelsiusAndFahrenheit() {
        val state = WeatherState(
            condition = WeatherCondition.CLEAR_DAY,
            temperatureCelsius = 20.0f,
            highCelsius = 25.0f,
            lowCelsius = 10.0f
        )
        assertEquals("20°", state.formatTemperature(useFahrenheit = false))
        assertEquals("68°", state.formatTemperature(useFahrenheit = true))
        assertEquals(TimeOfDayPhase.MIDDAY, state.timePhase)
    }
}
