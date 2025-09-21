package com.example.camerax_mlkit

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues // [추가] 사진 파일 정보를 담기 위한 도구
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore // [추가] 안드로이드 미디어 저장소에 접근하기 위한 도구
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageCapture // [추가] 사진 촬영 기능을 사용하기 위한 도구
import androidx.camera.core.ImageCaptureException // [추가] 사진 촬영 중 오류를 처리하기 위한 도구
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
import java.text.SimpleDateFormat // [추가] 날짜와 시간을 원하는 형식으로 만들기 위한 도구
import java.util.Locale // [추가] 국가별 날짜/시간 형식을 설정하기 위한 도구
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


class MainActivity : AppCompatActivity() {

    // --- 클래스 멤버 변수 선언 ---
    private lateinit var viewBinding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var barcodeScanner: BarcodeScanner
    // [수정] cameraController를 클래스 멤버로 선언해야 다른 함수에서도 사용 가능합니다.
    private lateinit var cameraController: LifecycleCameraController

    private lateinit var geofencingClient: GeofencingClient

    // (기존 코드는 그대로 유지)
    private val payPromptReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == TriggerGate.ACTION_PAY_PROMPT) {
                Log.d("MainActivity", "ACTION_PAY_PROMPT 수신 -> PaymentPromptActivity 실행")
                val reason = intent.getStringExtra("reason")
                val geo    = intent.getBooleanExtra("geo", false)
                val beacon = intent.getBooleanExtra("beacon", false)
                val wifi   = intent.getBooleanExtra("wifi", false)
                startActivity(
                    Intent(this@MainActivity, PaymentPromptActivity::class.java).apply {
                        putExtra(PaymentPromptActivity.EXTRA_TITLE,   "결제 안내")
                        putExtra(PaymentPromptActivity.EXTRA_MESSAGE, "안전한 QR 결제를 진행하세요.")
                        putExtra(PaymentPromptActivity.EXTRA_TRIGGER, reason ?: "prompt")
                        putExtra("geo", geo); putExtra("beacon", beacon); putExtra("wifi", wifi)
                    }
                )
            }
        }
    }

    private val geofencePendingIntent: PendingIntent by lazy {
        PendingIntent.getBroadcast(
            applicationContext, 0,
            Intent(applicationContext, com.example.camerax_mlkit.geofence.GeofenceBroadcastReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private val blePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val fine = result[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val scan = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) (result[Manifest.permission.BLUETOOTH_SCAN] ?: false) else true
        val connect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) (result[Manifest.permission.BLUETOOTH_CONNECT] ?: false) else true
        if (fine && scan && connect) {
            BeaconForegroundService.start(this)
        } else {
            Toast.makeText(this, "BLE 권한 거부(비콘 감지 비활성화)", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            startActivity(intent)
        }
    }

    private val notifPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* ignore */ }

    // --- 생명주기 함수 ---
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WifiTrigger.start(this)
        ensurePostNotificationsPermission()

        // 뷰 바인딩: XML 레이아웃과 코드를 연결합니다.
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        // [수정] 촬영 버튼 클릭 리스너를 여기서 설정합니다. 화면이 생성될 때 딱 한 번만 설정하면 됩니다.
        viewBinding.cameraCaptureButton.setOnClickListener { takePhoto() }

        // 카메라 권한이 있으면 카메라를 시작하고, 없으면 요청합니다.
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        // 위치 권한이 있으면 지오펜스를 설정합니다.
        ensureLocationPermission {
            initGeofencing()
            addOrUpdateDuksungGeofence()
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        // 블루투스 권한이 있으면 비콘 서비스를 시작합니다.
        ensureBlePermissions()
    }


    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(TriggerGate.ACTION_PAY_PROMPT)
        ContextCompat.registerReceiver(this, payPromptReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onResume() {
        super.onResume()
        TriggerGate.onAppResumed(applicationContext)
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(payPromptReceiver)
        } catch (_: IllegalArgumentException) { /* 이미 해제된 경우 대비 */ }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        if(::barcodeScanner.isInitialized) {
            barcodeScanner.close()
        }
    }

    // --- 기능 함수 ---
    // MainActivity.kt 파일 내부
    private fun startCamera() {
        cameraController = LifecycleCameraController(baseContext)
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
                    return@MlKitAnalyzer
                }

                // 1. TriggerGate를 확인하여 반복 실행을 방지합니다.
                if (TriggerGate.isTriggered()) {
                    return@MlKitAnalyzer
                }

                // 2. QrCodeViewModel을 생성하여 QR 코드 정보를 가져옵니다.
                val qrCodeViewModel = QrCodeViewModel(barcodeResults[0])

                // 3. QR 코드 내용으로 결제 다이얼로그를 즉시 실행합니다.
                startPaymentPrompt(qrCodeViewModel.qrContent)
            }
        )
        cameraController.bindToLifecycle(this)
        previewView.controller = cameraController
    }

    private fun startPaymentPrompt(qrCode: String) {
        val intent = Intent(this, PaymentPromptActivity::class.java).apply {
            putExtra(PaymentPromptActivity.EXTRA_QR_CODE, qrCode)
        }
        startActivity(intent)
    }

    private fun takePhoto() {
        // 파일 이름을 위한 시간 형식 설정 (예: "2025-09-20-20-05-30-123.jpg")
        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US).format(System.currentTimeMillis())

        // 사진 파일에 대한 정보 설정 (이름, 타입 등)
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            // 최신 안드로이드 버전에서는 저장 경로를 지정해주는 것이 좋습니다.
            if(Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
            }
        }

        // 사진을 어디에, 어떻게 저장할지 최종 옵션을 만듭니다.
        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            .build()

        // 설정된 옵션으로 사진을 찍습니다.
        cameraController.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this), // 결과를 메인 스레드에서 처리
            object : ImageCapture.OnImageSavedCallback {
                // 사진 촬영 실패 시 호출됩니다.
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "사진 촬영 실패: ${exc.message}", exc)
                    Toast.makeText(baseContext, "사진 촬영 실패", Toast.LENGTH_SHORT).show()
                }

                // 사진 저장이 성공적으로 완료되면 호출됩니다.
                override fun onImageSaved(output: ImageCapture.OutputFileResults){
                    val msg = "사진 저장 성공: ${output.savedUri}"
                    Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                    Log.d(TAG, msg)
                }
            }
        )
    }


    // --- 권한 관련 함수들 (기존 코드와 동일) ---
    private fun ensureBlePermissions() {
        val needS = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val required = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION).apply {
            if (needS) { add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_CONNECT) }
        }
        val missing = required.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing) {
            blePermissionLauncher.launch(required.toTypedArray())
        } else {
            BeaconForegroundService.start(this)
        }
    }


    private fun ensurePostNotificationsPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            val p = Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                notifPermLauncher.launch(p)
            }
        }
    }

    private fun ensureLocationPermission(onGranted: () -> Unit = {}) {
        val fineGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted || !coarseGranted) {
            ActivityCompat.requestPermissions(this, LOCATION_PERMISSIONS, REQUEST_CODE_LOCATION)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val bgGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!bgGranted) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Toast.makeText(this, "백그라운드 위치 허용이 필요하면 설정에서 ‘항상 허용’을 선택하세요.", Toast.LENGTH_LONG).show()
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                    startActivity(intent)
                } else {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQUEST_CODE_BACKGROUND_LOCATION)
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
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT or Geofence.GEOFENCE_TRANSITION_DWELL)
            .setLoiteringDelay(5_000)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .build()
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER or GeofencingRequest.INITIAL_TRIGGER_DWELL)
            .addGeofence(geofence)
            .build()
        val fineOk = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseOk = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fineOk || !coarseOk) return
        geofencingClient.removeGeofences(listOf(DUKSUNG_GEOFENCE_ID)).addOnCompleteListener {
            geofencingClient.addGeofences(request, geofencePendingIntent)
                .addOnSuccessListener { Toast.makeText(this, "지오펜스 등록 완료(덕성여대)", Toast.LENGTH_SHORT).show() }
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
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


    // --- 상수 선언 ---
    companion object {
        private const val TAG = "CameraX-MLKit"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private const val REQUEST_CODE_LOCATION = 11
        private const val REQUEST_CODE_BACKGROUND_LOCATION = 12

        private val LOCATION_PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        private val REQUIRED_PERMISSIONS = mutableListOf(Manifest.permission.CAMERA).toTypedArray()

        private const val DUKSUNG_LAT = 37.65326
        private const val DUKSUNG_LNG = 127.0164
        private const val DUKSUNG_RADIUS_METERS = 200f
        private const val DUKSUNG_GEOFENCE_ID = "DUKSUNG"
    }
}