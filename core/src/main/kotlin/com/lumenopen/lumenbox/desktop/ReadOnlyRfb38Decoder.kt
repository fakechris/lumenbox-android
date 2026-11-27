package com.lumenopen.lumenbox.desktop

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

class RfbProtocolException(
    val code: String,
    override val message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

data class RfbFrameSnapshot(
    val width: Int,
    val height: Int,
    val desktopName: String,
    /** Packed ARGB_8888 (`0xFFRRGGBB`) pixel array of length `width * height`. */
    val argbPixels: IntArray,
    val frameNumber: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RfbFrameSnapshot) return false
        return width == other.width &&
            height == other.height &&
            desktopName == other.desktopName &&
            frameNumber == other.frameNumber &&
            argbPixels.contentEquals(other.argbPixels)
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + desktopName.hashCode()
        result = 31 * result + argbPixels.contentHashCode()
        result = 31 * result + frameNumber.hashCode()
        return result
    }
}

/**
 * Pure Kotlin read-only RFB 3.8 (VNC) stream state machine and pixel decoder
 * (`docs/106 §5 R6`, `docs/110 §0.7, §4.4`, INV-2258).
 *
 * Security & Read-Only Invariants:
 * 1. **Zero Input Encodings**: This class physically implements NO `KeyEvent` (message type 4),
 *    NO `PointerEvent` (message type 5), and NO `ClientCutText` (message type 6) encoder or API.
 *    The ONLY client-to-server bytes ever emitted are:
 *    - `"RFB 003.008\n"` handshake banner
 *    - `1` (`SecurityType = None`)
 *    - `1` (`ClientInit shared = 1`)
 *    - Message `0` (`SetPixelFormat`: 32bpp, 24-bit true-colour RGBX)
 *    - Message `2` (`SetEncodings`: `CopyRect` `1`, `Raw` `0`, `DesktopSize` `-223`)
 *    - Message `3` (`FramebufferUpdateRequest`: initial full frame, then incremental)
 * 2. Bounds-checks dimensions (`<= 4096 x 4096`), rectangle coordinates, and server cut-text length.
 */
