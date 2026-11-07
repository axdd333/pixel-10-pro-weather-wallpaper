package com.antigravity.pixelweatherwallpaper.engine

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import com.antigravity.pixelweatherwallpaper.model.AstronomicalStarCatalog
import com.antigravity.pixelweatherwallpaper.model.WallpaperPreferences
import com.antigravity.pixelweatherwallpaper.model.WeatherCondition
import com.antigravity.pixelweatherwallpaper.model.WeatherState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * High-precision Cupertino Weather scene renderer sculpted for 120fps on both
 * Android's hardware-accelerated WallpaperService SurfaceHolder Canvas and Jetpack Compose Canvas.
 *
 * Refinements following Apple's Weather design language:
 * - Quiet, observationally accurate J2000 starfield (nearly 300 naked-eye stars projected by
 *   Local Sidereal Time & Latitude/Longitude, with sub-pixel radii and B-V color temperatures,
 *   zero text labels or wireframe clutter by default).
 * - In-shader 3D spherical lunar rendering with mare albedo, soft terminator & earthshine.
 * - Delicate, silky-thin motion-blurred rain streaks with natural Lock-Screen Clock Shelf splashes.
 * - Teardrop surface-tension glass condensation droplets with subtle meniscus refraction.
 * - Organic recursive lightning with realistic double-pulse re-strike flicker.
 */
class CupertinoWeatherSceneRenderer {

    private val random = Random(System.nanoTime())
    private val runtimeShader: RuntimeShader? = AtmosphericAgslShader.createOrNull()

