// Microphone capture (16kHz PCM16) and speaker playback (24kHz PCM16) for the Gemini Live API.

package com.example.myapplication.gemini

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/** Captures mic audio as 16kHz mono PCM16 and delivers ~100ms chunks via [onChunk]. */
class MicRecorder(private val onChunk: (ByteArray) -> Unit) {
  private companion object {
    const val TAG = "MicRecorder"
    const val SAMPLE_RATE = 16000
  }

  private val running = AtomicBoolean(false)
  private var thread: Thread? = null
  @Volatile private var record: AudioRecord? = null

  @SuppressLint("MissingPermission")
  fun start() {
    if (running.getAndSet(true)) return
    val minBuf =
        AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val bufSize = maxOf(minBuf, SAMPLE_RATE * 2 / 2) // ~1s buffer
    val ar =
        AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize)
    if (ar.state != AudioRecord.STATE_INITIALIZED) {
      Log.e(TAG, "AudioRecord failed to initialize")
      ar.release()
      running.set(false)
      return
    }
    record = ar
    val chunk = ByteArray(SAMPLE_RATE * 2 / 10) // 100ms = 3200 bytes
    ar.startRecording()
    thread =
        Thread {
              while (running.get()) {
                val read = ar.read(chunk, 0, chunk.size)
                if (read > 0) onChunk(if (read == chunk.size) chunk.copyOf() else chunk.copyOf(read))
              }
            }
            .apply { start() }
  }

  fun stop() {
    if (!running.getAndSet(false)) return
    thread?.join(500)
    thread = null
    record?.run {
      try {
        stop()
      } catch (_: Exception) {}
      release()
    }
    record = null
  }
}

/** Plays 24kHz mono PCM16 audio chunks received from Gemini. */
class SpeakerPlayer {
  private companion object {
    const val SAMPLE_RATE = 24000
  }

  private val queue = LinkedBlockingQueue<ByteArray>()
  private val running = AtomicBoolean(false)
  private var thread: Thread? = null
  @Volatile private var track: AudioTrack? = null

  fun start() {
    if (running.getAndSet(true)) return
    val minBuf =
        AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val at =
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
            .setBufferSizeInBytes(maxOf(minBuf, SAMPLE_RATE)) // ~0.5s
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    track = at
    at.play()
    thread =
        Thread {
              while (running.get()) {
                val data =
                    try {
                      queue.take()
                    } catch (e: InterruptedException) {
                      break
                    }
                if (data.isEmpty()) continue
                var off = 0
                while (off < data.size && running.get()) {
                  val w = at.write(data, off, data.size - off)
                  if (w <= 0) break
                  off += w
                }
              }
            }
            .apply { start() }
  }

  fun enqueue(pcm: ByteArray) {
    if (running.get()) queue.offer(pcm)
  }

  /** Drop pending audio (used on barge-in / interrupt). */
  fun flush() {
    queue.clear()
    track?.let {
      try {
        it.pause()
        it.flush()
        it.play()
      } catch (_: Exception) {}
    }
  }

  fun stop() {
    if (!running.getAndSet(false)) return
    queue.clear()
    queue.offer(ByteArray(0)) // unblock take()
    track?.let {
      try {
        it.stop()
      } catch (_: Exception) {}
    }
    thread?.interrupt()
    thread?.join(500)
    thread = null
    track?.let {
      try {
        it.release()
      } catch (_: Exception) {}
    }
    track = null
  }
}
