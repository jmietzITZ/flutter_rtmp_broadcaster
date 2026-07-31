package com.app.rtmp_publisher

import android.app.Activity
import android.graphics.Bitmap
import android.util.Log
import android.view.SurfaceHolder
import android.view.View
import com.pedro.common.ConnectChecker
import com.pedro.common.socket.base.SocketType
import com.pedro.library.rtmp.RtmpCamera2
import com.pedro.library.view.OpenGlView
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.platform.PlatformView
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class CameraNativeView(
    private var activity: Activity?,
    private val enableAudio: Boolean,
    private val preset: ResolutionPreset,
    private var cameraName: String,
    private var dartMessenger: DartMessenger?
) : PlatformView, SurfaceHolder.Callback, ConnectChecker {

    private val glView = OpenGlView(requireNotNull(activity) { "Activity is required" })
    private val rtmpCamera = RtmpCamera2(glView, this)
    private var isSurfaceCreated = false
    private var fps = 0

    init {
        glView.holder.addCallback(this)
        rtmpCamera.streamClient.setSocketType(SocketType.JAVA)
        rtmpCamera.streamClient.setReTries(MAX_RETRIES)
        rtmpCamera.setFpsListener { fps = it }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        isSurfaceCreated = true
        startPreview(cameraName)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        isSurfaceCreated = false
    }

    override fun onConnectionStarted(url: String) = Unit

    override fun onConnectionSuccess() = Unit

    override fun onConnectionFailed(reason: String) {
        activity?.runOnUiThread {
            if (rtmpCamera.streamClient.reTry(RETRY_DELAY_MS, reason, null)) {
                dartMessenger?.send(DartMessenger.EventType.RTMP_RETRY, reason)
            } else {
                dartMessenger?.send(DartMessenger.EventType.RTMP_STOPPED, reason)
                rtmpCamera.stopStream()
            }
        }
    }

    override fun onNewBitrate(bitrate: Long) = Unit

    override fun onDisconnect() {
        activity?.runOnUiThread {
            dartMessenger?.send(DartMessenger.EventType.RTMP_STOPPED, "Disconnected")
        }
    }

    override fun onAuthError() {
        activity?.runOnUiThread {
            dartMessenger?.send(DartMessenger.EventType.ERROR, "Auth error")
        }
    }

    override fun onAuthSuccess() = Unit

    fun close() {
        runCatching { if (rtmpCamera.isRecording) rtmpCamera.stopRecord() }
        runCatching { if (rtmpCamera.isStreaming) rtmpCamera.stopStream() }
        runCatching { if (rtmpCamera.isOnPreview) rtmpCamera.stopPreview() }
    }

    fun takePicture(filePath: String, result: MethodChannel.Result) {
        val file = File(filePath)
        if (file.exists()) {
            result.error("fileExists", "File at path '$filePath' already exists. Cannot overwrite.", null)
            return
        }
        glView.takePhoto { bitmap ->
            try {
                BufferedOutputStream(FileOutputStream(file)).use { output ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output)
                }
                view.post { result.success(null) }
            } catch (error: IOException) {
                result.error("IOError", "Failed saving image", error.message)
            }
        }
    }

    fun startVideoRecording(filePath: String?, result: MethodChannel.Result) {
        if (filePath.isNullOrBlank()) {
            result.error("videoRecordingFailed", "Must specify a filePath.", null)
            return
        }
        if (File(filePath).exists()) {
            result.error("fileExists", "File at path '$filePath' already exists. Cannot overwrite.", null)
            return
        }
        if (rtmpCamera.isRecording) {
            result.error("videoRecordingFailed", "A video recording is already active.", null)
            return
        }

        try {
            if (!rtmpCamera.isStreaming && !prepareVideoAndAudio()) {
                result.error("videoRecordingFailed", "This device cannot prepare the requested recording.", null)
                return
            }
            rtmpCamera.startRecord(filePath)
            result.success(null)
        } catch (error: Exception) {
            result.error("videoRecordingFailed", error.message, null)
        }
    }

    fun startVideoStreaming(url: String?, bitrate: Int?, result: MethodChannel.Result) {
        if (url.isNullOrBlank()) {
            result.error("videoStreamingFailed", "Must specify a url.", null)
            return
        }
        if (rtmpCamera.isStreaming) {
            result.error("videoStreamingFailed", "A video stream is already active.", null)
            return
        }

        try {
            if (!rtmpCamera.isRecording && !prepareVideoAndAudio(bitrate)) {
                result.error("videoStreamingFailed", "This device cannot prepare the requested stream.", null)
                return
            }
            rtmpCamera.startStream(url)
            result.success(null)
        } catch (error: Exception) {
            result.error("videoStreamingFailed", error.message, null)
        }
    }

    fun startVideoRecordingAndStreaming(
        filePath: String?,
        url: String?,
        bitrate: Int?,
        result: MethodChannel.Result
    ) {
        if (filePath.isNullOrBlank()) {
            result.error("videoRecordingFailed", "Must specify a filePath.", null)
            return
        }
        if (File(filePath).exists()) {
            result.error("fileExists", "File at path '$filePath' already exists.", null)
            return
        }
        if (url.isNullOrBlank()) {
            result.error("videoStreamingFailed", "Must specify a url.", null)
            return
        }
        if (rtmpCamera.isStreaming || rtmpCamera.isRecording) {
            result.error("videoRecordingFailed", "A video stream or recording is already active.", null)
            return
        }

        try {
            if (!prepareVideoAndAudio(bitrate)) {
                result.error("videoRecordingFailed", "This device cannot prepare the requested stream.", null)
                return
            }
            rtmpCamera.startStreamAndRecord(url, filePath)
            result.success(null)
        } catch (error: Exception) {
            result.error("videoRecordingFailed", error.message, null)
        }
    }

    fun pauseVideoStreaming(result: MethodChannel.Result) {
        result.error("unsupported", "Pausing an RTMP stream is not supported.", null)
    }

    fun resumeVideoStreaming(result: MethodChannel.Result) {
        result.error("unsupported", "Resuming an RTMP stream is not supported.", null)
    }

    fun stopVideoRecordingOrStreaming(result: MethodChannel.Result) {
        try {
            if (rtmpCamera.isRecording) rtmpCamera.stopRecord()
            if (rtmpCamera.isStreaming) rtmpCamera.stopStream()
            result.success(null)
        } catch (error: Exception) {
            result.error("videoRecordingFailed", error.message, null)
        }
    }

    fun stopVideoRecording(result: MethodChannel.Result) {
        try {
            if (rtmpCamera.isRecording) rtmpCamera.stopRecord()
            result.success(null)
        } catch (error: Exception) {
            result.error("stopVideoRecordingFailed", error.message, null)
        }
    }

    fun stopVideoStreaming(result: MethodChannel.Result) {
        try {
            if (rtmpCamera.isStreaming) rtmpCamera.stopStream()
            result.success(null)
        } catch (error: Exception) {
            result.error("stopVideoStreamingFailed", error.message, null)
        }
    }

    fun pauseVideoRecording(result: MethodChannel.Result) {
        try {
            if (!rtmpCamera.isRecording) {
                result.error("pauseVideoRecordingFailed", "No video recording is active.", null)
                return
            }
            rtmpCamera.pauseRecord()
            result.success(null)
        } catch (error: Exception) {
            result.error("pauseVideoRecordingFailed", error.message, null)
        }
    }

    fun resumeVideoRecording(result: MethodChannel.Result) {
        try {
            if (!rtmpCamera.isRecording) {
                result.error("resumeVideoRecordingFailed", "No video recording is active.", null)
                return
            }
            rtmpCamera.resumeRecord()
            result.success(null)
        } catch (error: Exception) {
            result.error("resumeVideoRecordingFailed", error.message, null)
        }
    }

    fun startPreviewWithImageStream(imageStreamChannel: Any) = Unit

    fun startPreview(cameraNameArg: String? = null) {
        val targetCamera = cameraNameArg?.takeIf { it.isNotEmpty() } ?: cameraName
        cameraName = targetCamera
        if (!isSurfaceCreated) return

        try {
            val previewSize = CameraUtils.computeBestPreviewSize(targetCamera, preset)
            if (rtmpCamera.isOnPreview) rtmpCamera.stopPreview()
            rtmpCamera.startPreview(targetCamera, previewSize.width, previewSize.height)
        } catch (error: Exception) {
            Log.e(TAG, "Unable to start camera preview", error)
            activity?.runOnUiThread {
                dartMessenger?.send(DartMessenger.EventType.ERROR, "Unable to start camera preview")
            }
        }
    }

    fun getStreamStatistics(result: MethodChannel.Result) {
        val streamClient = rtmpCamera.streamClient
        result.success(hashMapOf<String, Any>(
            "cacheSize" to streamClient.getCacheSize(),
            "sentAudioFrames" to streamClient.getSentAudioFrames(),
            "sentVideoFrames" to streamClient.getSentVideoFrames(),
            "droppedAudioFrames" to streamClient.getDroppedAudioFrames(),
            "droppedVideoFrames" to streamClient.getDroppedVideoFrames(),
            "isAudioMuted" to rtmpCamera.isAudioMuted,
            "bitrate" to rtmpCamera.bitrate,
            "width" to rtmpCamera.streamWidth,
            "height" to rtmpCamera.streamHeight,
            "fps" to fps
        ))
    }

    override fun getView(): View = glView

    override fun dispose() {
        close()
        glView.holder.removeCallback(this)
        isSurfaceCreated = false
        dartMessenger = null
        activity = null
    }

    private fun prepareVideoAndAudio(bitrate: Int? = null): Boolean {
        val profile = CameraUtils.getBestAvailableCamcorderProfileForResolutionPreset(cameraName, preset)
        val audioPrepared = !enableAudio || rtmpCamera.prepareAudio()
        val videoPrepared = rtmpCamera.prepareVideo(
            profile.videoFrameWidth,
            profile.videoFrameHeight,
            bitrate ?: profile.videoBitRate
        )
        return audioPrepared && videoPrepared
    }

    companion object {
        private const val TAG = "CameraNativeView"
        private const val MAX_RETRIES = 10
        private const val RETRY_DELAY_MS = 5000L
    }
}
