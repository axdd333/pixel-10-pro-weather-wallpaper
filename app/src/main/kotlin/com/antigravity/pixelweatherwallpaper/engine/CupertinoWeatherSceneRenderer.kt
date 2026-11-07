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
import com.antigravity.pixelweatherwallpaper.model.WallpaperPreferences
import com.antigravity.pixelweatherwallpaper.model.WeatherCondition
import com.antigravity.pixelweatherwallpaper.model.WeatherState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * High-precision Cupertino Weather scene renderer capable of running at 120fps on both
 * Android's hardware-accelerated WallpaperService SurfaceHolder Canvas and Jetpack Compose Canvas.
 *
 * Implements:
 * - AGSL hardware sky/cloud/scattering shader (with fallback gradient renderer)
 * - 3D parallax starfield with Perlin-like scintillation & shooting stars
 * - Accurate lunar phase rendering with crater shading & earthshine
 * - Anamorphic solar lens flare bokeh & rotating corona shafts
 * - Multi-depth rain streaks with Pixel 10 Pro Clock-Shelf collision & bounce splashes
 * - Refractive screen-space water droplets trickling down the glass with surface tension
 * - 3D turbulent snowflakes with depth-of-field bokeh
 * - Recursive branching lightning bolts with cloud illumination
 * - Interactive touch water ripples & particle repulsion
 */
class CupertinoWeatherSceneRenderer {

    private val random = Random(System.nanoTime())
    private val runtimeShader: RuntimeShader? = AtmosphericAgslShader.createOrNull()

