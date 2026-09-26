package com.example.service

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

class StereoBalanceAudioProcessor : BaseAudioProcessor() {

    @Volatile
    var balance: Float = 0f // -1.0f (full left) to +1.0f (full right)
        set(value) {
            field = value.coerceIn(-1.0f, 1.0f)
        }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        if (inputAudioFormat.channelCount != 2) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val curBalance = balance
        if (curBalance == 0f || inputAudioFormat.channelCount != 2) {
            val buffer = replaceOutputBuffer(remaining)
            buffer.put(inputBuffer)
            buffer.flip()
            return
        }

        // Calculate left & right volume multipliers
        // curBalance = -1.0 (100% Left)  -> leftFactor = 1.0, rightFactor = 0.0
        // curBalance = 0.0  (Centered)    -> leftFactor = 1.0, rightFactor = 1.0
        // curBalance = 1.0  (100% Right) -> leftFactor = 0.0, rightFactor = 1.0
        val leftFactor = if (curBalance <= 0f) 1.0f else (1.0f - curBalance)
        val rightFactor = if (curBalance >= 0f) 1.0f else (1.0f + curBalance)

        val buffer = replaceOutputBuffer(remaining)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)

        while (inputBuffer.hasRemaining()) {
            if (inputBuffer.remaining() >= 4) {
                val leftSample = inputBuffer.short
                val rightSample = inputBuffer.short
                val adjLeft = (leftSample * leftFactor).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                val adjRight = (rightSample * rightFactor).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                buffer.putShort(adjLeft)
                buffer.putShort(adjRight)
            } else {
                buffer.put(inputBuffer.get())
            }
        }
        buffer.flip()
    }
}
