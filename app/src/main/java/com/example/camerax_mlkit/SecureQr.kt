package com.example.camerax_mlkit

import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ECDH/HKDF로 획득한 "세션키"를 받아 QR 토큰을 만드는 유틸.
 * - 키를 네트워크로 주고받지 않음
 * - AAD에 kid를 넣어 무결성 바인딩
 * - QR 문자열 형태: "kid=<kid>&ct=<b64url(iv||cipher||tag)>"
 */
object SecureQr {
    private const val AES_MODE = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128                 // 16바이트 태그
    private val rnd = SecureRandom()

    /**
     * @param kid         세션키 식별자(key_id)
     * @param sessionKey  32바이트 AES 키 (ECDH+HKDF로 도출)
     * @param sessionId   앱/단말 세션 식별자(서버가 재사용 방지/추적에 활용)
     * @param merchantId  상점/지오펜스 ID 등
     * @param amount      (선택) 결제 금액
     * @param extra       (선택) 추가 필드 (e.g., type=account)
     *
     * @return "kid=...&ct=..." 형태의 QR 텍스트
     */
    fun buildEncryptedToken(
        kid: String,
        sessionKey: ByteArray,
        sessionId: String,
        merchantId: String,
        amount: Long? = null,
        extra: Map<String, String> = emptyMap()
    ): String {
        require(sessionKey.size == 32) { "sessionKey must be 32 bytes (AES-256)" }

        // 1) JSON 페이로드 구성
        val payload = JSONObject().apply {
            put("v", 1)
            put("sid", sessionId)
            put("mid", merchantId)
            put("ts", System.currentTimeMillis())
            if (amount != null) put("amt", amount)
            for ((k, v) in extra) put(k, v)
        }.toString().toByteArray(StandardCharsets.UTF_8)

        // 2) AES-GCM 암호화 (IV 12B, AAD = kid)
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(sessionKey, "AES"),
            GCMParameterSpec(TAG_BITS, iv)
        )
        cipher.updateAAD(kid.toByteArray(StandardCharsets.UTF_8))
        val ct = cipher.doFinal(payload)

        // 3) QR 텍스트로 합치기: kid=<kid>&ct=<base64url(iv||ct)>
        val token = Base64.encodeToString(
            iv + ct,
            Base64.URL_SAFE or Base64.NO_WRAP
        )
        return "kid=$kid&ct=$token"
    }
}
