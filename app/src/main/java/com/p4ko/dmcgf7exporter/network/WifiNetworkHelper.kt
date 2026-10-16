package com.p4ko.dmcgf7exporter.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.PatternMatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL
import kotlin.coroutines.resume

/**
 * Manages automatic Wi-Fi connection and disconnection to the LUMIX DMC-GF7 access point
 * (e.g., SSID starting with `GF7-`) and binds HTTP traffic to that Wi-Fi [Network].
 */
class WifiNetworkHelper(context: Context) {
    private val appContext = context.applicationContext
    private val connectivityManager: ConnectivityManager? =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    /**
     * Connects to the camera's Wi-Fi access point if not already connected, binds the process
     * to that Wi-Fi [Network], and invokes [onUnexpectedDisconnect] if the Wi-Fi drops later.
     */
    suspend fun connectToCameraWifi(
        ssidOrPrefix: String,
        password: String = "",
        cameraHost: String,
        onStatusUpdate: (String) -> Unit = {},
        onUnexpectedDisconnect: () -> Unit = {}
    ): Network {
        val cm = connectivityManager
            ?: throw IOException("ConnectivityManager が利用できません")

        val cleanSsid = ssidOrPrefix.trim().ifEmpty { DEFAULT_SSID_PREFIX }
        val cleanPass = password.trim()
        val cleanHost = LumixCameraClient.sanitizeHost(cameraHost)

        // Check if we already have an active bound network or if the phone is already on the camera's subnet
        val existingCameraNetwork = findWifiNetworkOnCameraSubnet(cleanHost)
        if (existingCameraNetwork != null && activeSpecifierCallback != null) {
            cm.bindProcessToNetwork(existingCameraNetwork)
            boundWifiNetwork = existingCameraNetwork
            return existingCameraNetwork
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            onStatusUpdate("カメラのWi-Fi ($cleanSsid*) を検索して接続中...")
            val network = requestCameraWifiNetworkQ(
                cm = cm,
                ssidOrPrefix = cleanSsid,
                password = cleanPass,
                onUnexpectedDisconnect = onUnexpectedDisconnect
            )
            cm.bindProcessToNetwork(network)
            boundWifiNetwork = network

            // Wait briefly for DHCP address / route readiness on the newly connected Wi-Fi interface
            onStatusUpdate("Wi-Fi接続完了。IPアドレス割り当てを確認中...")
            waitForSubnetReady(cm, network, cleanHost)
            return network
        } else {
            onStatusUpdate("カメラのWi-Fi ($cleanSsid) に接続中...")
            connectLegacyWifi(cleanSsid, cleanPass)
            val network = findActiveWifiNetwork()
                ?: throw IOException("カメラのWi-Fi ($cleanSsid) に接続できませんでした")
            cm.bindProcessToNetwork(network)
            boundWifiNetwork = network
            return network
        }
    }

    /**
     * Disconnects from the camera's Wi-Fi access point and unbinds the process network
     * so the Android device returns to its normal Wi-Fi / mobile connection.
     */
    fun disconnectFromCameraWifi() {
        val cm = connectivityManager
        runCatching {
            cm?.bindProcessToNetwork(null)
        }
        boundWifiNetwork = null

        val callback = activeSpecifierCallback
        activeSpecifierCallback = null
        if (callback != null && cm != null) {
            runCatching {
                cm.unregisterNetworkCallback(callback)
            }
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            runCatching {
                val wm = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                @Suppress("DEPRECATION")
                wm?.disconnect()
            }
        }
    }

    fun findActiveWifiNetwork(): Network? {
        boundWifiNetwork?.let { bound ->
            val caps = connectivityManager?.getNetworkCapabilities(bound)
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return bound
            }
        }

        val cm = connectivityManager ?: return null
        val active = cm.activeNetwork
        if (active != null) {
            val activeCaps = cm.getNetworkCapabilities(active)
            if (activeCaps != null && activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return active
            }
        }

