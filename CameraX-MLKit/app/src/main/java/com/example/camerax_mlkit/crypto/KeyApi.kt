// app/src/main/java/com/example/camerax_mlkit/crypto/KeyApi.kt
package com.example.camerax_mlkit.crypto

import com.squareup.moshi.Json
import retrofit2.http.Body
import retrofit2.http.POST

// ===== 키 교환 (ECDH) =====
data class EcdhReq(
    @Json(name = "client_pub_b64") val clientPubB64: String, // 클라이언트 공개키 (Base64)
    @Json(name = "nonce_b64")      val nonceB64: String,     // 클라이언트 랜덤 논스 (Base64)
    @Json(name = "device_id")      val deviceId: String,     // 앱 고유 ID (BuildConfig.APPLICATION_ID)
    @Json(name = "app_ver")        val appVer: String        // 앱 버전 (BuildConfig.VERSION_NAME)
)

data class EcdhResp(
    @Json(name = "key_id")           val keyId: String,       // 세션 키 식별자
    @Json(name = "ttl_sec")          val ttlSec: Long,        // 세션 키 TTL (초 단위)
    @Json(name = "server_pub_b64")   val serverPubB64: String,// 서버 공개키 (Base64)
    @Json(name = "server_nonce_b64") val serverNonceB64: String // 서버 논스 (Base64)
)

// ===== QR 토큰 발급 =====
// 클라이언트는 단순히 uuid를 요청으로 보내고,
// 서버는 세션키 기반으로 토큰을 발급하여 HMAC으로 무결성을 보장.
data class QrTokenReq(
    @Json(name = "uuid") val uuid: String
)

data class QrTokenResp(
    @Json(name = "token")  val token: String,   // QR 코드에 그대로 넣을 불투명 문자열
    @Json(name = "ts")     val ts: Long,        // 서버 발급 시각
    @Json(name = "hmac")   val hmacB64: String  // HMAC-SHA256(token|ts), Base64 인코딩
)

// ===== Retrofit 인터페이스 =====
interface KeyApi {
    @POST("/v1/keys/ecdh")
    suspend fun ecdh(@Body req: EcdhReq): EcdhResp

    @POST("/v1/qr/issue")
    suspend fun issueQrToken(@Body req: QrTokenReq): QrTokenResp
}
