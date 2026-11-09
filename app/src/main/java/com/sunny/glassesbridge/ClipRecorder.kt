package com.sunny.glassesbridge

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.SystemClock
import java.io.File
import java.nio.ByteBuffer

/** All calls run on the same recording worker, never on the UI thread. */
class ClipRecorder(
    context: Context,
    private val width: Int,
    private val height: Int,
    withPhoneSound: Boolean,
) {
    private val dir = File(context.filesDir, "recordings").apply { mkdirs() }
    private val stem = "glasses-${System.currentTimeMillis()}"
    private val videoFile = File(dir, "$stem-video.mp4")
    private val audioFile = File(dir, "$stem-audio.m4a")
    private val outputFile = File(dir, "$stem.mp4")
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var microphone: MediaRecorder? = null
    private var track = -1
    private var muxerStarted = false
    private var written = 0
    private var previousPts = -1L
    private var finished = false
    val startedMs = SystemClock.elapsedRealtime()
    var soundWarning: String? = null
        private set

    init {
        try {
            muxer = MediaMuxer(videoFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 24)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also {
                it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                it.start()
            }
            if (withPhoneSound) {
                try {
                    @Suppress("DEPRECATION")
                    val mic = MediaRecorder()
                    microphone = mic
                    mic.setAudioSource(MediaRecorder.AudioSource.MIC)
                    mic.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    mic.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    mic.setAudioSamplingRate(44100)
                    mic.setAudioChannels(1)
                    mic.setAudioEncodingBitRate(128000)
                    mic.setOutputFile(audioFile.absolutePath)
                    mic.prepare()
                    mic.start()
                } catch (e: Exception) {
                    runCatching { microphone?.release() }
                    microphone = null
                    audioFile.delete()
                    soundWarning = "Microphone unavailable; recording without sound."
                }
            }
        } catch (e: Exception) {
            release()
            videoFile.delete()
            audioFile.delete()
            throw e
        }
    }

    fun frame(i420: ByteArray, w: Int, h: Int, arrivalMs: Long) {
        check(!finished)
        require(w == width && h == height) { "Glasses changed video resolution. Reconnect and try again." }
        require(i420.size == width * height * 3 / 2) { "Unexpected camera pixel layout." }
        val encoder = checkNotNull(codec)
        drain(false)
        val index = encoder.dequeueInputBuffer(10_000)
        if (index < 0) return // Bounded backpressure: drop a frame rather than grow memory.
        val image = encoder.getInputImage(index)
            ?: error("The phone encoder does not expose a YUV input image.")
        val pts = maxOf(previousPts + 1, (arrivalMs - startedMs).coerceAtLeast(0) * 1000)
        try {
            val y = width * height
            val planes = image.planes
            YuvPlanes.writePlane(i420, 0, width, height, planes[0].buffer, planes[0].rowStride, planes[0].pixelStride)
            YuvPlanes.writePlane(i420, y, width / 2, height / 2, planes[1].buffer, planes[1].rowStride, planes[1].pixelStride)
            YuvPlanes.writePlane(i420, y + y / 4, width / 2, height / 2, planes[2].buffer, planes[2].rowStride, planes[2].pixelStride)
        } finally {
            image.close()
        }
        previousPts = pts
        encoder.queueInputBuffer(index, 0, width * height * 3 / 2, pts, 0)
        drain(false)
    }

    private fun drain(end: Boolean) {
        val encoder = checkNotNull(codec)
        val info = MediaCodec.BufferInfo()
        val deadline = SystemClock.elapsedRealtime() + 3000
        while (true) {
            val index = encoder.dequeueOutputBuffer(info, if (end) 10_000 else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!end) return
                    check(SystemClock.elapsedRealtime() < deadline) { "Video encoder did not finish." }
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxerStarted) { "Encoder format changed during recording." }
                    track = checkNotNull(muxer).addTrack(encoder.outputFormat)
                    checkNotNull(muxer).start()
                    muxerStarted = true
                }
                index >= 0 -> {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            check(muxerStarted)
                            val buffer = checkNotNull(encoder.getOutputBuffer(index))
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            checkNotNull(muxer).writeSampleData(track, buffer, info)
                            written++
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    } finally { encoder.releaseOutputBuffer(index, false) }
                }
            }
        }
    }

    fun finish(): File {
        check(!finished)
        finished = true
        var audioValid = false
        try {
            microphone?.let { mic ->
                audioValid = runCatching { mic.stop(); audioFile.length() > 0 }.getOrDefault(false)
                if (!audioValid) soundWarning = "Sound could not be saved; the video is silent."
            }
            val encoder = checkNotNull(codec)
            var input = -1
            val deadline = SystemClock.elapsedRealtime() + 3000
            while (input < 0 && SystemClock.elapsedRealtime() < deadline) {
                drain(false)
                input = encoder.dequeueInputBuffer(10_000)
            }
            check(input >= 0) { "Encoder input timed out." }
            encoder.queueInputBuffer(input, 0, 0, maxOf(0, previousPts + 1), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
            check(written > 0) { "No video frames were recorded. Check your glasses connection." }
            checkNotNull(muxer).stop()
            muxerStarted = false
        } catch (e: Exception) {
            videoFile.delete()
            audioFile.delete()
            throw e
        } finally { release() }
        if (audioValid) {
            try {
                combine(videoFile, audioFile, outputFile)
                videoFile.delete()
            } catch (e: Exception) {
                outputFile.delete()
                soundWarning = "Sound merge failed; the original silent video was kept."
                check(videoFile.renameTo(outputFile)) { "Could not save the recorded video." }
            }
        } else {
            check(videoFile.renameTo(outputFile)) { "Could not save the recorded video." }
        }
        audioFile.delete()
        return outputFile
    }

    fun discard() {
        if (!finished) {
            finished = true
            runCatching { microphone?.stop() }
            release()
            videoFile.delete()
            audioFile.delete()
        }
    }

    private fun release() {
        runCatching { microphone?.release() }; microphone = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }; codec = null
        if (muxerStarted) runCatching { muxer?.stop() }
        runCatching { muxer?.release() }; muxer = null
        muxerStarted = false
    }

    private fun combine(video: File, audio: File, out: File) {
        val ve = MediaExtractor()
        val ae = MediaExtractor()
        var result: MediaMuxer? = null
        var started = false
        try {
            ve.setDataSource(video.absolutePath)
            ae.setDataSource(audio.absolutePath)
            fun find(ex: MediaExtractor, prefix: String): Int = (0 until ex.trackCount).first {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true
            }
            val vi = find(ve, "video/")
            val ai = find(ae, "audio/")
            val videoFormat = ve.getTrackFormat(vi)
            val duration = if (videoFormat.containsKey(MediaFormat.KEY_DURATION)) videoFormat.getLong(MediaFormat.KEY_DURATION) else Long.MAX_VALUE
            val m = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            result = m
            val vt = m.addTrack(videoFormat)
            val at = m.addTrack(ae.getTrackFormat(ai))
            ve.selectTrack(vi); ae.selectTrack(ai)
            m.start(); started = true
            val buffer = ByteBuffer.allocateDirect(4 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            fun copy(ex: MediaExtractor, target: Int, maxTime: Long) {
                val first = ex.sampleTime.coerceAtLeast(0)
                while (true) {
                    buffer.clear()
                    val size = ex.readSampleData(buffer, 0)
                    if (size < 0) break
                    val pts = (ex.sampleTime - first).coerceAtLeast(0)
                    if (pts > maxTime) break
                    val flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    info.set(0, size, pts, flags)
                    m.writeSampleData(target, buffer, info)
                    ex.advance()
                }
            }
            copy(ve, vt, Long.MAX_VALUE)
            copy(ae, at, duration)
            m.stop(); started = false
        } finally {
            if (started) runCatching { result?.stop() }
            runCatching { result?.release() }
            ve.release(); ae.release()
        }
    }
}
