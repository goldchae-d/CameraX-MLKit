package com.example.camerax_mlkit

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController.COORDINATE_SYSTEM_VIEW_REFERENCED
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.camerax_mlkit.databinding.ActivityMainBinding
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.example.camerax_mlkit.WifiTrigger
import com.example.camerax_mlkit.TriggerGate
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter



class MainActivity : AppCompatActivity() {

    private lateinit var viewBinding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var barcodeScanner: BarcodeScanner

    // 지오펜스
    private lateinit var geofencingClient: GeofencingClient

    // MainActivity 클래스 안에 추가
    private val payPromptReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == TriggerGate.ACTION_PAY_PROMPT) {
                // 필요하면 디버깅 로그
                Log.d("MainActivity", "ACTION_PAY_PROMPT 수신 -> PaymentPromptActivity 실행")

                // TriggerGate가 넘긴 정보들(선택)
                val reason = intent.getStringExtra("reason")
                val geo    = intent.getBooleanExtra("geo", false)
                val beacon = intent.getBooleanExtra("beacon", false)
                val wifi   = intent.getBooleanExtra("wifi", false)

                // 바로 바텀시트 Activity 실행
                startActivity(
                    Intent(this@MainActivity, PaymentPromptActivity::class.java).apply {
                        // PaymentPromptActivity 쪽에서 쓰려면 extras 전달
                        putExtra(PaymentPromptActivity.EXTRA_TITLE,   "결제 안내")
                        putExtra(PaymentPromptActivity.EXTRA_MESSAGE, "안전한 QR 결제를 진행하세요.")
                        putExtra(PaymentPromptActivity.EXTRA_TRIGGER, reason ?: "prompt")
                        // 필요 시 현재 상태도 같이
                        putExtra("geo", geo); putExtra("beacon", beacon); putExtra("wifi", wifi)
                    }
                )
            }
        }
    }


    private val geofencePendingIntent: PendingIntent by lazy {
        PendingIntent.getBroadcast(
            applicationContext,
            0,
            Intent(
                applicationContext,
                com.example.camerax_mlkit.geofence.GeofenceBroadcastReceiver::class.java
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // BLE 권한 런처
    // BLE 권한 런처
    private val blePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val fine = result[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val scan = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            (result[Manifest.permission.BLUETOOTH_SCAN] ?: false) else true
        val connect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            (result[Manifest.permission.BLUETOOTH_CONNECT] ?: false) else true

        if (fine && scan && connect) {
            BeaconForegroundService.start(this)
        } else {
            Toast.makeText(this, "BLE 권한 거부(비콘 감지 비활성화)", Toast.LENGTH_LONG).show()
            // 필요 시 설정 화면으로 이동
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)
            )
            startActivity(intent)
        }

    }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* ignore */ }

    private fun ensurePostNotificationsPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            val p = Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                notifPermLauncher.launch(p)
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WifiTrigger.start(this)
        ensurePostNotificationsPermission()
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        // 카메라 권한 → 카메라 시작
        if (allPermissionsGranted()) startCamera()
        else ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)

        // 위치 권한 확보 후 지오펜스 등록
        ensureLocationPermission {
            initGeofencing()
            addOrUpdateDuksungGeofence()
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        // BLE 권한 확인 후 포그라운드 서비스 시작
        ensureBlePermissions()
    }

    // BLE 권한 체크 → 이미 허용이면 바로 서비스 시작
    private fun ensureBlePermissions() {
        val needS = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val required = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION).apply {
            if (needS) { add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_CONNECT) }
        }
        val missing = required.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing) {
            blePermissionLauncher.launch(required.toTypedArray())
        } else {
            BeaconForegroundService.start(this)
        }
    }

    private fun startCamera() {
        val cameraController = LifecycleCameraController(baseContext)
        val previewView: PreviewView = viewBinding.viewFinder

        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        barcodeScanner = BarcodeScanning.getClient(options)

        cameraController.setImageAnalysisAnalyzer(
            ContextCompat.getMainExecutor(this),
            MlKitAnalyzer(
                listOf(barcodeScanner),
                COORDINATE_SYSTEM_VIEW_REFERENCED,
                ContextCompat.getMainExecutor(this)
            ) { result: MlKitAnalyzer.Result? ->
                val barcodeResults = result?.getValue(barcodeScanner)
                if (barcodeResults == null || barcodeResults.isEmpty() || barcodeResults.first() == null) {
                    previewView.overlay.clear()
                    previewView.setOnTouchListener { v, _ ->
                        v.performClick(); false
                    }
                    return@MlKitAnalyzer
                }

                val qrCodeViewModel = QrCodeViewModel(barcodeResults[0])
                val qrCodeDrawable = QrCodeDrawable(qrCodeViewModel)

                previewView.setOnTouchListener(qrCodeViewModel.qrCodeTouchCallback)
                previewView.overlay.clear()
                previewView.overlay.add(qrCodeDrawable)
            }
        )

        cameraController.bindToLifecycle(this)
        previewView.controller = cameraController
    }

    // 위치 권한 확보
    private fun ensureLocationPermission(onGranted: () -> Unit = {}) {
        val fineGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!fineGranted || !coarseGranted) {
            ActivityCompat.requestPermissions(this, LOCATION_PERMISSIONS, REQUEST_CODE_LOCATION)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val bgGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (!bgGranted) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Toast.makeText(this, "백그라운드 위치 허용이 필요하면 설정에서 ‘항상 허용’을 선택하세요.", Toast.LENGTH_LONG).show()
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null))
                    startActivity(intent)
                } else {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                        REQUEST_CODE_BACKGROUND_LOCATION
                    )
                    return
                }
            }
        }
        onGranted()
    }

    private fun initGeofencing() {
        geofencingClient = LocationServices.getGeofencingClient(this)
    }

    private fun addOrUpdateDuksungGeofence() {
        val geofence = Geofence.Builder()
            .setRequestId(DUKSUNG_GEOFENCE_ID)
            .setCircularRegion(DUKSUNG_LAT, DUKSUNG_LNG, DUKSUNG_RADIUS_METERS)
            .setTransitionTypes(
                Geofence.GEOFENCE_TRANSITION_ENTER or
                        Geofence.GEOFENCE_TRANSITION_EXIT or
                        Geofence.GEOFENCE_TRANSITION_DWELL
            )
            .setLoiteringDelay(5_000)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .build()

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(
                GeofencingRequest.INITIAL_TRIGGER_ENTER or GeofencingRequest.INITIAL_TRIGGER_DWELL
            )
            .addGeofence(geofence)
            .build()

        val fineOk = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseOk = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fineOk || !coarseOk) return

        geofencingClient.removeGeofences(listOf(DUKSUNG_GEOFENCE_ID)).addOnCompleteListener {
            geofencingClient.addGeofences(request, geofencePendingIntent)
                .addOnSuccessListener {
                    Toast.makeText(this, "지오펜스 등록 완료(덕성여대)", Toast.LENGTH_SHORT).show()
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "지오펜스 등록 실패", e)
                    if (e is SecurityException) Log.e(TAG, "권한 문제: 위치/백그라운드 위치 확인 필요")
                    Toast.makeText(this, "지오펜스 등록 실패: ${e.message}", Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        barcodeScanner.close()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_CODE_PERMISSIONS -> {
                if (allPermissionsGranted()) startCamera()
                else { Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show(); finish() }
            }
            REQUEST_CODE_LOCATION -> {
                val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                if (granted) ensureLocationPermission { /* 필요 시 재등록 */ }
                else Toast.makeText(this, "위치 권한이 필요합니다(지오펜싱).", Toast.LENGTH_LONG).show()
            }
            REQUEST_CODE_BACKGROUND_LOCATION -> {
                val bgGranted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
                if (!bgGranted) Toast.makeText(this, "백그라운드 위치 권한이 거부되었습니다.", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        TriggerGate.onAppResumed(applicationContext)  // ← 이 한 줄!
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(TriggerGate.ACTION_PAY_PROMPT)

        // API 21~34 전체에서 안전하게 동작
        ContextCompat.registerReceiver(
            /* context = */ this,
            /* receiver = */ payPromptReceiver,
            /* filter   = */ filter,
            /* flags    = */ ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }



    override fun onStop() {
        super.onStop()
        // Activity가 화면에서 사라질 때 해제 (메모리 릭/중복 방지)
        try {
            unregisterReceiver(payPromptReceiver)
        } catch (_: IllegalArgumentException) { /* 이미 해제된 경우 대비 */ }
    }


    companion object {
        private const val TAG = "CameraX-MLKit"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private const val REQUEST_CODE_LOCATION = 11
        private const val REQUEST_CODE_BACKGROUND_LOCATION = 12

        private val LOCATION_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA
        ).toTypedArray()

        // 덕성여대 좌표/반경
        private const val DUKSUNG_LAT = 37.65326
        private const val DUKSUNG_LNG = 127.0164
        private const val DUKSUNG_RADIUS_METERS = 200f
        private const val DUKSUNG_GEOFENCE_ID = "DUKSUNG"
    }
}
