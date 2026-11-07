# Cupertino Weather 120Hz Live Wallpaper for Google Pixel 10 Pro

An ultra-detailed, 120Hz hardware-accelerated Android Live Wallpaper (`WallpaperService`) and interactive Weather Studio engineered for the **Google Pixel 10 Pro** (LTPO OLED Smooth Display, Android 13–16+), meticulously recreating and extending Apple's iOS 17/18 Live Weather Wallpapers.

---

## Highlights & Architectural Details

### 1. Hardware-Accelerated AGSL Atmospheric & Volumetric Cloud Shader
* **5-Octave Fractal Brownian Motion (`AtmosphericAgslShader.kt`)**: Runs directly on the Pixel 10 Pro GPU via Android 13+ `RuntimeShader` (AGSL).
* **Rayleigh & Mie Scattering**: Smoothly interpolates sky dome zenith, mid-sky, and horizon palettes across Deep Night, Dawn, Golden Hour, High Noon, Overcast Slate, and Twilight.
* **Crepuscular God-Rays & Silver Linings**: Computes directional cloud rim-lighting and radial light shafts around the sun disc, plus nocturnal high-altitude **Aurora Borealis** ribbons on clear nights.
* **Filmic Dithering**: Eliminates 8-bit OLED gradient banding during dark twilight and night skies.

### 2. 120Hz Zero-Allocation Physics & Particle Engine (`CupertinoWeatherSceneRenderer.kt`)
* **Pixel 10 Pro Lock-Screen Clock Shelf Collision**: Just like Apple's iOS Lock Screen Weather effect, foreground raindrops physically collide with the top shelf of the lock-screen clock widget and burst into gravity-arced micro-splashes.
* **Refractive Glass Lens Droplets**: Screen-space condensation and raindrops bead on the virtual glass, exhibiting slip-stick surface-tension physics, upper-rim contact shadows, specular caustic highlights, and meniscus refraction.
* **Multi-Depth Rain & 3D Snowfall**: Depth-layered rain streaks slanted by real-time wind velocity, and 3D turbulent snowflakes with foreground depth-of-field bokeh and crystalline edge frost.
* **Procedural Branching Lightning**: Recursive multi-segment lightning bolts with violet-blue plasma glow, white-hot cores, and internal cloud deck illumination.
* **Celestial Mechanics**: Twinkling starfield with Vega/Betelgeuse color tints, periodic shooting stars, and accurate synodic **Lunar Phase** rendering with lunar mare craters and earthshine.

### 3. Native Pixel 10 Pro System Integration (`CupertinoWeatherWallpaperService.kt`)
* **`Choreographer` Vsync Loop**: Synchronizes every frame to the Pixel 10 Pro's 120Hz Smooth Display via `SurfaceHolder.lockHardwareCanvas()`.
* **3D Gyroscope + Launcher Swipe Parallax**: Fuses `Sensor.TYPE_GRAVITY` / `TYPE_ACCELEROMETER` tilt vectors with `onOffsetsChanged` home-screen page swipes for genuine multi-plane depth.
* **Material You Dynamic Color Extraction (`onComputeColors`)**: Reports curated primary, secondary, and tertiary `WallpaperColors` for each weather state so Pixel Launcher icons, widgets, and system accents harmonize with the sky.
* **Interactive Touch Ripples & Particle Repulsion**: Tapping or dragging across the home screen spawns harmonic water ripples and physically pushes nearby glass droplets and snowflakes away from your finger.

### 4. Real-Time Open-Meteo Sync & Frosted-Glass Studio (`WeatherStudioScreen.kt`)
* **Zero-API-Key Live Weather**: Automatically fetches real-time temperature, WMO weather code, precipitation, wind speed/direction, humidity, UV index, surface pressure, and hourly forecast from the Open-Meteo API.
* **8 Instant Atmosphere Presets**: *Sunny*, *Clear Night*, *Partly Cloudy*, *Overcast & Mist*, *Rain*, *Thunderstorm*, *Snowfall*, and *Golden Hour*.
* **Lock-Screen Clock Shelf Preview**: Built-in full-screen Pixel 10 Pro Lock Screen preview mode to inspect rain splashes bouncing off the clock typography before applying.

---

## Project Structure

```text
pixel-weather-live-wallpaper/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── kotlin/com/antigravity/pixelweatherwallpaper/
│   │   ├── MainActivity.kt
│   │   ├── data/WeatherRepository.kt
│   │   ├── engine/
│   │   │   ├── AtmosphericAgslShader.kt
│   │   │   └── CupertinoWeatherSceneRenderer.kt
│   │   ├── model/WeatherModels.kt
│   │   ├── service/CupertinoWeatherWallpaperService.kt
│   │   └── ui/WeatherStudioScreen.kt
│   └── res/
│       ├── drawable/ic_launcher_background.xml & ic_launcher_foreground.xml
│       └── xml/weather_wallpaper.xml
└── .github/workflows/android-build.yml
```

## Building & Installing on Pixel 10 Pro

```bash
# Assemble Debug APK and run unit tests
./gradlew assembleDebug testDebugUnitTest

# Install directly onto a connected Pixel 10 Pro via ADB
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
