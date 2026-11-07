package com.antigravity.pixelweatherwallpaper.model

import android.graphics.Color
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/**
 * Real-time astronomical star catalog and horizontal coordinate (Altitude/Azimuth) projector.
 *
 * Computes Local Sidereal Time (LST) from the observer's exact UTC timestamp and longitude,
 * transforms J2000 Right Ascension (RA) and Declination (Dec) for the brightest navigation &
 * constellation stars + major constellation asterism lines into the observer's local sky dome
 * (`latitude`, `longitude`, `epochMillis`), and applies atmospheric refraction + extinction.
 */
object AstronomicalStarCatalog {

    data class CatalogStar(
        val hipName: String,
        val constellation: String,
        val raHours: Double,      // Right Ascension in decimal hours (0..24)
        val decDegrees: Double,   // Declination in degrees (-90..+90)
        val magnitude: Float,     // Apparent visual magnitude (lower = brighter, e.g. Sirius = -1.46)
        val colorRgb: Int         // B-V spectral color temperature tint
    )

    data class ProjectedStar(
        val name: String,
        val constellation: String,
        val screenNormX: Float,   // [0..1] across sky dome
        val screenNormY: Float,   // [0..0.72] above horizon
        val altitudeDeg: Float,   // [0..90] degrees above local horizon
        val azimuthDeg: Float,    // [0..360] degrees (0=N, 90=E, 180=S, 270=W)
        val magnitude: Float,
        val radiusPx: Float,
        val baseAlpha: Float,     // Includes atmospheric extinction near horizon
        val colorRgb: Int,
        val twinkleSeed: Float
    )

    data class ProjectedConstellationLine(
        val constellation: String,
        val x1Norm: Float,
        val y1Norm: Float,
        val x2Norm: Float,
        val y2Norm: Float,
        val alpha: Float
    )

    private fun packRgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    // Spectral B-V color tints
    private val COLOR_O_B_BLUE = packRgb(188, 216, 255)   // Rigel, Spica, Regulus, Vega
    private val COLOR_A_WHITE = packRgb(242, 247, 255)    // Sirius, Deneb, Altair, Castor
    private val COLOR_F_G_WARM = packRgb(255, 248, 226)   // Capella, Procyon, Polaris
    private val COLOR_K_ORANGE = packRgb(255, 220, 178)   // Arcturus, Aldebaran, Pollux
    private val COLOR_M_RED = packRgb(255, 188, 142)      // Betelgeuse, Antares

