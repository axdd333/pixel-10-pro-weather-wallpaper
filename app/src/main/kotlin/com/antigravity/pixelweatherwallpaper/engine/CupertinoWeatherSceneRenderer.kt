package com.antigravity.pixelweatherwallpaper.engine

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import com.antigravity.pixelweatherwallpaper.model.AstronomicalStarCatalog
import com.antigravity.pixelweatherwallpaper.model.WallpaperPreferences
import com.antigravity.pixelweatherwallpaper.model.WeatherCondition
import com.antigravity.pixelweatherwallpaper.model.WeatherState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Ultra-fast 120Hz Cupertino Weather scene renderer engineered for zero GC allocations,
 * zero CPU BlurMaskFilters, and zero per-frame Shader allocations on the Pixel 10 Pro.
 */
class CupertinoWeatherSceneRenderer {

    private val random = Random(System.nanoTime())
    private val runtimeShader: RuntimeShader? = AtmosphericAgslShader.createOrNull()

    // Pre-allocated Paints (zero per-frame shader or BlurMaskFilter allocations)
    private val shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fallbackSkyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
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

    // Cached J2000 astronomical sky projection
    private var cachedStarCatalogLat = Double.NaN
    private var cachedStarCatalogLon = Double.NaN
    private var cachedStarCatalogBucket = -1L
    private var cachedProjectedStars: List<AstronomicalStarCatalog.ProjectedStar> = emptyList()

    // Pre-allocated particle pools
    private val rainDrops = Array(MAX_RAINDROPS) { RainParticle() }
    private val splashes = Array(MAX_SPLASHES) { SplashParticle() }
    private val glassDroplets = Array(MAX_GLASS_DROPLETS) { GlassDroplet() }
    private val snowflakes = Array(MAX_SNOWFLAKES) { SnowParticle() }

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
    private val boltSegments = FloatArray(80)
    private var boltSegmentCount = 0

    init {
        initializePools()
    }

