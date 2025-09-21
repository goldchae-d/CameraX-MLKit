package com.example.camerax_mlkit.util

import android.graphics.*
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import androidx.core.graphics.scale
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object QrUtils {

    /** QR 생성 옵션 */
    data class Options(
        val size: Int = 512,                  // 출력 이미지 크기
        val margin: Int = 1,                  // Quiet zone (1~4 권장)
        val ecLevel: ErrorCorrectionLevel = ErrorCorrectionLevel.M,  // 오류 정정 레벨
        val fgColor: Int = Color.BLACK,       // 전경색 (QR 점)
        val bgColor: Int = Color.WHITE,       // 배경색
        val transparentBg: Boolean = false,   // 배경 투명 여부
        val centerLogo: Bitmap? = null,       // 중앙 로고 (선택)
        val logoScale: Float = 0.2f           // 로고 크기 (QR 대비 비율, 0.15~0.25 권장)
    )

    /** 기존 시그니처 유지 (호환성용) */
    fun generate(text: String, size: Int = 512): Bitmap {
        return generate(text, Options(size = size))
    }

    /** 옵션 기반 QR 생성 */
    fun generate(text: String, opt: Options): Bitmap {
        require(opt.size > 0) { "size must be > 0" }
        require(opt.logoScale in 0f..0.5f) { "logoScale must be between 0 and 0.5" }

        // ZXing 힌트 설정
        val hints = hashMapOf<EncodeHintType, Any>(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to opt.ecLevel,
            EncodeHintType.MARGIN to opt.margin
        )

        val matrix = QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            opt.size,
            opt.size,
            hints
        )

        // KTX createBitmap 사용
        val bmp = createBitmap(opt.size, opt.size)
        val bg = if (opt.transparentBg) Color.TRANSPARENT else opt.bgColor

        // 픽셀 채우기 (KTX set[] 사용)
        for (x in 0 until opt.size) {
            for (y in 0 until opt.size) {
                bmp[x, y] = if (matrix[x, y]) opt.fgColor else bg
            }
        }

        // 중앙 로고 오버레이 (선택)
        opt.centerLogo?.takeIf { !it.isRecycled && opt.logoScale > 0f }?.let { logo ->
            val canvas = Canvas(bmp)
            val side = (opt.size * opt.logoScale).toInt().coerceAtLeast(1)

            // KTX scale() 사용
            val scaledLogo = logo.scale(side, side)

            val left = (opt.size - side) / 2f
            val top = (opt.size - side) / 2f

            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(scaledLogo, left, top, paint)
        }

        return bmp
    }
}
