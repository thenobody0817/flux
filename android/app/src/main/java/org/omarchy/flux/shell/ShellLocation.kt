package org.omarchy.flux.shell

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import androidx.core.location.LocationManagerCompat

/**
 * One foreground weather lookup, with a bounded lifetime and no background tracking. The
 * shell asks for one position, gets it or an explanation, and nothing runs afterwards.
 */
class ShellLocation(private val activity: Activity) {
    private val handler = Handler(Looper.getMainLooper())
    private val signals = mutableListOf<CancellationSignal>()
    private var pending: ((Location?, String?) -> Unit)? = null
    private var generation = 0
    private val timeout = Runnable { finish(null, "Location timed out. Try again outdoors.") }

    fun request(ask: Boolean, callback: (Location?, String?) -> Unit) {
        if (pending != null) {
            callback(null, "A location request is already in progress.")
            return
        }
        pending = callback
        if (!granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            if (!ask) {
                finish(null, "Allow location access using the weather compass button.")
                return
            }
            activity.requestPermissions(
                arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
                PERMISSION_REQUEST,
            )
            return
        }
        locate()
    }

    fun permissionResult() {
        if (pending == null) return
        if (!granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            finish(null, "Location permission was denied. You can enter a city instead.")
            return
        }
        locate()
    }

    fun cancel() {
        finish(null, "Location request cancelled.")
    }

    private fun granted(permission: String) =
        activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun locate() {
        val manager = activity.getSystemService(LocationManager::class.java) ?: return finish(
            null, "No location provider is available. Enter a city instead.",
        )
        if (!manager.isLocationEnabled) {
            finish(null, "Turn on Android location services or enter a city.")
            return
        }
        val providers = mutableListOf<String>()
        if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            providers.add(LocationManager.NETWORK_PROVIDER)
        }
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION) &&
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        ) {
            providers.add(LocationManager.GPS_PROVIDER)
        }
        if (providers.isEmpty()) {
            finish(null, "No location provider is available. Enter a city instead.")
            return
        }
        val token = ++generation
        val remaining = intArrayOf(providers.size)
        handler.postDelayed(timeout, 20000)
        for (provider in providers) {
            val signal = CancellationSignal()
            signals.add(signal)
            try {
                // The compat call covers Android 10, where getCurrentLocation does not exist.
                LocationManagerCompat.getCurrentLocation(
                    manager,
                    provider,
                    signal,
                    activity.mainExecutor,
                ) { location ->
                    if (token != generation || pending == null) return@getCurrentLocation
                    if (location != null) finish(location, null)
                    else if (--remaining[0] == 0) {
                        finish(null, "Location unavailable. Try again or enter a city.")
                    }
                }
            } catch (_: SecurityException) {
                if (--remaining[0] == 0) {
                    finish(null, "Location unavailable. Check Android location permissions.")
                }
            } catch (_: IllegalArgumentException) {
                if (--remaining[0] == 0) {
                    finish(null, "Location unavailable. Check Android location permissions.")
                }
            }
        }
    }

    private fun finish(location: Location?, error: String?) {
        generation++
        handler.removeCallbacks(timeout)
        for (signal in signals) signal.cancel()
        signals.clear()
        val callback = pending
        pending = null
        callback?.invoke(location, error)
    }

    companion object {
        const val PERMISSION_REQUEST = 21
    }
}
