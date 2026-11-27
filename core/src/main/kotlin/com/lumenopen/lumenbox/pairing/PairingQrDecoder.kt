package com.lumenopen.lumenbox.pairing

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.lumenopen.lumenbox.relay.NoiseRelayException
import com.lumenopen.lumenbox.relay.ParsedPairingOffer

sealed interface QrScanOutcome {
    data class ValidOffer(val rawUri: String, val offer: ParsedPairingOffer) : QrScanOutcome
    data class RejectedOffer(val code: String, val reason: String) : QrScanOutcome
    data class IgnoredNonLumenBox(val rawText: String) : QrScanOutcome
    data object NoQrFound : QrScanOutcome
}

/**
 * Offline Apache-2.0 QR decoder (`docs/110 §3.2`, INV-2254).
 *
 * - Decodes CameraX `ImageFormat.YUV_420_888` Y-plane luminance directly (including padded `rowStride > width`),
 *   requiring zero Google Play Services so domestic and enterprise air-gapped Android phones work identically.
 * - Enforces `#fragment`-only `lumenbox://pair#...` validation and rejects query-string leaks or expired codes.
 */
object PairingQrDecoder {
    private val hints: Map<DecodeHintType, Any> = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.CHARACTER_SET to "UTF-8",
        DecodeHintType.TRY_HARDER to true,
    )

    fun evaluateDecodedText(
        rawText: String,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): QrScanOutcome {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return QrScanOutcome.NoQrFound
        val lower = trimmed.lowercase()
        val looksLikeLumenBoxPair = lower.startsWith("lumenbox://pair") ||
            lower.contains("pairsecret=") ||
            (lower.startsWith("http") && lower.contains("/pair") && lower.contains("hostpub="))
        if (!looksLikeLumenBoxPair && !lower.startsWith("lumenbox://")) {
            return QrScanOutcome.IgnoredNonLumenBox(trimmed)
        }
        return try {
            val offer = ParsedPairingOffer.parse(trimmed, nowEpochMillis)
            QrScanOutcome.ValidOffer(trimmed, offer)
        } catch (err: NoiseRelayException) {
            QrScanOutcome.RejectedOffer(err.code, err.message ?: "Invalid pairing QR")
        }
    }

    fun decodeYPlane(
        yPlane: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int = width,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): QrScanOutcome {
        if (width <= 0 || height <= 0 || rowStride < width) return QrScanOutcome.NoQrFound
        val requiredBytes = (height - 1) * rowStride + width
        if (yPlane.size < requiredBytes) return QrScanOutcome.NoQrFound

        val compactY = if (rowStride == width) {
            yPlane
        } else {
            ByteArray(width * height).also { dst ->
                for (row in 0 until height) {
                    System.arraycopy(yPlane, row * rowStride, dst, row * width, width)
                }
            }
        }

        val source = PlanarYUVLuminanceSource(
            compactY,
            width,
            height,
            0,
            0,
            width,
            height,
            false,
        )
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        val reader = QRCodeReader()
        val decodedText = try {
            reader.decode(bitmap, hints).text
        } catch (_: ReaderException) {
            return QrScanOutcome.NoQrFound
        } finally {
            reader.reset()
        }
        return evaluateDecodedText(decodedText, nowEpochMillis)
    }
}