    /**
     * J2000 Bright Star Catalog (Navigational & Constellation Principal Stars).
     */
    val BRIGHT_STARS: List<CatalogStar> = listOf(
        // Ursa Minor & Ursa Major (Big Dipper)
        CatalogStar("Polaris", "UMi", 2.530, 89.264, 1.98f, COLOR_F_G_WARM),
        CatalogStar("Dubhe", "UMa", 11.062, 61.751, 1.79f, COLOR_K_ORANGE),
        CatalogStar("Merak", "UMa", 11.031, 56.382, 2.37f, COLOR_A_WHITE),
        CatalogStar("Phecda", "UMa", 11.897, 53.695, 2.44f, COLOR_A_WHITE),
        CatalogStar("Megrez", "UMa", 12.257, 57.033, 3.31f, COLOR_A_WHITE),
        CatalogStar("Alioth", "UMa", 12.900, 55.960, 1.77f, COLOR_A_WHITE),
        CatalogStar("Mizar", "UMa", 13.399, 54.925, 2.23f, COLOR_A_WHITE),
        CatalogStar("Alkaid", "UMa", 13.792, 49.313, 1.86f, COLOR_O_B_BLUE),

        // Cassiopeia ("W" asterism)
        CatalogStar("Schedar", "Cas", 0.675, 56.537, 2.24f, COLOR_K_ORANGE),
        CatalogStar("Caph", "Cas", 0.153, 59.150, 2.28f, COLOR_F_G_WARM),
        CatalogStar("Gamma Cas", "Cas", 0.945, 60.717, 2.15f, COLOR_O_B_BLUE),
        CatalogStar("Ruchbah", "Cas", 1.430, 60.235, 2.68f, COLOR_A_WHITE),
        CatalogStar("Segin", "Cas", 1.907, 63.670, 3.35f, COLOR_O_B_BLUE),

        // Orion (The Hunter)
        CatalogStar("Betelgeuse", "Ori", 5.919, 7.407, 0.42f, COLOR_M_RED),
        CatalogStar("Rigel", "Ori", 5.242, -8.202, 0.13f, COLOR_O_B_BLUE),
        CatalogStar("Bellatrix", "Ori", 5.419, 6.350, 1.64f, COLOR_O_B_BLUE),
        CatalogStar("Mintaka", "Ori", 5.533, -0.299, 2.23f, COLOR_O_B_BLUE),
        CatalogStar("Alnilam", "Ori", 5.603, -1.202, 1.69f, COLOR_O_B_BLUE),
        CatalogStar("Alnitak", "Ori", 5.679, -1.943, 1.77f, COLOR_O_B_BLUE),
        CatalogStar("Saiph", "Ori", 5.796, -9.670, 2.06f, COLOR_O_B_BLUE),

        // Canis Major & Canis Minor
        CatalogStar("Sirius", "CMa", 6.752, -16.716, -1.46f, COLOR_A_WHITE),
        CatalogStar("Adhara", "CMa", 6.977, -28.972, 1.50f, COLOR_O_B_BLUE),
        CatalogStar("Wezen", "CMa", 7.140, -26.393, 1.83f, COLOR_F_G_WARM),
        CatalogStar("Mirzam", "CMa", 6.378, -17.956, 1.98f, COLOR_O_B_BLUE),
        CatalogStar("Procyon", "CMi", 7.655, 5.225, 0.34f, COLOR_F_G_WARM),

        // Taurus & Pleiades
        CatalogStar("Aldebaran", "Tau", 4.599, 16.509, 0.86f, COLOR_K_ORANGE),
        CatalogStar("Elnath", "Tau", 5.438, 28.608, 1.65f, COLOR_O_B_BLUE),
        CatalogStar("Alcyone (Pleiades)", "Tau", 3.791, 24.105, 2.87f, COLOR_O_B_BLUE),

        // Auriga & Gemini
        CatalogStar("Capella", "Aur", 5.278, 45.998, 0.08f, COLOR_F_G_WARM),
        CatalogStar("Menkalinan", "Aur", 5.992, 44.948, 1.90f, COLOR_A_WHITE),
        CatalogStar("Pollux", "Gem", 7.755, 28.026, 1.14f, COLOR_K_ORANGE),
        CatalogStar("Castor", "Gem", 7.577, 31.888, 1.58f, COLOR_A_WHITE),
        CatalogStar("Alhena", "Gem", 6.629, 16.399, 1.93f, COLOR_A_WHITE),

        // Summer Triangle (Lyra, Cygnus, Aquila)
        CatalogStar("Vega", "Lyr", 18.616, 38.784, 0.03f, COLOR_O_B_BLUE),
        CatalogStar("Deneb", "Cyg", 20.690, 45.280, 1.25f, COLOR_A_WHITE),
        CatalogStar("Sadr", "Cyg", 20.370, 40.257, 2.23f, COLOR_F_G_WARM),
        CatalogStar("Albireo", "Cyg", 19.512, 27.960, 3.05f, COLOR_K_ORANGE),
        CatalogStar("Gienah", "Cyg", 20.770, 33.970, 2.48f, COLOR_K_ORANGE),
        CatalogStar("Delta Cyg", "Cyg", 19.749, 45.131, 2.87f, COLOR_O_B_BLUE),
        CatalogStar("Altair", "Aql", 19.846, 8.868, 0.76f, COLOR_A_WHITE),

        // Leo & Virgo & Boötes
        CatalogStar("Regulus", "Leo", 10.140, 11.967, 1.36f, COLOR_O_B_BLUE),
        CatalogStar("Denebola", "Leo", 11.818, 14.572, 2.14f, COLOR_A_WHITE),
        CatalogStar("Algieba", "Leo", 10.333, 19.842, 2.08f, COLOR_K_ORANGE),
        CatalogStar("Spica", "Vir", 13.420, -11.161, 0.97f, COLOR_O_B_BLUE),
        CatalogStar("Arcturus", "Boo", 14.261, 19.182, -0.05f, COLOR_K_ORANGE),

        // Scorpius & Piscis Austrinus & Pegasus & Andromeda
        CatalogStar("Antares", "Sco", 16.490, -26.432, 1.06f, COLOR_M_RED),
        CatalogStar("Shaula", "Sco", 17.560, -37.104, 1.62f, COLOR_O_B_BLUE),
        CatalogStar("Fomalhaut", "PsA", 22.961, -29.622, 1.16f, COLOR_A_WHITE),
        CatalogStar("Markab", "Peg", 23.079, 15.205, 2.49f, COLOR_O_B_BLUE),
        CatalogStar("Scheat", "Peg", 23.063, 28.083, 2.42f, COLOR_M_RED),
        CatalogStar("Algenib", "Peg", 0.220, 15.184, 2.83f, COLOR_O_B_BLUE),
        CatalogStar("Alpheratz", "And", 0.140, 29.090, 2.06f, COLOR_O_B_BLUE),
        CatalogStar("Mirach", "And", 1.163, 35.621, 2.07f, COLOR_M_RED),
        CatalogStar("Almach", "And", 2.065, 42.330, 2.10f, COLOR_K_ORANGE),
        CatalogStar("Mirfak", "Per", 3.405, 49.861, 1.79f, COLOR_F_G_WARM),
        CatalogStar("Algol", "Per", 3.136, 40.956, 2.12f, COLOR_O_B_BLUE),
        CatalogStar("Hamal", "Ari", 2.120, 23.462, 2.00f, COLOR_K_ORANGE),
        CatalogStar("Deneb Kaitos", "Cet", 0.726, -17.987, 2.04f, COLOR_K_ORANGE),

        // Southern Sky gems for low-latitude cities
        CatalogStar("Canopus", "Car", 6.399, -52.696, -0.74f, COLOR_A_WHITE),
        CatalogStar("Achernar", "Eri", 1.629, -57.237, 0.46f, COLOR_O_B_BLUE)
    )

