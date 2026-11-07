package com.antigravity.pixelweatherwallpaper.engine

import android.graphics.RuntimeShader
import android.os.Build

/**
 * Hardware-accelerated Android 13+ (API 33+) AGSL Fragment Shader for the atmospheric
 * sky dome, Rayleigh/Mie horizon scattering, volumetric multi-octave FBM clouds,
 * crepuscular sun rays, aurora borealis ribbons, and internal storm lightning illumination.
 *
 * Designed specifically for Pixel 10 Pro OLED 120Hz Smooth Display.
 */
object AtmosphericAgslShader {

    private const val SHADER_SOURCE = """
        uniform float2 uResolution;
        uniform float uTime;
        uniform float uSolarElevation;    // -1.0 (night) to +1.0 (noon)
        uniform float2 uSunPos;           // Normalized [0..1, 0..1]
        uniform float uCloudCover;        // 0.0 to 1.0
        uniform float uPrecipIntensity;   // 0.0 to 1.0
        uniform float uFogDensity;        // 0.0 to 1.0
        uniform float uLightningFlash;    // 0.0 to 1.0
        uniform float2 uLightningOrigin;  // Normalized [0..1, 0..1]
        uniform float2 uParallax;         // Gyro + launcher swipe offset [-1..1]
        uniform float uBloomIntensity;    // 0.0 to 1.5
        uniform float uIsSnow;            // 0.0 or 1.0

        // Fast deterministic 2D hash
        float hash21(float2 p) {
            p = fract(p * float2(234.34, 435.345));
            p += dot(p, p + 34.23);
            return fract(p.x * p.y);
        }

        // Smooth 2D value noise with Hermite interpolation
        float noise(float2 p) {
            float2 i = floor(p);
            float2 f = fract(p);
            float2 u = f * f * (3.0 - 2.0 * f);
            float a = hash21(i);
            float b = hash21(i + float2(1.0, 0.0));
            float c = hash21(i + float2(0.0, 1.0));
            float d = hash21(i + float2(1.0, 1.0));
            return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
        }

        // 5-octave Fractal Brownian Motion with rotational domain shift
        float fbm(float2 p) {
            float value = 0.0;
            float amp = 0.52;
            float2 shift = float2(100.0, 100.0);
            for (int i = 0; i < 5; i++) {
                value += amp * noise(p);
                // Rotate octave slightly to eliminate grid artifacts
                p = float2(p.x * 1.6 - p.y * 1.2, p.x * 1.2 + p.y * 1.6) + shift;
                amp *= 0.48;
            }
            return value;
        }

        half4 main(float2 fragCoord) {
            float2 uv = fragCoord / max(uResolution, float2(1.0, 1.0));
            float aspect = uResolution.x / max(uResolution.y, 1.0);

            // Apply subtle 3D parallax to sky dome coordinates
            float2 skyUv = uv + uParallax * float2(0.035, 0.022);
            float y = clamp(skyUv.y, 0.0, 1.0);

            // --- 1. Apple Weather Sky Palette Interpolation ---
            // Night palette (deep indigo to midnight navy)
            float3 nightZenith = float3(0.024, 0.039, 0.110);
            float3 nightMid    = float3(0.055, 0.090, 0.215);
            float3 nightHoriz  = float3(0.110, 0.165, 0.320);

            // Golden hour / Twilight palette (cobalt zenith -> rose -> warm amber horizon)
            float3 goldenZenith = float3(0.130, 0.220, 0.480);
            float3 goldenMid    = float3(0.580, 0.330, 0.490);
            float3 goldenHoriz  = float3(0.965, 0.560, 0.310);

            // Clear Cupertino Day palette (cerulean zenith -> azure mid -> crisp cyan-white horizon)
            float3 dayZenith = float3(0.095, 0.385, 0.785);
            float3 dayMid    = float3(0.255, 0.580, 0.915);
            float3 dayHoriz  = float3(0.640, 0.835, 0.975);

            // Storm / Overcast slate palette
            float3 stormZenith = float3(0.095, 0.125, 0.185);
            float3 stormMid    = float3(0.185, 0.230, 0.305);
            float3 stormHoriz  = float3(0.310, 0.365, 0.435);

            // Blend based on solar elevation (-1..1)
            float nightFactor = smoothstep(0.08, -0.28, uSolarElevation);
            float goldenFactor = 1.0 - clamp(abs(uSolarElevation - 0.06) / 0.32, 0.0, 1.0);
            float dayFactor = smoothstep(-0.05, 0.38, uSolarElevation);

            float3 clearZenith = mix(nightZenith, dayZenith, dayFactor);
            float3 clearMid    = mix(nightMid, dayMid, dayFactor);
            float3 clearHoriz  = mix(nightHoriz, dayHoriz, dayFactor);

            clearZenith = mix(clearZenith, goldenZenith, goldenFactor * 0.85);
            clearMid    = mix(clearMid, goldenMid, goldenFactor * 0.90);
            clearHoriz  = mix(clearHoriz, goldenHoriz, goldenFactor * 0.95);

            // Desaturate and darken under heavy cloud/precipitation
            float overcastWeight = clamp(uCloudCover * 0.65 + uPrecipIntensity * 0.55, 0.0, 0.94);
            // At night, keep storm sky darker
            float stormDarken = mix(1.0, 0.35, nightFactor);
            float3 zenithCol = mix(clearZenith, stormZenith * stormDarken, overcastWeight);
            float3 midCol    = mix(clearMid, stormMid * stormDarken, overcastWeight);
            float3 horizCol  = mix(clearHoriz, stormHoriz * stormDarken, overcastWeight);

            // Smooth vertical sky gradient with Rayleigh curve
            float3 skyColor = mix(zenithCol, midCol, smoothstep(0.0, 0.58, y));
            skyColor = mix(skyColor, horizCol, smoothstep(0.45, 1.0, y));

            // --- 2. Solar / Lunar Mie Scattering & Crepuscular God-Rays ---
            float2 sunDelta = (uv - uSunPos) * float2(aspect, 1.0);
            float sunDist = length(sunDelta);

            if (uSolarElevation > -0.15) {
                float sunVis = (1.0 - overcastWeight * 0.82) * smoothstep(-0.15, 0.10, uSolarElevation);
                float3 sunGlowCol = mix(float3(1.0, 0.52, 0.22), float3(1.0, 0.96, 0.86), dayFactor);

                // Exponential Mie forward scattering
                float mieHalo = exp(-sunDist * 3.8) * 0.58 + exp(-sunDist * 11.0) * 0.45;
                // Crepuscular radial light shafts modulated by angular noise
                float angle = atan(sunDelta.y, sunDelta.x);
                float rayNoise = noise(float2(angle * 4.5 + uTime * 0.08, sunDist * 2.0 - uTime * 0.12));
                float crepuscular = exp(-sunDist * 2.2) * smoothstep(0.35, 0.85, rayNoise) * 0.32 * uCloudCover;

                skyColor += sunGlowCol * (mieHalo + crepuscular) * sunVis * uBloomIntensity;
            } else {
                // Subtle nocturnal aurora / high-altitude airglow ribbon on clear nights
                float auroraMask = smoothstep(0.48, 0.12, y) * smoothstep(0.02, 0.18, y) * (1.0 - overcastWeight);
                float auroraWave = fbm(float2(skyUv.x * 2.6 + uTime * 0.03, skyUv.y * 4.0 - uTime * 0.02));
                float3 auroraCol = mix(float3(0.08, 0.75, 0.58), float3(0.36, 0.25, 0.85), skyUv.x);
                skyColor += auroraCol * pow(auroraWave, 3.0) * auroraMask * 0.24 * uBloomIntensity;
            }

            // --- 3. Multi-Layered Volumetric Cumulus & Stratus Deck (3D Parallax) ---
            if (uCloudCover > 0.02) {
                // Upper altitude cirrus / altocumulus layer
                float2 cloudUvFar = float2((uv.x + uParallax.x * 0.06) * aspect * 1.45 + uTime * 0.011, uv.y * 2.8 - 0.15);
                float nFar = fbm(cloudUvFar);
                float farThreshold = mix(0.68, 0.24, uCloudCover);
                float farDensity = smoothstep(farThreshold, farThreshold + 0.28, nFar) * smoothstep(0.82, 0.18, y);

                // Lower altitude hero cumulus deck (moves faster with parallax & wind)
                float2 cloudUvNear = float2((uv.x + uParallax.x * 0.12) * aspect * 2.15 - uTime * 0.022, (uv.y + uParallax.y * 0.04) * 3.4 - 0.30);
                float nNear = fbm(cloudUvNear);
                float nearThreshold = mix(0.64, 0.20, uCloudCover);
                float nearDensity = smoothstep(nearThreshold, nearThreshold + 0.24, nNear) * smoothstep(0.90, 0.08, y);

                // Vertical gradient inside cloud for self-shadowing (top lit, bottom dark)
                float nLit = fbm(cloudUvNear - normalize(uSunPos - uv + 0.001) * 0.09);
                float rimLight = clamp((nNear - nLit) * 3.2, 0.0, 1.0);

                float3 cloudLitColor = mix(
                    mix(float3(0.22, 0.28, 0.42), float3(0.98, 0.99, 1.0), dayFactor),
                    float3(1.0, 0.68, 0.46),
                    goldenFactor * 0.85
                );
                float3 cloudShadowColor = mix(
                    float3(0.07, 0.10, 0.18),
                    mix(float3(0.52, 0.62, 0.74), float3(0.22, 0.26, 0.34), uPrecipIntensity),
                    dayFactor
                );

                // Silver lining scattering near the sun
                float silverLining = exp(-sunDist * 3.5) * rimLight * (1.0 - nightFactor * 0.7) * 0.65;
                float3 finalCloudCol = mix(cloudShadowColor, cloudLitColor, clamp(0.35 + rimLight * 0.65, 0.0, 1.0)) + silverLining;

                // Internal cloud lightning flash illumination
                if (uLightningFlash > 0.01) {
                    float flashDist = length((uv - uLightningOrigin) * float2(aspect, 1.0));
                    float cloudGlow = exp(-flashDist * 2.6) * uLightningFlash;
                    finalCloudCol += float3(0.72, 0.84, 1.0) * cloudGlow * 1.65;
                }

                skyColor = mix(skyColor, finalCloudCol, clamp(farDensity * 0.45 + nearDensity * 0.78, 0.0, 0.92));
            }

            // --- 4. Rolling Atmospheric Fog & Frost Vignette ---
            if (uFogDensity > 0.01) {
                float2 fogUv = float2(uv.x * aspect * 1.8 + uTime * 0.035, uv.y * 2.5);
                float fogNoise = fbm(fogUv);
                float fogMask = smoothstep(0.15, 0.95, y) * (0.55 + 0.45 * fogNoise) * uFogDensity;
                float3 fogCol = mix(horizCol, float3(0.82, 0.86, 0.90), 0.35 * dayFactor);
                skyColor = mix(skyColor, fogCol, clamp(fogMask * 0.82, 0.0, 0.88));
            }

            if (uIsSnow > 0.5) {
                // Subtle crystalline frost vignette around screen edges
                float2 edgeDist = abs(uv - 0.5) * 2.0;
                float edgeMask = smoothstep(0.72, 1.05, max(edgeDist.x, edgeDist.y));
                float frostPattern = fbm(uv * float2(aspect * 9.0, 9.0));
                skyColor += float3(0.80, 0.90, 1.0) * edgeMask * frostPattern * 0.22;
            }

            // Global ambient sheet-lightning flash
            if (uLightningFlash > 0.01) {
                skyColor += float3(0.55, 0.68, 0.95) * uLightningFlash * 0.28;
            }

            // Subtle filmic dithering to prevent 8-bit OLED banding in smooth twilight gradients
            float dither = (hash21(fragCoord + fract(uTime)) - 0.5) / 255.0;
            skyColor = clamp(skyColor + dither, 0.0, 1.0);

            return half4(half3(skyColor), 1.0);
        }
    """

    fun createOrNull(): RuntimeShader? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { RuntimeShader(SHADER_SOURCE) }.getOrNull()
        } else {
            null
        }
    }
}
