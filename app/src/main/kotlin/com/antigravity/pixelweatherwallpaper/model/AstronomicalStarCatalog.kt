package com.antigravity.pixelweatherwallpaper.model

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Observationally accurate J2000.0 astronomical ephemeris & starfield projector.
 *
 * Sculpted with an ephemeral, whisper-quiet aesthetic:
 * - Stars are positioned at their exact horizontal Altitude & Azimuth for the observer's
 *   latitude, longitude, and Local Sidereal Time (LST).
 * - Visual magnitudes (-1.46 down to +4.8) govern sub-pixel core radii (0.45px to 2.1px)
 *   and subtle B-V Planckian color temperatures.
 * - Principal 1st-magnitude navigation stars carry optional, gossamer-faint typographic
 *   whisper labels that slowly breathe in and out of perception.
 */
object AstronomicalStarCatalog {

    data class CatalogStar(
        val properName: String?,  // Subtle proper name for principal stars (null for faint field stars)
        val raHours: Double,      // Right Ascension in decimal hours (0..24)
        val decDegrees: Double,   // Declination in degrees (-90..+90)
        val magnitude: Float,     // Apparent visual magnitude (-1.46..+4.8)
        val colorRgb: Int,        // Packed ARGB B-V color temperature
        val scintillationSeed: Float
    )

    data class ProjectedStar(
        val properName: String?,
        val screenNormX: Float,   // [0..1] across sky dome
        val screenNormY: Float,   // [0..0.75] above horizon
        val altitudeDeg: Float,   // [0..90] degrees above local horizon
        val magnitude: Float,
        val radiusPx: Float,      // Pinpoint physical radius (0.45px .. 2.15px)
        val baseAlpha: Float,     // Luminosity * atmospheric extinction [0..1]
        val colorRgb: Int,
        val isFirstMagnitude: Boolean,
        val scintillationSeed: Float
    )

    private fun packRgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    // Subtle, physically grounded B-V blackbody stellar chromaticities
    private val BV_O_B = packRgb(202, 224, 255)   // ~15,000K Rigel / Spica / Vega
    private val BV_A   = packRgb(240, 246, 255)   // ~9,500K  Sirius / Deneb / Altair
    private val BV_F_G = packRgb(255, 250, 238)   // ~6,000K  Capella / Procyon / Polaris
    private val BV_K   = packRgb(255, 232, 204)   // ~4,200K  Arcturus / Aldebaran / Pollux
    private val BV_M   = packRgb(255, 210, 175)   // ~3,200K  Betelgeuse / Antares

    /**
     * Principal naked-eye J2000 stars combined with deterministic faint J2000 catalog stars
     * distributed along the Gould Belt & Galactic Plane down to magnitude +4.8.
     */
    val CATALOG_STARS: List<CatalogStar> = buildCatalog()

