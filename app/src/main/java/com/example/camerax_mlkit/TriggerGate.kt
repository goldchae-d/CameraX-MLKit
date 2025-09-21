// app/src/main/java/com/example/camerax_mlkit/TriggerGate.kt
package com.example.camerax_mlkit

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner

object TriggerGate {

    // ── [0] 내부 브로드캐스트(메인 액티비티가 수신) ─────────────────────
    /** MainActivity 가 등록한 BroadcastReceiver 가 받는 액션 */
    const val ACTION_PAY_PROMPT = "com.example.camerax_mlkit.ACTION_PAY_PROMPT"

    // ── [1] 언제 뜰지 정책 ─────────────────────────────────────────────
    enum class Policy { GEO_AND_BEACON, GEO_ONLY, BEACON_ONLY, GEO_OR_BEACON }
    private const val TAG = "TriggerGate"

    /** 현재 요구: 지오펜스 안 + 비콘 근접일 때만 표시 */
    private val POLICY = Policy.GEO_AND_BEACON
    // "지오펜스 안이면 무조건" 원하면 ↑ 을 Policy.GEO_ONLY 로 바꾸세요.

    // ── [2] 상태 플래그 ───────────────────────────────────────────────
    @Volatile private var onTrustedWifi: Boolean = false
    @Volatile private var inGeofence: Boolean = false
    @Volatile private var nearBeacon: Boolean = false

    // ── [3] 디바운스/타이머 ───────────────────────────────────────────
    private var lastShownAt = 0L
    private const val COOLDOWN_MS = 3_000L             // 연속 표시 방지
    private const val BEACON_NEAR_TIMEOUT_MS = 15_000L // 근접 유지 시간

    /**
     * 마지막 트리거 후 쿨다운이 지났는지 확인.
     * 외부(MainActivity)에서도 호출 가능해야 하므로 public으로 둡니다.
     */
    @Synchronized
    fun isTriggered(): Boolean { // <--- 'private'을 제거하여 외부 접근 허용
        val now = SystemClock.elapsedRealtime()
        return (now - lastShownAt <= COOLDOWN_MS)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var beaconTimeout: Runnable? = null

    // ── [4] 지오펜스 상태를 디스크에 저장(좌표는 저장하지 않음) ────────────
    private const val PREF = "trigger_gate"
    private const val KEY_IN_ZONE = "in_zone"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 외부에서 지오펜스 변화를 알려줄 때 호출 (BR/서비스에서) */
    fun onGeofenceChanged(ctx: Context, inZone: Boolean, fenceId: String = "DUKSUNG") {
        // 메모리 상태
        inGeofence = inZone
        if (!inZone) nearBeacon = false  // 영역 이탈 시 근접 플래그 리셋(선택)

        // 영구 저장(좌표는 절대 저장하지 않음)
        prefs(ctx).edit().putBoolean(KEY_IN_ZONE, inZone).apply()

        Log.d(TAG, "onGeofenceChanged → geo=$inGeofence, beacon=$nearBeacon, fenceId=$fenceId")

        // 포그라운드에서 지오펜스만으로도 바로 유도하고 싶다면,
        // 정책을 GEO_ONLY 로 바꾸거나 아래 maybeShow 호출 유지
        maybeShow(ctx, reason = if (inZone) "geofence_enter" else "geofence_exit")

        // (선택) 지오펜스 진입 & 앱이 포그라운드면 바로 바텀시트 뜨도록 내부 브로드캐스트
        if (inZone && isAppForeground()) {
            ctx.sendBroadcast(
                Intent(ACTION_PAY_PROMPT).apply {
                    putExtra("fenceId", fenceId)
                    putExtra("reason", "geofence_enter")
                    putExtra("geo", true)
                    putExtra("beacon", nearBeacon)
                }
            )
        }
    }

    /** 비콘 근접 이벤트(BeaconForegroundService/Monitor → 여기로) */
    fun onBeaconNear(ctx: Context) {
        nearBeacon = true

        // 타임아웃 재설정
        beaconTimeout?.let { mainHandler.removeCallbacks(it) }
        beaconTimeout = Runnable {
            nearBeacon = false
            Log.d(TAG, "Beacon timeout -> false")
        }
        mainHandler.postDelayed(beaconTimeout!!, BEACON_NEAR_TIMEOUT_MS)

        Log.d(TAG, "onBeaconNear → geo=$inGeofence, beacon=$nearBeacon")
        maybeShow(ctx, reason = "beacon_near")
    }

    /** 앱(카메라)이 다시 포그라운드로 왔을 때 MainActivity.onResume()에서 호출 */
    fun onAppResumed(ctx: Context) {
        Log.d(TAG, "onAppResumed → geo=$inGeofence, beacon=$nearBeacon")
        maybeShow(ctx, reason = "app_resumed")
    }

    // ── [5] 외부 셋터(신뢰 Wi-Fi 등) ─────────────────────────────────────
    fun setTrustedWifi(on: Boolean) {
        onTrustedWifi = on
        Log.d(TAG, "WiFi gate -> $on")
    }
    fun setGeofence(inZone: Boolean) { inGeofence = inZone }
    fun setBeacon(near: Boolean) { nearBeacon = near }

    // ── [6] 정책 평가 + 표시(브로드캐스트 or 직접 Activity) ───────────────
    @Synchronized
    private fun maybeShow(ctx: Context, reason: String) {
        val now = SystemClock.elapsedRealtime()

        // 정책 평가
        val allowByPolicy = when (POLICY) {
            Policy.GEO_AND_BEACON -> inGeofence && nearBeacon
            Policy.GEO_ONLY       -> inGeofence
            Policy.BEACON_ONLY    -> nearBeacon
            Policy.GEO_OR_BEACON  -> inGeofence || nearBeacon
        }

        if (!(allowByPolicy || onTrustedWifi)) {
            Log.d(TAG, "NO popup (policy fail): policy=$POLICY geo=$inGeofence beacon=$nearBeacon wifi=$onTrustedWifi")
            return
        }

        // 쿨다운
        if (now - lastShownAt <= COOLDOWN_MS) {
            Log.d(TAG, "NO popup (cooldown): dt=${now - lastShownAt}ms")
            return
        }

        // 포그라운드에서만 직접 UI 트리거(백그라운드는 서비스 알림이 담당)
        if (!isAppForeground()) {
            Log.d(TAG, "BG – rely on full-screen notification")
            return
        }

        lastShownAt = now

        // 우선 내부 브로드캐스트로 메인 액티비티가 바텀시트를 띄우게 함
        Log.d(TAG, "SEND ACTION_PAY_PROMPT (reason=$reason)")
        ctx.sendBroadcast(
            Intent(ACTION_PAY_PROMPT).apply {
                putExtra("reason", reason)
                putExtra("geo", inGeofence)
                putExtra("beacon", nearBeacon)
                putExtra("wifi", onTrustedWifi)
            }
        )

        // 만약 Receiver를 등록하지 않았더라도 안전하게 보이길 원하면
        // 아래의 직접 호출을 주석 해제하세요(중복 표시는 피해야 함).
        //
        // val i = Intent(ctx, PaymentPromptActivity::class.java)
        //     .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        // ctx.startActivity(i)
    }

    // ── [7] 저장된 지오펜스 상태 조회(다른 컴포넌트에서 필요 시) ───────────
    fun isInZone(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_IN_ZONE, false)

    fun tryShowNow(ctx: Context, reason: String) {
        maybeShow(ctx, reason)           // 내부 정책/쿨다운을 그대로 따름
    }

    // ── [8] 유틸 ───────────────────────────────────────────────────────
    private fun isAppForeground(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
}
