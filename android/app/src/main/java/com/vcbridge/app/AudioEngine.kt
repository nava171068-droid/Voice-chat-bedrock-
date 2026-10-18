package com.vcbridge.app

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Captures the microphone (16 kHz mono), sends 20 ms mu-law frames through [onFrame],
 * and mixes incoming frames from every speaker into one AudioTrack.
 */
class AudioEngine(private val onFrame: (ByteArray) -> Unit) {
    companion object {
        const val RATE = 16000
        const val FRAME = 320 // 20 ms
        private const val MAX_QUEUE = 10
    }

    @Volatile var muted = true
    @Volatile var deaf = false
    @Volatile var master = 100
    @Volatile var gainDb = 0
    @Volatile var thrDb = -50
    val userVol = ConcurrentHashMap<Int, Int>()

    private val queues = ConcurrentHashMap<Int, ConcurrentLinkedQueue<ShortArray>>()
    @Volatile private var running = false
    private var capThread: Thread? = null
    private var playThread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        capThread = Thread({ captureLoop() }, "vc-capture").also { it.start() }
        playThread = Thread({ playLoop() }, "vc-play").also { it.start() }
    }

    fun stop() {
        running = false
        try { capThread?.join(500) } catch (e: InterruptedException) { }
        try { playThread?.join(500) } catch (e: InterruptedException) { }
        capThread = null
        playThread = null
        queues.clear()
    }

    /** Called for every frame received from another member. */
    fun play(num: Int, data: ByteArray) {
        if (!running || deaf || data.isEmpty()) return
        val pcm = ShortArray(data.size)
        for (i in data.indices) pcm[i] = Mulaw.decode(data[i])
        val q = queues.getOrPut(num) { ConcurrentLinkedQueue() }
        q.add(pcm)
        while (q.size > MAX_QUEUE) q.poll()
    }

    @SuppressLint("MissingPermission")
    private fun captureLoop() {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            max(minBuf, FRAME * 2 * 4)
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return
        }
        rec.startRecording()
        val buf = ShortArray(FRAME)
        var hang = 0
        try {
            while (running) {
                var n = 0
                var failed = false
                while (n < FRAME && running) {
                    val k = rec.read(buf, n, FRAME - n)
                    if (k <= 0) { failed = true; break }
                    n += k
                }
                if (failed || n < FRAME) break
                if (muted) { hang = 0; continue }

                val g = 10.0.pow(gainDb / 20.0)
                var sum = 0.0
                for (i in 0 until FRAME) {
                    val v = (buf[i] * g).coerceIn(-32768.0, 32767.0)
                    buf[i] = v.toInt().toShort()
                    sum += v * v
                }
                val rms = sqrt(sum / FRAME)
                val db = if (rms < 1.0) -127.0 else 20.0 * log10(rms / 32768.0)
                if (db > thrDb) hang = 12 else if (hang > 0) hang-- else continue

                val out = ByteArray(FRAME)
                for (i in 0 until FRAME) out[i] = Mulaw.encode(buf[i])
                onFrame(out)
            }
        } finally {
            try { rec.stop() } catch (e: IllegalStateException) { }
            rec.release()
        }
    }

    private fun playLoop() {
        val minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(max(minBuf, FRAME * 2 * 6))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
        val mix = IntArray(FRAME)
        val out = ShortArray(FRAME)
        try {
            while (running) {
                java.util.Arrays.fill(mix, 0)
                if (!deaf) {
                    for ((num, q) in queues) {
                        val f = q.poll() ?: continue
                        val vol = (userVol[num] ?: 100) / 100.0 * (master / 100.0)
                        val len = minOf(f.size, FRAME)
                        for (i in 0 until len) mix[i] += (f[i] * vol).toInt()
                    }
                }
                for (i in 0 until FRAME) out[i] = mix[i].coerceIn(-32768, 32767).toShort()
                track.write(out, 0, FRAME) // blocking write paces the loop at 20 ms/frame
            }
        } finally {
            try { track.stop() } catch (e: IllegalStateException) { }
            track.release()
        }
    }
}
