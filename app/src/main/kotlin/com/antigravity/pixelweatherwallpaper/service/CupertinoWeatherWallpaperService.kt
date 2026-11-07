package com.antigravity.pixelweatherwallpaper.service

import android.app.WallpaperColors
import android.content.Context
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.service.wallpaper.WallpaperService
import android.view.Choreographer
import android.view.MotionEvent
import android.view.SurfaceHolder
import com.antigravity.pixelweatherwallpaper.data.WeatherRepository
import com.antigravity.pixelweatherwallpaper.engine.CupertinoWeatherSceneRenderer
import com.antigravity.pixelweatherwallpaper.model.WeatherCondition

/**
 * Android System Live Wallpaper Service engineered for Google Pixel 10 Pro (120Hz Smooth Display).
 *
 * Features:
 * - Choreographer-driven vsync frame loop with zero-allocation rendering
 * - Hardware-accelerated SurfaceHolder Canvas (API 26+ lockHardwareCanvas)
 * - Gravity/Accelerometer 3D parallax fused with Pixel Launcher page swipe offsets
 * - Android 13+ Dynamic Material You WallpaperColors extraction per weather state
 * - Automatic battery conservation when screen is off or occluded
 */
class CupertinoWeatherWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine {
        return WeatherWallpaperEngine()
    }

    private inner class WeatherWallpaperEngine :
        Engine(),
        Choreographer.FrameCallback,
        SensorEventListener {

        private val repository by lazy { WeatherRepository.getInstance(applicationContext) }
        private val renderer = CupertinoWeatherSceneRenderer()
        private val choreographer = Choreographer.getInstance()

        private var sensorManager: SensorManager? = null
        private var tiltSensor: Sensor? = null

        private var isVisibleNow = false
        private var lastFrameNanos = 0L

        // Fused parallax state
        private var launcherXOffset = 0f // -0.5..+0.5
        private var gyroNormX = 0f
        private var gyroNormY = 0f

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)
            setOffsetNotificationsEnabled(true)

            sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            tiltSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
                ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            isVisibleNow = visible
            if (visible) {
                repository.refreshWeatherIfNeeded(force = false)
                registerTiltSensor()
                lastFrameNanos = System.nanoTime()
                choreographer.removeFrameCallback(this)
                choreographer.postFrameCallback(this)
                notifyColorsChanged()
            } else {
                unregisterTiltSensor()
                choreographer.removeFrameCallback(this)
            }
        }

        override fun onSurfaceChanged(
            holder: SurfaceHolder?,
            format: Int,
            width: Int,
            height: Int
        ) {
            super.onSurfaceChanged(holder, format, width, height)
            renderer.setViewportSize(width, height)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder?) {
            isVisibleNow = false
            unregisterTiltSensor()
            choreographer.removeFrameCallback(this)
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            isVisibleNow = false
            unregisterTiltSensor()
            choreographer.removeFrameCallback(this)
            super.onDestroy()
        }

        override fun onOffsetsChanged(
            xOffset: Float,
            yOffset: Float,
            xOffsetStep: Float,
            yOffsetStep: Float,
            xPixelOffset: Int,
            yPixelOffset: Int
        ) {
            super.onOffsetsChanged(
                xOffset,
                yOffset,
                xOffsetStep,
                yOffsetStep,
                xPixelOffset,
                yPixelOffset
            )
            launcherXOffset = (xOffset - 0.5f) * 0.9f
            updateFusedParallax()
        }

        override fun onTouchEvent(event: MotionEvent?) {
            if (event != null &&
                (event.actionMasked == MotionEvent.ACTION_DOWN ||
                    event.actionMasked == MotionEvent.ACTION_MOVE)
            ) {
                val prefs = repository.preferences.value
                renderer.onTouchInteraction(event.x, event.y, prefs)
            }
            super.onTouchEvent(event)
        }

        override fun doFrame(frameTimeNanos: Long) {
            if (!isVisibleNow) return

            val dtSeconds = if (lastFrameNanos == 0L) {
                1f / 120f
            } else {
                ((frameTimeNanos - lastFrameNanos) / 1_000_000_000.0).toFloat().coerceIn(0.002f, 0.05f)
            }
            lastFrameNanos = frameTimeNanos

            val holder = surfaceHolder
            val surface = holder?.surface
            if (surface != null && surface.isValid) {
                var canvas = try {
                    holder.lockHardwareCanvas()
                } catch (_: Exception) {
                    try {
                        holder.lockCanvas()
                    } catch (_: Exception) {
                        null
                    }
                }

                if (canvas != null) {
                    try {
                        renderer.setViewportSize(canvas.width, canvas.height)
                        renderer.renderFrame(
                            canvas = canvas,
                            dtSeconds = dtSeconds,
                            weather = repository.weatherState.value,
                            preferences = repository.preferences.value
                        )
                    } finally {
                        try {
                            holder.unlockCanvasAndPost(canvas)
                        } catch (_: Exception) {
                        }
                    }
                }
            }

            if (isVisibleNow) {
                choreographer.postFrameCallback(this)
            }
        }

        override fun onComputeColors(): WallpaperColors? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return null
            val weather = repository.weatherState.value
            val (primary, secondary, tertiary) = when (weather.condition) {
                WeatherCondition.CLEAR_DAY -> Triple(
                    Color.valueOf(Color.rgb(36, 122, 224)),
                    Color.valueOf(Color.rgb(122, 195, 250)),
                    Color.valueOf(Color.rgb(255, 245, 214))
                )
                WeatherCondition.CLEAR_NIGHT -> Triple(
                    Color.valueOf(Color.rgb(14, 24, 58)),
                    Color.valueOf(Color.rgb(42, 68, 128)),
                    Color.valueOf(Color.rgb(185, 215, 255))
                )
                WeatherCondition.PARTLY_CLOUDY -> Triple(
                    Color.valueOf(Color.rgb(54, 134, 218)),
                    Color.valueOf(Color.rgb(198, 222, 248)),
                    Color.valueOf(Color.rgb(245, 250, 255))
                )
                WeatherCondition.OVERCAST_FOG -> Triple(
                    Color.valueOf(Color.rgb(78, 94, 114)),
                    Color.valueOf(Color.rgb(138, 152, 168)),
                    Color.valueOf(Color.rgb(200, 210, 220))
                )
                WeatherCondition.RAIN -> Triple(
                    Color.valueOf(Color.rgb(44, 62, 88)),
                    Color.valueOf(Color.rgb(92, 118, 152)),
                    Color.valueOf(Color.rgb(180, 210, 242))
                )
                WeatherCondition.THUNDERSTORM -> Triple(
                    Color.valueOf(Color.rgb(26, 34, 52)),
                    Color.valueOf(Color.rgb(74, 92, 134)),
                    Color.valueOf(Color.rgb(172, 200, 255))
                )
                WeatherCondition.SNOW -> Triple(
                    Color.valueOf(Color.rgb(92, 118, 152)),
                    Color.valueOf(Color.rgb(188, 210, 236)),
                    Color.valueOf(Color.rgb(245, 250, 255))
                )
                WeatherCondition.GOLDEN_HOUR -> Triple(
                    Color.valueOf(Color.rgb(232, 122, 74)),
                    Color.valueOf(Color.rgb(144, 78, 124)),
                    Color.valueOf(Color.rgb(42, 68, 138))
                )
            }
            return WallpaperColors(primary, secondary, tertiary)
        }

        override fun onSensorChanged(event: SensorEvent?) {
            if (event == null) return
            // Standard portrait gravity is ~ (0, 9.8, z). Map tilt X/Y to [-1..1]
            val gx = (-event.values[0] / 6.5f).coerceIn(-1f, 1f)
            val gy = ((event.values[1] - 6.8f) / 6.5f).coerceIn(-1f, 1f)
            gyroNormX = gx
            gyroNormY = gy
            updateFusedParallax()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

        private fun updateFusedParallax() {
            val fusedX = (launcherXOffset + gyroNormX * 0.65f).coerceIn(-1.2f, 1.2f)
            val fusedY = (gyroNormY * 0.55f).coerceIn(-1.0f, 1.0f)
            renderer.setParallaxOffset(fusedX, fusedY)
        }

        private fun registerTiltSensor() {
            if (!repository.preferences.value.enableGyroParallax) return
            val sensor = tiltSensor ?: return
            sensorManager?.registerListener(
                this,
                sensor,
                SensorManager.SENSOR_DELAY_GAME
            )
        }

        private fun unregisterTiltSensor() {
            sensorManager?.unregisterListener(this)
        }
    }
}