        @Suppress("DEPRECATION")
        val allNetworks = cm.allNetworks
        for (network in allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return network
            }
        }
        return null
    }

    fun openConnection(url: URL): HttpURLConnection {
        val wifiNetwork = findActiveWifiNetwork()
        return if (wifiNetwork != null) {
            wifiNetwork.openConnection(url) as HttpURLConnection
        } else {
            url.openConnection() as HttpURLConnection
        }
    }

    private fun findWifiNetworkOnCameraSubnet(cameraHost: String): Network? {
        val cm = connectivityManager ?: return null
        val subnetPrefix = cameraHost.substringBeforeLast('.', "") + "."
        if (subnetPrefix == ".") return null

        @Suppress("DEPRECATION")
        val allNetworks = cm.allNetworks
        for (network in allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            if (isNetworkOnSubnet(cm, network, subnetPrefix)) {
                return network
            }
        }
        return null
    }

    private fun isNetworkOnSubnet(
        cm: ConnectivityManager,
        network: Network,
        subnetPrefix: String
    ): Boolean {
        val linkProps = cm.getLinkProperties(network) ?: return false
        for (linkAddr in linkProps.linkAddresses) {
            val addr = linkAddr.address
            if (addr is Inet4Address) {
                val hostAddr = addr.hostAddress ?: continue
                if (hostAddr.startsWith(subnetPrefix)) {
                    return true
                }
            }
        }
        return false
    }

    private suspend fun waitForSubnetReady(
        cm: ConnectivityManager,
        network: Network,
        cameraHost: String
    ) {
        val subnetPrefix = cameraHost.substringBeforeLast('.', "") + "."
        for (i in 1..15) {
            if (subnetPrefix == "." || isNetworkOnSubnet(cm, network, subnetPrefix)) {
                delay(300)
                return
            }
            delay(300)
        }
    }

    private suspend fun requestCameraWifiNetworkQ(
        cm: ConnectivityManager,
        ssidOrPrefix: String,
        password: String,
        onUnexpectedDisconnect: () -> Unit
    ): Network {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw IOException("Android 10 未満では WifiNetworkSpecifier は使用できません")
        }

        // Release any previous callback before requesting a new one
        activeSpecifierCallback?.let { oldCallback ->
            runCatching { cm.unregisterNetworkCallback(oldCallback) }
            activeSpecifierCallback = null
        }

        val specifierBuilder = WifiNetworkSpecifier.Builder()
        if (shouldUsePrefixMatch(ssidOrPrefix)) {
            specifierBuilder.setSsidPattern(
                PatternMatcher(ssidOrPrefix, PatternMatcher.PATTERN_PREFIX)
            )
        } else {
            // Even when full SSID is provided, prefix match also matches exact SSID and avoids failing if suffix varies
            specifierBuilder.setSsid(ssidOrPrefix)
        }

        if (password.isNotEmpty()) {
            specifierBuilder.setWpa2Passphrase(password)
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifierBuilder.build())
            .build()

        val connectedNetwork = withTimeoutOrNull(WIFI_CONNECT_TIMEOUT_MS) {
            suspendCancellableCoroutine<Network?> { cont ->
                var resumed = false
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (!resumed) {
                            resumed = true
                            if (cont.isActive) {
                                cont.resume(network)
                            }
                        }
                    }

                    override fun onUnavailable() {
                        if (!resumed) {
                            resumed = true
                            activeSpecifierCallback = null
                            if (cont.isActive) {
                                cont.resume(null)
                            }
                        }
                    }

                    override fun onLost(network: Network) {
                        if (boundWifiNetwork == network) {
                            boundWifiNetwork = null
                            runCatching { cm.bindProcessToNetwork(null) }
                            onUnexpectedDisconnect()
                        }
                    }
                }

                activeSpecifierCallback = callback
                cm.requestNetwork(request, callback)

                cont.invokeOnCancellation {
                    runCatching { cm.unregisterNetworkCallback(callback) }
                    if (activeSpecifierCallback === callback) {
                        activeSpecifierCallback = null
                    }
                }
            }
        }

        if (connectedNetwork == null) {
            disconnectFromCameraWifi()
            throw IOException(
                "カメラのWi-Fi ($ssidOrPrefix) に接続できませんでした。カメラ側のWi-FiがONになっているか、パスワード設定がある場合は設定欄を確認してください。"
            )
        }

        return connectedNetwork
    }

    @Suppress("DEPRECATION")
    private suspend fun connectLegacyWifi(ssidOrPrefix: String, password: String) {
        val wm = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: throw IOException("WifiManager が利用できません")
        if (!wm.isWifiEnabled) {
            wm.isWifiEnabled = true
        }
        val config = WifiConfiguration().apply {
            SSID = "\"$ssidOrPrefix\""
            if (password.isEmpty()) {
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
            } else {
                preSharedKey = "\"$password\""
            }
        }
        val netId = wm.addNetwork(config)
        if (netId != -1) {
            wm.disconnect()
            wm.enableNetwork(netId, true)
            wm.reconnect()
            delay(3000)
        }
    }

    companion object {
        const val DEFAULT_SSID_PREFIX = "MYCAM"
        private const val WIFI_CONNECT_TIMEOUT_MS = 45_000L

        @Volatile
        private var boundWifiNetwork: Network? = null

        @Volatile
        private var activeSpecifierCallback: ConnectivityManager.NetworkCallback? = null

        /**
         * Determines whether [ssidInput] should be treated as a prefix pattern (e.g. `"MYCAM"` or `"GF7-"`)
         * or an exact SSID (e.g. `"GF7-1A2B3C"`).
         */
        fun shouldUsePrefixMatch(ssidInput: String): Boolean {
            val trimmed = ssidInput.trim()
            return trimmed.isEmpty() ||
                trimmed.endsWith("-") ||
                trimmed.equals("MYCAM", ignoreCase = true) ||
                trimmed.equals("GF7", ignoreCase = true) ||
                trimmed.equals("DMC-GF7", ignoreCase = true)
        }
    }
}
