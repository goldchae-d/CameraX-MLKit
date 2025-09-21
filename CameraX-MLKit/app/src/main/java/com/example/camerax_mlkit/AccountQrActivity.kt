// app/src/main/java/com/example/camerax_mlkit/AccountQrActivity.kt
package com.example.camerax_mlkit

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.camerax_mlkit.crypto.*
import com.example.camerax_mlkit.util.QrUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.SecretKey

class AccountQrActivity : AppCompatActivity() {

    private lateinit var imgQr: ImageView
    private lateinit var txtLastUpdated: TextView
    private lateinit var progressLoading: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_account_qr)

        imgQr = findViewById(R.id.imgQr)
        txtLastUpdated = findViewById(R.id.txtLastUpdated)
        progressLoading = findViewById(R.id.progressLoading)

        // 바깥 영역 클릭하면 닫기
        findViewById<View>(android.R.id.content).setOnClickListener { finish() }

        // 화면 보이는 동안 QR 자동 갱신
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    refreshTokenAndQr()
                    delay(60_000) // 60초마다 갱신
                }
            }
        }
    }

    /**
     * 세션 키 생성 (없거나 만료되었을 때 서버와 ECDH 교환 수행)
     */
    private suspend fun ensureSession(): Triple<String, SecretKey, SecretKey> =
        withContext(Dispatchers.IO) {
            // 이미 저장된 세션 있으면 사용
            SessionStore.load(this@AccountQrActivity)?.let { return@withContext it }

            // 1) 클라 키쌍 생성 + 논스 준비
            val kp = CryptoBox.genEcKeyPair()
            val clientPubB64 = CryptoBox.b64(kp.public.encoded)
            val clientNonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val clientNonceB64 = CryptoBox.b64(clientNonce)

            // 2) 서버와 키 교환
            val resp = RetrofitProvider.keyApi.ecdh(
                EcdhReq(
                    clientPubB64 = clientPubB64,
                    nonceB64 = clientNonceB64,                  // ✅ KeyApi.kt와 동일한 필드명
                    deviceId = applicationContext.packageName,  // ✅ BuildConfig 대신 안전한 방식
                    appVer = packageManager.getPackageInfo(packageName, 0).versionName
                )
            )

            // 3) 공유 비밀로부터 세션키 파생
            val shared = CryptoBox.ecdhSharedSecret(kp.private, resp.serverPubB64)
            val serverNonce = CryptoBox.b64d(resp.serverNonceB64)
            val keys = CryptoBox.deriveSessionKeys(shared, clientNonce, serverNonce)

            val expiresAt = System.currentTimeMillis() + resp.ttlSec * 1000L
            SessionStore.save(
                ctx = this@AccountQrActivity,
                keyId = resp.keyId,
                enc = keys.encKey,
                mac = keys.macKey,
                expiresAtMs = expiresAt
            )
            Triple(resp.keyId, keys.encKey, keys.macKey)
        }

    /**
     * QR 코드 요청 → 서버 응답을 QR 이미지로 표시
     */
    private fun refreshTokenAndQr() {
        lifecycleScope.launch {
            progressLoading.visibility = View.VISIBLE
            try {
                ensureSession() // 세션 확보

                val uuid = UUID.randomUUID().toString()
                val resp = withContext(Dispatchers.IO) {
                    RetrofitProvider.keyApi.issueQrToken(QrTokenReq(uuid))
                }

                // 서버가 주는 토큰을 QR로 표시
                val bmp = QrUtils.generate(resp.token, size = 720)
                imgQr.setImageBitmap(bmp)

                val now = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                txtLastUpdated.text = getString(R.string.last_updated, now)

            } catch (e: Exception) {
                e.printStackTrace()
                // 실패 시 임시 QR 표시
                val uuid = UUID.randomUUID().toString()
                imgQr.setImageBitmap(QrUtils.generate(uuid, size = 720))
                Toast.makeText(this@AccountQrActivity, "서버 오류: 임시 QR 표시", Toast.LENGTH_SHORT).show()
            } finally {
                progressLoading.visibility = View.GONE
            }
        }
    }
}
