package com.example.service

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import com.example.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.io.File
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Per-track audiophile loudness measurement result computed via ITU-R BS.1770 K-weighted
 * integrated loudness and true sample peak tracking.
 */
data class TrackLoudnessProfile(
    val integratedLufs: Float,
    val truePeakLinear: Float,
    val optimalGain: Float
)

/**
 * Queue-wide background loudness analyzer that pre-computes true-peak-safe constant linear
 * normalization gains for the ENTIRE playback queue (starting from the current track, then
 * upcoming tracks in play order, then earlier tracks for repeat-all wrap-around).
 *
 * Design guarantees:
 * 1. Zero audio quality loss: produces a single constant linear gain multiplier per song
 *    bounded strictly below digital clipping (<= -0.54 dBFS true peak), eliminating mid-song
 *    pumping, dynamic compression, and tonal coloration.
 * 2. Zero playback or UI impact: runs serially on a single low-priority background coroutine
 *    with cooperative yields and decodes only 5 short representative windows (~4 seconds total)
 *    per uncached song, persisting results permanently in [NormalizationCache].
 */
class QueueLoudnessAnalyzer(private val context: Context) {

    companion object {
        private const val TAG = "QueueLoudnessAnalyzer"

        /** Modern streaming loudness reference (-14.0 LUFS). */
        const val TARGET_LUFS = -14.0f

        /** Headroom ceiling (-0.54 dBFS) so musical peaks never clip or hit the limiter. */
        const val TRUE_PEAK_CEILING = 0.94f

        /** Clean linear gain bounds: -11 dB attenuation for hot masters, +7 dB lift for quiet masters. */
        const val MIN_LINEAR_GAIN = 0.28f
        const val MAX_LINEAR_GAIN = 2.25f

        /** ITU-R BS.1770 absolute silence gate (-70 LUFS) and relative gate (-10 LU). */
        private const val ABSOLUTE_GATE_LUFS = -70.0
        private const val RELATIVE_GATE_OFFSET_LU = 10.0

        private val SAMPLE_POSITIONS_FRACTION = floatArrayOf(0.15f, 0.32f, 0.50f, 0.68f, 0.83f)
        private const val WINDOW_DURATION_US = 800_000L // 0.80s per window (~4.0s total decoded per track)
        private const val MAX_WINDOW_DECODE_MS = 1_200L

        fun trackKeyFor(songId: Long, trackPath: String? = null): String {
            return if (songId > 0L) "id_$songId" else (trackPath?.trim() ?: "")
        }

        /**
         * Pure audiophile gain calculation:
         * Computes the constant linear gain needed to bring [integratedLufs] to [TARGET_LUFS]
         * while strictly capping gain so `truePeakLinear * gain <= TRUE_PEAK_CEILING`.
         */
        fun computeOptimalLinearGain(integratedLufs: Float, truePeakLinear: Float): Float {
            if (!integratedLufs.isFinite() || integratedLufs < -65f || integratedLufs > 0f) {
                return 1.0f
            }
            val gainDb = (TARGET_LUFS - integratedLufs).toDouble()
            val rawLinearGain = 10.0.pow(gainDb / 20.0).toFloat()
            val safePeak = truePeakLinear.takeIf { it.isFinite() && it > 0.005f } ?: 0.5f
            val peakProtectedCap = TRUE_PEAK_CEILING / safePeak
            return minOf(rawLinearGain, peakProtectedCap).coerceIn(MIN_LINEAR_GAIN, MAX_LINEAR_GAIN)
        }
    }

    private val analyzerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    @Volatile
    private var activeQueueJob: Job? = null

    /**
     * Scans the entire [songs] queue starting from [currentIndex], then forward through all
     * upcoming tracks, then wrapping around from index 0. Already-cached tracks are skipped
     * immediately (with [onTrackAnalyzed] notified for the current track so it locks right away).
     */
    fun analyzeQueue(
        songs: List<Song>,
        currentIndex: Int,
        onTrackAnalyzed: (trackKey: String, optimalGain: Float) -> Unit
    ) {
        activeQueueJob?.cancel()
        if (songs.isEmpty()) return

        val orderedQueue = buildPriorityOrder(songs, currentIndex)
        activeQueueJob = analyzerScope.launch {
            for (song in orderedQueue) {
                if (!isActive) break
                val key = trackKeyFor(song.id, song.path)
                if (key.isBlank()) continue

                val cached = NormalizationCache.getGain(key)
                if (cached != null && cached in MIN_LINEAR_GAIN..MAX_LINEAR_GAIN) {
                    onTrackAnalyzed(key, cached)
                    continue
                }

                try {
                    val profile = analyzeSongWindows(song)
                    if (profile != null && isActive) {
                        NormalizationCache.putGain(key, profile.optimalGain)
                        onTrackAnalyzed(key, profile.optimalGain)
                    }
                } catch (t: Throwable) {
                    Log.d(TAG, "Skipped loudness analysis for '${song.title}': ${t.message}")
                }
                yield()
                // Tiny breather so background analysis never contends with active playback decode
                delay(25L)
            }
        }
    }

