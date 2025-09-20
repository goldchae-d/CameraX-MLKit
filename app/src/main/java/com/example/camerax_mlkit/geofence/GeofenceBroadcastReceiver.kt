package com.example.camerax_mlkit.geofence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.camerax_mlkit.TriggerGate
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

class GeofenceBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) { Log.e("GeofenceBR", "Geofencing error: ${event.errorCode}"); return }
        val transition = event.geofenceTransition
        val ids = event.triggeringGeofences?.map { it.requestId } ?: emptyList()
        when (transition) {
            Geofence.GEOFENCE_TRANSITION_ENTER,
            Geofence.GEOFENCE_TRANSITION_DWELL -> {
                Log.d("GeofenceBR", "ENTER/DWELL -> $ids")
                TriggerGate.onGeofenceChanged(context.applicationContext, true)
            }
            Geofence.GEOFENCE_TRANSITION_EXIT -> {
                Log.d("GeofenceBR", "EXIT -> $ids")
                TriggerGate.onGeofenceChanged(context.applicationContext, false)
            }
            else -> Log.d("GeofenceBR", "UNKNOWN -> $ids")
        }
    }
}
