package com.cameracid.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.DatagramSocket
import java.net.Socket

/**
 * On a device with both WiFi and cellular data active, Android may route general app traffic
 * over cellular instead of WiFi -- especially when the WiFi network (like this camera's own
 * access point) has no internet access and gets deprioritized. Explicitly binding our sockets
 * to the WiFi network sidesteps that entirely, rather than requiring the user to toggle
 * Airplane Mode manually.
 */
object NetworkUtils {

    fun findWifiNetwork(context: Context): android.net.Network? {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return network
            }
        }
        return null
    }

    fun bindToWifi(context: Context, socket: Socket) {
        try {
            findWifiNetwork(context)?.bindSocket(socket)
        } catch (_: Exception) {
        }
    }

    fun bindToWifi(context: Context, socket: DatagramSocket) {
        try {
            findWifiNetwork(context)?.bindSocket(socket)
        } catch (_: Exception) {
        }
    }
}