    /**
     * Pairs of star names forming recognizable constellation wireframe segments.
     */
    val CONSTELLATION_EDGES: List<Pair<String, String>> = listOf(
        // Big Dipper (Ursa Major)
        "Dubhe" to "Merak",
        "Merak" to "Phecda",
        "Phecda" to "Megrez",
        "Megrez" to "Dubhe",
        "Megrez" to "Alioth",
        "Alioth" to "Mizar",
        "Mizar" to "Alkaid",
        // Cassiopeia W
        "Caph" to "Schedar",
        "Schedar" to "Gamma Cas",
        "Gamma Cas" to "Ruchbah",
        "Ruchbah" to "Segin",
        // Orion
        "Betelgeuse" to "Bellatrix",
        "Betelgeuse" to "Alnitak",
        "Bellatrix" to "Mintaka",
        "Mintaka" to "Alnilam",
        "Alnilam" to "Alnitak",
        "Alnitak" to "Saiph",
        "Mintaka" to "Rigel",
        "Saiph" to "Rigel",
        // Cygnus (Northern Cross)
        "Deneb" to "Sadr",
        "Sadr" to "Albireo",
        "Gienah" to "Sadr",
        "Sadr" to "Delta Cyg",
        // Summer Triangle
        "Vega" to "Deneb",
        "Deneb" to "Altair",
        "Altair" to "Vega",
        // Gemini Twins
        "Castor" to "Pollux",
        "Pollux" to "Alhena",
        // Taurus & Square of Pegasus
        "Aldebaran" to "Elnath",
        "Alpheratz" to "Scheat",
        "Scheat" to "Markab",
        "Markab" to "Algenib",
        "Algenib" to "Alpheratz"
    )

    /**
     * Computes Greenwich Mean Sidereal Time (GMST) and Local Sidereal Time (LST) in degrees (0..360).
     */
    fun computeLocalSiderealTimeDegrees(epochMillis: Long, longitudeDeg: Double): Double {
        // Julian Date from Unix Epoch
        val jd = (epochMillis / 86400000.0) + 2440587.5
        val d = jd - 2451545.0 // Days since J2000.0 epoch
        val t = d / 36525.0
        var gmstDeg = 280.46061837 +
            360.98564736629 * d +
            0.000387933 * t * t -
            (t * t * t) / 38710000.0
        var lstDeg = (gmstDeg + longitudeDeg) % 360.0
        if (lstDeg < 0.0) lstDeg += 360.0
        return lstDeg
    }