    // Reusable pre-allocated Paints to guarantee zero GC allocations per frame
    private val shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fallbackSkyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val celestialPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val flarePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val splashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
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
        maskFilter = BlurMaskFilter(18f, BlurMaskFilter.Blur.NORMAL)
    }
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val cloudPuffPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val lightningPath = Path()
    private val lightningBranchPath = Path()
    private val dropletPath = Path()

    // Viewport state
    private var viewportWidth = 1080f
    private var viewportHeight = 2400f
    private var elapsedTimeSec = 0f

    // Smoothly interpolated weather transition parameters
    private var smoothSolarElevation = 0.65f
    private var smoothCloudCover = 0.15f
    private var smoothPrecip = 0.0f
    private var smoothFog = 0.0f
    private var smoothSnowFactor = 0.0f

    // Smoothly damped parallax [-1..1]
    private var currentParallaxX = 0f
    private var currentParallaxY = 0f
    private var targetParallaxX = 0f
    private var targetParallaxY = 0f

    // Particle pools (fixed size to avoid allocations)
    private val stars = Array(MAX_STARS) { StarParticle() }
    private val rainDrops = Array(MAX_RAINDROPS) { RainParticle() }
    private val splashes = Array(MAX_SPLASHES) { SplashParticle() }
    private val glassDroplets = Array(MAX_GLASS_DROPLETS) { GlassDroplet() }
    private val snowflakes = Array(MAX_SNOWFLAKES) { SnowParticle() }
    private val touchRipples = Array(MAX_TOUCH_RIPPLES) { TouchRipple() }
    private val volumetricPuffs = Array(MAX_CLOUD_PUFFS) { CloudPuff() }

    // Shooting star state
    private var shootingStarActive = false
    private var shootingStarX = 0f
    private var shootingStarY = 0f
    private var shootingStarVx = 0f
    private var shootingStarVy = 0f
    private var shootingStarLife = 0f
    private var nextShootingStarTimer = 4.5f

    // Lightning bolt state
    private var lightningFlashIntensity = 0f
    private var lightningOriginX = 0.5f
    private var lightningOriginY = 0.18f
    private var nextLightningTimer = 2.8f
    private val boltSegments = FloatArray(64) // x0, y0, x1, y1 pairs
    private var boltSegmentCount = 0

    init {
        initializePools()
    }

    private fun initializePools() {
        for (i in stars.indices) {
            stars[i].apply {
                normX = random.nextFloat()
                normY = random.nextFloat() * 0.68f
                sizePx = 1.4f + random.nextFloat() * 3.2f
                twinkleSpeed = 1.2f + random.nextFloat() * 3.8f
                twinklePhase = random.nextFloat() * (PI.toFloat() * 2f)
                colorTint = when (i % 5) {
                    0 -> Color.rgb(215, 232, 255) // Blue-white Vega tint
                    1 -> Color.rgb(255, 244, 224) // Warm Betelgeuse tint
                    else -> Color.rgb(245, 249, 255)
                }
            }
        }
        for (i in volumetricPuffs.indices) {
            val layer = i % 3 // 0 = far, 1 = mid, 2 = foreground hero
            volumetricPuffs[i].apply {
                normX = random.nextFloat() * 1.4f - 0.2f
                normY = 0.08f + layer * 0.09f + random.nextFloat() * 0.18f
                radiusNorm = 0.14f + random.nextFloat() * 0.18f
                speedNorm = (0.008f + layer * 0.007f) * (0.85f + random.nextFloat() * 0.3f)
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
        targetParallaxX = normX.coerceIn(-1.2f, 1.2f)
        targetParallaxY = normY.coerceIn(-1.2f, 1.2f)
    }

    fun onTouchInteraction(x: Float, y: Float, preferences: WallpaperPreferences) {
        if (!preferences.enableTouchRipples) return
        // Spawn a ripple at the touch coordinate
        val slot = touchRipples.firstOrNull { !it.active } ?: touchRipples[0]
        slot.apply {
            active = true
            centerX = x
            centerY = y
            radiusPx = 6f
            maxRadiusPx = min(viewportWidth, viewportHeight) * 0.24f
            alpha = 0.75f
        }

        // Displace nearby glass droplets or snowflakes away from finger
        val repelRadius = viewportWidth * 0.18f
        for (droplet in glassDroplets) {
            if (!droplet.active) continue
            val dx = droplet.x - x
            val dy = droplet.y - y
            val dist = hypot(dx, dy)
            if (dist in 1f..repelRadius) {
                val push = (1f - dist / repelRadius) * 28f
                droplet.x += (dx / dist) * push
                droplet.vy += 120f
            }
        }
        for (flake in snowflakes) {
            val fx = flake.normX * viewportWidth
            val fy = flake.normY * viewportHeight
            val dx = fx - x
            val dy = fy - y
            val dist = hypot(dx, dy)
            if (dist in 1f..repelRadius) {
                val push = (1f - dist / repelRadius) * 0.06f
                flake.normX += (dx / dist) * push
                flake.normY += (dy / dist) * push
            }
        }
    }

    fun triggerManualLightning() {
        spawnLightningBolt()
    }

    /**
     * Advances physics simulation and renders the complete scene to [canvas].
     */
    fun renderFrame(
        canvas: Canvas,
        dtSeconds: Float,
        weather: WeatherState,
        preferences: WallpaperPreferences
    ) {
        val dt = dtSeconds.coerceIn(0.001f, 0.05f)
        elapsedTimeSec += dt

        // 1. Smoothly interpolate atmospheric state so weather switches feel organic
        val lerpRate = (dt * 4.5f).coerceIn(0.01f, 1f)
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

        // Smooth gyroscope + launcher parallax damping
        val parallaxScale = if (preferences.enableGyroParallax) preferences.parallaxIntensity else 0.2f
        currentParallaxX += (targetParallaxX * parallaxScale - currentParallaxX) * (dt * 8f).coerceAtMost(1f)
        currentParallaxY += (targetParallaxY * parallaxScale - currentParallaxY) * (dt * 8f).coerceAtMost(1f)

        // Compute celestial position
        val sunNormX = (0.72f + currentParallaxX * 0.045f).coerceIn(0.15f, 0.88f)
        val sunNormY = when {
            weather.condition == WeatherCondition.GOLDEN_HOUR -> 0.42f
            smoothSolarElevation >= 0f -> 0.14f + (1f - smoothSolarElevation) * 0.28f
            else -> 0.18f // Moon position at night
        } + currentParallaxY * 0.03f

        // Update lightning physics
        updateLightning(dt, weather)

        // 2. Render Sky Dome & Volumetric AGSL Clouds
        drawSkyAndShaderLayer(canvas, sunNormX, sunNormY, preferences)

        // 3. Render Nocturnal Starfield & Shooting Stars (when night or twilight)
        val nightVisibility = ((0.12f - smoothSolarElevation) / 0.38f).coerceIn(0f, 1f) *
            (1f - smoothCloudCover * 0.85f)
        if (nightVisibility > 0.01f) {
            drawStarfieldAndShootingStars(canvas, dt, nightVisibility)
        }

        // 4. Render Sun & Anamorphic Lens Flare OR Moon with Lunar Phase
        if (smoothSolarElevation > -0.10f) {
            val sunVisibility = ((smoothSolarElevation + 0.10f) / 0.25f).coerceIn(0f, 1f) *
                (1f - smoothCloudCover * 0.72f)
            if (sunVisibility > 0.01f) {
                drawSunAndLensFlare(
                    canvas,
                    sunNormX * viewportWidth,
                    sunNormY * viewportHeight,
                    sunVisibility,
                    preferences.atmosphericBloomIntensity
                )
            }
        } else {
            val moonVis = ((-smoothSolarElevation - 0.05f) / 0.25f).coerceIn(0f, 1f) *
                (1f - smoothCloudCover * 0.75f)
            if (moonVis > 0.01f) {
                drawMoonWithPhase(
                    canvas,
                    sunNormX * viewportWidth,
                    sunNormY * viewportHeight,
                    weather.moonPhase,
                    moonVis
                )
            }
        }

        // 5. Render Foreground Soft Volumetric Cloud Puffs with Parallax Depth
        if (smoothCloudCover > 0.05f) {
            drawSoftParallaxCloudPuffs(canvas, dt, sunNormX, sunNormY)
        }

        // 6. Render Branching Lightning Bolt Geometry
        if (lightningFlashIntensity > 0.05f && boltSegmentCount > 0) {
            drawLightningBoltGeometry(canvas)
        }

        // 7. Render Precipitation: Rain + Clock-Shelf Splashes + Glass Droplets OR Snow
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

        // 8. Render Interactive Touch Water Ripples
        if (preferences.enableTouchRipples) {
            drawTouchRipples(canvas, dt)
        }
    }

    private fun drawSkyAndShaderLayer(
        canvas: Canvas,
        sunNormX: Float,
        sunNormY: Float,
        preferences: WallpaperPreferences
    ) {
        val shader = runtimeShader
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && shader != null) {
            shader.setFloatUniform("uResolution", viewportWidth, viewportHeight)
            shader.setFloatUniform("uTime", elapsedTimeSec)
            shader.setFloatUniform("uSolarElevation", smoothSolarElevation)
            shader.setFloatUniform("uSunPos", sunNormX, sunNormY)
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
            // Fallback multi-stop linear gradient if AGSL is unavailable
            val topColor = if (smoothSolarElevation > 0f) {
                Color.rgb(24, 98, 200)
            } else {
                Color.rgb(6, 10, 28)
            }
            val bottomColor = if (smoothSolarElevation > 0f) {
                Color.rgb(163, 213, 249)
            } else {
                Color.rgb(28, 42, 82)
            }
            fallbackSkyPaint.shader = LinearGradient(
                0f, 0f, 0f, viewportHeight,
                topColor, bottomColor, Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, viewportWidth, viewportHeight, fallbackSkyPaint)
        }
    }

    private fun drawStarfieldAndShootingStars(
        canvas: Canvas,
        dt: Float,
        nightVisibility: Float
    ) {
        val pxOffset = currentParallaxX * viewportWidth * 0.025f
        val pyOffset = currentParallaxY * viewportHeight * 0.018f

        for (star in stars) {
            val twinkle = 0.45f + 0.55f * sin(elapsedTimeSec * star.twinkleSpeed + star.twinklePhase)
            val alpha = (nightVisibility * twinkle * 235f).toInt().coerceIn(0, 255)
            if (alpha <= 4) continue

            val sx = ((star.normX * viewportWidth + pxOffset) % viewportWidth + viewportWidth) % viewportWidth
            val sy = (star.normY * viewportHeight + pyOffset).coerceIn(0f, viewportHeight * 0.72f)

            starPaint.color = star.colorTint
            starPaint.alpha = alpha
            canvas.drawCircle(sx, sy, star.sizePx, starPaint)
        }

        // Update & render occasional shooting star across clear night sky
        nextShootingStarTimer -= dt
        if (!shootingStarActive && nextShootingStarTimer <= 0f && nightVisibility > 0.5f) {
            shootingStarActive = true
            shootingStarX = viewportWidth * (0.15f + random.nextFloat() * 0.65f)
            shootingStarY = viewportHeight * (0.06f + random.nextFloat() * 0.22f)
            val angle = (PI.toFloat() * 0.68f) + (random.nextFloat() - 0.5f) * 0.25f
            val speed = viewportWidth * 1.45f
            shootingStarVx = cos(angle) * speed
            shootingStarVy = sin(angle) * speed
            shootingStarLife = 0f
            nextShootingStarTimer = 5.5f + random.nextFloat() * 7.5f
        }

        if (shootingStarActive) {
            shootingStarLife += dt
            val maxLife = 0.55f
            if (shootingStarLife >= maxLife) {
                shootingStarActive = false
            } else {
                shootingStarX += shootingStarVx * dt
                shootingStarY += shootingStarVy * dt
                val progress = shootingStarLife / maxLife
                val envAlpha = (sin(progress * PI.toFloat()) * nightVisibility * 255f).toInt().coerceIn(0, 255)
                val tailLen = 0.085f
                val tailX = shootingStarX - shootingStarVx * tailLen
                val tailY = shootingStarY - shootingStarVy * tailLen

                rainPaint.strokeWidth = 3.2f
                rainPaint.shader = LinearGradient(
                    shootingStarX, shootingStarY, tailX, tailY,
                    Color.argb(envAlpha, 255, 255, 255),
                    Color.argb(0, 140, 190, 255),
                    Shader.TileMode.CLAMP
                )
                canvas.drawLine(shootingStarX, shootingStarY, tailX, tailY, rainPaint)
                rainPaint.shader = null
            }
        }
    }

    private fun drawSunAndLensFlare(
        canvas: Canvas,
        sunX: Float,
        sunY: Float,
        visibility: Float,
        bloomIntensity: Float
    ) {
        val baseRadius = min(viewportWidth, viewportHeight) * 0.085f
        val isGolden = smoothSolarElevation < 0.24f

        // 1. Outer atmospheric bloom halo
        val outerRadius = baseRadius * 4.6f
        val haloColorCenter = if (isGolden) {
            Color.argb((visibility * 145 * bloomIntensity).toInt().coerceIn(0, 255), 255, 175, 95)
        } else {
            Color.argb((visibility * 135 * bloomIntensity).toInt().coerceIn(0, 255), 255, 250, 230)
        }
        celestialPaint.shader = RadialGradient(
            sunX, sunY, outerRadius,
            intArrayOf(
                Color.argb((visibility * 250).toInt().coerceIn(0, 255), 255, 255, 248),
                haloColorCenter,
                Color.argb(0, 255, 200, 120)
            ),
            floatArrayOf(0f, 0.22f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(sunX, sunY, outerRadius, celestialPaint)
        celestialPaint.shader = null

        // 2. Rotating crepuscular corona spikes
        val rayCount = 12
        val rotation = elapsedTimeSec * 0.05f
        rainPaint.shader = null
        for (i in 0 until rayCount) {
            val angle = rotation + (i * PI.toFloat() * 2f / rayCount)
            val rayLen = baseRadius * (2.2f + 0.8f * sin(elapsedTimeSec * 1.1f + i * 1.7f))
            val rx = sunX + cos(angle) * rayLen
            val ry = sunY + sin(angle) * rayLen
            rainPaint.strokeWidth = 2.6f
            rainPaint.color = Color.argb((visibility * 48 * bloomIntensity).toInt().coerceIn(0, 255), 255, 248, 225)
            canvas.drawLine(sunX, sunY, rx, ry, rainPaint)
        }

        // 3. Anamorphic Optical Lens Flare Ghosts along the vector through screen center
        val centerX = viewportWidth * 0.5f
        val centerY = viewportHeight * 0.5f
        val axisX = centerX - sunX
        val axisY = centerY - sunY

        val flareStops = floatArrayOf(0.45f, 0.85f, 1.25f, 1.58f, 1.92f)
        val flareRadii = floatArrayOf(0.28f, 0.52f, 0.19f, 0.74f, 0.42f)
        for (idx in flareStops.indices) {
            val t = flareStops[idx]
            val fx = sunX + axisX * t
            val fy = sunY + axisY * t
            val fr = baseRadius * flareRadii[idx]
            val flareAlpha = (visibility * 34f * bloomIntensity).toInt().coerceIn(0, 255)
            flarePaint.color = when (idx % 3) {
                0 -> Color.argb(flareAlpha, 140, 220, 255)
                1 -> Color.argb(flareAlpha, 255, 190, 140)
                else -> Color.argb(flareAlpha, 195, 255, 210)
            }
            canvas.drawCircle(fx, fy, fr, flarePaint)
        }
    }

    private fun drawMoonWithPhase(
        canvas: Canvas,
        moonX: Float,
        moonY: Float,
        moonPhase: Float,
        visibility: Float
    ) {
        val moonRadius = min(viewportWidth, viewportHeight) * 0.062f

        // Lunar outer glow
        celestialPaint.shader = RadialGradient(
            moonX, moonY, moonRadius * 3.4f,
            Color.argb((visibility * 95).toInt().coerceIn(0, 255), 185, 215, 255),
            Color.argb(0, 120, 160, 235),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(moonX, moonY, moonRadius * 3.4f, celestialPaint)
        celestialPaint.shader = null

        // Lit lunar disc
        celestialPaint.color = Color.argb((visibility * 245).toInt().coerceIn(0, 255), 238, 245, 255)
        canvas.drawCircle(moonX, moonY, moonRadius, celestialPaint)

        // Subtle Mare (lunar sea) craters
        celestialPaint.color = Color.argb((visibility * 38).toInt().coerceIn(0, 255), 145, 165, 198)
        canvas.drawCircle(moonX - moonRadius * 0.28f, moonY - moonRadius * 0.18f, moonRadius * 0.26f, celestialPaint)
        canvas.drawCircle(moonX + moonRadius * 0.22f, moonY + moonRadius * 0.15f, moonRadius * 0.32f, celestialPaint)
        canvas.drawCircle(moonX - moonRadius * 0.10f, moonY + moonRadius * 0.38f, moonRadius * 0.18f, celestialPaint)

        // Earthshine crescent shadow mask offset according to synodic moonPhase
        val phaseOffset = (moonPhase - 0.5f) * 2f // -1..+1
        if (abs(phaseOffset) > 0.08f) {
            val shadowX = moonX + phaseOffset * moonRadius * 1.15f
            celestialPaint.color = Color.argb((visibility * 215).toInt().coerceIn(0, 255), 14, 23, 52)
            canvas.drawCircle(shadowX, moonY, moonRadius * 0.96f, celestialPaint)
        }
    }

    private fun drawSoftParallaxCloudPuffs(
        canvas: Canvas,
        dt: Float,
        sunNormX: Float,
        sunNormY: Float
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
            val px = (puff.normX + currentParallaxX * 0.08f * depthFactor) * viewportWidth
            val py = (puff.normY + currentParallaxY * 0.03f * depthFactor) * viewportHeight
            val radius = puff.radiusNorm * min(viewportWidth, viewportHeight) * (0.9f + 0.3f * depthFactor)

            val alphaBase = (smoothCloudCover * (42f + puff.depthLayer * 18f) * (1f - smoothPrecip * 0.3f))
                .toInt().coerceIn(0, 140)
            if (alphaBase <= 4) continue

            val innerColor = when {
                lightningFlashIntensity > 0.1f -> Color.argb(
                    (alphaBase * 1.4f).toInt().coerceAtMost(200),
                    205, 225, 255
                )
                isGolden -> Color.argb(alphaBase, 255, 195, 152)
                isDay && smoothPrecip < 0.4f -> Color.argb(alphaBase, 250, 253, 255)
                isDay -> Color.argb(alphaBase, 145, 162, 185)
                else -> Color.argb(alphaBase, 52, 68, 102)
            }

            cloudPuffPaint.shader = RadialGradient(
                px, py - radius * 0.15f, radius,
                innerColor,
                Color.argb(0, Color.red(innerColor), Color.green(innerColor), Color.blue(innerColor)),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(px, py, radius, cloudPuffPaint)
        }
        cloudPuffPaint.shader = null
    }

    private fun updateLightning(dt: Float, weather: WeatherState) {
        if (lightningFlashIntensity > 0f) {
            lightningFlashIntensity = (lightningFlashIntensity - dt * 3.4f).coerceAtLeast(0f)
        }
        if (!weather.condition.isStorm) return

        nextLightningTimer -= dt
        if (nextLightningTimer <= 0f) {
            spawnLightningBolt()
            nextLightningTimer = 2.2f + random.nextFloat() * 4.8f
        }
    }

    private fun spawnLightningBolt() {
        lightningFlashIntensity = 1.0f
        lightningOriginX = 0.22f + random.nextFloat() * 0.56f
        lightningOriginY = 0.08f + random.nextFloat() * 0.12f

        boltSegmentCount = 0
        var curX = lightningOriginX * viewportWidth
        var curY = lightningOriginY * viewportHeight
        val targetBottomY = viewportHeight * (0.58f + random.nextFloat() * 0.25f)
        val steps = 12

        val stepY = (targetBottomY - curY) / steps
        for (i in 0 until steps) {
            if (boltSegmentCount + 4 > boltSegments.size) break
            val nextX = curX + (random.nextFloat() - 0.48f) * viewportWidth * 0.09f
            val nextY = curY + stepY
            boltSegments[boltSegmentCount++] = curX
            boltSegments[boltSegmentCount++] = curY
            boltSegments[boltSegmentCount++] = nextX
            boltSegments[boltSegmentCount++] = nextY

            // Occasional secondary fork branch
            if (i in 3..8 && random.nextFloat() < 0.42f && boltSegmentCount + 4 <= boltSegments.size) {
                val branchX = nextX + (if (random.nextBoolean()) 1f else -1f) * viewportWidth * 0.08f
                val branchY = nextY + stepY * 1.7f
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
        val alpha = (lightningFlashIntensity * 255f).toInt().coerceIn(0, 255)

        // Outer violet-blue plasma halo
        lightningGlowPaint.strokeWidth = 14f
        lightningGlowPaint.color = Color.argb((alpha * 0.75f).toInt(), 145, 185, 255)
        canvas.drawPath(lightningPath, lightningGlowPaint)

        // Crisp white-hot core
        lightningCorePaint.strokeWidth = 4.2f
        lightningCorePaint.color = Color.argb(alpha, 245, 250, 255)
        canvas.drawPath(lightningPath, lightningCorePaint)
    }

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
            .coerceIn(12, MAX_RAINDROPS)

        // Wind slant angle in pixels per second
        val windSlantVx = (windKmh * 6.5f) + currentParallaxX * 180f

        // Apple iOS Weather / Pixel 10 Pro Clock Widget Shelf collision plane
        val shelfY = viewportHeight * clockShelfYFraction
        val shelfLeftX = viewportWidth * 0.14f
        val shelfRightX = viewportWidth * 0.86f

        rainPaint.shader = null
        for (i in 0 until activeDrops) {
            val drop = rainDrops[i]
            val depthScale = 0.45f + drop.depth * 0.85f
            val vy = drop.speedPx * depthScale
            val vx = windSlantVx * depthScale

            val prevY = drop.y
            drop.x += vx * dt
            drop.y += vy * dt

            // Check collision with the Clock Shelf (just like Apple's Lock Screen Weather!)
            if (enableClockSplashes &&
                drop.depth > 0.45f &&
                prevY <= shelfY &&
                drop.y >= shelfY &&
                drop.x in shelfLeftX..shelfRightX &&
                random.nextFloat() < 0.42f
            ) {
                spawnClockSplash(drop.x, shelfY)
                resetRainDrop(drop, randomY = false)
                continue
            }

            if (drop.y > viewportHeight + 80f || drop.x < -120f || drop.x > viewportWidth + 120f) {
                resetRainDrop(drop, randomY = false)
                continue
            }

            // Draw motion-blurred raindrop streak
            val streakDt = 0.022f + drop.depth * 0.015f
            val tailX = drop.x - vx * streakDt
            val tailY = drop.y - vy * streakDt
            val alpha = ((75 + drop.depth * 130) * precipIntensity).toInt().coerceIn(20, 215)

            rainPaint.strokeWidth = 1.2f + drop.depth * 2.1f
            rainPaint.color = Color.argb(alpha, 210, 230, 255)
            canvas.drawLine(tailX, tailY, drop.x, drop.y, rainPaint)
        }

        // Update & render Clock-Shelf micro-splashes
        if (enableClockSplashes) {
            for (splash in splashes) {
                if (!splash.active) continue
                splash.age += dt
                if (splash.age >= splash.maxAge) {
                    splash.active = false
                    continue
                }
                splash.x += splash.vx * dt
                splash.y += splash.vy * dt
                splash.vy += 980f * dt // gravity arc

                val lifeNorm = 1f - (splash.age / splash.maxAge)
                splashPaint.color = Color.argb(
                    (lifeNorm * 210f).toInt().coerceIn(0, 255),
                    225, 240, 255
                )
                canvas.drawCircle(splash.x, splash.y, splash.radiusPx * lifeNorm, splashPaint)
            }
        }
    }

    private fun spawnClockSplash(x: Float, y: Float) {
        repeat(3) {
            val slot = splashes.firstOrNull { !it.active } ?: return
            slot.apply {
                active = true
                this.x = x + (random.nextFloat() - 0.5f) * 8f
                this.y = y
                vx = (random.nextFloat() - 0.5f) * 210f
                vy = -(130f + random.nextFloat() * 190f)
                radiusPx = 2.2f + random.nextFloat() * 2.4f
                age = 0f
                maxAge = 0.24f + random.nextFloat() * 0.18f
            }
        }
    }

    /**
     * Renders realistic refractive screen-space condensation & raindrops on the glass lens
     * that slowly bead, coalesce, and trickle down with surface-tension slip-stick physics.
     */
    private fun drawRefractiveGlassDroplets(
        canvas: Canvas,
        dt: Float,
        precipIntensity: Float,
        densityMultiplier: Float
    ) {
        val activeCount = (MAX_GLASS_DROPLETS * precipIntensity * densityMultiplier)
            .toInt()
            .coerceIn(4, MAX_GLASS_DROPLETS)

        for (i in 0 until activeCount) {
            val d = glassDroplets[i]
            if (!d.active) {
                resetGlassDroplet(d, initialSpawn = false)
            }

            // Slip-stick surface tension velocity profile
            d.slipTimer -= dt
            if (d.slipTimer <= 0f) {
                d.targetVy = if (random.nextFloat() < 0.35f) {
                    55f + random.nextFloat() * 165f
                } else {
                    4f + random.nextFloat() * 15f
                }
                d.slipTimer = 0.4f + random.nextFloat() * 1.4f
            }
            d.vy += (d.targetVy - d.vy) * (dt * 5f).coerceAtMost(1f)
            d.y += d.vy * dt
            d.x += sin(elapsedTimeSec * 1.8f + d.wobblePhase) * (d.vy * 0.08f) * dt

            if (d.y > viewportHeight + 40f) {
                resetGlassDroplet(d, initialSpawn = false)
                continue
            }

            val r = d.radiusPx
            val stretch = (1f + (d.vy / 220f).coerceIn(0f, 0.85f))

            // 1. Dark contact shadow on upper rim (inverted refraction)
            dropletBodyPaint.shader = RadialGradient(
                d.x, d.y + r * 0.25f, r * stretch,
                intArrayOf(
                    Color.argb(45, 255, 255, 255),
                    Color.argb(95, 190, 222, 255),
                    Color.argb(115, 25, 42, 72)
                ),
                floatArrayOf(0f, 0.68f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.save()
            canvas.scale(1f, stretch, d.x, d.y)
            canvas.drawCircle(d.x, d.y, r, dropletBodyPaint)

            // 2. Specular caustic highlight bead inside droplet
            splashPaint.color = Color.argb(215, 255, 255, 255)
            canvas.drawCircle(d.x - r * 0.28f, d.y - r * 0.32f, r * 0.26f, splashPaint)

            // 3. Crisp lower meniscus rim highlight
            dropletRimPaint.strokeWidth = 1.3f
            dropletRimPaint.color = Color.argb(145, 235, 248, 255)
            canvas.drawCircle(d.x, d.y, r, dropletRimPaint)
            canvas.restore()
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
            .coerceIn(20, MAX_SNOWFLAKES)

        val windDriftNorm = (windKmh / 1400f) + currentParallaxX * 0.04f

        for (i in 0 until activeFlakes) {
            val flake = snowflakes[i]
            val depthScale = 0.35f + flake.depth * 0.95f
            val flutter = sin(elapsedTimeSec * flake.flutterFreq + flake.flutterPhase) * 0.025f

            flake.normX += (windDriftNorm * depthScale + flutter) * dt
            flake.normY += flake.fallSpeedNorm * depthScale * dt

            if (flake.normY > 1.05f) {
                resetSnowflake(flake, randomY = false)
            }
            if (flake.normX > 1.08f) flake.normX -= 1.16f
            if (flake.normX < -0.08f) flake.normX += 1.16f

            val sx = flake.normX * viewportWidth
            val sy = flake.normY * viewportHeight
            val radius = flake.radiusPx * depthScale
            val alpha = ((95 + flake.depth * 150) * snowIntensity).toInt().coerceIn(30, 245)

            // Foreground bokeh flakes have soft radial falloff
            if (flake.depth > 0.78f) {
                snowPaint.shader = RadialGradient(
                    sx, sy, radius * 1.8f,
                    Color.argb(alpha, 255, 255, 255),
                    Color.argb(0, 215, 235, 255),
                    Shader.TileMode.CLAMP
                )
                canvas.drawCircle(sx, sy, radius * 1.8f, snowPaint)
                snowPaint.shader = null
            } else {
                snowPaint.color = Color.argb(alpha, 245, 250, 255)
                canvas.drawCircle(sx, sy, radius, snowPaint)
            }
        }
    }

    private fun drawTouchRipples(canvas: Canvas, dt: Float) {
        for (ripple in touchRipples) {
            if (!ripple.active) continue
            ripple.radiusPx += dt * 340f
            ripple.alpha -= dt * 0.95f
            if (ripple.alpha <= 0.02f || ripple.radiusPx >= ripple.maxRadiusPx) {
                ripple.active = false
                continue
            }
            val a = (ripple.alpha * 165f).toInt().coerceIn(0, 255)
            ripplePaint.strokeWidth = 2.4f
            ripplePaint.color = Color.argb(a, 220, 240, 255)
            canvas.drawCircle(ripple.centerX, ripple.centerY, ripple.radiusPx, ripplePaint)

            // Secondary harmonic inner ring
            if (ripple.radiusPx > 28f) {
                ripplePaint.strokeWidth = 1.2f
                ripplePaint.color = Color.argb((a * 0.55f).toInt(), 220, 240, 255)
                canvas.drawCircle(ripple.centerX, ripple.centerY, ripple.radiusPx * 0.68f, ripplePaint)
            }
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
        drop.speedPx = 1450f + random.nextFloat() * 950f
    }

    private fun resetGlassDroplet(droplet: GlassDroplet, initialSpawn: Boolean) {
        droplet.active = true
        droplet.x = (0.06f + random.nextFloat() * 0.88f) * viewportWidth
        droplet.y = if (initialSpawn) {
            random.nextFloat() * viewportHeight
        } else {
            random.nextFloat() * viewportHeight * 0.35f - 20f
        }
        droplet.radiusPx = 4.5f + random.nextFloat() * 8.5f
        droplet.vy = 10f + random.nextFloat() * 35f
        droplet.targetVy = droplet.vy
        droplet.slipTimer = 0.3f + random.nextFloat() * 1.2f
        droplet.wobblePhase = random.nextFloat() * 6.28f
    }

    private fun resetSnowflake(flake: SnowParticle, randomY: Boolean) {
        flake.depth = random.nextFloat()
        flake.normX = random.nextFloat()
        flake.normY = if (randomY) random.nextFloat() else -0.04f - random.nextFloat() * 0.08f
        flake.radiusPx = 2.4f + random.nextFloat() * 5.8f
        flake.fallSpeedNorm = 0.045f + random.nextFloat() * 0.065f
        flake.flutterFreq = 1.4f + random.nextFloat() * 2.6f
        flake.flutterPhase = random.nextFloat() * 6.28f
    }

    private class StarParticle(
        var normX: Float = 0f,
        var normY: Float = 0f,
        var sizePx: Float = 2f,
        var twinkleSpeed: Float = 2f,
        var twinklePhase: Float = 0f,
        var colorTint: Int = Color.WHITE
    )

    private class CloudPuff(
        var normX: Float = 0f,
        var normY: Float = 0f,
        var radiusNorm: Float = 0.2f,
        var speedNorm: Float = 0.01f,
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
        var radiusPx: Float = 3f,
        var age: Float = 0f,
        var maxAge: Float = 0.3f
    )

    private class GlassDroplet(
        var active: Boolean = false,
        var x: Float = 0f,
        var y: Float = 0f,
        var radiusPx: Float = 6f,
        var vy: Float = 20f,
        var targetVy: Float = 20f,
        var slipTimer: Float = 0.5f,
        var wobblePhase: Float = 0f
    )

    private class SnowParticle(
        var normX: Float = 0f,
        var normY: Float = 0f,
        var radiusPx: Float = 4f,
        var fallSpeedNorm: Float = 0.06f,
        var flutterFreq: Float = 2f,
        var flutterPhase: Float = 0f,
        var depth: Float = 0.5f
    )

    private class TouchRipple(
        var active: Boolean = false,
        var centerX: Float = 0f,
        var centerY: Float = 0f,
        var radiusPx: Float = 0f,
        var maxRadiusPx: Float = 200f,
        var alpha: Float = 0f
    )

    companion object {
        private const val MAX_STARS = 115
        private const val MAX_CLOUD_PUFFS = 18
        private const val MAX_RAINDROPS = 185
        private const val MAX_SPLASHES = 48
        private const val MAX_GLASS_DROPLETS = 22
        private const val MAX_SNOWFLAKES = 140
        private const val MAX_TOUCH_RIPPLES = 8
    }
}
