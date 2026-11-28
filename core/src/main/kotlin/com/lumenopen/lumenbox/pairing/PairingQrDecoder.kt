package com.lumenopen.lumenbox.pairing

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.qrcode.QRCodeMultiReader
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
 * - Tries `HybridBinarizer` first, falling back to `GlobalHistogramBinarizer` when high-density QR modules
 *   are small relative to 8x8 local block thresholds.
 * - Some valid pairing codes (2-5 % by payload, all Version 8) carry a finder-like 1:1:3:1:1 block in their data
 *   area. ZXing's single-candidate detector stops scanning once it has three consistent candidates and
 *   picks that block instead of the real bottom-left finder. `QRCodeMultiReader` tries every candidate
 *   triple, so it is the second tier; it works on camera frames, unlike `PURE_BARCODE` (INV-2302).
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
        val reader = QRCodeReader()
        val hybrid = BinaryBitmap(HybridBinarizer(source))
        val decodedText = tryDecodeBitmap(reader, hybrid, hints)
            ?: tryDecodeAnyCandidate(hybrid)
            ?: tryDecodeBitmap(reader, BinaryBitmap(GlobalHistogramBinarizer(source)), hints)
            ?: return QrScanOutcome.NoQrFound
        return evaluateDecodedText(decodedText, nowEpochMillis)
    }

    /** Every finder-pattern triple, not just the first three that agree; a pairing code is preferred. */
    private fun tryDecodeAnyCandidate(bitmap: BinaryBitmap): String? {
        val texts = try {
            QRCodeMultiReader().decodeMultiple(bitmap, hints).map { it.text }
        } catch (_: ReaderException) {
            return null
        }
        return texts.firstOrNull { it.trim().lowercase().startsWith("lumenbox://") } ?: texts.firstOrNull()
    }

    private fun tryDecodeBitmap(
        reader: QRCodeReader,
        bitmap: BinaryBitmap,
        decodeHints: Map<DecodeHintType, Any>,
    ): String? =
        try {
            reader.decode(bitmap, decodeHints).text
        } catch (_: ReaderException) {
            null
        } finally {
            reader.reset()
        }
}