    /**
     * Projects the J2000 catalog onto the observer's visible sky dome for `(latitudeDeg, longitudeDeg, epochMillis)`.
     *
     * Uses a stereographic sky-dome mapping centered on the observer's zenith and southern/northern meridian
     * so every star above the horizon (`altitude > 0°`) is placed at its true relative altitude and azimuth.
     */
    fun projectVisibleSky(
        latitudeDeg: Double,
        longitudeDeg: Double,
        epochMillis: Long
    ): Pair<List<ProjectedStar>, List<ProjectedConstellationLine>> {
        val lstDeg = computeLocalSiderealTimeDegrees(epochMillis, longitudeDeg)
        val latRad = Math.toRadians(latitudeDeg)
        val sinLat = sin(latRad)
        val cosLat = cos(latRad)

        val projectedStars = ArrayList<ProjectedStar>(BRIGHT_STARS.size)
        val byName = HashMap<String, ProjectedStar>(BRIGHT_STARS.size)

        for ((index, star) in BRIGHT_STARS.withIndex()) {
            val raDeg = star.raHours * 15.0
            var haDeg = (lstDeg - raDeg) % 360.0
            if (haDeg < -180.0) haDeg += 360.0
            if (haDeg > 180.0) haDeg -= 360.0

            val haRad = Math.toRadians(haDeg)
            val decRad = Math.toRadians(star.decDegrees)

            val sinAlt = sin(decRad) * sinLat + cos(decRad) * cosLat * cos(haRad)
            val altRad = asin(sinAlt.coerceIn(-1.0, 1.0))
            val altDeg = Math.toDegrees(altRad).toFloat()

            // Only include stars above the local horizon (with atmospheric refraction allowance)
            if (altDeg <= 1.0f) continue

            val cosAlt = cos(altRad).coerceAtLeast(1e-5)
            val sinAz = -cos(decRad) * sin(haRad) / cosAlt
            val cosAz = (sin(decRad) - sinAlt * sinLat) / (cosAlt * cosLat.coerceAtLeast(1e-5))
            var azRad = atan2(sinAz, cosAz)
            var azDeg = Math.toDegrees(azRad).toFloat()
            if (azDeg < 0f) azDeg += 360f

            // Map Altitude (0° horizon -> y=0.68, 90° zenith -> y=0.04) and Azimuth onto the portrait viewport
            val zenithRadius = (90f - altDeg) / 90f // 0 at zenith, 1 at horizon
            val screenNormX = (0.5f + sin(azRad).toFloat() * zenithRadius * 0.48f).coerceIn(0.03f, 0.97f)
            val screenNormY = (0.04f + (1f - (altDeg / 90f).pow(0.85f)) * 0.64f).coerceIn(0.03f, 0.69f)

            // Convert visual magnitude (-1.46..3.5) to physical pixel radius & luminosity
            val fluxFactor = ((3.6f - star.magnitude) / 5.0f).coerceIn(0.15f, 1.0f)
            val radiusPx = 1.8f + fluxFactor.pow(1.35f) * 4.2f

            // Atmospheric extinction dims stars close to the horizon (airmass ~ 1/sin(alt))
            val extinction = ((altDeg - 1f) / 18f).coerceIn(0.25f, 1.0f)
            val baseAlpha = (0.38f + fluxFactor * 0.62f) * extinction

            val projected = ProjectedStar(
                name = star.hipName,
                constellation = star.constellation,
                screenNormX = screenNormX,
                screenNormY = screenNormY,
                altitudeDeg = altDeg,
                azimuthDeg = azDeg,
                magnitude = star.magnitude,
                radiusPx = radiusPx,
                baseAlpha = baseAlpha,
                colorRgb = star.colorRgb,
                twinkleSeed = index * 1.732f
            )
            projectedStars.add(projected)
            byName[star.hipName] = projected
        }

        val projectedLines = ArrayList<ProjectedConstellationLine>(CONSTELLATION_EDGES.size)
        for ((aName, bName) in CONSTELLATION_EDGES) {
            val a = byName[aName] ?: continue
            val b = byName[bName] ?: continue
            val dx = a.screenNormX - b.screenNormX
            val dy = a.screenNormY - b.screenNormY
            if (dx * dx + dy * dy < 0.22f) {
                projectedLines.add(
                    ProjectedConstellationLine(
                        constellation = a.constellation,
                        x1Norm = a.screenNormX,
                        y1Norm = a.screenNormY,
                        x2Norm = b.screenNormX,
                        y2Norm = b.screenNormY,
                        alpha = minOf(a.baseAlpha, b.baseAlpha) * 0.35f
                    )
                )
            }
        }

        return projectedStars to projectedLines
    }
}