class ReadOnlyRfb38Decoder(
    private val sendToServer: (ByteArray) -> Unit,
    private val onFrameUpdated: (RfbFrameSnapshot) -> Unit,
) {
    companion object {
        val RFB_3_8_VERSION_BANNER: ByteArray = "RFB 003.008\n".toByteArray(StandardCharsets.US_ASCII)
        const val MAX_DIMENSION = 4096
        private const val MAX_CUT_TEXT_BYTES = 1_048_576

        // Allowed client-to-server message types (0 = SetPixelFormat, 2 = SetEncodings, 3 = FramebufferUpdateRequest)
        const val CLIENT_MSG_SET_PIXEL_FORMAT: Byte = 0
        const val CLIENT_MSG_SET_ENCODINGS: Byte = 2
        const val CLIENT_MSG_FRAMEBUFFER_UPDATE_REQUEST: Byte = 3

        // Server-to-client message types
        private const val SERVER_MSG_FRAMEBUFFER_UPDATE = 0
        private const val SERVER_MSG_SET_COLOUR_MAP_ENTRIES = 1
        private const val SERVER_MSG_BELL = 2
        private const val SERVER_MSG_SERVER_CUT_TEXT = 3

        // Encodings
        const val ENCODING_RAW = 0
        const val ENCODING_COPY_RECT = 1
        const val ENCODING_DESKTOP_SIZE = -223
    }

    private enum class Phase {
        SERVER_VERSION,
        SECURITY_TYPES,
        SECURITY_REASON_FAILURE,
        SECURITY_RESULT,
        SERVER_INIT_HEADER,
        SERVER_INIT_NAME,
        SERVER_MESSAGE_TYPE,
        FB_UPDATE_HEADER,
        FB_RECT_HEADER,
        FB_RECT_RAW_DATA,
        FB_RECT_COPY_DATA,
        COLOUR_MAP_HEADER,
        COLOUR_MAP_ENTRIES,
        CUT_TEXT_HEADER,
        CUT_TEXT_PAYLOAD,
    }

    private val lock = Any()
    private var buffer = ByteArray(65_536)
    private var readPos = 0
    private var writePos = 0

    private var phase = Phase.SERVER_VERSION
    private var failureReasonLength = 0

    private var fbWidth = 0
    private var fbHeight = 0
    private var desktopName = ""
    private var serverNameByteLength = 0
    private var argbBuffer = IntArray(0)
    private var frameCounter = 0L

    private var remainingRects = 0
    private var rectX = 0
    private var rectY = 0
    private var rectW = 0
    private var rectH = 0
    private var rectEncoding = 0

    private var colourMapEntryCount = 0
    private var cutTextByteLength = 0

    fun currentSnapshot(): RfbFrameSnapshot? = synchronized(lock) {
        if (fbWidth <= 0 || fbHeight <= 0) null
        else RfbFrameSnapshot(fbWidth, fbHeight, desktopName, argbBuffer.copyOf(), frameCounter)
    }

    /** Feeds arbitrary inbound byte chunks from WebSocket or `RelayMux` (`vnc-ro` stream). */
    fun onServerBytes(chunk: ByteArray) {
        if (chunk.isEmpty()) return
        var pendingCallbacks: MutableList<RfbFrameSnapshot>? = null
        val outboundQueue = mutableListOf<ByteArray>()

        synchronized(lock) {
            appendBytesLocked(chunk)
            while (true) {
                val progressed = stepLocked(
                    emitOutbound = { outboundQueue.add(it) },
                    emitSnapshot = { snap ->
                        if (pendingCallbacks == null) pendingCallbacks = mutableListOf()
                        pendingCallbacks!!.add(snap)
                    },
                )
                if (!progressed) break
            }
        }

        outboundQueue.forEach { sendToServer(it) }
        pendingCallbacks?.forEach { onFrameUpdated(it) }
    }

    /** Explicitly requests a non-incremental full-screen refresh (still strictly read-only msg type 3). */
    fun requestFullRefresh() {
        val packet: ByteArray? = synchronized(lock) {
            if (fbWidth <= 0 || fbHeight <= 0) null
            else encodeFramebufferUpdateRequest(incremental = false, x = 0, y = 0, width = fbWidth, height = fbHeight)
        }
        if (packet != null) {
            sendToServer(packet)
        }
    }

    private fun stepLocked(
        emitOutbound: (ByteArray) -> Unit,
        emitSnapshot: (RfbFrameSnapshot) -> Unit,
    ): Boolean {
        return when (phase) {
            Phase.SERVER_VERSION -> {
                if (availableLocked() < 12) return false
                val ver = String(takeBytesLocked(12), StandardCharsets.US_ASCII)
                if (!ver.startsWith("RFB ") || !ver.endsWith("\n")) {
                    throw RfbProtocolException("invalid_rfb_banner", "Invalid RFB server banner: $ver")
                }
                emitOutbound(RFB_3_8_VERSION_BANNER.copyOf())
                phase = Phase.SECURITY_TYPES
                true
            }

            Phase.SECURITY_TYPES -> {
                if (availableLocked() < 1) return false
                val count = peekU8Locked(0)
                if (count == 0) {
                    if (availableLocked() < 5) return false
                    skipLocked(1)
                    failureReasonLength = readIntBELocked()
                    if (failureReasonLength < 0 || failureReasonLength > MAX_CUT_TEXT_BYTES) {
                        throw RfbProtocolException("security_failed", "RFB security handshake rejected by server")
                    }
                    phase = Phase.SECURITY_REASON_FAILURE
                    return true
                }
                if (availableLocked() < 1 + count) return false
                skipLocked(1)
                val secTypes = takeBytesLocked(count)
                // SecurityType 1 = None (authenticated upstream via single-use 60s view ticket or Noise_IK)
                if (secTypes.none { it.toInt() == 1 }) {
                    throw RfbProtocolException(
                        "unsupported_security_type",
                        "Expected RFB SecurityType=None (1) behind view ticket, got ${secTypes.map { it.toInt() and 0xff }}",
                    )
                }
                emitOutbound(byteArrayOf(1))
                phase = Phase.SECURITY_RESULT
                true
            }

            Phase.SECURITY_REASON_FAILURE -> {
                if (availableLocked() < failureReasonLength) return false
                val reason = String(takeBytesLocked(failureReasonLength), StandardCharsets.UTF_8)
                throw RfbProtocolException("security_failed", "RFB server refused connection: $reason")
            }

            Phase.SECURITY_RESULT -> {
                if (availableLocked() < 4) return false
                val status = readIntBELocked()
                if (status != 0) {
                    if (availableLocked() >= 4) {
                        failureReasonLength = readIntBELocked().coerceIn(0, MAX_CUT_TEXT_BYTES)
                        phase = Phase.SECURITY_REASON_FAILURE
                        return true
                    }
                    throw RfbProtocolException("security_result_failed", "RFB SecurityResult status=$status")
                }
                // ClientInit: shared = 1 (read-only shared view; never kick other viewers)
                emitOutbound(byteArrayOf(1))
                phase = Phase.SERVER_INIT_HEADER
                true
            }

            Phase.SERVER_INIT_HEADER -> {
                // width(2) + height(2) + pixelFormat(16) + nameLength(4) = 24 bytes
                if (availableLocked() < 24) return false
                val w = readU16BELocked()
                val h = readU16BELocked()
                if (w <= 0 || h <= 0 || w > MAX_DIMENSION || h > MAX_DIMENSION) {
                    throw RfbProtocolException("invalid_dimensions", "Invalid RFB desktop size: ${w}x${h}")
                }
                skipLocked(16) // Server default PixelFormat (we immediately send SetPixelFormat!)
                val nameLen = readIntBELocked()
                if (nameLen < 0 || nameLen > 65_536) {
                    throw RfbProtocolException("invalid_name_length", "Invalid RFB desktop name length: $nameLen")
                }
                fbWidth = w
                fbHeight = h
                argbBuffer = IntArray(w * h) { 0xFF000000.toInt() }
                serverNameByteLength = nameLen
                phase = Phase.SERVER_INIT_NAME
                true
            }

            Phase.SERVER_INIT_NAME -> {
                if (availableLocked() < serverNameByteLength) return false
                desktopName = String(takeBytesLocked(serverNameByteLength), StandardCharsets.UTF_8)
                // Configure 32bpp true-colour little-endian RGBX (R shift 16, G shift 8, B shift 0)
                emitOutbound(encodeSetPixelFormat32BppArgb())
                emitOutbound(encodeSetEncodings())
                emitOutbound(encodeFramebufferUpdateRequest(incremental = false, x = 0, y = 0, width = fbWidth, height = fbHeight))
                phase = Phase.SERVER_MESSAGE_TYPE
                true
            }

            Phase.SERVER_MESSAGE_TYPE -> {
                if (availableLocked() < 1) return false
                when (val msgType = readU8Locked()) {
                    SERVER_MSG_FRAMEBUFFER_UPDATE -> {
                        phase = Phase.FB_UPDATE_HEADER
                        true
                    }
                    SERVER_MSG_SET_COLOUR_MAP_ENTRIES -> {
                        phase = Phase.COLOUR_MAP_HEADER
                        true
                    }
                    SERVER_MSG_BELL -> {
                        // Read-only mobile viewer ignores audible bell
                        true
                    }
                    SERVER_MSG_SERVER_CUT_TEXT -> {
                        phase = Phase.CUT_TEXT_HEADER
                        true
                    }
                    else -> throw RfbProtocolException(
                        "unknown_server_message",
                        "Unsupported RFB server message type: $msgType",
                    )
                }
            }

            Phase.FB_UPDATE_HEADER -> {
                // padding(1) + number-of-rectangles(2) = 3 bytes
                if (availableLocked() < 3) return false
                skipLocked(1)
                remainingRects = readU16BELocked()
                if (remainingRects == 0) {
                    finishFramebufferUpdateLocked(emitOutbound, emitSnapshot)
                } else {
                    phase = Phase.FB_RECT_HEADER
                }
                true
            }

            Phase.FB_RECT_HEADER -> {
                // x(2) + y(2) + w(2) + h(2) + encoding-type(4) = 12 bytes
                if (availableLocked() < 12) return false
                rectX = readU16BELocked()
                rectY = readU16BELocked()
                rectW = readU16BELocked()
                rectH = readU16BELocked()
                rectEncoding = readIntBELocked()

                when (rectEncoding) {
                    ENCODING_DESKTOP_SIZE -> {
                        if (rectW <= 0 || rectH <= 0 || rectW > MAX_DIMENSION || rectH > MAX_DIMENSION) {
                            throw RfbProtocolException("invalid_resize", "Invalid DesktopSize pseudo-rect: ${rectW}x${rectH}")
                        }
                        fbWidth = rectW
                        fbHeight = rectH
                        argbBuffer = IntArray(fbWidth * fbHeight) { 0xFF000000.toInt() }
                        advanceAfterRectLocked(emitOutbound, emitSnapshot)
                        true
                    }

                    ENCODING_RAW -> {
                        validateRectBoundsLocked(rectX, rectY, rectW, rectH)
                        if (rectW == 0 || rectH == 0) {
                            advanceAfterRectLocked(emitOutbound, emitSnapshot)
                        } else {
                            phase = Phase.FB_RECT_RAW_DATA
                        }
                        true
                    }

                    ENCODING_COPY_RECT -> {
                        validateRectBoundsLocked(rectX, rectY, rectW, rectH)
                        phase = Phase.FB_RECT_COPY_DATA
                        true
                    }

                    else -> throw RfbProtocolException(
                        "unsupported_encoding",
                        "Unexpected RFB encoding $rectEncoding",
                    )
                }
            }

            Phase.FB_RECT_RAW_DATA -> {
                val byteCount = rectW * rectH * 4
                if (availableLocked() < byteCount) return false
                var srcIdx = readPos
                for (row in 0 until rectH) {
                    val dstRowStart = (rectY + row) * fbWidth + rectX
                    for (col in 0 until rectW) {
                        // Little-endian 32bpp with redShift=16, greenShift=8, blueShift=0:
                        // byte[0]=blue, byte[1]=green, byte[2]=red, byte[3]=unused
                        val b = buffer[srcIdx].toInt() and 0xff
                        val g = buffer[srcIdx + 1].toInt() and 0xff
                        val r = buffer[srcIdx + 2].toInt() and 0xff
                        argbBuffer[dstRowStart + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                        srcIdx += 4
                    }
                }
                skipLocked(byteCount)
                advanceAfterRectLocked(emitOutbound, emitSnapshot)
                true
            }

            Phase.FB_RECT_COPY_DATA -> {
                if (availableLocked() < 4) return false
                val srcX = readU16BELocked()
                val srcY = readU16BELocked()
                validateRectBoundsLocked(srcX, srcY, rectW, rectH)
                if (rectW > 0 && rectH > 0) {
                    val temp = IntArray(rectW * rectH)
                    for (row in 0 until rectH) {
                        System.arraycopy(
                            argbBuffer,
                            (srcY + row) * fbWidth + srcX,
                            temp,
                            row * rectW,
                            rectW,
                        )
                    }
                    for (row in 0 until rectH) {
                        System.arraycopy(
                            temp,
                            row * rectW,
                            argbBuffer,
                            (rectY + row) * fbWidth + rectX,
                            rectW,
                        )
                    }
                }
                advanceAfterRectLocked(emitOutbound, emitSnapshot)
                true
            }

            Phase.COLOUR_MAP_HEADER -> {
                if (availableLocked() < 5) return false
                skipLocked(1) // padding
                skipLocked(2) // first-colour
                colourMapEntryCount = readU16BELocked()
                phase = Phase.COLOUR_MAP_ENTRIES
                true
            }

            Phase.COLOUR_MAP_ENTRIES -> {
                val bytesNeeded = colourMapEntryCount * 6
                if (availableLocked() < bytesNeeded) return false
                skipLocked(bytesNeeded)
                phase = Phase.SERVER_MESSAGE_TYPE
                true
            }

            Phase.CUT_TEXT_HEADER -> {
                // padding(3) + length(4) = 7 bytes
                if (availableLocked() < 7) return false
                skipLocked(3)
                val len = readIntBELocked()
                if (len < 0 || len > MAX_CUT_TEXT_BYTES) {
                    throw RfbProtocolException("cut_text_too_large", "ServerCutText length out of bounds: $len")
                }
                cutTextByteLength = len
                phase = Phase.CUT_TEXT_PAYLOAD
                true
            }

            Phase.CUT_TEXT_PAYLOAD -> {
                if (availableLocked() < cutTextByteLength) return false
                skipLocked(cutTextByteLength) // Strictly ignored: never writes remote clipboard to phone clipboard
                phase = Phase.SERVER_MESSAGE_TYPE
                true
            }
        }
    }

    private fun advanceAfterRectLocked(
        emitOutbound: (ByteArray) -> Unit,
        emitSnapshot: (RfbFrameSnapshot) -> Unit,
    ) {
        remainingRects -= 1
        if (remainingRects <= 0) {
            finishFramebufferUpdateLocked(emitOutbound, emitSnapshot)
        } else {
            phase = Phase.FB_RECT_HEADER
        }
    }

    private fun finishFramebufferUpdateLocked(
        emitOutbound: (ByteArray) -> Unit,
        emitSnapshot: (RfbFrameSnapshot) -> Unit,
    ) {
        frameCounter += 1L
        emitSnapshot(
            RfbFrameSnapshot(
                width = fbWidth,
                height = fbHeight,
                desktopName = desktopName,
                argbPixels = argbBuffer.copyOf(),
                frameNumber = frameCounter,
            )
        )
        emitOutbound(
            encodeFramebufferUpdateRequest(
                incremental = true,
                x = 0,
                y = 0,
                width = fbWidth,
                height = fbHeight,
            )
        )
        phase = Phase.SERVER_MESSAGE_TYPE
    }

    private fun validateRectBoundsLocked(x: Int, y: Int, w: Int, h: Int) {
        if (x < 0 || y < 0 || w < 0 || h < 0 || x + w > fbWidth || y + h > fbHeight) {
            throw RfbProtocolException(
                "rect_out_of_bounds",
                "Rectangle ($x,$y ${w}x${h}) exceeds framebuffer ${fbWidth}x${fbHeight}",
            )
        }
    }

    private fun encodeSetPixelFormat32BppArgb(): ByteArray {
        val buf = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN)
        buf.put(CLIENT_MSG_SET_PIXEL_FORMAT)
        buf.put(0); buf.put(0); buf.put(0) // 3 bytes padding
        buf.put(32) // bits-per-pixel = 32
        buf.put(24) // depth = 24
        buf.put(0) // big-endian-flag = 0 (little-endian)
        buf.put(1) // true-colour-flag = 1
        buf.putShort(255) // red-max
        buf.putShort(255) // green-max
        buf.putShort(255) // blue-max
        buf.put(16) // red-shift
        buf.put(8) // green-shift
        buf.put(0) // blue-shift
        buf.put(0); buf.put(0); buf.put(0) // pixelFormat padding
        return buf.array()
    }

    private fun encodeSetEncodings(): ByteArray {
        val encodings = intArrayOf(ENCODING_COPY_RECT, ENCODING_RAW, ENCODING_DESKTOP_SIZE)
        val buf = ByteBuffer.allocate(4 + encodings.size * 4).order(ByteOrder.BIG_ENDIAN)
        buf.put(CLIENT_MSG_SET_ENCODINGS)
        buf.put(0) // padding
        buf.putShort(encodings.size.toShort())
        for (enc in encodings) {
            buf.putInt(enc)
        }
        return buf.array()
    }

    private fun encodeFramebufferUpdateRequest(
        incremental: Boolean,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ): ByteArray {
        val buf = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN)
        buf.put(CLIENT_MSG_FRAMEBUFFER_UPDATE_REQUEST)
        buf.put(if (incremental) 1.toByte() else 0.toByte())
        buf.putShort(x.toShort())
        buf.putShort(y.toShort())
        buf.putShort(width.toShort())
        buf.putShort(height.toShort())
        return buf.array()
    }

    // ---- Ring/Slice Buffer Helpers ----

    private fun availableLocked(): Int = writePos - readPos

    private fun appendBytesLocked(chunk: ByteArray) {
        val unread = availableLocked()
        if (readPos > 0 && writePos + chunk.size > buffer.size) {
            System.arraycopy(buffer, readPos, buffer, 0, unread)
            readPos = 0
            writePos = unread
        }
        if (writePos + chunk.size > buffer.size) {
            var nextCap = buffer.size.coerceAtLeast(1024)
            while (nextCap < unread + chunk.size) {
                nextCap *= 2
            }
            val grown = ByteArray(nextCap)
            System.arraycopy(buffer, readPos, grown, 0, unread)
            buffer = grown
            readPos = 0
            writePos = unread
        }
        System.arraycopy(chunk, 0, buffer, writePos, chunk.size)
        writePos += chunk.size
    }

    private fun peekU8Locked(offset: Int): Int =
        buffer[readPos + offset].toInt() and 0xff

    private fun readU8Locked(): Int {
        val v = buffer[readPos].toInt() and 0xff
        readPos += 1
        compactIfEmptyLocked()
        return v
    }

    private fun readU16BELocked(): Int {
        val b0 = buffer[readPos].toInt() and 0xff
        val b1 = buffer[readPos + 1].toInt() and 0xff
        readPos += 2
        compactIfEmptyLocked()
        return (b0 shl 8) or b1
    }

    private fun readIntBELocked(): Int {
        val b0 = buffer[readPos].toInt() and 0xff
        val b1 = buffer[readPos + 1].toInt() and 0xff
        val b2 = buffer[readPos + 2].toInt() and 0xff
        val b3 = buffer[readPos + 3].toInt() and 0xff
        readPos += 4
        compactIfEmptyLocked()
        return (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
    }

    private fun skipLocked(count: Int) {
        readPos += count
        compactIfEmptyLocked()
    }

    private fun takeBytesLocked(count: Int): ByteArray {
        val out = buffer.copyOfRange(readPos, readPos + count)
        readPos += count
        compactIfEmptyLocked()
        return out
    }

    private fun compactIfEmptyLocked() {
        if (readPos == writePos) {
            readPos = 0
            writePos = 0
        }
    }
}