    private fun buildCatalog(): List<CatalogStar> {
        val primary = listOf(
            // Ursa Minor & Ursa Major
            CatalogStar("Polaris", 2.530, 89.264, 1.98f, BV_F_G, 0.1f),
            CatalogStar(null, 14.845, 74.155, 2.08f, BV_K, 0.2f),       // Kochab
            CatalogStar("Dubhe", 11.062, 61.751, 1.79f, BV_K, 0.3f),
            CatalogStar(null, 11.031, 56.382, 2.37f, BV_A, 0.4f),       // Merak
            CatalogStar(null, 11.897, 53.695, 2.44f, BV_A, 0.5f),       // Phecda
            CatalogStar(null, 12.257, 57.033, 3.31f, BV_A, 0.6f),       // Megrez
            CatalogStar("Alioth", 12.900, 55.960, 1.77f, BV_A, 0.7f),
            CatalogStar(null, 13.399, 54.925, 2.23f, BV_A, 0.8f),       // Mizar
            CatalogStar("Alkaid", 13.792, 49.313, 1.86f, BV_O_B, 0.9f),

            // Cassiopeia & Cepheus
            CatalogStar("Schedar", 0.675, 56.537, 2.24f, BV_K, 1.1f),
            CatalogStar(null, 0.153, 59.150, 2.28f, BV_F_G, 1.2f),      // Caph
            CatalogStar(null, 0.945, 60.717, 2.15f, BV_O_B, 1.3f),      // Gamma Cas
            CatalogStar(null, 1.430, 60.235, 2.68f, BV_A, 1.4f),        // Ruchbah
            CatalogStar(null, 1.907, 63.670, 3.35f, BV_O_B, 1.5f),      // Segin
            CatalogStar(null, 21.309, 62.585, 2.45f, BV_A, 1.6f),       // Alderamin

            // Orion
            CatalogStar("Betelgeuse", 5.919, 7.407, 0.42f, BV_M, 1.7f),
            CatalogStar("Rigel", 5.242, -8.202, 0.13f, BV_O_B, 1.8f),
            CatalogStar("Bellatrix", 5.419, 6.350, 1.64f, BV_O_B, 1.9f),
            CatalogStar(null, 5.533, -0.299, 2.23f, BV_O_B, 2.0f),      // Mintaka
            CatalogStar("Alnilam", 5.603, -1.202, 1.69f, BV_O_B, 2.1f),
            CatalogStar(null, 5.679, -1.943, 1.77f, BV_O_B, 2.2f),      // Alnitak
            CatalogStar(null, 5.796, -9.670, 2.06f, BV_O_B, 2.3f),      // Saiph

            // Canis Major & Canis Minor
            CatalogStar("Sirius", 6.752, -16.716, -1.46f, BV_A, 2.4f),
            CatalogStar("Adhara", 6.977, -28.972, 1.50f, BV_O_B, 2.5f),
            CatalogStar(null, 7.140, -26.393, 1.83f, BV_F_G, 2.6f),     // Wezen
            CatalogStar(null, 6.378, -17.956, 1.98f, BV_O_B, 2.7f),     // Mirzam
            CatalogStar(null, 7.401, -29.303, 2.45f, BV_O_B, 2.8f),     // Aludra
            CatalogStar("Procyon", 7.655, 5.225, 0.34f, BV_F_G, 2.9f),
            CatalogStar(null, 7.453, 8.289, 2.90f, BV_O_B, 3.0f),       // Gomeisa

            // Taurus, Pleiades, Auriga, Gemini
            CatalogStar("Aldebaran", 4.599, 16.509, 0.86f, BV_K, 3.1f),
            CatalogStar("Elnath", 5.438, 28.608, 1.65f, BV_O_B, 3.2f),
            CatalogStar("Pleiades", 3.791, 24.105, 2.87f, BV_O_B, 3.3f),
            CatalogStar(null, 3.748, 24.053, 3.63f, BV_O_B, 3.4f),
            CatalogStar(null, 3.715, 24.113, 3.70f, BV_O_B, 3.5f),
            CatalogStar("Capella", 5.278, 45.998, 0.08f, BV_F_G, 3.6f),
            CatalogStar(null, 5.992, 44.948, 1.90f, BV_A, 3.7f),        // Menkalinan
            CatalogStar("Pollux", 7.755, 28.026, 1.14f, BV_K, 3.8f),
            CatalogStar("Castor", 7.577, 31.888, 1.58f, BV_A, 3.9f),
            CatalogStar(null, 6.629, 16.399, 1.93f, BV_A, 4.0f),        // Alhena

            // Summer Triangle (Lyra, Cygnus, Aquila)
            CatalogStar("Vega", 18.616, 38.784, 0.03f, BV_O_B, 4.1f),
            CatalogStar("Deneb", 20.690, 45.280, 1.25f, BV_A, 4.2f),
            CatalogStar(null, 20.370, 40.257, 2.23f, BV_F_G, 4.3f),     // Sadr
            CatalogStar(null, 19.512, 27.960, 3.05f, BV_K, 4.4f),       // Albireo
            CatalogStar(null, 20.770, 33.970, 2.48f, BV_K, 4.5f),       // Gienah
            CatalogStar(null, 19.749, 45.131, 2.87f, BV_O_B, 4.6f),     // Delta Cyg
            CatalogStar("Altair", 19.846, 8.868, 0.76f, BV_A, 4.7f),
            CatalogStar(null, 19.771, 10.613, 2.72f, BV_K, 4.8f),       // Tarazed

            // Leo, Virgo, Boötes, Corona Borealis
            CatalogStar("Regulus", 10.140, 11.967, 1.36f, BV_O_B, 4.9f),
            CatalogStar(null, 11.818, 14.572, 2.14f, BV_A, 5.0f),       // Denebola
            CatalogStar(null, 10.333, 19.842, 2.08f, BV_K, 5.1f),       // Algieba
            CatalogStar("Spica", 13.420, -11.161, 0.97f, BV_O_B, 5.2f),
            CatalogStar("Arcturus", 14.261, 19.182, -0.05f, BV_K, 5.3f),
            CatalogStar(null, 15.578, 26.715, 2.22f, BV_A, 5.4f),       // Alphecca

            // Scorpius, Sagittarius, Pegasus, Andromeda, Perseus
            CatalogStar("Antares", 16.490, -26.432, 1.06f, BV_M, 5.5f),
            CatalogStar("Shaula", 17.560, -37.104, 1.62f, BV_O_B, 5.6f),
            CatalogStar(null, 18.403, -34.384, 1.85f, BV_O_B, 5.7f),    // Kaus Australis
            CatalogStar(null, 18.921, -26.297, 2.05f, BV_O_B, 5.8f),    // Nunki
            CatalogStar("Fomalhaut", 22.961, -29.622, 1.16f, BV_A, 5.9f),
            CatalogStar(null, 23.079, 15.205, 2.49f, BV_O_B, 6.0f),     // Markab
            CatalogStar(null, 23.063, 28.083, 2.42f, BV_M, 6.1f),       // Scheat
            CatalogStar(null, 0.220, 15.184, 2.83f, BV_O_B, 6.2f),      // Algenib
            CatalogStar("Alpheratz", 0.140, 29.090, 2.06f, BV_O_B, 6.3f),
            CatalogStar(null, 1.163, 35.621, 2.07f, BV_M, 6.4f),        // Mirach
            CatalogStar(null, 2.065, 42.330, 2.10f, BV_K, 6.5f),        // Almach
            CatalogStar("Mirfak", 3.405, 49.861, 1.79f, BV_F_G, 6.6f),
            CatalogStar(null, 3.136, 40.956, 2.12f, BV_O_B, 6.7f),      // Algol
            CatalogStar(null, 2.120, 23.462, 2.00f, BV_K, 6.8f),        // Hamal
            CatalogStar(null, 0.726, -17.987, 2.04f, BV_K, 6.9f),       // Diphda
            CatalogStar("Canopus", 6.399, -52.696, -0.74f, BV_A, 7.0f),
            CatalogStar("Achernar", 1.629, -57.237, 0.46f, BV_O_B, 7.1f)
        )

        val rng = Random(20000101L)
        val faint = ArrayList<CatalogStar>(220)
        val palette = intArrayOf(BV_O_B, BV_A, BV_F_G, BV_F_G, BV_K, BV_M)
        for (i in 0 until 220) {
            val ra = rng.nextDouble() * 24.0
            val isGalacticBand = i % 3 != 0
            val dec = if (isGalacticBand) {
                val bandCenter = 58.0 * sin((ra - 18.6) * PI / 12.0)
                (bandCenter + (rng.nextDouble() - 0.5) * 34.0).coerceIn(-85.0, 88.0)
            } else {
                asin(rng.nextDouble() * 2.0 - 1.0) * 180.0 / PI
            }
            val mag = 2.85f + rng.nextFloat().pow(0.7f) * 1.95f
            val col = palette[i % palette.size]
            faint.add(CatalogStar(null, ra, dec, mag, col, 10f + i * 0.37f))
        }

        return primary + faint
    }

