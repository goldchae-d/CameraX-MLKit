package com.example.camerax_mlkit

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import java.util.*

class BeaconMonitor(
    private val context: Context,
    private val targetNamePrefix: String? = null,          // 기기 이름 프리픽스 매칭(옵션)
    private val targetServiceUuid: ParcelUuid? = null,     // Service UUID 필터(옵션)
    private val ibeaconUuid: UUID? = null,                 // iBeacon UUID 매칭(옵션)
    private val rssiThreshold: Int = -70,                  // 근접 트리거용 RSSI 임계치
    private val minTriggerIntervalMs: Long = 30_000L,      // onNear 중복 방지 간격
    private val exitTimeoutMs: Long = 5_000L,              // ★ EXIT 판정 타임아웃(기본 5초)
    private val onNear: () -> Unit,                        // 근접(ENTER) 트리거
    private val onFar: (() -> Unit)? = null                // ★ 이탈(EXIT) 콜백(옵션)
) {
    private var lastTriggerTs = 0L
    private var scanner: BluetoothLeScanner? = null
    private var started = false

    // ★ 마지막으로 "매칭되는 광고"를 본 시각 (RSSI 임계 통과와 무관하게 갱신)
    @Volatile private var lastSeenMs: Long = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val exitChecker = object : Runnable {
        override fun run() {
            if (started) {
                val now = System.currentTimeMillis()
                if (now - lastSeenMs >= exitTimeoutMs) {
                    // EXIT 판정: onFar() 통지(한 번만 보내고 싶다면 내부에 상태 플래그 추가)
                    onFar?.invoke()
                    // 다음 ENTER가 잘 재발생하도록 lastSeen을 now로 보정
                    lastSeenMs = now
                }
                handler.postDelayed(this, 1_000L) // 1초 주기 체크
            }
        }
    }

    private fun hasBlePermissions(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val scanOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED else true
        val connOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED else true
        return fine && scanOk && connOk
    }

    private fun buildFilters(): List<ScanFilter> {
        val filters = mutableListOf<ScanFilter>()
        if (targetServiceUuid != null) {
            filters += ScanFilter.Builder().setServiceUuid(targetServiceUuid).build()
        }
        // iBeacon은 Manufacturer Data이므로 ScanFilter에서 잡기 어렵다.
        // 이름 프리픽스도 대부분 광고 패킷에 없을 수 있어 콜백 내에서 검증한다.
        return filters
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach { handle(it) } }
        override fun onScanFailed(errorCode: Int) { /* no-op */ }
    }

    private fun handle(res: ScanResult) {
        val record = res.scanRecord ?: return

        // 광고 이름 프리픽스 매칭
        val nameOk = targetNamePrefix?.let { prefix ->
            // (수정 후) 권한 필요 없는 값만 사용
            val n = record.deviceName
            n?.startsWith(prefix, ignoreCase = true) == true
        } ?: true

        // iBeacon UUID 매칭 (Manufacturer Data: Apple 0x004C)
        val iBeaconOk = ibeaconUuid?.let { target ->
            val data = record.getManufacturerSpecificData(0x004C) // Apple company ID
            if (data != null && data.size >= 23) {
                val isIBeacon = (data[0].toInt() == 0x02) && (data[1].toInt() == 0x15)
                if (isIBeacon) {
                    val uuidBytes = data.copyOfRange(2, 18)
                    val uuidStr = bytesToUuidString(uuidBytes)
                    try { UUID.fromString(uuidStr) == target } catch (_: IllegalArgumentException) { false }
                } else false
            } else false
        } ?: true

        if (nameOk && iBeaconOk) {
            // ★ 매칭되는 광고를 본 즉시 lastSeen 갱신(ENTER/EXIT 판정의 기준)
            lastSeenMs = System.currentTimeMillis()

            // 근접(ENTER) 트리거는 RSSI 임계 통과 + 최소 간격 보장
            val rssi = res.rssi
            if (rssi >= rssiThreshold) maybeTrigger()
        }
    }

    private fun maybeTrigger() {
        val now = System.currentTimeMillis()
        if (now - lastTriggerTs >= minTriggerIntervalMs) {
            lastTriggerTs = now
            onNear()
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (started) return
        if (!hasBlePermissions()) return

        val bt = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (bt == null || !bt.isEnabled) return

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY) // ★ 가장 민감
            .setReportDelay(0L)                              // ★ 즉시 콜백
            .build()

        val filters = buildFilters()
        scanner = bt.bluetoothLeScanner
        scanner?.startScan(filters, settings, callback)

        // ★ EXIT 타임아웃 워처 시작
        lastSeenMs = System.currentTimeMillis()
        handler.post(exitChecker)

        started = true
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!started) return
        scanner?.stopScan(callback)
        handler.removeCallbacks(exitChecker)
        started = false
    }

    private fun bytesToUuidString(b: ByteArray): String {
        require(b.size == 16)
        fun hex(i: Int) = String.format("%02X", b[i])
        val s = buildString { for (i in 0 until 16) append(hex(i)) }
        return "${s.substring(0,8)}-${s.substring(8,12)}-${s.substring(12,16)}-${s.substring(16,20)}-${s.substring(20,32)}"
    }
}