    private fun initializePools() {
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
        // Gently displace nearby glass droplets or snowflakes without drawing any circles on screen
        val repelRadius = viewportWidth * 0.14f
        for (droplet in glassDroplets) {
            if (!droplet.active) continue
            val dx = droplet.x - x
            val dy = droplet.y - y
            val dist = hypot(dx, dy)
            if (dist in 1f..repelRadius) {
                val push = (1f - dist / repelRadius) * 18f
                droplet.x += (dx / dist) * push
                droplet.vy += 80f
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

        // 2. Render Sky Dome, Solar Corona, 3D Spherical Moon & Volumetric AGSL Clouds in a single GPU pass
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

        // 4. Render Branching Lightning Bolt Geometry
        if (lightningFlashIntensity > 0.04f && boltSegmentCount > 0) {
            drawLightningBoltGeometry(canvas)
        }

        // 5. Render Precipitation: Silky Rain + Clock-Shelf Splashes + Teardrop Glass Droplets OR Snow
        val densityMult = preferences.particleDensityMultiplier.coerceIn(0.4f, 1.6f)
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
        val bucket = nowEpochMs / 60_000L
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

            val distToMoon = hypot(sx - moonX, sy - moonY)
            if (distToMoon < moonExclusionRadius) continue

            val horizonFactor = (sy / (viewportHeight * 0.72f)).coerceIn(0.2f, 1.0f)
            val wave = sin(elapsedTimeSec * (2.1f + horizonFactor * 1.6f) + cStar.scintillationSeed)
            val scintillation = 1.0f - (0.12f + 0.16f * horizonFactor) * (0.5f - 0.5f * wave)

            val starAlpha = (nightVisibility * cStar.baseAlpha * scintillation * 245f)
                .toInt()
                .coerceIn(0, 245)
            if (starAlpha <= 5) continue

            if (cStar.isFirstMagnitude) {
                starPaint.color = cStar.colorRgb
                starPaint.alpha = (starAlpha * 0.18f).toInt()
                canvas.drawCircle(sx, sy, cStar.radiusPx * 2.3f, starPaint)
            }

            starPaint.color = cStar.colorRgb
            starPaint.alpha = starAlpha
            canvas.drawCircle(sx, sy, cStar.radiusPx, starPaint)

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
            nextShootingStarTimer = 10.0f + random.nextFloat() * 14.0f
        }

        if (shootingStarActive) {
            shootingStarLife += dt
            val maxLife = 0.44f
            if (shootingStarLife >= maxLife) {
                shootingStarActive = false
            } else {
                shootingStarX += shootingStarVx * dt
                shootingStarY += shootingStarVy * dt
                val progress = shootingStarLife / maxLife
                val envAlpha = (sin(progress * PI.toFloat()) * nightVisibility * 195f).toInt().coerceIn(0, 195)
                val tailLen = 0.062f
                val tailX = shootingStarX - shootingStarVx * tailLen
                val tailY = shootingStarY - shootingStarVy * tailLen

                rainPaint.strokeWidth = 1.5f
                rainPaint.color = Color.argb(envAlpha, 235, 245, 255)
                canvas.drawLine(shootingStarX, shootingStarY, tailX, tailY, rainPaint)
            }
        }
    }

    private fun updateLightning(dt: Float, weather: WeatherState) {
        if (lightningFlashIntensity > 0f) {
            lightningFlashIntensity = (lightningFlashIntensity - dt * 4.2f).coerceAtLeast(0f)
        }
        if (lightningRestrikePending) {
            lightningRestrikeTimer -= dt
            if (lightningRestrikeTimer <= 0f) {
                lightningRestrikePending = false
                lightningFlashIntensity = 0.78f
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
        val steps = 13

        val stepY = (targetBottomY - curY) / steps
        for (i in 0 until steps) {
            if (boltSegmentCount + 4 > boltSegments.size) break
            val nextX = curX + (random.nextFloat() - 0.48f) * viewportWidth * 0.065f
            val nextY = curY + stepY
            boltSegments[boltSegmentCount++] = curX
            boltSegments[boltSegmentCount++] = curY
            boltSegments[boltSegmentCount++] = nextX
            boltSegments[boltSegmentCount++] = nextY

            if (i in 3..9 && random.nextFloat() < 0.35f && boltSegmentCount + 4 <= boltSegments.size) {
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

        // Hardware-friendly layered stroke glow (zero BlurMaskFilter CPU stall)
        lightningGlowPaint.strokeWidth = 11f
        lightningGlowPaint.color = Color.argb((alpha * 0.24f).toInt(), 145, 190, 255)
        canvas.drawPath(lightningPath, lightningGlowPaint)

        lightningGlowPaint.strokeWidth = 5.5f
        lightningGlowPaint.color = Color.argb((alpha * 0.45f).toInt(), 185, 215, 255)
        canvas.drawPath(lightningPath, lightningGlowPaint)

        lightningCorePaint.strokeWidth = 2.0f
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
            .coerceIn(14, MAX_RAINDROPS)

        val windSlantVx = (windKmh * 5.2f) + currentParallaxX * 140f

        val shelfY = viewportHeight * clockShelfYFraction
        val shelfLeftX = viewportWidth * 0.16f
        val shelfRightX = viewportWidth * 0.84f

        for (i in 0 until activeDrops) {
            val drop = rainDrops[i]
            val depthScale = 0.42f + drop.depth * 0.88f
            val vy = drop.speedPx * depthScale
            val vx = windSlantVx * depthScale

            val prevY = drop.y
            drop.x += vx * dt
            drop.y += vy * dt

            if (enableClockSplashes &&
                drop.depth > 0.52f &&
                prevY <= shelfY &&
                drop.y >= shelfY &&
                drop.x in shelfLeftX..shelfRightX &&
                random.nextFloat() < 0.35f
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

    private fun drawRefractiveGlassDroplets(
        canvas: Canvas,
        dt: Float,
        precipIntensity: Float,
        densityMultiplier: Float
    ) {
        val activeCount = (MAX_GLASS_DROPLETS * precipIntensity * densityMultiplier)
            .toInt()
            .coerceIn(3, MAX_GLASS_DROPLETS)

        dropletBodyPaint.color = Color.argb(38, 200, 228, 255)
        dropletRimPaint.strokeWidth = 0.85f
        dropletRimPaint.color = Color.argb(95, 235, 248, 255)
        starPaint.color = Color.argb(175, 255, 255, 255)

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

            canvas.drawPath(dropletPath, dropletBodyPaint)
            canvas.drawPath(dropletPath, dropletRimPaint)
            canvas.drawCircle(d.x - r * 0.26f, d.y - r * 0.22f, r * 0.20f, starPaint)
        }
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
            val alpha = ((65 + flake.depth * 140) * snowIntensity).toInt().coerceIn(20, 215)

            // Fast concentric two-pass circle for soft bokeh without allocating RadialGradient per flake
            if (flake.depth > 0.7f) {
                snowPaint.color = Color.argb((alpha * 0.32f).toInt(), 245, 250, 255)
                canvas.drawCircle(sx, sy, radius * 1.65f, snowPaint)
            }
            snowPaint.color = Color.argb(alpha, 250, 253, 255)
            canvas.drawCircle(sx, sy, radius, snowPaint)
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
        droplet.radiusPx = 3.2f + random.nextFloat() * 4.8f
        droplet.vy = 6f + random.nextFloat() * 24f
        droplet.targetVy = droplet.vy
        droplet.slipTimer = 0.4f + random.nextFloat() * 1.4f
        droplet.wobblePhase = random.nextFloat() * 6.28f
    }

    private fun resetSnowflake(flake: SnowParticle, randomY: Boolean) {
        flake.depth = random.nextFloat()
        flake.normX = random.nextFloat()
        flake.normY = if (randomY) random.nextFloat() else -0.04f - random.nextFloat() * 0.08f
        flake.radiusPx = 1.8f + random.nextFloat() * 3.8f
        flake.fallSpeedNorm = 0.042f + random.nextFloat() * 0.058f
        flake.flutterFreq = 1.3f + random.nextFloat() * 2.2f
        flake.flutterPhase = random.nextFloat() * 6.28f
    }

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
        var radiusPx: Float = 3.2f,
        var fallSpeedNorm: Float = 0.055f,
        var flutterFreq: Float = 1.8f,
        var flutterPhase: Float = 0f,
        var depth: Float = 0.5f
    )

    companion object {
        private const val MAX_RAINDROPS = 145
        private const val MAX_SPLASHES = 36
        private const val MAX_GLASS_DROPLETS = 12
        private const val MAX_SNOWFLAKES = 110
    }
}
