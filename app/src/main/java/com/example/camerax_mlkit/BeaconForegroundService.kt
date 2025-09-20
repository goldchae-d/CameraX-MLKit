package com.example.camerax_mlkit

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import java.util.UUID

class BeaconForegroundService : Service() {

    companion object {
        private const val CH_SCAN = "beacon_scan"// 진행중(LOW)
        private const val CH_FULL = "beacon_fullscreen"// 풀스크린(HIGH)
        private const val FG_ID = 1000

        fun start(ctx: Context) {
            val i = Intent(ctx, BeaconForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, BeaconForegroundService::class.java))
        }
    }

    private lateinit var monitor: BeaconMonitor

    // 블루투스 ON 이벤트에서 모니터 재시작
    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (BluetoothAdapter.ACTION_STATE_CHANGED == intent.action) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_ON) {
                    try {
                        if (::monitor.isInitialized) {
                            monitor.stop()
                            monitor.start()
                        }
                    } catch (_: Exception) { /* ignore */ }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForeground(FG_ID, buildOngoingNotification())

        // BT 상태 리시버 등록
        registerReceiver(btReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))

        monitor = BeaconMonitor(
            context = this,
            targetNamePrefix = null,
            ibeaconUuid = UUID.fromString("74278BDA-B644-4520-8F0C-720EAF059935"),
            rssiThreshold = -90,
            minTriggerIntervalMs = 5_000L,
            onNear = {
                android.util.Log.d("BeaconFS", "onNear() called")
                TriggerGate.onBeaconNear(applicationContext)
                val isForeground = ProcessLifecycleOwner.get()
                    .lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                if (!isForeground) {
                    android.util.Log.d("BeaconFS", "showFullScreenPayment()")
                    showFullScreenPayment()
                }
            }
        )


        // onFar/exitTimeout을 BeaconMonitor가 지원한다면 거기서 EXIT 시간을 5초 전후로 줄이세요
        monitor.start()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(btReceiver)
        } catch (_: Throwable) { /* already unregistered */ }

        if (::monitor.isInitialized) monitor.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 진행중 알림(LOW) – 기존 그대로
            nm.createNotificationChannel(
                NotificationChannel(CH_SCAN, "Beacon scanning", NotificationManager.IMPORTANCE_LOW)
            )
            // ✅ 풀스크린 알림(HIGH)
            nm.createNotificationChannel(
                NotificationChannel(CH_FULL, "Payment prompt", NotificationManager.IMPORTANCE_HIGH).apply {
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )
        }
    }

    private fun buildOngoingNotification(): Notification {
        val contentPI = PendingIntent.getActivity(
            this, 1,
            Intent(this, PaymentPromptActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CH_SCAN)   // ← 진행중은 LOW 채널
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("비콘 감지 준비")
            .setContentText("근처 매장 감지 중…")
            .setOngoing(true)
            .setContentIntent(contentPI)
            .build()
    }

    private fun showFullScreenPayment() {
        val fullPI = PendingIntent.getActivity(
            this, 2,
            Intent(this, PaymentPromptActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CH_FULL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("결제 준비")
            .setContentText("근처 매장을 감지했어요. 결제 방법을 선택하세요.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL) // 권장 카테고리
            .setAutoCancel(true)

        // ── API 가드: NotificationManager 인스턴스 (getSystemService(Class) 는 API 23+)
        val nm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getSystemService(NotificationManager::class.java)
        } else {
            null
        }

        // ── Android 14+ 에서만 canUseFullScreenIntent() 체크
        val canFsi = if (Build.VERSION.SDK_INT >= 34) {
            nm?.canUseFullScreenIntent() == true
        } else {
            true // 14 미만은 기존 동작
        }

        if (canFsi) {
            builder.setFullScreenIntent(fullPI, true)
                .setContentIntent(fullPI)
        } else {
            // 풀스크린 동의가 없으면 heads-up + 설정으로 유도 액션
            val settingsIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // 채널 상세 설정 (API 26+)
                Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                    putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
                    putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, CH_FULL)
                }
            } else {
                // 앱 알림 설정 (API 21~25)
                Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra("app_package", packageName)
                    putExtra("app_uid", applicationInfo.uid)
                }
            }

            val settingsPi = PendingIntent.getActivity(
                this, 3, settingsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            builder.addAction(0, "전체화면 허용 설정", settingsPi)
                .setContentIntent(fullPI) // 최소 동작: 탭 시 액티비티 열기
        }

        safeNotify(builder.build())
    }



    @SuppressLint("MissingPermission")
    private fun safeNotify(n: Notification) {
        val nm = NotificationManagerCompat.from(this)
        if (Build.VERSION.SDK_INT < 33 || nm.areNotificationsEnabled()) {
            try { nm.notify(2001, n) } catch (_: SecurityException) {}
        }
    }

}
