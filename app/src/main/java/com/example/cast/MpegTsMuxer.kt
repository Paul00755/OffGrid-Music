package com.example.cast

import java.io.ByteArrayOutputStream

/**
 * Pure-Kotlin ISO/IEC 13818-1 MPEG-2 Transport Stream (.ts) segment muxer for Chromecast HLS.
 *
 * Produces standards-compliant 188-byte TS packets containing:
 * - PAT (PID 0x0000) and PMT (PID 0x1000) with valid MPEG-2 CRC32
 * - H.264 / AVC video PES (PID 0x0100, stream_type 0x1B) with AUD + SPS/PPS on IDR keyframes and PCR
 * - ADTS AAC-LC audio PES (PID 0x0101, stream_type 0x0F) with 90 kHz PTS timestamps
 */
class MpegTsMuxer(
    initialPatCc: Int = 0,
    initialPmtCc: Int = 0,
    initialVideoCc: Int = 0,
    initialAudioCc: Int = 0
) {
    private val out = ByteArrayOutputStream(256 * 1024)

    var patCc: Int = initialPatCc and 0x0F
        private set
    var pmtCc: Int = initialPmtCc and 0x0F
        private set
    var videoCc: Int = initialVideoCc and 0x0F
        private set
    var audioCc: Int = initialAudioCc and 0x0F
        private set

    fun writePatAndPmt() {
        writePat()
        writePmt()
    }

    fun writePat() {
        val section = byteArrayOf(
            0x00, // table_id = PAT
            0xB0.toByte(), 0x0D, // section_syntax_indicator=1, length=13
            0x00, 0x01, // transport_stream_id = 1
            0xC1.toByte(), // version 0, current_next=1
            0x00, // section_number
            0x00, // last_section_number
            0x00, 0x01, // program_number = 1
            (0xE0 or ((PID_PMT ushr 8) and 0x1F)).toByte(),
            (PID_PMT and 0xFF).toByte()
        )
        writePsiPacket(PID_PAT, patCc, section)
        patCc = (patCc + 1) and 0x0F
    }

    fun writePmt() {
        val section = byteArrayOf(
            0x02, // table_id = PMT
            0xB0.toByte(), 0x17, // section_syntax_indicator=1, length=23
            0x00, 0x01, // program_number = 1
            0xC1.toByte(), // version 0, current_next=1
            0x00, // section_number
            0x00, // last_section_number
            (0xE0 or ((PID_VIDEO ushr 8) and 0x1F)).toByte(),
            (PID_VIDEO and 0xFF).toByte(), // PCR_PID = video PID
            0xF0.toByte(), 0x00, // program_info_length = 0
            // Stream 1: H.264 / AVC
            STREAM_TYPE_H264.toByte(),
            (0xE0 or ((PID_VIDEO ushr 8) and 0x1F)).toByte(),
            (PID_VIDEO and 0xFF).toByte(),
            0xF0.toByte(), 0x00,
            // Stream 2: ADTS AAC
            STREAM_TYPE_AAC.toByte(),
            (0xE0 or ((PID_AUDIO ushr 8) and 0x1F)).toByte(),
            (PID_AUDIO and 0xFF).toByte(),
            0xF0.toByte(), 0x00
        )
        writePsiPacket(PID_PMT, pmtCc, section)
        pmtCc = (pmtCc + 1) and 0x0F
    }

    /**
     * Writes one H.264 video access unit (Annex-B) wrapped in PES packets on PID_VIDEO.
     * On keyframes, prepends AUD + SPS/PPS (if not already present) and attaches PCR + random_access_indicator.
     */
    fun writeVideoFrame(
        annexBData: ByteArray,
        pts90k: Long,
        isKeyframe: Boolean,
        spsPpsAnnexB: ByteArray? = null
    ) {
        if (annexBData.isEmpty()) return

        val payloadBuf = ByteArrayOutputStream(annexBData.size + (spsPpsAnnexB?.size ?: 0) + 16)
        // Always start video AU with H.264 Access Unit Delimiter (AUD) for strict HLS/MSE parsers
        if (!startsWithAud(annexBData)) {
            payloadBuf.write(H264_AUD)
        }
        if (isKeyframe && spsPpsAnnexB != null && spsPpsAnnexB.isNotEmpty() && !containsSps(annexBData)) {
            payloadBuf.write(spsPpsAnnexB)
        }
        payloadBuf.write(annexBData)
        val esPayload = payloadBuf.toByteArray()

        val pesHeader = buildPesHeader(
            streamId = STREAM_ID_VIDEO,
            payloadLength = 0, // Unbounded video PES is standard in MPEG-TS
            pts90k = pts90k
        )
        val fullPes = ByteArray(pesHeader.size + esPayload.size)
        System.arraycopy(pesHeader, 0, fullPes, 0, pesHeader.size)
        System.arraycopy(esPayload, 0, fullPes, pesHeader.size, esPayload.size)

        videoCc = writePesPackets(
            pid = PID_VIDEO,
            startCc = videoCc,
            pesData = fullPes,
            includePcr = isKeyframe,
            pcr90k = pts90k,
            randomAccess = isKeyframe
        )
    }

    /**
     * Writes one or more concatenated ADTS AAC frames wrapped in a single PES packet on PID_AUDIO.
     */
    fun writeAudioAdtsChunk(
        adtsData: ByteArray,
        pts90k: Long
    ) {
        if (adtsData.isEmpty()) return
        val pesPayloadLen = adtsData.size + 8 // 3 optional header bytes + 5 PTS bytes
        val safeLen = if (pesPayloadLen <= 0xFFFF) pesPayloadLen else 0
        val pesHeader = buildPesHeader(
            streamId = STREAM_ID_AUDIO,
            payloadLength = safeLen,
            pts90k = pts90k
        )
        val fullPes = ByteArray(pesHeader.size + adtsData.size)
        System.arraycopy(pesHeader, 0, fullPes, 0, pesHeader.size)
        System.arraycopy(adtsData, 0, fullPes, pesHeader.size, adtsData.size)

        audioCc = writePesPackets(
            pid = PID_AUDIO,
            startCc = audioCc,
            pesData = fullPes,
            includePcr = false,
            pcr90k = 0L,
            randomAccess = true
        )
    }

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun writePsiPacket(pid: Int, cc: Int, sectionWithoutCrc: ByteArray) {
        val packet = ByteArray(TS_PACKET_SIZE) { 0xFF.toByte() }
        packet[0] = TS_SYNC_BYTE
        packet[1] = (0x40 or ((pid ushr 8) and 0x1F)).toByte() // PUSI = 1
        packet[2] = (pid and 0xFF).toByte()
        packet[3] = (0x10 or (cc and 0x0F)).toByte() // payload only
        packet[4] = 0x00 // pointer field = 0

        System.arraycopy(sectionWithoutCrc, 0, packet, 5, sectionWithoutCrc.size)
        val crc = mpeg2Crc32(sectionWithoutCrc)
        val crcPos = 5 + sectionWithoutCrc.size
        packet[crcPos] = ((crc ushr 24) and 0xFF).toByte()
        packet[crcPos + 1] = ((crc ushr 16) and 0xFF).toByte()
        packet[crcPos + 2] = ((crc ushr 8) and 0xFF).toByte()
        packet[crcPos + 3] = (crc and 0xFF).toByte()

        out.write(packet)
    }

    private fun writePesPackets(
        pid: Int,
        startCc: Int,
        pesData: ByteArray,
        includePcr: Boolean,
        pcr90k: Long,
        randomAccess: Boolean
    ): Int {
        var cc = startCc and 0x0F
        var offset = 0
        var first = true

        while (offset < pesData.size) {
            val packet = ByteArray(TS_PACKET_SIZE)
            packet[0] = TS_SYNC_BYTE
            packet[1] = ((if (first) 0x40 else 0x00) or ((pid ushr 8) and 0x1F)).toByte()
            packet[2] = (pid and 0xFF).toByte()

            val remaining = pesData.size - offset
            val needPcrHere = first && includePcr
            val needRaiHere = first && randomAccess && !needPcrHere
            val minAdaptBytes = when {
                needPcrHere -> 8 // 1 length + 1 flags + 6 PCR
                needRaiHere -> 2 // 1 length + 1 flags
                else -> 0
            }
            val maxPayload = TS_PAYLOAD_MAX - minAdaptBytes
            val payloadBytes = minOf(remaining, maxPayload)
            val stuffingBytes = maxPayload - payloadBytes
            val hasAdaptation = minAdaptBytes > 0 || stuffingBytes > 0

            if (!hasAdaptation) {
                packet[3] = (0x10 or cc).toByte() // payload only
                System.arraycopy(pesData, offset, packet, 4, payloadBytes)
            } else {
                packet[3] = (0x30 or cc).toByte() // adaptation + payload
                val adaptTotalBytes = TS_PAYLOAD_MAX - payloadBytes // >= 1
                val adaptFieldLength = adaptTotalBytes - 1
                packet[4] = adaptFieldLength.toByte()

                var cursor = 5
                if (adaptFieldLength > 0) {
                    var flags = 0
                    if (first && randomAccess) flags = flags or 0x40
                    if (needPcrHere) flags = flags or 0x10
                    packet[cursor++] = flags.toByte()

                    if (needPcrHere) {
                        val base = pcr90k and 0x1FFFFFFFFL
                        packet[cursor++] = ((base ushr 25) and 0xFFL).toByte()
                        packet[cursor++] = ((base ushr 17) and 0xFFL).toByte()
                        packet[cursor++] = ((base ushr 9) and 0xFFL).toByte()
                        packet[cursor++] = ((base ushr 1) and 0xFFL).toByte()
                        packet[cursor++] = ((((base and 1L) shl 7) or 0x7EL) and 0xFFL).toByte()
                        packet[cursor++] = 0x00
                    }
                    val adaptEnd = 4 + adaptTotalBytes
                    while (cursor < adaptEnd) {
                        packet[cursor++] = 0xFF.toByte()
                    }
                }
                System.arraycopy(pesData, offset, packet, 4 + adaptTotalBytes, payloadBytes)
            }

            out.write(packet)
            offset += payloadBytes
            cc = (cc + 1) and 0x0F
            first = false
        }
        return cc
    }

    private fun buildPesHeader(streamId: Int, payloadLength: Int, pts90k: Long): ByteArray {
        val pts = pts90k and 0x1FFFFFFFFL
        return byteArrayOf(
            0x00, 0x00, 0x01,
            streamId.toByte(),
            ((payloadLength ushr 8) and 0xFF).toByte(),
            (payloadLength and 0xFF).toByte(),
            0x80.toByte(), // marker bits '10'
            0x80.toByte(), // PTS only (PTS_DTS_flags = 10)
            0x05, // PES_header_data_length = 5 bytes
            (0x21L or ((pts ushr 29) and 0x0EL)).toByte(),
            ((pts ushr 22) and 0xFFL).toByte(),
            (0x01L or ((pts ushr 14) and 0xFEL)).toByte(),
            ((pts ushr 7) and 0xFFL).toByte(),
            (0x01L or ((pts shl 1) and 0xFEL)).toByte()
        )
    }

    companion object {
        const val TS_PACKET_SIZE = 188
        private const val TS_PAYLOAD_MAX = 184
        private const val TS_SYNC_BYTE: Byte = 0x47

        const val PID_PAT = 0x0000
        const val PID_PMT = 0x1000
        const val PID_VIDEO = 0x0100
        const val PID_AUDIO = 0x0101

        const val STREAM_TYPE_H264 = 0x1B
        const val STREAM_TYPE_AAC = 0x0F

        private const val STREAM_ID_VIDEO = 0xE0
        private const val STREAM_ID_AUDIO = 0xC0

        private val H264_AUD = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x09, 0xF0.toByte())

        private val ADTS_SAMPLE_RATES = intArrayOf(
            96000, 88200, 64000, 48000, 44100, 32000,
            24000, 22050, 16000, 12000, 11025, 8000, 7350
        )

        private val CRC32_TABLE = IntArray(256).apply {
            for (i in 0 until 256) {
                var crc = i shl 24
                for (j in 0 until 8) {
                    crc = if ((crc and Int.MIN_VALUE) != 0) {
                        (crc shl 1) xor 0x04C11DB7
                    } else {
                        crc shl 1
                    }
                }
                this[i] = crc
            }
        }

        fun mpeg2Crc32(data: ByteArray, offset: Int = 0, length: Int = data.size): Int {
            var crc = -1 // 0xFFFFFFFF
            for (i in offset until offset + length) {
                val idx = ((crc ushr 24) xor (data[i].toInt() and 0xFF)) and 0xFF
                crc = (crc shl 8) xor CRC32_TABLE[idx]
            }
            return crc
        }

        fun adtsSampleRateIndex(sampleRate: Int): Int {
            val exact = ADTS_SAMPLE_RATES.indexOf(sampleRate)
            if (exact >= 0) return exact
            var bestIdx = 4 // 44100 fallback
            var bestDiff = Int.MAX_VALUE
            for (i in ADTS_SAMPLE_RATES.indices) {
                val diff = kotlin.math.abs(ADTS_SAMPLE_RATES[i] - sampleRate)
                if (diff < bestDiff) {
                    bestDiff = diff
                    bestIdx = i
                }
            }
            return bestIdx
        }

        /**
         * Wraps a raw AAC-LC access unit with a standards-compliant 7-byte ISO/IEC 13818-7 ADTS header.
         */
        fun wrapAacWithAdtsHeader(
            rawAac: ByteArray,
            sampleRate: Int,
            channelCount: Int,
            aacObjectType: Int = 2 // AAC-LC
        ): ByteArray {
            val frameLen = rawAac.size + 7
            val profileMinusOne = (aacObjectType - 1).coerceIn(0, 3)
            val freqIdx = adtsSampleRateIndex(sampleRate)
            val chanCfg = channelCount.coerceIn(1, 7)

            val out = ByteArray(frameLen)
            out[0] = 0xFF.toByte()
            out[1] = 0xF1.toByte() // MPEG-4, layer 0, no CRC
            out[2] = ((profileMinusOne shl 6) or (freqIdx shl 2) or (chanCfg ushr 2)).toByte()
            out[3] = (((chanCfg and 0x03) shl 6) or (frameLen ushr 11)).toByte()
            out[4] = ((frameLen ushr 3) and 0xFF).toByte()
            out[5] = (((frameLen and 0x07) shl 5) or 0x1F).toByte()
            out[6] = 0xFC.toByte()
            System.arraycopy(rawAac, 0, out, 7, rawAac.size)
            return out
        }

        /**
         * Ensures H.264 NAL units use Annex-B start codes (`00 00 00 01`).
         */
        fun ensureAnnexB(data: ByteArray): ByteArray {
            if (data.size >= 4 &&
                data[0] == 0.toByte() && data[1] == 0.toByte() &&
                (data[2] == 1.toByte() || (data[2] == 0.toByte() && data[3] == 1.toByte()))
            ) {
                return data
            }
            // Convert 4-byte length-prefixed AVCC NALs if needed
            val out = ByteArrayOutputStream(data.size + 16)
            var pos = 0
            while (pos + 4 <= data.size) {
                val nalLen = ((data[pos].toInt() and 0xFF) shl 24) or
                    ((data[pos + 1].toInt() and 0xFF) shl 16) or
                    ((data[pos + 2].toInt() and 0xFF) shl 8) or
                    (data[pos + 3].toInt() and 0xFF)
                pos += 4
                if (nalLen <= 0 || pos + nalLen > data.size) {
                    return data
                }
                out.write(0); out.write(0); out.write(0); out.write(1)
                out.write(data, pos, nalLen)
                pos += nalLen
            }
            return if (out.size() > 0) out.toByteArray() else data
        }

        private fun startsWithAud(data: ByteArray): Boolean {
            if (data.size >= 5 && data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 0.toByte() && data[3] == 1.toByte()) {
                return (data[4].toInt() and 0x1F) == 9
            }
            if (data.size >= 4 && data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 1.toByte()) {
                return (data[3].toInt() and 0x1F) == 9
            }
            return false
        }

        private fun containsSps(data: ByteArray): Boolean {
            var i = 0
            while (i + 4 < data.size) {
                if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                    if (data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte() && i + 4 < data.size) {
                        if ((data[i + 4].toInt() and 0x1F) == 7) return true
                        i += 4
                        continue
                    } else if (data[i + 2] == 1.toByte()) {
                        if ((data[i + 3].toInt() and 0x1F) == 7) return true
                        i += 3
                        continue
                    }
                }
                i++
            }
            return false
        }
    }
}
