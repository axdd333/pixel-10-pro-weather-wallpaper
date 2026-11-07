package com.antigravity.pixelweatherwallpaper.ui

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.pixelweatherwallpaper.data.WeatherRepository
import com.antigravity.pixelweatherwallpaper.engine.CupertinoWeatherSceneRenderer
import com.antigravity.pixelweatherwallpaper.model.AstronomicalStarCatalog
import com.antigravity.pixelweatherwallpaper.model.PresetLocation
import com.antigravity.pixelweatherwallpaper.model.WallpaperPreferences
import com.antigravity.pixelweatherwallpaper.model.WeatherCondition
import com.antigravity.pixelweatherwallpaper.model.WeatherState
import com.antigravity.pixelweatherwallpaper.service.CupertinoWeatherWallpaperService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Full-screen Cupertino Weather Studio & Live Preview for Google Pixel 10 Pro.
 * Renders the live 120Hz AGSL + Particle engine in the background while providing
 * frosted-glass Apple Weather cards, a Pixel 10 Pro Lock-Screen Clock Shelf overlay mode,
 * and one-tap Live Wallpaper activation.
 */
@Composable
fun WeatherStudioScreen(
    repository: WeatherRepository,
    onRequestLocationPermission: () -> Unit
) {
    val context = LocalContext.current
    val weather by repository.weatherState.collectAsState()
    val prefs by repository.preferences.collectAsState()

    val renderer = remember { CupertinoWeatherSceneRenderer() }
    var frameTick by remember { mutableLongStateOf(0L) }
    var dtSeconds by remember { mutableFloatStateOf(1f / 120f) }
    var lockScreenPreviewMode by remember { mutableStateOf(false) }
    var activeTab by remember { mutableIntStateOf(0) } // 0 = Scenes, 1 = Physics & Optics, 2 = Cities

    // Continuous 120Hz Choreographer-synced frame loop for live preview
    LaunchedEffect(Unit) {
        var lastNanos = 0L
        while (true) {
            withFrameNanos { nanos ->
                if (lastNanos != 0L) {
                    dtSeconds = ((nanos - lastNanos) / 1_000_000_000.0)
                        .toFloat()
                        .coerceIn(0.002f, 0.05f)
                }
                lastNanos = nanos
                frameTick = nanos
            }
        }
    }

    // Bind device tilt sensor for live 3D parallax inside the Studio app
    DisposableEffect(prefs.enableGyroParallax) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event == null) return
                val gx = (-event.values[0] / 6.5f).coerceIn(-1f, 1f)
                val gy = ((event.values[1] - 6.8f) / 6.5f).coerceIn(-1f, 1f)
                renderer.setParallaxOffset(gx, gy)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (prefs.enableGyroParallax && sensor != null) {
            sensorManager?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        }
        onDispose {
            sensorManager?.unregisterListener(listener)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(prefs.enableTouchRipples) {
                detectTapGestures(
                    onDoubleTap = {
                        if (lockScreenPreviewMode) {
                            lockScreenPreviewMode = false
                        } else {
                            renderer.triggerManualLightning()
                        }
                    },
                    onTap = { offset ->
                        renderer.onTouchInteraction(offset.x, offset.y, prefs)
                    }
                )
            }
    ) {
        // 1. Live 120Hz AGSL + Particle Canvas
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Read frameTick so Compose invalidates this draw scope every vsync
            val unusedTick = frameTick
            val w = size.width.toInt()
            val h = size.height.toInt()
            renderer.setViewportSize(w, h)
            renderer.renderFrame(
                canvas = drawContext.canvas.nativeCanvas,
                dtSeconds = dtSeconds,
                weather = weather,
                preferences = prefs
            )
        }

        // 2. Lock Screen Clock-Shelf Inspection Mode vs Studio Controls
        AnimatedVisibility(
            visible = lockScreenPreviewMode,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Pixel10ProLockScreenOverlay(
                weather = weather,
                prefs = prefs,
                onExitPreview = { lockScreenPreviewMode = false },
                onTriggerLightning = { renderer.triggerManualLightning() }
            )
        }

        AnimatedVisibility(
            visible = !lockScreenPreviewMode,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp,
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp
                    )
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top Bar: Pixel 10 Pro Badge + Lock Screen Preview Toggle
                StudioTopBar(
                    isLiveSync = prefs.isLiveWeatherSync,
                    onToggleLockPreview = { lockScreenPreviewMode = true },
                    onStrikeLightning = { renderer.triggerManualLightning() }
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Hero Apple Weather Header (Location, Giant Temp, Condition, H/L)
                CupertinoWeatherHeroHeader(
                    weather = weather,
                    useFahrenheit = prefs.useFahrenheit,
                    onToggleUnit = {
                        repository.updatePreferences { it.copy(useFahrenheit = !it.useFahrenheit) }
                    }
                )

                Spacer(modifier = Modifier.height(18.dp))

                // Primary CTA: Apply Live Wallpaper to Pixel 10 Pro Home & Lock Screen
                ApplyLiveWallpaperCard(
                    context = context,
                    onEnterLockPreview = { lockScreenPreviewMode = true }
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Hourly Forecast Frosted Glass Card
                HourlyForecastGlassCard(
                    weather = weather,
                    useFahrenheit = prefs.useFahrenheit
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Studio Mode Switcher Tabs
                StudioCategoryTabs(
                    activeTab = activeTab,
                    onSelectTab = { activeTab = it }
                )

                Spacer(modifier = Modifier.height(12.dp))

                when (activeTab) {
                    0 -> WeatherAtmosphereDeckCard(
                        weather = weather,
                        prefs = prefs,
                        onSelectCondition = { condition ->
                            repository.updatePreferences {
                                it.copy(
                                    isLiveWeatherSync = false,
                                    selectedCondition = condition
                                )
                            }
                        },
                        onToggleLiveSync = { enabled ->
                            if (enabled) {
                                onRequestLocationPermission()
                            }
                            repository.updatePreferences { it.copy(isLiveWeatherSync = enabled) }
                        }
                    )

                    1 -> PhysicsAndOpticsTunerCard(
                        prefs = prefs,
                        onUpdatePrefs = { transform -> repository.updatePreferences(transform) },
                        onTriggerLightning = { renderer.triggerManualLightning() }
                    )

                    2 -> WorldCitiesPickerCard(
                        prefs = prefs,
                        onSelectCity = { city ->
                            repository.selectPresetCity(city, switchToLive = prefs.isLiveWeatherSync)
                        },
                        onUseDeviceGps = onRequestLocationPermission
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 2x2 Bento Telemetry Grid (Wind, UV, Humidity, Pressure/Visibility)
                TelemetryBentoGrid(weather = weather)

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun StudioTopBar(
    isLiveSync: Boolean,
    onToggleLockPreview: () -> Unit,
    onStrikeLightning: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.16f))
                .border(0.8.dp, Color.White.copy(alpha = 0.28f), CircleShape)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (isLiveSync) Color(0xFF4ADE80) else Color(0xFF60A5FA))
            )
            Spacer(modifier = Modifier.width(7.dp))
            Text(
                text = if (isLiveSync) "PIXEL 10 PRO • LIVE METEO" else "PIXEL 10 PRO • 120HZ STUDIO",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.7.sp
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.16f))
                    .border(0.8.dp, Color.White.copy(alpha = 0.28f), CircleShape)
                    .clickable { onStrikeLightning() }
                    .padding(horizontal = 11.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "⚡ Flash",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.18f))
                    .border(0.8.dp, Color.White.copy(alpha = 0.32f), CircleShape)
                    .clickable { onToggleLockPreview() }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "Lock Screen View",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun CupertinoWeatherHeroHeader(
    weather: WeatherState,
    useFahrenheit: Boolean,
    onToggleUnit: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(top = 6.dp)
    ) {
        Text(
            text = weather.locationName,
            color = Color.White,
            fontSize = 32.sp,
            fontWeight = FontWeight.Normal,
            letterSpacing = 0.3.sp
        )
        Text(
            text = weather.formatTemperature(useFahrenheit),
            color = Color.White,
            fontSize = 88.sp,
            fontWeight = FontWeight.Thin,
            letterSpacing = (-3).sp,
            modifier = Modifier.clickable { onToggleUnit() }
        )
        Text(
            text = "${weather.condition.iconGlyph}  ${weather.condition.label}",
            color = Color.White.copy(alpha = 0.92f),
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "${weather.formatHighLow(useFahrenheit)}  •  Tap temp for °F/°C",
            color = Color.White.copy(alpha = 0.80f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun ApplyLiveWallpaperCard(
    context: Context,
    onEnterLockPreview: () -> Unit
) {
    GlassCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Cupertino Dynamic Wallpaper",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "AGSL volumetric clouds, 120Hz rain physics & clock-shelf splashes",
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Button(
                onClick = { launchSystemLiveWallpaperPicker(context, onFallback = onEnterLockPreview) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color(0xFF0F172A)
                ),
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text(
                    text = "Set Live",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun HourlyForecastGlassCard(
    weather: WeatherState,
    useFahrenheit: Boolean
) {
    GlassCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = weather.condition.subtitle.uppercase(Locale.US),
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.6.sp
            )
            Spacer(modifier = Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.6.dp)
                    .background(Color.White.copy(alpha = 0.22f))
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                weather.hourlyForecast.forEach { point ->
                    val displayTemp = if (useFahrenheit) {
                        (point.tempCelsius * 9f / 5f + 32f).roundToInt()
                    } else {
                        point.tempCelsius
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 10.dp)
                    ) {
                        Text(
                            text = point.label,
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = point.glyph,
                            fontSize = 20.sp
                        )
                        if (point.precipProbabilityPercent >= 15) {
                            Text(
                                text = "${point.precipProbabilityPercent}%",
                                color = Color(0xFF7DD3FC),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        } else {
                            Spacer(modifier = Modifier.height(14.dp))
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "$displayTemp°",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StudioCategoryTabs(
    activeTab: Int,
    onSelectTab: (Int) -> Unit
) {
    val tabs = listOf("Atmospheres", "Optics & Physics", "World Cities")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Black.copy(alpha = 0.24f))
            .border(0.8.dp, Color.White.copy(alpha = 0.20f), RoundedCornerShape(18.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        tabs.forEachIndexed { index, title ->
            val selected = index == activeTab
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (selected) Color.White.copy(alpha = 0.26f) else Color.Transparent)
                    .clickable { onSelectTab(index) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = title,
                    color = if (selected) Color.White else Color.White.copy(alpha = 0.72f),
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun WeatherAtmosphereDeckCard(
    weather: WeatherState,
    prefs: WallpaperPreferences,
    onSelectCondition: (WeatherCondition) -> Unit,
    onToggleLiveSync: (Boolean) -> Unit
) {
    GlassCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Real-Time Open-Meteo Sync",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Automatically matches local sky, solar elevation & storms",
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 12.sp
                    )
                }
                Switch(
                    checked = prefs.isLiveWeatherSync,
                    onCheckedChange = onToggleLiveSync,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF38BDF8)
                    )
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "APPLE WEATHER ATMOSPHERE PRESETS",
                color = Color.White.copy(alpha = 0.70f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            )
            Spacer(modifier = Modifier.height(10.dp))

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(WeatherCondition.entries) { cond ->
                    val isSelected = !prefs.isLiveWeatherSync && prefs.selectedCondition == cond
                    Column(
                        modifier = Modifier
                            .width(128.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(
                                if (isSelected) {
                                    Color.White.copy(alpha = 0.30f)
                                } else {
                                    Color.White.copy(alpha = 0.12f)
                                }
                            )
                            .border(
                                width = if (isSelected) 1.5.dp else 0.8.dp,
                                color = if (isSelected) Color.White else Color.White.copy(alpha = 0.22f),
                                shape = RoundedCornerShape(18.dp)
                            )
                            .clickable { onSelectCondition(cond) }
                            .padding(12.dp)
                    ) {
                        Text(text = cond.iconGlyph, fontSize = 26.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = cond.label,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = cond.subtitle,
                            color = Color.White.copy(alpha = 0.72f),
                            fontSize = 10.sp,
                            lineHeight = 13.sp,
                            maxLines = 2
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PhysicsAndOpticsTunerCard(
    prefs: WallpaperPreferences,
    onUpdatePrefs: ((WallpaperPreferences) -> WallpaperPreferences) -> Unit,
    onTriggerLightning: () -> Unit
) {
    GlassCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "OPTICS, PARALLAX & PIXEL 10 PRO CLOCK SHELF",
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            )
            Spacer(modifier = Modifier.height(12.dp))

            ToggleSettingRow(
                title = "Refractive Glass Lens Droplets",
                subtitle = "Beads of rain coalesce and trickle down the screen glass",
                checked = prefs.enableGlassDroplets,
                onCheckedChange = { enabled ->
                    onUpdatePrefs { it.copy(enableGlassDroplets = enabled) }
                }
            )

            ToggleSettingRow(
                title = "Lock-Screen Clock Shelf Splashes",
                subtitle = "Raindrops bounce off the top of the Pixel lock-screen clock",
                checked = prefs.enableClockShelfSplashes,
                onCheckedChange = { enabled ->
                    onUpdatePrefs { it.copy(enableClockShelfSplashes = enabled) }
                }
            )

            ToggleSettingRow(
                title = "3D Gyroscope Tilt Parallax",
                subtitle = "Shifts volumetric clouds, stars & sun corona as you tilt",
                checked = prefs.enableGyroParallax,
                onCheckedChange = { enabled ->
                    onUpdatePrefs { it.copy(enableGyroParallax = enabled) }
                }
            )

            ToggleSettingRow(
                title = "Live J2000 Constellation Wireframes",
                subtitle = "Connects Orion, Ursa Major, Cassiopeia & Cygnus for your LST/Lat/Lon",
                checked = prefs.showConstellationLines,
                onCheckedChange = { enabled ->
                    onUpdatePrefs { it.copy(showConstellationLines = enabled) }
                }
            )

            ToggleSettingRow(
                title = "1st-Magnitude Navigation Star Labels",
                subtitle = "Displays Sirius, Vega, Betelgeuse, Rigel, Capella & Polaris at night",
                checked = prefs.showStarLabels,
                onCheckedChange = { enabled ->
                    onUpdatePrefs { it.copy(showStarLabels = enabled) }
                }
            )

            ToggleSettingRow(
                title = "Manual Solar Elevation Scrubber",
                subtitle = "Override astronomical sun angle (-1.0 Midnight to +1.0 Zenith)",
                checked = prefs.customTimeSliderEnabled,
                onCheckedChange = { enabled ->
                    onUpdatePrefs { it.copy(customTimeSliderEnabled = enabled) }
                }
            )

            if (prefs.customTimeSliderEnabled) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Solar Elevation: ${String.format(Locale.US, "%+.2f", prefs.customSolarElevation)}",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Slider(
                    value = prefs.customSolarElevation,
                    onValueChange = { elev ->
                        onUpdatePrefs { it.copy(customSolarElevation = elev) }
                    },
                    valueRange = -0.9f..0.95f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color(0xFFFBBF24)
                    )
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Particle Density: ${(prefs.particleDensityMultiplier * 100).roundToInt()}%",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Slider(
                value = prefs.particleDensityMultiplier,
                onValueChange = { mult ->
                    onUpdatePrefs { it.copy(particleDensityMultiplier = mult) }
                },
                valueRange = 0.5f..1.6f,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color(0xFF38BDF8)
                )
            )

            Text(
                text = "Solar Corona & Aurora Bloom: ${(prefs.atmosphericBloomIntensity * 100).roundToInt()}%",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Slider(
                value = prefs.atmosphericBloomIntensity,
                onValueChange = { bloom ->
                    onUpdatePrefs { it.copy(atmosphericBloomIntensity = bloom) }
                },
                valueRange = 0.3f..1.4f,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color(0xFFF472B6)
                )
            )
        }
    }
}

@Composable
private fun WorldCitiesPickerCard(
    prefs: WallpaperPreferences,
    onSelectCity: (PresetLocation) -> Unit,
    onUseDeviceGps: () -> Unit
) {
    GlassCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "CURATED WORLD WEATHER LOCATIONS",
                    color = Color.White.copy(alpha = 0.72f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )
                Text(
                    text = "📍 Use Device GPS",
                    color = Color(0xFF7DD3FC),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { onUseDeviceGps() }
                )
            }
            Spacer(modifier = Modifier.height(10.dp))

            PresetLocation.ALL.forEach { city ->
                val isSelected = prefs.selectedPresetCity == city.name
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (isSelected) Color.White.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.08f)
                        )
                        .clickable { onSelectCity(city) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = city.name,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${city.region} • ${city.defaultCondition.label}",
                            color = Color.White.copy(alpha = 0.74f),
                            fontSize = 12.sp
                        )
                    }
                    Text(
                        text = city.defaultCondition.iconGlyph,
                        fontSize = 22.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun TelemetryBentoGrid(weather: WeatherState) {
    val lstDeg = remember(weather.longitude, weather.lastUpdatedEpochMs) {
        AstronomicalStarCatalog.computeLocalSiderealTimeDegrees(
            System.currentTimeMillis(),
            weather.longitude
        )
    }
    val lstHours = (lstDeg / 15.0).toInt()
    val lstMinutes = (((lstDeg / 15.0) - lstHours) * 60.0).toInt()
    val moonIllumPct = (WeatherState.computeMoonIlluminationFraction(weather.moonPhase) * 100f).roundToInt()
    val moonName = WeatherState.moonPhaseName(weather.moonPhase)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TelemetryMiniCard(
                modifier = Modifier.weight(1f),
                label = "WIND & GUSTS",
                value = "${weather.windSpeedKmh.roundToInt()} km/h",
                detail = "Direction ${weather.windDirectionDegrees.roundToInt()}° WSW"
            )
            TelemetryMiniCard(
                modifier = Modifier.weight(1f),
                label = "HUMIDITY & DEW",
                value = "${weather.humidityPercent}%",
                detail = "Cloud Deck ${(weather.cloudCoverFraction * 100).roundToInt()}%"
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TelemetryMiniCard(
                modifier = Modifier.weight(1f),
                label = "LUNAR PHASE & EPHEMERIS",
                value = moonName,
                detail = "$moonIllumPct% Illuminated • Synodic ${(weather.moonPhase * 29.53f).roundToInt()}d"
            )
            TelemetryMiniCard(
                modifier = Modifier.weight(1f),
                label = "SIDEREAL SKY DOME (J2000)",
                value = String.format(Locale.US, "%02dh %02dm LST", lstHours, lstMinutes),
                detail = String.format(Locale.US, "Lat %.2f° • Lon %.2f°", weather.latitude, weather.longitude)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TelemetryMiniCard(
                modifier = Modifier.weight(1f),
                label = "UV & SOLAR ELEV",
                value = String.format(Locale.US, "%.1f UV", weather.uvIndex),
                detail = "Solar Elev ${String.format(Locale.US, "%+.2f", weather.solarElevation)}"
            )
            TelemetryMiniCard(
                modifier = Modifier.weight(1f),
                label = "PRESSURE & VISIBILITY",
                value = "${weather.pressureHpa} hPa",
                detail = "Visibility ${weather.visibilityKm.roundToInt()} km"
            )
        }
    }
}

@Composable
private fun TelemetryMiniCard(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    detail: String
) {
    GlassCard(modifier = modifier) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = label,
                color = Color.White.copy(alpha = 0.68f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.7.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = value,
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = detail,
                color = Color.White.copy(alpha = 0.76f),
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun ToggleSettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 11.sp
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF38BDF8)
            )
        )
    }
}

/**
 * Simulates the Google Pixel 10 Pro / Apple iOS Lock Screen Clock Shelf so the user can
 * watch raindrops physically collide and splash off the clock typography in real time.
 */
@Composable
private fun Pixel10ProLockScreenOverlay(
    weather: WeatherState,
    prefs: WallpaperPreferences,
    onExitPreview: () -> Unit,
    onTriggerLightning: () -> Unit
) {
    val timeText = remember {
        SimpleDateFormat("h:mm", Locale.getDefault()).format(Date())
    }
    val dateText = remember {
        SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date())
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 84.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "$dateText  •  ${weather.condition.iconGlyph} ${weather.formatTemperature(prefs.useFahrenheit)}",
                color = Color.White.copy(alpha = 0.90f),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = timeText,
                color = Color.White.copy(alpha = 0.94f),
                fontSize = 96.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-2).sp
            )
            Text(
                text = "${weather.locationName} • ${weather.condition.label}",
                color = Color.White.copy(alpha = 0.80f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }

        // Bottom pill controls
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 42.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
                    .clickable { onTriggerLightning() }
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            ) {
                Text(
                    text = "⚡ Strike Lightning",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.24f))
                    .border(1.dp, Color.White.copy(alpha = 0.45f), CircleShape)
                    .clickable { onExitPreview() }
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            ) {
                Text(
                    text = "Exit Lock Screen View",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.20f),
                        Color.White.copy(alpha = 0.11f)
                    )
                )
            )
            .border(
                width = 0.9.dp,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.40f),
                        Color.White.copy(alpha = 0.12f)
                    )
                ),
                shape = RoundedCornerShape(24.dp)
            ),
        color = Color.Transparent,
        content = content
    )
}

private fun launchSystemLiveWallpaperPicker(context: Context, onFallback: () -> Unit) {
    try {
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(context, CupertinoWeatherWallpaperService::class.java)
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        try {
            val genericIntent = Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(genericIntent)
        } catch (_: Exception) {
            onFallback()
        }
    }
}
