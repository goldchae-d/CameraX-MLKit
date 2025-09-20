package com.example.camerax_mlkit

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import java.lang.ref.WeakReference

object WifiTrigger {
    // ✔ 실제 값으로 교체하세요
    private val TRUSTED_SSIDS  = setOf("DUKSUNG_WIFI", "MY_STORE_WIFI")
    private val TRUSTED_BSSIDS = setOf("00:11:22:33:44:55", "aa:bb:cc:dd:ee:ff") // 소문자 권장

    // ⚠️ Context를 강한 참조로 들고 있지 않도록 WeakReference 사용
    private var appCtxRef: WeakReference<Context>? = null

    private var cm: ConnectivityManager? = null
    private var registered = false

    fun start(ctx: Context) {
        if (registered) return
        val appCtx = ctx.applicationContext
        appCtxRef = WeakReference(appCtx)

        // minSdk 21 호환
        cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        cm?.registerNetworkCallback(req, callback)
        registered = true
        Log.d("WifiTrigger", "registered")
    }

    @Suppress("unused") // 필요 시 종료 시점에서 호출하세요(예: Application.onTerminate, MainActivity.onDestroy)
    fun stop() {
        if (!registered) return
        try { cm?.unregisterNetworkCallback(callback) } catch (_: Exception) {}
        registered = false
        Log.d("WifiTrigger", "unregistered")
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { checkWifi(network) }
        override fun onLost(network: Network) {
            TriggerGate.setTrustedWifi(false)
            Log.d("WifiTrigger", "WiFi lost")
        }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            checkWifi(network, caps)
        }
    }

    @SuppressLint("MissingPermission") // SSID/BSSID 읽기엔 위치 권한 필요(사용자 허용 + 기기 '위치' 스위치 ON)
    private fun checkWifi(network: Network, caps: NetworkCapabilities? = null) {
        val ctx = appCtxRef?.get() ?: return

        // 1) API 31+: NetworkCapabilities.transportInfo에서 WifiInfo
        val capabilities = caps ?: cm?.getNetworkCapabilities(network)
        val wifiInfoFromCaps: WifiInfo? = if (Build.VERSION.SDK_INT >= 31) {
            capabilities?.transportInfo as? WifiInfo
        } else null

        var ssid: String?
        var bssid: String?

        if (wifiInfoFromCaps != null) {
            ssid  = wifiInfoFromCaps.ssid?.trim('"')
            bssid = wifiInfoFromCaps.bssid?.lowercase()
        } else {
            // 2) 구버전/미제공 대비: WifiManager fallback
            @Suppress("DEPRECATION")
            val info = (ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo
            ssid  = info?.ssid?.trim('"')
            bssid = info?.bssid?.lowercase()
        }

        val trusted = (ssid in TRUSTED_SSIDS) || (bssid != null && bssid in TRUSTED_BSSIDS)

        TriggerGate.setTrustedWifi(trusted)
        Log.d("WifiTrigger", "ssid=$ssid bssid=$bssid -> trusted=$trusted")

        if (trusted) {
            // Wi-Fi만으로도 팝업 유도 (정책·쿨다운은 TriggerGate 쪽에서 판단)
            TriggerGate.tryShowNow(ctx, "WIFI")
        }

    }
}
