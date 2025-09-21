// app/src/main/java/com/example/camerax_mlkit/crypto/CryptoBox.kt
package com.example.camerax_mlkit.crypto

import android.util.Base64
import java.nio.ByteBuffer
import java.security.*
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoBox {

    // ===== ECDH (Elliptic Curve Diffie-Hellman) =====
    // 여기서는 prime256v1(secp256r1) 곡선을 예시로 사용
    fun genEcKeyPair(): KeyPair {
        val kpg: KeyPairGenerator = KeyPairGenerator.getInstance("EC")
        kpg.initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        return kpg.generateKeyPair()
    }

    fun ecdhSharedSecret(privateKey: PrivateKey, serverPubB64: String): ByteArray {
        val serverPub: ByteArray = Base64.decode(serverPubB64, Base64.NO_WRAP)
        val kf: KeyFactory = KeyFactory.getInstance("EC")
        val pubKey = kf.generatePublic(X509EncodedKeySpec(serverPub))
        val ka: KeyAgreement = KeyAgreement.getInstance("ECDH")
        ka.init(privateKey)
        ka.doPhase(pubKey, true)
        return ka.generateSecret() // raw shared secret
    }

    // ===== HKDF-SHA256 파생: encKey, macKey 생성 =====
    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, outLen: Int): ByteArray {
        fun hmac(key: ByteArray, data: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256"))
                doFinal(data)
            }

        val prk: ByteArray = hmac(salt, ikm)
        var t = ByteArray(0)
        val okm = ByteArray(outLen)
        var generated = 0
        var counter: Byte = 1

        while (generated < outLen) {
            val bb = ByteBuffer.allocate(t.size + info.size + 1)
            bb.put(t)
            bb.put(info)
            bb.put(counter)
            t = hmac(prk, bb.array())

            val toCopy = minOf(t.size, outLen - generated)
            System.arraycopy(t, 0, okm, generated, toCopy)
            generated += toCopy
            counter++
        }
        return okm
    }

    data class SessionKeys(val encKey: SecretKey, val macKey: SecretKey)

    fun deriveSessionKeys(shared: ByteArray, clientNonce: ByteArray, serverNonce: ByteArray): SessionKeys {
        val salt: ByteArray = clientNonce + serverNonce
        val info: ByteArray = "qr-session-v1".toByteArray()
        val okm: ByteArray = hkdfSha256(shared, salt, info, 64) // 32(enc) + 32(mac)
        val encKey: SecretKey = SecretKeySpec(okm.copyOfRange(0, 32), "AES")
        val macKey: SecretKey = SecretKeySpec(okm.copyOfRange(32, 64), "HmacSHA256")
        return SessionKeys(encKey, macKey)
    }

    // ===== HMAC-SHA256 =====
    fun hmacSha256(macKey: SecretKey, data: ByteArray): ByteArray {
        val mac: Mac = Mac.getInstance("HmacSHA256")
        mac.init(macKey)
        return mac.doFinal(data)
    }

    // ===== AES-GCM (서버/클라 모두 필요 시) =====
    fun aesGcmEncrypt(
        encKey: SecretKey,
        plaintext: ByteArray,
        aad: ByteArray? = null
    ): Pair<ByteArray, ByteArray /* iv */> {
        val iv: ByteArray = SecureRandom().generateSeed(12)
        val cipher: javax.crypto.Cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, encKey, GCMParameterSpec(128, iv))
        if (aad != null) cipher.updateAAD(aad)
        val ct: ByteArray = cipher.doFinal(plaintext)
        return ct to iv
    }

    fun aesGcmDecrypt(
        encKey: SecretKey,
        ciphertext: ByteArray,
        iv: ByteArray,
        aad: ByteArray? = null
    ): ByteArray {
        val cipher: javax.crypto.Cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, encKey, GCMParameterSpec(128, iv))
        if (aad != null) cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    // ===== Base64 편의 함수 =====
    fun b64(data: ByteArray): String = Base64.encodeToString(data, Base64.NO_WRAP)
    fun b64d(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
}
