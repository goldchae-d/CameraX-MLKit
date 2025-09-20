package com.example.camerax_mlkit

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.example.camerax_mlkit.crypto.AesGcm
import com.example.camerax_mlkit.crypto.SessionKeyManager
import com.example.camerax_mlkit.crypto.RetrofitProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONObject


class PaymentPromptActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TITLE   = "extra_title"
        const val EXTRA_MESSAGE = "extra_message"
        const val EXTRA_TRIGGER = "extra_trigger"
    }

    private var dialog: BottomSheetDialog? = null
    private var sheetView: View? = null            // 바텀시트 루트 보관
    private var latestQrText: String? = null       // 생성한 암호화 토큰 문자열

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 잠금화면/꺼진 화면에서도 켜지도록
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

        val title   = intent.getStringExtra(EXTRA_TITLE)   ?: "사장님 결제 안내"
        val message = intent.getStringExtra(EXTRA_MESSAGE) ?: "결제 방법을 선택하세요"
        val trigger = intent.getStringExtra(EXTRA_TRIGGER) ?: "UNKNOWN"
        Log.d("PaymentPromptActivity", "title=$title, message=$message, trigger=$trigger")

        showOrExpandPayChooser(title, message, trigger)

        // 세션키 확보 → AES-GCM 암호화 → QR 문자열 생성
        lifecycleScope.launch {
            try {
                val (kid, sk) = SessionKeyManager.ensureKey(
                    this@PaymentPromptActivity,
                    RetrofitProvider.keyApi
                )
                val sid = SessionIdProvider.get(this@PaymentPromptActivity)

                val qrText = SecureQr.buildEncryptedToken(
                    kid = kid,
                    sessionKey = sk,
                    sessionId = sid,
                    merchantId = "DUKSUNG",
                    amount = null,
                    extra = mapOf("type" to "account")
                )

                latestQrText = qrText
                setTokenTextIfPresent(qrText)
                // val bmp = QrBitmap.encode(qrText); imageView.setImageBitmap(bmp)
            } catch (t: Throwable) {
                Log.e("PaymentPromptActivity", "QR 토큰 생성 실패", t)
                latestQrText = null
            }
        }


    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val title   = intent?.getStringExtra(EXTRA_TITLE)   ?: "사장님 결제 안내"
        val message = intent?.getStringExtra(EXTRA_MESSAGE) ?: "결제 방법을 선택하세요"
        val trigger = intent?.getStringExtra(EXTRA_TRIGGER) ?: "UNKNOWN"
        showOrExpandPayChooser(title, message, trigger)
    }

    private fun showOrExpandPayChooser(title: String, message: String, trigger: String) {
        // 이미 떠있으면 텍스트만 갱신하고 확장
        dialog?.let { existing ->
            existing.findViewById<TextView>(R.id.tvTitle)?.text = title
            existing.findViewById<TextView>(R.id.tvMessage)?.text = message
            val sheet = existing.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let { BottomSheetBehavior.from(it).state = BottomSheetBehavior.STATE_EXPANDED }
            // 기존 뷰 참조 갱신
            sheetView = existing.findViewById(android.R.id.content) ?: sheetView
            // 혹시 이미 만들어둔 토큰이 있으면 반영
            latestQrText?.let { setTokenTextIfPresent(it) }
            return
        }

        val d = BottomSheetDialog(this)
        val v = layoutInflater.inflate(R.layout.dialog_pay_chooser, null, false)
        d.setContentView(v)
        d.setDismissWithAnimation(true)
        d.setOnShowListener {
            val sheet = d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let { BottomSheetBehavior.from(it).state = BottomSheetBehavior.STATE_EXPANDED }
        }

        // 루트 보관 (토큰 텍스트 반영 등에 사용)
        sheetView = v

        // 제목/메시지
        v.findViewById<TextView>(R.id.tvTitle)?.text = title
        v.findViewById<TextView>(R.id.tvMessage)?.text = message
        Log.d("PaymentPromptActivity", "trigger=$trigger")

        // (선택) 이미 토큰이 있으면 표시(존재할 때만)
        latestQrText?.let { setTokenTextIfPresent(it) }

        // 버튼 액션
        v.findViewById<View>(R.id.btnAccountQr)?.setOnClickListener {
            d.dismiss(); openAccountQr(); finish()
        }
        v.findViewById<View>(R.id.btnInApp)?.setOnClickListener {
            d.dismiss(); openInAppScanner(); finish()
        }
        v.findViewById<View>(R.id.btnKakao)?.setOnClickListener {
            d.dismiss(); openKakaoPay(); finish()
        }
        v.findViewById<View>(R.id.btnNaver)?.setOnClickListener {
            d.dismiss(); openNaverPay(); finish()
        }

        d.setOnCancelListener { finish() }
        d.show()
        dialog = d
    }

    /** 레이아웃에 tvToken id 가 있는 경우에만 텍스트를 세팅 */
    private fun setTokenTextIfPresent(text: String) {
        val root = sheetView ?: return
        val tvId = resources.getIdentifier("tvToken", "id", packageName)
        if (tvId != 0) {
            (root.findViewById<View>(tvId) as? TextView)?.apply {
                this.text = text
                this.visibility = View.VISIBLE
            }
        }
    }

    private fun openAccountQr() {
        val token = latestQrText.orEmpty()
        startActivity(
            Intent(this, AccountQrActivity::class.java)
                .putExtra("qr_token", token)
        )
    }

    private fun openInAppScanner() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("openScanner", true)
        )
    }

    private fun openKakaoPay() {
        val pkgKakaoPay = "com.kakaopay.app"
        val pkgKakaoTalk = "com.kakao.talk"
        val tryUris = listOf("kakaotalk://kakaopay/qr", "kakaotalk://kakaopay/home")
        for (u in tryUris) {
            try { startActivity(Intent(Intent.ACTION_VIEW, u.toUri())); return } catch (_: Exception) {}
        }
        if (launchPackage(pkgKakaoPay)) return
        if (launchPackage(pkgKakaoTalk)) return
        openStoreOrWeb(pkgKakaoPay, "카카오페이")
    }

    private fun openNaverPay() {
        val qrScheme = "naversearchapp://search?qmenu=qrcode&version=1"
        try { startActivity(Intent(Intent.ACTION_VIEW, qrScheme.toUri())); return } catch (_: Exception) {}
        val naverQrHome = "https%3A%2F%2Fm.pay.naver.com%2Fqr%2Fhome"
        val inApp = "naversearchapp://inappbrowser?url=$naverQrHome&target=inpage&version=6"
        try { startActivity(Intent(Intent.ACTION_VIEW, inApp.toUri())); return } catch (_: Exception) {}
        val pkgNaver = "com.nhn.android.search"
        if (launchPackage(pkgNaver)) return
        openStoreOrWeb(pkgNaver, "네이버")
    }

    private fun launchPackage(packageName: String): Boolean = try {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
            true
        } else false
    } catch (_: Exception) { false }

    private fun openStoreOrWeb(packageName: String, storeQuery: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, "market://details?id=$packageName".toUri()))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$packageName".toUri()))
            } catch (_: Exception) {
                startActivity(Intent(Intent.ACTION_VIEW, "market://search?q=$storeQuery".toUri()))
            }
        }
    }

    override fun onDestroy() {
        dialog?.setOnShowListener(null)
        dialog?.setOnCancelListener(null)
        dialog = null
        sheetView = null
        super.onDestroy()
    }
}