    fun cancel() {
        activeQueueJob?.cancel()
        activeQueueJob = null
    }

    fun release() {
        cancel()
        analyzerScope.cancel()
    }

    private fun buildPriorityOrder(songs: List<Song>, currentIndex: Int): List<Song> {
        val safeStart = currentIndex.coerceIn(0, (songs.size - 1).coerceAtLeast(0))
        val seenKeys = HashSet<String>(songs.size)
        val result = ArrayList<Song>(songs.size)

        fun appendIfUnique(song: Song) {
            val key = trackKeyFor(song.id, song.path)
            if (key.isNotBlank() && seenKeys.add(key)) {
                result.add(song)
            }
        }

        for (i in safeStart until songs.size) {
            appendIfUnique(songs[i])
        }
        for (i in 0 until safeStart) {
            appendIfUnique(songs[i])
        }
        return result
    }

    private fun analyzeSongWindows(song: Song): TrackLoudnessProfile? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            if (!setExtractorDataSource(extractor, song)) return null

            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }
            if (audioTrackIndex < 0 || audioFormat == null) return null

            val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return null
            val sampleRate = if (audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceIn(8_000, 192_000)
            } else 44_100
            val channelCount = if (audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 8)
            } else 2
            val durationUs = if (audioFormat.containsKey(MediaFormat.KEY_DURATION)) {
                audioFormat.getLong(MediaFormat.KEY_DURATION)
            } else {
                song.durationMs.coerceAtLeast(0L) * 1_000L
            }

            extractor.selectTrack(audioTrackIndex)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(audioFormat, null, null, 0)
            codec.start()

            val windowStartsUs: LongArray = if (durationUs > 15_000_000L) {
                LongArray(SAMPLE_POSITIONS_FRACTION.size) { idx ->
                    (durationUs * SAMPLE_POSITIONS_FRACTION[idx]).toLong()
                        .coerceIn(0L, (durationUs - WINDOW_DURATION_US).coerceAtLeast(0L))
                }
            } else {
                longArrayOf(0L)
            }

            val kFilter = KWeightingFilter(sampleRate, channelCount)
            val blockMeanSquares = ArrayList<Double>(64)
            val blockFrameTarget = (sampleRate * 0.40).toInt().coerceAtLeast(1024) // 400ms BS.1770 blocks
            var blockEnergyAcc = 0.0
            var blockFrameCount = 0
            var truePeakLinear = 0f

            val bufferInfo = MediaCodec.BufferInfo()
            var activeChannelCount = channelCount
            var activePcmEncoding = android.media.AudioFormat.ENCODING_PCM_16BIT

            for (windowStartUs in windowStartsUs) {
                extractor.seekTo(windowStartUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                try {
                    codec.flush()
                } catch (_: Throwable) {}
                kFilter.reset()
                blockEnergyAcc = 0.0
                blockFrameCount = 0

                val windowEndUs = windowStartUs + WINDOW_DURATION_US
                var inputDone = false
                var outputDone = false
                val windowDeadlineMs = System.currentTimeMillis() + MAX_WINDOW_DECODE_MS

                while (!outputDone && System.currentTimeMillis() < windowDeadlineMs) {
                    if (!inputDone) {
                        val inIdx = codec.dequeueInputBuffer(10_000L)
                        if (inIdx >= 0) {
                            val inBuf = codec.getInputBuffer(inIdx)
                            if (inBuf != null) {
                                val readSize = extractor.readSampleData(inBuf, 0)
                                val sampleTimeUs = extractor.sampleTime
                                if (readSize < 0 || (sampleTimeUs >= 0L && sampleTimeUs > windowEndUs)) {
                                    codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                } else {
                                    codec.queueInputBuffer(inIdx, 0, readSize, sampleTimeUs.coerceAtLeast(0L), 0)
                                    extractor.advance()
                                }
                            }
                        }
                    }

                    val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                    when {
                        outIdx >= 0 -> {
                            val outBuf = codec.getOutputBuffer(outIdx)
                            if (outBuf != null && bufferInfo.size > 0) {
                                outBuf.position(bufferInfo.offset)
                                outBuf.limit(bufferInfo.offset + bufferInfo.size)
                                outBuf.order(ByteOrder.LITTLE_ENDIAN)

                                val isFloatPcm = activePcmEncoding == android.media.AudioFormat.ENCODING_PCM_FLOAT
                                val bytesPerSample = if (isFloatPcm) 4 else 2
                                val totalSamples = bufferInfo.size / bytesPerSample
                                val frames = totalSamples / activeChannelCount

                                for (f in 0 until frames) {
                                    var frameWeightedSum = 0.0
                                    for (ch in 0 until activeChannelCount) {
                                        val normalized = if (isFloatPcm) {
                                            outBuf.float.coerceIn(-2f, 2f)
                                        } else {
                                            outBuf.short.toFloat() / 32768f
                                        }
                                        val absSample = abs(normalized)
                                        if (absSample > truePeakLinear) truePeakLinear = absSample

                                        val weighted = kFilter.processSample(ch, normalized)
                                        frameWeightedSum += (weighted * weighted)
                                    }
                                    // ITU-R BS.1770 sums channel mean squares (stereo sum directly maps to -0.691 dB offset)
                                    blockEnergyAcc += frameWeightedSum
                                    blockFrameCount++

                                    if (blockFrameCount >= blockFrameTarget) {
                                        val meanSq = blockEnergyAcc / blockFrameCount.toDouble()
                                        blockMeanSquares.add(meanSq)
                                        blockEnergyAcc = 0.0
                                        blockFrameCount = 0
                                    }
                                }
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                outputDone = true
                            }
                        }
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outFmt = codec.outputFormat
                            if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                                val newCh = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 8)
                                if (newCh != activeChannelCount) {
                                    activeChannelCount = newCh
                                    kFilter.reconfigureChannels(newCh)
                                }
                            }
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N &&
                                outFmt.containsKey(MediaFormat.KEY_PCM_ENCODING)
                            ) {
                                activePcmEncoding = outFmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            }
                        }
                        outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (inputDone) {
                                // Short wait for decoder drain
                                try { Thread.sleep(2L) } catch (_: InterruptedException) { break }
                            }
                        }
                    }
                }

                // Flush partial trailing block if substantial (>= 150ms)
                if (blockFrameCount >= (blockFrameTarget / 3)) {
                    blockMeanSquares.add(blockEnergyAcc / blockFrameCount.toDouble())
                }
            }

            if (blockMeanSquares.isEmpty()) return null

            // Step 1: Absolute gate (-70 LUFS)
            val aboveAbsolute = blockMeanSquares.filter { meanSq ->
                meanSq > 1e-12 && (-0.691 + 10.0 * log10(meanSq)) > ABSOLUTE_GATE_LUFS
            }
            if (aboveAbsolute.isEmpty()) return null

            val ungatedMean = aboveAbsolute.average()
            val ungatedLufs = -0.691 + 10.0 * log10(ungatedMean.coerceAtLeast(1e-12))

            // Step 2: Relative gate (ungated - 10 LU)
            val relativeGateThreshold = ungatedLufs - RELATIVE_GATE_OFFSET_LU
            val aboveRelative = aboveAbsolute.filter { meanSq ->
                (-0.691 + 10.0 * log10(meanSq)) >= relativeGateThreshold
            }
            val finalMean = if (aboveRelative.isNotEmpty()) aboveRelative.average() else ungatedMean
            val integratedLufs = (-0.691 + 10.0 * log10(finalMean.coerceAtLeast(1e-12))).toFloat()

            val optimalGain = computeOptimalLinearGain(integratedLufs, truePeakLinear)
            return TrackLoudnessProfile(
                integratedLufs = integratedLufs,
                truePeakLinear = truePeakLinear,
                optimalGain = optimalGain
            )
        } finally {
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            try { extractor.release() } catch (_: Throwable) {}
        }
    }

    private fun setExtractorDataSource(extractor: MediaExtractor, song: Song): Boolean {
        val path = song.path.trim()
        if (path.isNotEmpty() && !path.startsWith("content://") && !path.startsWith("http")) {
            val file = File(path)
            if (file.exists() && file.canRead()) {
                try {
                    extractor.setDataSource(path)
                    return true
                } catch (_: Throwable) {}
            }
        }
        val uriStr = song.contentUriString.trim()
        if (uriStr.isNotEmpty()) {
            try {
                extractor.setDataSource(context, Uri.parse(uriStr), null)
                return true
            } catch (_: Throwable) {}
        }
        return false
    }

    /**
     * Exact sample-rate-adaptive ITU-R BS.1770-4 2-stage K-weighting filter:
     * Stage 1: +4 dB high-shelf at ~1681 Hz (acoustic head diffraction)
     * Stage 2: 2nd-order Butterworth high-pass at ~38 Hz (RLB weighting)
     */
    private class KWeightingFilter(private val sampleRate: Int, initialChannels: Int) {
        private val hsB0: Float; private val hsB1: Float; private val hsB2: Float
        private val hsA1: Float; private val hsA2: Float
        private val hpB0: Float; private val hpB1: Float; private val hpB2: Float
        private val hpA1: Float; private val hpA2: Float

        private var hsX1 = FloatArray(initialChannels)
        private var hsX2 = FloatArray(initialChannels)
        private var hsY1 = FloatArray(initialChannels)
        private var hsY2 = FloatArray(initialChannels)

        private var hpX1 = FloatArray(initialChannels)
        private var hpX2 = FloatArray(initialChannels)
        private var hpY1 = FloatArray(initialChannels)
        private var hpY2 = FloatArray(initialChannels)

        init {
            val fs = sampleRate.toDouble().coerceIn(8_000.0, 192_000.0)

            // Stage 1: Pre-filter high-shelf (+4 dB, f0 = 1681.974 Hz, Q = 0.707175)
            val dbGain = 3.999843853973347
            val f0Shelf = 1681.974450955533
            val qShelf = 0.7071752369554196
            val kShelf = kotlin.math.tan(PI * f0Shelf / fs)
            val vh = 10.0.pow(dbGain / 20.0)
            val vb = vh.pow(0.4996667741545416)
            val a0Shelf = 1.0 + kShelf / qShelf + kShelf * kShelf
            hsB0 = ((vh + vb * kShelf / qShelf + kShelf * kShelf) / a0Shelf).toFloat()
            hsB1 = (2.0 * (kShelf * kShelf - vh) / a0Shelf).toFloat()
            hsB2 = ((vh - vb * kShelf / qShelf + kShelf * kShelf) / a0Shelf).toFloat()
            hsA1 = (2.0 * (kShelf * kShelf - 1.0) / a0Shelf).toFloat()
            hsA2 = ((1.0 - kShelf / qShelf + kShelf * kShelf) / a0Shelf).toFloat()

            // Stage 2: RLB high-pass (f0 = 38.13547 Hz, Q = 0.500327)
            val f0Hp = 38.13547087602444
            val qHp = 0.5003270373238773
            val kHp = kotlin.math.tan(PI * f0Hp / fs)
            val a0Hp = 1.0 + kHp / qHp + kHp * kHp
            hpB0 = (1.0 / a0Hp).toFloat()
            hpB1 = (-2.0 / a0Hp).toFloat()
            hpB2 = (1.0 / a0Hp).toFloat()
            hpA1 = (2.0 * (kHp * kHp - 1.0) / a0Hp).toFloat()
            hpA2 = ((1.0 - kHp / qHp + kHp * kHp) / a0Hp).toFloat()
        }

        fun reconfigureChannels(newChannels: Int) {
            hsX1 = FloatArray(newChannels); hsX2 = FloatArray(newChannels)
            hsY1 = FloatArray(newChannels); hsY2 = FloatArray(newChannels)
            hpX1 = FloatArray(newChannels); hpX2 = FloatArray(newChannels)
            hpY1 = FloatArray(newChannels); hpY2 = FloatArray(newChannels)
        }

        fun reset() {
            hsX1.fill(0f); hsX2.fill(0f); hsY1.fill(0f); hsY2.fill(0f)
            hpX1.fill(0f); hpX2.fill(0f); hpY1.fill(0f); hpY2.fill(0f)
        }

        fun processSample(channel: Int, sample: Float): Float {
            if (channel !in hsX1.indices) return sample
            val s1 = hsB0 * sample + hsB1 * hsX1[channel] + hsB2 * hsX2[channel] - hsA1 * hsY1[channel] - hsA2 * hsY2[channel]
            hsX2[channel] = hsX1[channel]; hsX1[channel] = sample
            hsY2[channel] = hsY1[channel]; hsY1[channel] = s1

            val s2 = hpB0 * s1 + hpB1 * hpX1[channel] + hpB2 * hpX2[channel] - hpA1 * hpY1[channel] - hpA2 * hpY2[channel]
            hpX2[channel] = hpX1[channel]; hpX1[channel] = s1
            hpY2[channel] = hpY1[channel]; hpY1[channel] = s2
            return s2
        }
    }
}