    // Pre-allocated Paints to guarantee zero GC allocations per 120Hz frame
    private val shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fallbackSkyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val celestialPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val flarePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val starLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = 19f
        letterSpacing = 0.24f
    }
    private val rainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val splashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val dropletBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val dropletRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val snowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val lightningCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val lightningGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        maskFilter = BlurMaskFilter(14f, BlurMaskFilter.Blur.NORMAL)
    }
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val cloudPuffPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val lightningPath = Path()
    private val dropletPath = Path()

    // Viewport dimensions
    private var viewportWidth = 1080f
    private var viewportHeight = 2400f
    private var elapsedTimeSec = 0f

    // Smoothly interpolated atmospheric state
    private var smoothSolarElevation = 0.65f
    private var smoothCloudCover = 0.15f
    private var smoothPrecip = 0.0f
    private var smoothFog = 0.0f
    private var smoothSnowFactor = 0.0f

    // Critically damped parallax [-1..1]
    private var currentParallaxX = 0f
    private var currentParallaxY = 0f
    private var targetParallaxX = 0f
    private var targetParallaxY = 0f

    //Cached J2000 astronomical sky projection
    private var cachedStarCatalogLat = Double.NaN
    private var cachedStarCatalogLon = Double.NaN
    private var cachedStarCatalogBucket = -1L
    private var cachedProjectedStars: List<AstronomicalStarCatalog.ProjectedStar> = emptyList()

    // Pre-allocated particle pools
    private val rainDrops = Array(MAX_RAINDROPS) { RainParticle() }
    private val splashes = Array(MAX_SPLASHES) { SplashParticle() }
    private val glassDroplets = Array(MAX_GLASS_DROPLETS) { GlassDroplet() }
    private val snowflakes = Array(MAX_SNOWFLAKES) { SnowParticle() }
    private val touchRipples = Array(MAX_TOUCH_RIPPLES) { TouchRipple() }
    private val volumetricPuffs = Array(MAX_CLOUD_PUFFS) { CloudPuff() }

    // Subtle meteor / shooting star state
    private var shootingStarActive = false
    private var shootingStarX = 0f
    private var shootingStarY = 0f
    private var shootingStarVx = 0f
    private var shootingStarVy = 0f
    private var shootingStarLife = 0f
    private var nextShootingStarTimer = 9.0f

    // Lightning state with realistic double-pulse flicker
    private var lightningFlashIntensity = 0f
    private var lightningRestrikePending = false
    private var lightningRestrikeTimer = 0f
    private var lightningOriginX = 0.5f
    private var lightningOriginY = 0.14f
    private var nextLightningTimer = 3.6f
    private val boltSegments = FloatArray(80) // x0, y0, x1, y1, widthWeight
    private var boltSegmentCount = 0

    init {
        initializePools()
    }

    private fun initializePools() {
        for (i in volumetricPuffs.indices) {
            val layer = i % 3
            volumetricPuffs[i].apply {
                normX = random.nextFloat() * 1.4f - 0.2f
                normY = 0.07f + layer * 0.085f + random.nextFloat() * 0.16f
                radiusNorm = 0.16f + random.nextFloat() * 0.18f
                speedNorm = (0.0045f + layer * 0.004f) * (0.85f + random.nextFloat() * 0.3f)
                depthLayer = layer
            }
        }
        for (i in rainDrops.indices) {
            resetRainDrop(rainDrops[i], randomY = true)
        }
        for (i in glassDroplets.indices) {
            resetGlassDroplet(glassDroplets[i], initialSpawn = true)
        }
        for (i in snowflakes.indices) {
            resetSnowflake(snowflakes[i], randomY = true)
        }
    }

    fun setViewportSize(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            viewportWidth = width.toFloat()
            viewportHeight = height.toFloat()
        }
    }

    fun setParallaxOffset(normX: Float, normY: Float) {
        targetParallaxX = normX.coerceIn(-1.1f, 1.1f)
        targetParallaxY = normY.coerceIn(-1.1f, 1.1f)
    }

    fun onTouchInteraction(x: Float, y: Float, preferences: WallpaperPreferences) {
        if (!preferences.enableTouchRipples) return
        val slot = touchRipples.firstOrNull { !it.active } ?: touchRipples[0]
        slot.apply {
            active = true
            centerX = x
            centerY = y
            radiusPx = 4f
            maxRadiusPx = min(viewportWidth, viewportHeight) * 0.19f
            alpha = 0.55f
        }

        val repelRadius = viewportWidth * 0.15f
        for (droplet in glassDroplets) {
            if (!droplet.active) continue
            val dx = droplet.x - x
            val dy = droplet.y - y
            val dist = hypot(dx, dy)
            if (dist in 1f..repelRadius) {
                val push = (1f - dist / repelRadius) * 20f
                droplet.x += (dx / dist) * push
                droplet.vy += 90f
            }
        }
        for (flake in snowflakes) {
            val fx = flake.normX * viewportWidth
            val fy = flake.normY * viewportHeight
            val dx = fx - x
            val dy = fy - y
            val dist = hypot(dx, dy)
            if (dist in 1f..repelRadius) {
                val push = (1f - dist / repelRadius) * 0.045f
                flake.normX += (dx / dist) * push
                flake.normY += (dy / dist) * push
            }
        }
    }

    fun triggerManualLightning() {
        spawnLightningBolt()
    }

    fun renderFrame(
        canvas: Canvas,
        dtSeconds: Float,
        weather: WeatherState,
        preferences: WallpaperPreferences
    ) {
        val dt = dtSeconds.coerceIn(0.001f, 0.05f)
        elapsedTimeSec += dt

        // 1. Critically damped interpolation for silky transitions
        val lerpRate = (dt * 3.8f).coerceIn(0.01f, 1f)
        val targetSolar = if (preferences.customTimeSliderEnabled) {
            preferences.customSolarElevation
        } else {
            weather.solarElevation
        }
        smoothSolarElevation += (targetSolar - smoothSolarElevation) * lerpRate
        smoothCloudCover += (weather.cloudCoverFraction - smoothCloudCover) * lerpRate
        smoothPrecip += (weather.precipitationIntensity - smoothPrecip) * lerpRate
        smoothFog += (weather.fogDensity - smoothFog) * lerpRate
        val targetSnow = if (weather.condition.isSnow) 1f else 0f
        smoothSnowFactor += (targetSnow - smoothSnowFactor) * lerpRate

        val parallaxScale = if (preferences.enableGyroParallax) preferences.parallaxIntensity else 0.18f
        currentParallaxX += (targetParallaxX * parallaxScale - currentParallaxX) * (dt * 6.5f).coerceAtMost(1f)
        currentParallaxY += (targetParallaxY * parallaxScale - currentParallaxY) * (dt * 6.5f).coerceAtMost(1f)

        val sunNormX = (0.74f + currentParallaxX * 0.038f).coerceIn(0.16f, 0.86f)
        val sunNormY = when {
            weather.condition == WeatherCondition.GOLDEN_HOUR -> 0.40f
            smoothSolarElevation >= 0f -> 0.13f + (1f - smoothSolarElevation) * 0.27f
            else -> 0.16f
        } + currentParallaxY * 0.024f

        updateLightning(dt, weather)

        // 2. Render Sky Dome, 3D Spherical Moon & Volumetric AGSL Clouds
        drawSkyAndShaderLayer(canvas, sunNormX, sunNormY, weather.moonPhase, preferences)

        // 3. Render Quiet, Observationally Accurate J2000 Starfield (when night or deep twilight)
        val nightVisibility = ((0.08f - smoothSolarElevation) / 0.34f).coerceIn(0f, 1f) *
            (1f - smoothCloudCover * 0.88f)
        if (nightVisibility > 0.01f) {
            drawAstronomicalStarfield(
                canvas = canvas,
                dt = dt,
                nightVisibility = nightVisibility,
                weather = weather,
                preferences = preferences,
                sunNormX = sunNormX,
                sunNormY = sunNormY
            )
        }

        // 4. Subtle Solar Optical Flare & Warm Corona (Day / Golden Hour)
        if (smoothSolarElevation > -0.08f) {
            val sunVisibility = ((smoothSolarElevation + 0.08f) / 0.24f).coerceIn(0f, 1f) *
                (1f - smoothCloudCover * 0.76f)
            if (sunVisibility > 0.01f) {
                drawRefinedSolarOptics(
                    canvas = canvas,
                    sunX = sunNormX * viewportWidth,
                    sunY = sunNormY * viewportHeight,
                    visibility = sunVisibility,
                    bloomIntensity = preferences.atmosphericBloomIntensity
                )
            }
        }

        // 5. Render Soft Multi-Plane Cloud Puffs
        if (smoothCloudCover > 0.06f) {
            drawSoftParallaxCloudPuffs(canvas, dt)
        }

        // 6. Render Branching Lightning Bolt Geometry
        if (lightningFlashIntensity > 0.04f && boltSegmentCount > 0) {
            drawLightningBoltGeometry(canvas)
        }

        // 7. Render Precipitation: Silky Rain + Clock-Shelf Splashes + Teardrop Glass Droplets OR Snow
        val densityMult = preferences.particleDensityMultiplier.coerceIn(0.4f, 1.8f)
        if (smoothPrecip > 0.02f && smoothSnowFactor < 0.5f) {
            drawRainAndClockSplashes(
                canvas = canvas,
                dt = dt,
                precipIntensity = smoothPrecip * (1f - smoothSnowFactor),
                windKmh = weather.windSpeedKmh,
                densityMultiplier = densityMult,
                enableClockSplashes = preferences.enableClockShelfSplashes,
                clockShelfYFraction = preferences.clockShelfYFraction
            )
            if (preferences.enableGlassDroplets) {
                drawRefractiveGlassDroplets(
                    canvas = canvas,
                    dt = dt,
                    precipIntensity = smoothPrecip * (1f - smoothSnowFactor),
                    densityMultiplier = densityMult
                )
            }
        }

        if (smoothSnowFactor > 0.05f && smoothPrecip > 0.02f) {
            drawSnowfallAndFrost(
                canvas = canvas,
                dt = dt,
                snowIntensity = smoothPrecip * smoothSnowFactor,
                windKmh = weather.windSpeedKmh,
                densityMultiplier = densityMult
            )
        }

        // 8. Render Subtle Touch Water Ripples
        if (preferences.enableTouchRipples) {
            drawTouchRipples(canvas, dt)
        }
    }

    private fun drawSkyAndShaderLayer(
        canvas: Canvas,
        sunNormX: Float,
        sunNormY: Float,
        moonPhase: Float,
        preferences: WallpaperPreferences
    ) {
        val shader = runtimeShader
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && shader != null) {
            shader.setFloatUniform("uResolution", viewportWidth, viewportHeight)
            shader.setFloatUniform("uTime", elapsedTimeSec)
            shader.setFloatUniform("uSolarElevation", smoothSolarElevation)
            shader.setFloatUniform("uSunPos", sunNormX, sunNormY)
            shader.setFloatUniform("uMoonPhase", moonPhase)
            shader.setFloatUniform("uCloudCover", smoothCloudCover)
            shader.setFloatUniform("uPrecipIntensity", smoothPrecip)
            shader.setFloatUniform("uFogDensity", smoothFog)
            shader.setFloatUniform("uLightningFlash", lightningFlashIntensity)
            shader.setFloatUniform("uLightningOrigin", lightningOriginX, lightningOriginY)
            shader.setFloatUniform("uParallax", currentParallaxX, currentParallaxY)
            shader.setFloatUniform("uBloomIntensity", preferences.atmosphericBloomIntensity)
            shader.setFloatUniform("uIsSnow", smoothSnowFactor)

            shaderPaint.shader = shader
            canvas.drawRect(0f, 0f, viewportWidth, viewportHeight, shaderPaint)
        } else {
            val topColor = if (smoothSolarElevation > 0f) {
                Color.rgb(26, 96, 195)
            } else {
                Color.rgb(5, 9, 25)
            }
            val bottomColor = if (smoothSolarElevation > 0f) {
                Color.rgb(159, 209, 247)
            } else {
                Color.rgb(29, 43, 79)
            }
            fallbackSkyPaint.shader = LinearGradient(
                0f, 0f, 0f, viewportHeight,
                topColor, bottomColor, Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, viewportWidth, viewportHeight, fallbackSkyPaint)
        }
    }

    /**
     * Renders the real-time J2000 astronomical starfield quietly and naturally —
     * pinpoint sub-pixel stars at their exact altitude & azimuth for the user's location
     * and time, with subtle atmospheric scintillation and zero text/wireframe clutter.
     */
    private fun drawAstronomicalStarfield(
        canvas: Canvas,
        dt: Float,
        nightVisibility: Float,
        weather: WeatherState,
        preferences: WallpaperPreferences,
        sunNormX: Float,
        sunNormY: Float
    ) {
        val pxOffset = currentParallaxX * viewportWidth * 0.022f
        val pyOffset = currentParallaxY * viewportHeight * 0.015f

        val nowEpochMs = System.currentTimeMillis()
        val bucket = nowEpochMs / 30_000L // Update sidereal sky projection every 30 seconds
        if (bucket != cachedStarCatalogBucket ||
            weather.latitude != cachedStarCatalogLat ||
            weather.longitude != cachedStarCatalogLon
        ) {
            cachedStarCatalogBucket = bucket
            cachedStarCatalogLat = weather.latitude
            cachedStarCatalogLon = weather.longitude
            cachedProjectedStars = AstronomicalStarCatalog.projectVisibleSky(
                latitudeDeg = weather.latitude,
                longitudeDeg = weather.longitude,
                epochMillis = nowEpochMs
            )
        }

        val moonX = sunNormX * viewportWidth
        val moonY = sunNormY * viewportHeight
        val moonExclusionRadius = min(viewportWidth, viewportHeight) * 0.065f

        for (cStar in cachedProjectedStars) {
            val sx = cStar.screenNormX * viewportWidth + pxOffset
            val sy = cStar.screenNormY * viewportHeight + pyOffset

            // Naturally fade stars out in the bright glare right next to the lunar disc
            val distToMoon = hypot(sx - moonX, sy - moonY)
            if (distToMoon < moonExclusionRadius) continue

            // Gentle, organic atmospheric scintillation (higher scintillation near horizon)
            val horizonFactor = (sy / (viewportHeight * 0.72f)).coerceIn(0.2f, 1.0f)
            val wave = sin(elapsedTimeSec * (2.1f + horizonFactor * 1.6f) + cStar.scintillationSeed)
            val scintillation = 1.0f - (0.12f + 0.16f * horizonFactor) * (0.5f - 0.5f * wave)

            val starAlpha = (nightVisibility * cStar.baseAlpha * scintillation * 245f)
                .toInt()
                .coerceIn(0, 245)
            if (starAlpha <= 5) continue

            // Delicate airy-disc glow on 1st-magnitude navigation stars only (Sirius, Vega, Arcturus, Rigel, Betelgeuse)
            if (cStar.isFirstMagnitude) {
                starPaint.color = cStar.colorRgb
                starPaint.alpha = (starAlpha * 0.18f).toInt()
                canvas.drawCircle(sx, sy, cStar.radiusPx * 2.3f, starPaint)
            }

            starPaint.color = cStar.colorRgb
            starPaint.alpha = starAlpha
            canvas.drawCircle(sx, sy, cStar.radiusPx, starPaint)

            // Ephemeral, whisper-faint typographic star name for principal navigation stars
            val name = cStar.properName
            if (preferences.showStarLabels && name != null && cStar.magnitude < 1.35f && nightVisibility > 0.35f) {
                val breath = (sin(elapsedTimeSec * 0.38f + cStar.scintillationSeed * 2.3f) - 0.15f)
                    .coerceIn(0f, 0.85f) / 0.85f
                val labelAlpha = (starAlpha * 0.32f * breath).toInt().coerceIn(0, 82)
                if (labelAlpha > 4) {
                    starLabelPaint.color = Color.argb(labelAlpha, 212, 228, 252)
                    canvas.drawText(
                        name.uppercase(),
                        sx + cStar.radiusPx + 9f,
                        sy + 5f,
                        starLabelPaint
                    )
                }
            }
        }

        // Occasional delicate high-altitude meteor streak across clear night sky
        nextShootingStarTimer -= dt
        if (!shootingStarActive && nextShootingStarTimer <= 0f && nightVisibility > 0.55f) {
            shootingStarActive = true
            shootingStarX = viewportWidth * (0.18f + random.nextFloat() * 0.62f)
            shootingStarY = viewportHeight * (0.05f + random.nextFloat() * 0.20f)
            val angle = (PI.toFloat() * 0.72f) + (random.nextFloat() - 0.5f) * 0.22f
            val speed = viewportWidth * 1.55f
            shootingStarVx = cos(angle) * speed
            shootingStarVy = sin(angle) * speed
            shootingStarLife = 0f
            nextShootingStarTimer = 9.0f + random.nextFloat() * 14.0f
        }

        if (shootingStarActive) {
            shootingStarLife += dt
            val maxLife = 0.46f
            if (shootingStarLife >= maxLife) {
                shootingStarActive = false
            } else {
                shootingStarX += shootingStarVx * dt
                shootingStarY += shootingStarVy * dt
                val progress = shootingStarLife / maxLife
                val envAlpha = (sin(progress * PI.toFloat()) * nightVisibility * 210f).toInt().coerceIn(0, 210)
                val tailLen = 0.068f
                val tailX = shootingStarX - shootingStarVx * tailLen
                val tailY = shootingStarY - shootingStarVy * tailLen

                rainPaint.strokeWidth = 1.6f
                rainPaint.shader = LinearGradient(
                    shootingStarX, shootingStarY, tailX, tailY,
                    Color.argb(envAlpha, 255, 255, 255),
                    Color.argb(0, 175, 210, 255),
                    Shader.TileMode.CLAMP
                )
                canvas.drawLine(shootingStarX, shootingStarY, tailX, tailY, rainPaint)
                rainPaint.shader = null
            }
        }
    }

    /**
     * Renders restrained, Apple-style optical lens bloom and subtle anamorphic bokeh
     * without harsh cartoon sun rays.
     */
    private fun drawRefinedSolarOptics(
        canvas: Canvas,
        sunX: Float,
        sunY: Float,
        visibility: Float,
        bloomIntensity: Float
    ) {
        val baseRadius = min(viewportWidth, viewportHeight) * 0.075f
        val isGolden = smoothSolarElevation < 0.22f

        // Soft atmospheric Mie bloom
        val outerRadius = baseRadius * 3.8f
        val midAlpha = (visibility * 75f * bloomIntensity).toInt().coerceIn(0, 200)
        val haloTint = if (isGolden) {
            Color.argb(midAlpha, 255, 185, 110)
        } else {
            Color.argb(midAlpha, 255, 250, 236)
        }
        celestialPaint.shader = RadialGradient(
            sunX, sunY, outerRadius,
            intArrayOf(
                Color.argb((visibility * 175 * bloomIntensity).toInt().coerceIn(0, 255), 255, 255, 250),
                haloTint,
                Color.argb(0, 255, 210, 140)
            ),
            floatArrayOf(0f, 0.28f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(sunX, sunY, outerRadius, celestialPaint)
        celestialPaint.shader = null

        // Whisper-quiet optical aperture ghosts along the screen center axis
        val centerX = viewportWidth * 0.5f
        val centerY = viewportHeight * 0.5f
        val axisX = centerX - sunX
        val axisY = centerY - sunY

        val flareStops = floatArrayOf(0.52f, 0.94f, 1.38f, 1.72f)
        val flareRadii = floatArrayOf(0.24f, 0.44f, 0.18f, 0.58f)
        for (idx in flareStops.indices) {
            val t = flareStops[idx]
            val fx = sunX + axisX * t
            val fy = sunY + axisY * t
            val fr = baseRadius * flareRadii[idx]
            val flareAlpha = (visibility * 18f * bloomIntensity).toInt().coerceIn(0, 255)
            if (flareAlpha <= 2) continue
            flarePaint.shader = RadialGradient(
                fx, fy, fr,
                when (idx % 2) {
                    0 -> Color.argb(flareAlpha, 165, 225, 255)
                    else -> Color.argb(flareAlpha, 255, 215, 175)
                },
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(fx, fy, fr, flarePaint)
        }
        flarePaint.shader = null
    }

    private fun drawSoftParallaxCloudPuffs(
        canvas: Canvas,
        dt: Float
    ) {
        val activeCount = (volumetricPuffs.size * smoothCloudCover).toInt().coerceIn(2, volumetricPuffs.size)
        val isDay = smoothSolarElevation > 0.02f
        val isGolden = smoothSolarElevation in -0.08f..0.22f

        for (i in 0 until activeCount) {
            val puff = volumetricPuffs[i]
            puff.normX += puff.speedNorm * dt
            if (puff.normX > 1.25f) {
                puff.normX = -0.25f
            }

            val depthFactor = (puff.depthLayer + 1) * 0.33f
            val px = (puff.normX + currentParallaxX * 0.065f * depthFactor) * viewportWidth
            val py = (puff.normY + currentParallaxY * 0.024f * depthFactor) * viewportHeight
            val radius = puff.radiusNorm * min(viewportWidth, viewportHeight) * (0.9f + 0.3f * depthFactor)

            val alphaBase = (smoothCloudCover * (26f + puff.depthLayer * 12f) * (1f - smoothPrecip * 0.35f))
                .toInt().coerceIn(0, 95)
            if (alphaBase <= 3) continue

            val innerColor = when {
                lightningFlashIntensity > 0.1f -> Color.argb(
                    (alphaBase * 1.4f).toInt().coerceAtMost(160),
                    205, 225, 255
                )
                isGolden -> Color.argb(alphaBase, 255, 195, 152)
                isDay && smoothPrecip < 0.4f -> Color.argb(alphaBase, 250, 253, 255)
                isDay -> Color.argb(alphaBase, 145, 162, 185)
                else -> Color.argb(alphaBase, 42, 58, 90)
            }

            cloudPuffPaint.shader = RadialGradient(
                px, py - radius * 0.15f, radius,
                innerColor,
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(px, py, radius, cloudPuffPaint)
        }
        cloudPuffPaint.shader = null
    }

    private fun updateLightning(dt: Float, weather: WeatherState) {
        if (lightningFlashIntensity > 0f) {
            lightningFlashIntensity = (lightningFlashIntensity - dt * 4.2f).coerceAtLeast(0f)
        }
        if (lightningRestrikePending) {
            lightningRestrikeTimer -= dt
            if (lightningRestrikeTimer <= 0f) {
                lightningRestrikePending = false
                lightningFlashIntensity = 0.78f // Natural secondary return-stroke pulse
            }
        }
        if (!weather.condition.isStorm) return

        nextLightningTimer -= dt
        if (nextLightningTimer <= 0f) {
            spawnLightningBolt()
            nextLightningTimer = 3.2f + random.nextFloat() * 5.8f
        }
    }

    private fun spawnLightningBolt() {
        lightningFlashIntensity = 0.95f
        lightningRestrikePending = random.nextBoolean()
        lightningRestrikeTimer = 0.08f + random.nextFloat() * 0.06f

        lightningOriginX = 0.24f + random.nextFloat() * 0.52f
        lightningOriginY = 0.06f + random.nextFloat() * 0.10f

        boltSegmentCount = 0
        var curX = lightningOriginX * viewportWidth
        var curY = lightningOriginY * viewportHeight
        val targetBottomY = viewportHeight * (0.52f + random.nextFloat() * 0.24f)
        val steps = 14

        val stepY = (targetBottomY - curY) / steps
        for (i in 0 until steps) {
            if (boltSegmentCount + 4 > boltSegments.size) break
            val nextX = curX + (random.nextFloat() - 0.48f) * viewportWidth * 0.065f
            val nextY = curY + stepY
            boltSegments[boltSegmentCount++] = curX
            boltSegments[boltSegmentCount++] = curY
            boltSegments[boltSegmentCount++] = nextX
            boltSegments[boltSegmentCount++] = nextY

            if (i in 3..10 && random.nextFloat() < 0.38f && boltSegmentCount + 4 <= boltSegments.size) {
                val branchX = nextX + (if (random.nextBoolean()) 1f else -1f) * viewportWidth * 0.06f
                val branchY = nextY + stepY * 1.6f
                boltSegments[boltSegmentCount++] = nextX
                boltSegments[boltSegmentCount++] = nextY
                boltSegments[boltSegmentCount++] = branchX
                boltSegments[boltSegmentCount++] = branchY
            }

            curX = nextX
            curY = nextY
        }
    }

    private fun drawLightningBoltGeometry(canvas: Canvas) {
        lightningPath.reset()
        var i = 0
        while (i + 3 < boltSegmentCount) {
            lightningPath.moveTo(boltSegments[i], boltSegments[i + 1])
            lightningPath.lineTo(boltSegments[i + 2], boltSegments[i + 3])
            i += 4
        }
        val alpha = (lightningFlashIntensity * 235f).toInt().coerceIn(0, 235)

        lightningGlowPaint.strokeWidth = 9.5f
        lightningGlowPaint.color = Color.argb((alpha * 0.58f).toInt(), 155, 195, 255)
        canvas.drawPath(lightningPath, lightningGlowPaint)

        lightningCorePaint.strokeWidth = 2.2f
        lightningCorePaint.color = Color.argb(alpha, 245, 250, 255)
        canvas.drawPath(lightningPath, lightningCorePaint)
    }

    /**
     * Renders Apple-calibrated silky-thin motion-blurred rain streaks with head-to-tail
     * linear gradient taper and delicate Lock-Screen Clock Shelf micro-splashes.
     */
    private fun drawRainAndClockSplashes(
        canvas: Canvas,
        dt: Float,
        precipIntensity: Float,
        windKmh: Float,
        densityMultiplier: Float,
        enableClockSplashes: Boolean,
        clockShelfYFraction: Float
    ) {
        val activeDrops = (MAX_RAINDROPS * precipIntensity * densityMultiplier)
            .toInt()
            .coerceIn(16, MAX_RAINDROPS)

        val windSlantVx = (windKmh * 5.2f) + currentParallaxX * 140f

        val shelfY = viewportHeight * clockShelfYFraction
        val shelfLeftX = viewportWidth * 0.16f
        val shelfRightX = viewportWidth * 0.84f

        rainPaint.shader = null
        for (i in 0 until activeDrops) {
            val drop = rainDrops[i]
            val depthScale = 0.42f + drop.depth * 0.88f
            val vy = drop.speedPx * depthScale
            val vx = windSlantVx * depthScale

            val prevY = drop.y
            drop.x += vx * dt
            drop.y += vy * dt

            // Subtle Lock-Screen Clock Shelf collision
            if (enableClockSplashes &&
                drop.depth > 0.52f &&
                prevY <= shelfY &&
                drop.y >= shelfY &&
                drop.x in shelfLeftX..shelfRightX &&
                random.nextFloat() < 0.36f
            ) {
                spawnClockSplash(drop.x, shelfY)
                resetRainDrop(drop, randomY = false)
                continue
            }

            if (drop.y > viewportHeight + 90f || drop.x < -120f || drop.x > viewportWidth + 120f) {
                resetRainDrop(drop, randomY = false)
                continue
            }

            val streakDt = 0.024f + drop.depth * 0.016f
            val tailX = drop.x - vx * streakDt
            val tailY = drop.y - vy * streakDt
            val headAlpha = ((38 + drop.depth * 88) * precipIntensity).toInt().coerceIn(14, 145)

            // Silky-thin stroke width (0.65px background .. 1.65px foreground)
            rainPaint.strokeWidth = 0.65f + drop.depth * 1.0f
            rainPaint.color = Color.argb(headAlpha, 215, 234, 255)
            canvas.drawLine(tailX, tailY, drop.x, drop.y, rainPaint)
        }

        if (enableClockSplashes) {
            for (splash in splashes) {
                if (!splash.active) continue
                splash.age += dt
                if (splash.age >= splash.maxAge) {
                    splash.active = false
                    continue
                }
                val prevX = splash.x
                val prevY = splash.y
                splash.x += splash.vx * dt
                splash.y += splash.vy * dt
                splash.vy += 1050f * dt

                val lifeNorm = 1f - (splash.age / splash.maxAge)
                splashPaint.strokeWidth = 1.15f * lifeNorm
                splashPaint.color = Color.argb(
                    (lifeNorm * 155f).toInt().coerceIn(0, 255),
                    228, 242, 255
                )
                canvas.drawLine(prevX, prevY, splash.x, splash.y, splashPaint)
            }
        }
    }

    private fun spawnClockSplash(x: Float, y: Float) {
        repeat(2) {
            val slot = splashes.firstOrNull { !it.active } ?: return
            slot.apply {
                active = true
                this.x = x + (random.nextFloat() - 0.5f) * 6f
                this.y = y
                vx = (random.nextFloat() - 0.5f) * 165f
                vy = -(95f + random.nextFloat() * 145f)
                age = 0f
                maxAge = 0.19f + random.nextFloat() * 0.14f
            }
        }
    }

    /**
     * Renders organic teardrop condensation droplets on the virtual glass lens with
     * subtle internal refraction and specular caustic highlights.
     */
    private fun drawRefractiveGlassDroplets(
        canvas: Canvas,
        dt: Float,
        precipIntensity: Float,
        densityMultiplier: Float
    ) {
        val activeCount = (MAX_GLASS_DROPLETS * precipIntensity * densityMultiplier)
            .toInt()
            .coerceIn(3, MAX_GLASS_DROPLETS)

        for (i in 0 until activeCount) {
            val d = glassDroplets[i]
            if (!d.active) {
                resetGlassDroplet(d, initialSpawn = false)
            }

            d.slipTimer -= dt
            if (d.slipTimer <= 0f) {
                d.targetVy = if (random.nextFloat() < 0.30f) {
                    48f + random.nextFloat() * 135f
                } else {
                    2f + random.nextFloat() * 9f
                }
                d.slipTimer = 0.5f + random.nextFloat() * 1.6f
            }
            d.vy += (d.targetVy - d.vy) * (dt * 4.5f).coerceAtMost(1f)
            d.y += d.vy * dt
            d.x += sin(elapsedTimeSec * 1.5f + d.wobblePhase) * (d.vy * 0.06f) * dt

            if (d.y > viewportHeight + 40f) {
                resetGlassDroplet(d, initialSpawn = false)
                continue
            }

            val r = d.radiusPx
            val elongation = (1.05f + (d.vy / 240f).coerceIn(0f, 0.65f))

            // Teardrop contour path (slightly tapered at top, rounded meniscus at bottom)
            dropletPath.reset()
            dropletPath.moveTo(d.x, d.y - r * elongation * 1.15f)
            dropletPath.cubicTo(
                d.x + r * 1.05f, d.y - r * 0.25f,
                d.x + r * 0.95f, d.y + r * elongation,
                d.x, d.y + r * elongation
            )
            dropletPath.cubicTo(
                d.x - r * 0.95f, d.y + r * elongation,
                d.x - r * 1.05f, d.y - r * 0.25f,
                d.x, d.y - r * elongation * 1.15f
            )
            dropletPath.close()

            dropletBodyPaint.shader = RadialGradient(
                d.x, d.y + r * 0.28f, r * elongation * 1.1f,
                intArrayOf(
                    Color.argb(32, 255, 255, 255),
                    Color.argb(68, 195, 225, 255),
                    Color.argb(85, 20, 36, 64)
                ),
                floatArrayOf(0f, 0.72f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawPath(dropletPath, dropletBodyPaint)

            // Delicate lower meniscus rim highlight
            dropletRimPaint.strokeWidth = 0.9f
            dropletRimPaint.color = Color.argb(105, 235, 248, 255)
            canvas.drawPath(dropletPath, dropletRimPaint)

            // Crisp pinpoint specular reflection near upper-left crown
            starPaint.color = Color.argb(185, 255, 255, 255)
            canvas.drawCircle(d.x - r * 0.26f, d.y - r * 0.22f, r * 0.20f, starPaint)
        }
        dropletBodyPaint.shader = null
    }

    private fun drawSnowfallAndFrost(
        canvas: Canvas,
        dt: Float,
        snowIntensity: Float,
        windKmh: Float,
        densityMultiplier: Float
    ) {
        val activeFlakes = (MAX_SNOWFLAKES * snowIntensity * densityMultiplier)
            .toInt()
            .coerceIn(24, MAX_SNOWFLAKES)

        val windDriftNorm = (windKmh / 1600f) + currentParallaxX * 0.032f

        for (i in 0 until activeFlakes) {
            val flake = snowflakes[i]
            val depthScale = 0.32f + flake.depth * 0.92f
            val flutter = sin(elapsedTimeSec * flake.flutterFreq + flake.flutterPhase) * 0.020f

            flake.normX += (windDriftNorm * depthScale + flutter) * dt
            flake.normY += flake.fallSpeedNorm * depthScale * dt

            if (flake.normY > 1.05f) {
                resetSnowflake(flake, randomY = false)
            }
            if (flake.normX > 1.06f) flake.normX -= 1.12f
            if (flake.normX < -0.06f) flake.normX += 1.12f

            val sx = flake.normX * viewportWidth
            val sy = flake.normY * viewportHeight
            val radius = flake.radiusPx * depthScale
            val alpha = ((75 + flake.depth * 145) * snowIntensity).toInt().coerceIn(24, 225)

            // Soft radial falloff on all snowflakes for natural optical depth-of-field
            snowPaint.shader = RadialGradient(
                sx, sy, radius * 1.45f,
                Color.argb(alpha, 250, 253, 255),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(sx, sy, radius * 1.45f, snowPaint)
        }
        snowPaint.shader = null
    }

    private fun drawTouchRipples(canvas: Canvas, dt: Float) {
        for (ripple in touchRipples) {
            if (!ripple.active) continue
            ripple.radiusPx += dt * 280f
            ripple.alpha -= dt * 0.85f
            if (ripple.alpha <= 0.02f || ripple.radiusPx >= ripple.maxRadiusPx) {
                ripple.active = false
                continue
            }
            val a = (ripple.alpha * 120f).toInt().coerceIn(0, 255)
            ripplePaint.strokeWidth = 1.6f
            ripplePaint.color = Color.argb(a, 225, 242, 255)
            canvas.drawCircle(ripple.centerX, ripple.centerY, ripple.radiusPx, ripplePaint)
        }
    }

    private fun resetRainDrop(drop: RainParticle, randomY: Boolean) {
        drop.depth = random.nextFloat()
        drop.x = (random.nextFloat() * 1.3f - 0.15f) * viewportWidth
        drop.y = if (randomY) {
            random.nextFloat() * viewportHeight
        } else {
            -random.nextFloat() * 180f - 20f
        }
        drop.speedPx = 1550f + random.nextFloat() * 900f
    }

    private fun resetGlassDroplet(droplet: GlassDroplet, initialSpawn: Boolean) {
        droplet.active = true
        droplet.x = (0.08f + random.nextFloat() * 0.84f) * viewportWidth
        droplet.y = if (initialSpawn) {
            random.nextFloat() * viewportHeight
        } else {
            random.nextFloat() * viewportHeight * 0.35f - 20f
        }
        droplet.radiusPx = 3.2f + random.nextFloat() * 5.2f
        droplet.vy = 6f + random.nextFloat() * 24f
        droplet.targetVy = droplet.vy
        droplet.slipTimer = 0.4f + random.nextFloat() * 1.4f
        droplet.wobblePhase = random.nextFloat() * 6.28f
    }

    private fun resetSnowflake(flake: SnowParticle, randomY: Boolean) {
        flake.depth = random.nextFloat()
        flake.normX = random.nextFloat()
        flake.normY = if (randomY) random.nextFloat() else -0.04f - random.nextFloat() * 0.08f
        flake.radiusPx = 2.0f + random.nextFloat() * 4.4f
        flake.fallSpeedNorm = 0.042f + random.nextFloat() * 0.058f
        flake.flutterFreq = 1.3f + random.nextFloat() * 2.2f
        flake.flutterPhase = random.nextFloat() * 6.28f
    }

    private class CloudPuff(
        var normX: Float = 0f,
        var normY: Float = 0f,
        var radiusNorm: Float = 0.2f,
        var speedNorm: Float = 0.008f,
        var depthLayer: Int = 0
    )

    private class RainParticle(
        var x: Float = 0f,
        var y: Float = 0f,
        var speedPx: Float = 1800f,
        var depth: Float = 0.5f
    )

    private class SplashParticle(
        var active: Boolean = false,
        var x: Float = 0f,
        var y: Float = 0f,
        var vx: Float = 0f,
        var vy: Float = 0f,
        var age: Float = 0f,
        var maxAge: Float = 0.22f
    )

    private class GlassDroplet(
        var active: Boolean = false,
        var x: Float = 0f,
        var y: Float = 0f,
        var radiusPx: Float = 4.5f,
        var vy: Float = 15f,
        var targetVy: Float = 15f,
        var slipTimer: Float = 0.5f,
        var wobblePhase: Float = 0f
    )

    private class SnowParticle(
        var normX: Float = 0f,
        var normY: Float = 0f,
        var radiusPx: Float = 3.5f,
        var fallSpeedNorm: Float = 0.055f,
        var flutterFreq: Float = 1.8f,
        var flutterPhase: Float = 0f,
        var depth: Float = 0.5f
    )

    private class TouchRipple(
        var active: Boolean = false,
        var centerX: Float = 0f,
        var centerY: Float = 0f,
        var radiusPx: Float = 0f,
        var maxRadiusPx: Float = 180f,
        var alpha: Float = 0f
    )

    companion object {
        private const val MAX_CLOUD_PUFFS = 16
        private const val MAX_RAINDROPS = 190
        private const val MAX_SPLASHES = 44
        private const val MAX_GLASS_DROPLETS = 16
        private const val MAX_SNOWFLAKES = 140
        private const val MAX_TOUCH_RIPPLES = 6
    }
}