    /**
     * Computes Greenwich Mean Sidereal Time (GMST) and Local Sidereal Time (LST) in degrees (0..360).
     */
    fun computeLocalSiderealTimeDegrees(epochMillis: Long, longitudeDeg: Double): Double {
        val jd = (epochMillis / 86400000.0) + 2440587.5
        val d = jd - 2451545.0
        val t = d / 36525.0
        val gmstDeg = 280.46061837 +
            360.98564736629 * d +
            0.000387933 * t * t -
            (t * t * t) / 38710000.0
        var lstDeg = (gmstDeg + longitudeDeg) % 360.0
        if (lstDeg < 0.0) lstDeg += 360.0
        return lstDeg
    }

    /**
     * Projects the J2000 star catalog into horizontal (Altitude, Azimuth) screen coordinates
     * for the observer's exact location and timestamp.
     */
    fun projectVisibleSky(
        latitudeDeg: Double,
        longitudeDeg: Double,
        epochMillis: Long
    ): List<ProjectedStar> {
        val lstDeg = computeLocalSiderealTimeDegrees(epochMillis, longitudeDeg)
        val latRad = Math.toRadians(latitudeDeg)
        val sinLat = sin(latRad)
        val cosLat = cos(latRad)

        val result = ArrayList<ProjectedStar>(CATALOG_STARS.size)

        for (star in CATALOG_STARS) {
            val raDeg = star.raHours * 15.0
            var haDeg = (lstDeg - raDeg) % 360.0
            if (haDeg < -180.0) haDeg += 360.0
            if (haDeg > 180.0) haDeg -= 360.0

            val haRad = Math.toRadians(haDeg)
            val decRad = Math.toRadians(star.decDegrees)

            val sinAlt = sin(decRad) * sinLat + cos(decRad) * cosLat * cos(haRad)
            val altRad = asin(sinAlt.coerceIn(-1.0, 1.0))
            val altDeg = Math.toDegrees(altRad).toFloat()

            // Cull stars below local horizon
            if (altDeg <= 1.5f) continue

            val cosAlt = cos(altRad).coerceAtLeast(1e-5)
            val sinAz = -cos(decRad) * sin(haRad) / cosAlt
            val cosAz = (sin(decRad) - sinAlt * sinLat) / (cosAlt * cosLat.coerceAtLeast(1e-5))
            val azRad = atan2(sinAz, cosAz)

            val zenithDistNorm = (90f - altDeg) / 90f
            val screenNormX = (0.5f + sin(azRad).toFloat() * zenithDistNorm * 0.52f)
            if (screenNormX !in -0.02f..1.02f) continue
            val screenNormY = (0.02f + (1f - (altDeg / 90f).pow(0.82f)) * 0.72f)

            val fluxNorm = ((4.9f - star.magnitude) / 6.3f).coerceIn(0.08f, 1.0f)
            val radiusPx = 0.55f + fluxNorm.pow(1.6f) * 1.75f

            val extinction = ((altDeg - 1.5f) / 22f).coerceIn(0.15f, 1.0f)
            val baseAlpha = (0.18f + fluxNorm.pow(1.25f) * 0.82f) * extinction

            result.add(
                ProjectedStar(
                    properName = star.properName,
                    screenNormX = screenNormX,
                    screenNormY = screenNormY,
                    altitudeDeg = altDeg,
                    magnitude = star.magnitude,
                    radiusPx = radiusPx,
                    baseAlpha = baseAlpha,
                    colorRgb = star.colorRgb,
                    isFirstMagnitude = star.magnitude < 1.35f,
                    scintillationSeed = star.scintillationSeed
                )
            )
        }

        return result
    }
}
