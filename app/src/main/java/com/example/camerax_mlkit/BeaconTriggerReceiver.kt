package com.example.camerax_mlkit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 비콘 근접 시 서비스(또는 다른 컴포넌트)에서 보내는 명시적 브로드캐스트를 받는다.
 * 보낼 때: sendBroadcast(Intent(BeaconTriggerReceiver.ACTION_BEACON_NEAR).setPackage(packageName))
 */
class BeaconTriggerReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_BEACON_NEAR = "com.example.camerax_mlkit.BEACON_NEAR"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_BEACON_NEAR) {
            Log.d("BeaconTriggerBR", "Beacon NEAR received")
            TriggerGate.onBeaconNear(context.applicationContext)
        }
    }
}
