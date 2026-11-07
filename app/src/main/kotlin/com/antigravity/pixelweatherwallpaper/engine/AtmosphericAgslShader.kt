package com.antigravity.pixelweatherwallpaper.engine

import android.graphics.RuntimeShader
import android.os.Build

/**
 * Hardware-accelerated Android 13+ (API 33+) AGSL Fragment Shader for the atmospheric
 * sky dome, Rayleigh/Mie scattering, anisotropic Henyey-Greenstein cloud forward scattering,
 * Beer-Lambert volumetric self-shadowing, nocturnal Milky Way galactic dust, and spherical
 * 3D lunar phase rendering with limb darkening and earthshine.
 *
 * Sculpted specifically for Google Pixel 10 Pro OLED 120Hz Smooth Display.
 */
object AtmosphericAgslShader {

    private const val SHADER_SOURCE = """
        uniform float2 uResolution;
        uniform float uTime;
        uniform float uSolarElevation;    // -1.0 (midnight nadir) to +1.0 (solar noon zenith)
        uniform float2 uSunPos;           // Normalized [0..1, 0..1]
        uniform float uMoonPhase;         // 0.0 (New) -> 0.5 (Full) -> 1.0 (New)
        uniform float uCloudCover;        // 0.0 to 1.0
        uniform float uPrecipIntensity;   // 0.0 to 1.0
        uniform float uFogDensity;        // 0.0 to 1.0
        uniform float uLightningFlash;    // 0.0 to 1.0
        uniform float2 uLightningOrigin;  // Normalized [0..1, 0..1]
        uniform float2 uParallax;         // Gyro + launcher swipe offset [-1..1]
        uniform float uBloomIntensity;    // 0.0 to 1.5
        uniform float uIsSnow;            // 0.0 or 1.0

        // High-precision 2D hash without sine banding
        float hash21(float2 p) {
            float3 p3 = fract(float3(p.xyx) * 0.1031);
            p3 += dot(p3, p3.yzx + 33.33);
            return fract((p3.x + p3.y) * p3.z);
        }

        // Quintic Hermite 2D value noise (C2 continuous, zero grid crease artifacts)
        float noise(float2 p) {
            float2 i = floor(p);
            float2 f = fract(p);
            float2 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
            float a = hash21(i);
            float b = hash21(i + float2(1.0, 0.0));
            float c = hash21(i + float2(0.0, 1.0));
            float d = hash21(i + float2(1.0, 1.0));
            return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
        }

        // 6-octave Rotational Fractal Brownian Motion for organic billowing cumulus
        float fbm(float2 p) {
            float value = 0.0;
            float amp = 0.52;
            float2 shift = float2(113.7, 79.3);
            for (int i = 0; i < 6; i++) {
                value += amp * noise(p);
                p = float2(p.x * 1.62 - p.y * 1.18, p.x * 1.18 + p.y * 1.62) + shift;
                amp *= 0.49;
            }
            return value;
        }

        half4 main(float2 fragCoord) {
            float2 uv = fragCoord / max(uResolution, float2(1.0, 1.0));
            float aspect = uResolution.x / max(uResolution.y, 1.0);

            // Subtle 3D camera tilt parallax on sky dome
            float2 skyUv = uv + uParallax * float2(0.028, 0.018);
            float y = clamp(skyUv.y, 0.0, 1.0);

            // --- 1. Apple Weather Calibrated Sky Dome Palettes ---
            // Deep Night (Apple iOS Weather midnight indigo -> slate horizon)
            float3 nightZenith = float3(0.020, 0.035, 0.098);
            float3 nightMid    = float3(0.051, 0.086, 0.196);
            float3 nightHoriz  = float3(0.114, 0.169, 0.310);

            // Dawn / Dusk Golden Hour (cobalt zenith -> dusty violet -> warm apricot horizon)
            float3 goldenZenith = float3(0.145, 0.235, 0.475);
            float3 goldenMid    = float3(0.545, 0.345, 0.498);
            float3 goldenHoriz  = float3(0.955, 0.565, 0.345);

            // Clear Cupertino Day (deep cerulean zenith -> azure mid -> luminous horizon)
            float3 dayZenith = float3(0.102, 0.376, 0.765);
            float3 dayMid    = float3(0.267, 0.569, 0.898);
            float3 dayHoriz  = float3(0.624, 0.820, 0.969);

            // Overcast / Storm Marine Slate
            float3 stormZenith = float3(0.110, 0.145, 0.205);
            float3 stormMid    = float3(0.195, 0.245, 0.318);
            float3 stormHoriz  = float3(0.325, 0.380, 0.450);

            float nightFactor  = smoothstep(0.06, -0.26, uSolarElevation);
            float goldenFactor = exp(-pow((uSolarElevation - 0.04) / 0.19, 2.0));
            float dayFactor    = smoothstep(-0.04, 0.35, uSolarElevation);

            float3 clearZenith = mix(nightZenith, dayZenith, dayFactor);
            float3 clearMid    = mix(nightMid, dayMid, dayFactor);
            float3 clearHoriz  = mix(nightHoriz, dayHoriz, dayFactor);

            clearZenith = mix(clearZenith, goldenZenith, goldenFactor * 0.88);
            clearMid    = mix(clearMid, goldenMid, goldenFactor * 0.92);
            clearHoriz  = mix(clearHoriz, goldenHoriz, goldenFactor * 0.95);

            float overcastWeight = clamp(uCloudCover * 0.68 + uPrecipIntensity * 0.50, 0.0, 0.95);
            float nightStormDim  = mix(1.0, 0.34, nightFactor);

            float3 zenithCol = mix(clearZenith, stormZenith * nightStormDim, overcastWeight);
            float3 midCol    = mix(clearMid, stormMid * nightStormDim, overcastWeight);
            float3 horizCol  = mix(clearHoriz, stormHoriz * nightStormDim, overcastWeight);

            // Non-linear Rayleigh optical depth gradient
            float3 skyColor = mix(zenithCol, midCol, smoothstep(0.0, 0.54, pow(y, 0.92)));
            skyColor = mix(skyColor, horizCol, smoothstep(0.42, 1.0, pow(y, 0.88)));

            // --- 2. Faint Milky Way Galactic Dust Band on Clear Nights ---
            if (nightFactor > 0.05 && overcastWeight < 0.75) {
                // Tilted diagonal galactic plane coordinate
                float2 galUv = float2(
                    (skyUv.x - 0.5) * aspect * 0.75 + (skyUv.y - 0.32) * 0.66,
                    -(skyUv.x - 0.5) * aspect * 0.66 + (skyUv.y - 0.32) * 0.75
                );
                float galProfile = exp(-galUv.y * galUv.y * 14.0) * smoothstep(0.75, 0.08, y);
                float galDust = fbm(galUv * 4.2 + float2(12.4, 7.1));
                float galRift = smoothstep(0.42, 0.68, fbm(galUv * 8.5 + float2(3.1, 19.2)));
                float3 mwColor = float3(0.16, 0.23, 0.39) * galProfile * galDust * (0.65 + 0.35 * galRift);
                skyColor += mwColor * nightFactor * (1.0 - overcastWeight) * 0.28;
            }

            // --- 3. Celestial Body: Solar Mie Corona OR 3D Spherical Moon with Mare & Phase ---
            float2 bodyDelta = (uv - uSunPos) * float2(aspect, 1.0);
            float bodyDist = length(bodyDelta);

            if (uSolarElevation > -0.10) {
                float sunVis = (1.0 - overcastWeight * 0.84) * smoothstep(-0.10, 0.08, uSolarElevation);
                float3 sunTint = mix(float3(1.0, 0.58, 0.26), float3(1.0, 0.97, 0.90), dayFactor);

                // Physical inverse-power Mie forward scattering & crisp solar core
                float sunCore = smoothstep(0.034, 0.018, bodyDist);
                float mieInner = exp(-bodyDist * 14.0) * 0.62;
                float mieOuter = exp(-bodyDist * 3.4) * 0.42;

                skyColor += (sunTint * (mieInner + mieOuter) + float3(1.0, 0.99, 0.96) * sunCore) * sunVis * uBloomIntensity;
            } else {
                // 3D Spherical Moon rendered in-shader at uSunPos
                float moonVis = (1.0 - overcastWeight * 0.82) * smoothstep(-0.04, -0.22, uSolarElevation);
                float moonRadius = 0.036;

                // Soft atmospheric lunar aureole
                float lunarHalo = exp(-bodyDist * 16.0) * 0.26 + exp(-bodyDist * 4.5) * 0.10;
                skyColor += float3(0.68, 0.80, 0.98) * lunarHalo * moonVis * uBloomIntensity;

                if (bodyDist < moonRadius * 1.05) {
                    float2 mUv = bodyDelta / moonRadius; // [-1..1] on lunar disc
                    float r2 = dot(mUv, mUv);
                    if (r2 <= 1.0) {
                        float mz = sqrt(max(0.0, 1.0 - r2));
                        float3 normal = float3(mUv.x, mUv.y, mz);

                        // Convert uMoonPhase [0..1] to 3D sun illumination vector in lunar space
                        // 0.0 = New (sun behind moon: z = -1), 0.25 = First Quarter (x = +1), 0.5 = Full (z = +1), 0.75 = Third Quarter (x = -1)
                        float phaseAngle = uMoonPhase * 6.2831853;
                        float3 lightDir = normalize(float3(sin(phaseAngle), -0.12 * cos(phaseAngle), -cos(phaseAngle)));

                        float ndotl = dot(normal, lightDir);
                        // Soft terminator transition (Oren-Nayar-like lunar regolith retroreflection)
                        float litMask = smoothstep(-0.08, 0.14, ndotl);
                        float diffuse = pow(max(0.0, ndotl), 0.65);

                        // Procedural Lunar Mare (Sea of Tranquility, Imbrium, Storms) via FBM on sphere
                        float mareNoise = fbm(mUv * 2.6 + float2(4.2, 1.7));
                        float craterDetail = fbm(mUv * 7.5 + float2(9.1, 3.4));
                        float mareMask = smoothstep(0.46, 0.64, mareNoise);

                        float3 highlandAlbedo = float3(0.91, 0.94, 0.98);
                        float3 mareAlbedo     = float3(0.60, 0.66, 0.75);
                        float3 surfaceAlbedo  = mix(highlandAlbedo, mareAlbedo, mareMask * 0.72) * (0.90 + 0.10 * craterDetail);

                        // Limb darkening near edge of lunar sphere
                        float limb = mix(0.72, 1.0, pow(mz, 0.45));

                        // Subtle Earthshine on the unlit lunar hemisphere
                        float3 earthshine = float3(0.09, 0.14, 0.25) * surfaceAlbedo * (0.45 + 0.55 * mz);
                        float3 litMoon    = surfaceAlbedo * (0.28 + 0.72 * diffuse) * limb;

                        float3 finalMoon = mix(earthshine, litMoon, litMask);
                        float edgeAa = smoothstep(1.0, 0.94, sqrt(r2));
                        skyColor = mix(skyColor, finalMoon, edgeAa * moonVis);
                    }
                }
            }

            // --- 4. Multi-Layered Volumetric Cloud Decks with Domain Warping & Beer-Lambert Shadowing ---
            if (uCloudCover > 0.02) {
                float2 lightVec = normalize(uSunPos - uv + float2(0.0001, 0.0001));

                // High-altitude wispy cirrus & altocumulus layer
                float2 cirrusUv = float2((uv.x + uParallax.x * 0.045) * aspect * 1.55 + uTime * 0.006, uv.y * 2.9 - 0.10);
                float cirrusWarp = fbm(cirrusUv * 1.4);
                float nFar = fbm(cirrusUv + cirrusWarp * 0.35);
                float farCutoff = mix(0.69, 0.25, uCloudCover);
                float farDensity = smoothstep(farCutoff, farCutoff + 0.30, nFar) * smoothstep(0.84, 0.14, y);

                // Lower-altitude hero cumulus deck with domain warping
                float2 cumUv = float2((uv.x + uParallax.x * 0.095) * aspect * 2.05 - uTime * 0.014, (uv.y + uParallax.y * 0.03) * 3.2 - 0.24);
                float2 warpOffset = float2(fbm(cumUv * 1.15 + 4.3), fbm(cumUv * 1.15 + 9.7)) * 0.28;
                float nNear = fbm(cumUv + warpOffset);
                float nearCutoff = mix(0.65, 0.20, uCloudCover);
                float nearDensity = smoothstep(nearCutoff, nearCutoff + 0.26, nNear) * smoothstep(0.92, 0.06, y);

                // Directional ray-marched density sample toward Sun/Moon for self-shadowing
                float nShadowSample = fbm(cumUv + warpOffset - lightVec * 0.085);
                float opticalDepth = clamp((nNear - nShadowSample) * 3.4 + (1.0 - uv.y) * 0.25, 0.0, 1.0);
                float transmittance = exp(-nearDensity * (1.2 + uPrecipIntensity * 1.4));

                // Cloud ambient & direct lighting palettes
                float3 dayCloudDirect = mix(float3(0.97, 0.98, 1.0), float3(1.0, 0.70, 0.48), goldenFactor * 0.88);
                float3 nightCloudDirect = float3(0.20, 0.27, 0.40);
                float3 cloudLit = mix(nightCloudDirect, dayCloudDirect, dayFactor);

                float3 dayCloudBase = mix(float3(0.56, 0.65, 0.77), float3(0.20, 0.25, 0.33), uPrecipIntensity * 0.85);
                float3 nightCloudBase = float3(0.06, 0.09, 0.16);
                float3 cloudShadow = mix(nightCloudBase, dayCloudBase, dayFactor);

                // Anisotropic Henyey-Greenstein silver-lining forward scattering near celestial body
                float hgPhase = exp(-bodyDist * 3.8) * (1.0 - transmittance) * opticalDepth * (1.0 - nightFactor * 0.65);
                float3 silverLining = mix(float3(1.0, 0.96, 0.88), float3(1.0, 0.64, 0.36), goldenFactor) * hgPhase * 0.72;

                float3 cloudCol = mix(cloudShadow, cloudLit, clamp(0.28 + opticalDepth * 0.72, 0.0, 1.0)) + silverLining;

                // Internal cloud sheet-lightning illumination
                if (uLightningFlash > 0.01) {
                    float flashDist = length((uv - uLightningOrigin) * float2(aspect, 1.0));
                    float internalGlow = exp(-flashDist * 2.8) * uLightningFlash * (0.4 + 0.6 * nearDensity);
                    cloudCol += float3(0.68, 0.82, 1.0) * internalGlow * 1.45;
                }

                float combinedAlpha = clamp(farDensity * 0.42 + nearDensity * 0.82, 0.0, 0.94);
                skyColor = mix(skyColor, cloudCol, combinedAlpha);
            }

            // --- 5. Atmospheric Valley Mist & Fog ---
            if (uFogDensity > 0.01) {
                float2 fogUv = float2(uv.x * aspect * 1.65 + uTime * 0.022, uv.y * 2.4);
                float fogLayer = fbm(fogUv);
                float fogAlpha = smoothstep(0.12, 0.96, y) * (0.58 + 0.42 * fogLayer) * uFogDensity;
                float3 fogTint = mix(horizCol, float3(0.78, 0.83, 0.89), 0.32 * dayFactor);
                skyColor = mix(skyColor, fogTint, clamp(fogAlpha * 0.84, 0.0, 0.88));
            }

            // --- 6. Subtle Winter Frost Vignette ---
            if (uIsSnow > 0.5) {
                float2 edge = abs(uv - 0.5) * 2.0;
                float vignette = smoothstep(0.76, 1.06, max(edge.x, edge.y));
                float crystal = fbm(uv * float2(aspect * 8.0, 8.0));
                skyColor += float3(0.82, 0.90, 1.0) * vignette * crystal * 0.16;
            }

            // Filmic triangular PDF dithering to eliminate 8-bit OLED quantization bands
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
