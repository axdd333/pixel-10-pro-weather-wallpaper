package com.antigravity.pixelweatherwallpaper

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.core.content.ContextCompat
import com.antigravity.pixelweatherwallpaper.data.WeatherRepository
import com.antigravity.pixelweatherwallpaper.ui.WeatherStudioScreen
import java.util.Locale

/**
 * Companion Studio Activity & Live Wallpaper Settings Activity for Pixel 10 Pro.
 * Configured to request 120Hz Smooth Display frame rate and optionally resolve
 * coarse GPS location for live Open-Meteo atmospheric synchronization.
 */
class MainActivity : ComponentActivity() {

    private val repository by lazy { WeatherRepository.getInstance(applicationContext) }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (granted) {
            resolveDeviceLocationAndSync()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        enableHighRefreshRateDisplay()

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme()
            ) {
                WeatherStudioScreen(
                    repository = repository,
                    onRequestLocationPermission = ::requestLocationOrSync
                )
            }
        }
    }

    private fun enableHighRefreshRateDisplay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.attributes = window.attributes.apply {
                preferredDisplayModeId = display?.supportedModes
                    ?.maxByOrNull { it.refreshRate }
                    ?.modeId ?: 0
            }
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun requestLocationOrSync() {
        val hasCoarse = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (hasCoarse) {
            resolveDeviceLocationAndSync()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun resolveDeviceLocationAndSync() {
        runCatching {
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val location = locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)

            if (location != null) {
                val lat = location.latitude
                val lon = location.longitude
                val cityName = runCatching {
                    @Suppress("DEPRECATION")
                    val addresses = Geocoder(this, Locale.getDefault()).getFromLocation(lat, lon, 1)
                    addresses?.firstOrNull()?.locality
                        ?: addresses?.firstOrNull()?.subAdminArea
                        ?: "Current Location"
                }.getOrDefault("Current Location")

                repository.setGeoCoordinates(cityName, lat, lon)
            } else {
                repository.refreshWeatherIfNeeded(force = true)
            }
        }
    }
}
