package com.shantanu.shield.location

import android.content.Context
import android.location.Geocoder
import android.os.Build
import com.shantanu.shield.remote.LocationFix
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns coordinates into something a parent can read, on the PARENT's device.
 *
 * Geocoding happens here rather than on the child for two reasons: the wire payload stays two doubles
 * instead of a second, larger representation of the same fact, and a child phone without a geocoder
 * cannot break the feature.
 *
 * Every path has a usable answer. [Geocoder.isPresent] is false on AOSP builds with no geocoding
 * backend, the network call fails offline, and some coordinates simply have no street address — in all
 * of those the parent still gets coordinates plus a working Maps link, never a blank.
 */
@Singleton
class AddressResolver @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** A short, human address for [fix], or null when none could be resolved. Never throws. */
    suspend fun resolve(fix: LocationFix): String? {
        if (!fix.isUsable) return null
        if (!runCatching { Geocoder.isPresent() }.getOrDefault(false)) return null

        return withContext(Dispatchers.IO) {
            runCatching {
                val geocoder = Geocoder(context, Locale.getDefault())
                @Suppress("DEPRECATION")   // the async overload is API 33+; this path is 24..32
                val addresses = geocoder.getFromLocation(fix.lat, fix.lon, 1)
                addresses?.firstOrNull()?.let { address ->
                    // Prefer a street line; fall back through locality to whatever is populated, so a
                    // rural fix still says something more useful than a bare number.
                    listOfNotNull(
                        address.getAddressLine(0)
                            ?: listOfNotNull(
                                address.subThoroughfare,
                                address.thoroughfare,
                                address.subLocality,
                                address.locality,
                                address.adminArea,
                            ).joinToString(", ").ifBlank { null }
                    ).firstOrNull()
                }
            }.getOrNull()
        }
    }

    /** Always-available fallback: "12.9716, 77.5946". */
    fun coordinates(fix: LocationFix): String =
        String.format(Locale.US, "%.5f, %.5f", fix.lat, fix.lon)

    /**
     * A `geo:` URI that any maps app can open. Includes the `q=` label so Google Maps drops a pin
     * rather than merely centring the camera, which looks identical to "no result".
     */
    fun mapsUri(fix: LocationFix): String {
        val lat = String.format(Locale.US, "%.6f", fix.lat)
        val lon = String.format(Locale.US, "%.6f", fix.lon)
        return "geo:$lat,$lon?q=$lat,$lon"
    }

    companion object {
        /** True when this device can geocode at all — lets the UI skip a doomed lookup. */
        fun geocodingAvailable(): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                runCatching { Geocoder.isPresent() }.getOrDefault(false)
    }
}
