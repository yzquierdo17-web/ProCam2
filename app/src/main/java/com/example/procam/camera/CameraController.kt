package com.example.procam.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.MediaCodecList
import android.media.ImageReader
import android.media.MediaRecorder
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.example.procam.cloud.CloudSync
import com.example.procam.film.Films
import com.example.procam.film.PhotoCodec
import kotlinx.coroutines.flow.*
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt

/** Camera2 wrapper: preview, manual controls, JPEG + RAW(DNG) capture, video recording. */
class CameraController(private val context: Context) {

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val _caps = MutableStateFlow(CameraCaps())
    val caps = _caps.asStateFlow()
    private val _live = MutableStateFlow(LiveReadout())
    val live = _live.asStateFlow()
    private val _recording = MutableStateFlow(false)
    val recording = _recording.asStateFlow()
    private val _previewSize = MutableStateFlow<Size?>(null)
    val previewSize = _previewSize.asStateFlow()

    /** How far the phone is physically rotated (0/90/180/270, clockwise), from the orientation sensor. */
    @Volatile var deviceOrientation = 0
    /** How far the screen is rotated (0/90/180/270), for mapping taps onto the sensor. */
    @Volatile var displayRotation = 0
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewBuilder: CaptureRequest.Builder? = null
    private var previewSurface: Surface? = null
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private var chars: CameraCharacteristics? = null
    private var settings = CameraSettings()
    private var autoGains: RggbChannelVector? = null
    private var autoTransform: ColorSpaceTransform? = null

    private var recorder: MediaRecorder? = null
    private var recUri: Uri? = null
    private var recPfd: ParcelFileDescriptor? = null
    private var recFps: Int? = null
    private var recRange: Range<Int>? = null
    private var recName = ""
    private var recMime = "video/mp4"

    /** True when the photo stream was opened as HEIC instead of JPEG. */
    @Volatile private var captureHeic = false

    private val pendingRaw = ConcurrentHashMap<Long, Image>()
    private val pendingResults = ConcurrentHashMap<Long, TotalCaptureResult>()
    private val pendingOrientation = ConcurrentHashMap<Long, Int>()

    // ---------- discovery ----------

    fun lenses(): List<LensInfo> = manager.cameraIdList.mapNotNull { id ->
        val c = manager.getCameraCharacteristics(id)
        val facing = c.get(CameraCharacteristics.LENS_FACING)
        if (facing == CameraCharacteristics.LENS_FACING_EXTERNAL) return@mapNotNull null
        val capList = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE !in capList) return@mapNotNull null
        val front = facing == CameraCharacteristics.LENS_FACING_FRONT
        val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val label = if (front) "Front" else if (f != null && size != null) {
            "${(f * 43.27f / hypot(size.width, size.height)).roundToInt()}mm"   // 35mm-equivalent
        } else id
        LensInfo(id, label, front)
    }

    fun defaultCameraId(): String? = lenses().let { l -> (l.firstOrNull { !it.front } ?: l.firstOrNull())?.id }

    private fun computeCaps(c: CameraCharacteristics): CameraCaps {
        val iso = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exp = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val ev = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val step = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
        val capList = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val zoom = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        return CameraCaps(
            isoMin = iso?.lower ?: 100,
            isoMax = iso?.upper ?: 800,
            shutterMinNs = exp?.lower ?: 1_000_000L,
            shutterMaxNs = (exp?.upper ?: 1_000_000_000L).coerceAtMost(10_000_000_000L),
            minFocusDiopter = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
            evMin = ev?.lower ?: 0,
            evMax = ev?.upper ?: 0,
            evStep = step?.toFloat() ?: 1f,
            manualSensor = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in capList,
            raw = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW in capList,
            apertures = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.toList() ?: emptyList(),
            videoFormats = videoFormats(c),
            zoomMin = zoom?.lower ?: 1f,
            zoomMax = (zoom?.upper ?: 1f).coerceAtMost(20f),
            heic = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.HEIC)?.isNotEmpty() == true,
            videoCodecs = VideoCodec.entries.filter { hasEncoder(it.codecMime) }.ifEmpty { listOf(VideoCodec.H264) },
        )
    }

    private fun hasEncoder(mime: String): Boolean =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }

    // ---------- lifecycle ----------

    @SuppressLint("MissingPermission")
    fun open(texture: SurfaceTexture, cameraId: String, s: CameraSettings) {
        close()
        settings = s
        val t = HandlerThread("camera").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h

        val c = manager.getCameraCharacteristics(cameraId)
        chars = c
        autoGains = null; autoTransform = null
        val cp = computeCaps(c)
        _caps.value = cp
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return

        val prev = pickSize(map.getOutputSizes(SurfaceTexture::class.java), 1920 * 1440)
        captureHeic = s.photoFormat == PhotoFormat.HEIC && cp.heic
        val photoFmt = if (captureHeic) ImageFormat.HEIC else ImageFormat.JPEG
        val jpeg = pickSize(map.getOutputSizes(photoFmt), Int.MAX_VALUE)
        texture.setDefaultBufferSize(prev.width, prev.height)
        _previewSize.value = prev
        previewSurface = Surface(texture)

        jpegReader = ImageReader.newInstance(jpeg.width, jpeg.height, photoFmt, 3).also {
            it.setOnImageAvailableListener({ r -> savePhoto(r) }, h)
        }
        if (cp.raw) {
            map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height }?.let { sz ->
                rawReader = ImageReader.newInstance(sz.width, sz.height, ImageFormat.RAW_SENSOR, 3).also {
                    it.setOnImageAvailableListener({ r -> onRawImage(r) }, h)
                }
            }
        }
        manager.openCamera(cameraId, stateCallback, h)
    }

    fun close() {
        if (recorder != null) stopRecording(restart = false)
        runCatching { session?.close() }; session = null
        runCatching { device?.close() }; device = null
        runCatching { jpegReader?.close() }; jpegReader = null
        runCatching { rawReader?.close() }; rawReader = null
        pendingRaw.values.forEach { runCatching { it.close() } }
        pendingRaw.clear(); pendingResults.clear(); pendingOrientation.clear()
        runCatching { previewSurface?.release() }; previewSurface = null
        previewBuilder = null
        thread?.quitSafely(); thread = null; handler = null
    }

    private fun pickSize(sizes: Array<Size>, maxArea: Int): Size {
        fun area(s: Size) = s.width * s.height
        return sizes.filter { it.width * 3 == it.height * 4 && area(it) <= maxArea }.maxByOrNull(::area)
            ?: sizes.filter { area(it) <= maxArea }.maxByOrNull(::area)
            ?: sizes.first()
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            if (handler == null) { camera.close(); return }   // closed while opening
            device = camera
            startSession(camera)
        }
        override fun onDisconnected(camera: CameraDevice) {
            camera.close(); if (device === camera) device = null
        }
        override fun onError(camera: CameraDevice, error: Int) {
            camera.close(); device = null
            _events.tryEmit("Camera error $error")
        }
    }

    private fun startSession(cam: CameraDevice) {
        val h = handler ?: return
        val outs = mutableListOf(
            OutputConfiguration(previewSurface ?: return),
            OutputConfiguration((jpegReader ?: return).surface),
        )
        rawReader?.let { outs += OutputConfiguration(it.surface) }
        val cfg = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR, outs, Executor { h.post(it) },
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) { session = s; startPreview() }
                override fun onConfigureFailed(s: CameraCaptureSession) { _events.tryEmit("Session config failed") }
            },
        )
        cam.createCaptureSession(cfg)
    }

    // ---------- preview + settings ----------

    private val liveCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
            _live.value = LiveReadout(
                res.get(CaptureResult.SENSOR_SENSITIVITY),
                res.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                res.get(CaptureResult.LENS_APERTURE),
            )
            if (settings.kelvin == null) {   // remember what auto-WB chose, as the base for Kelvin
                autoGains = res.get(CaptureResult.COLOR_CORRECTION_GAINS)
                autoTransform = res.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
            }
        }
    }

    private fun startPreview() {
        val dev = device ?: return
        val ses = session ?: return
        val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(previewSurface ?: return) }
        previewBuilder = b
        applySettings(b, settings)
        runCatching { ses.setRepeatingRequest(b.build(), liveCallback, handler) }
    }

    fun updateSettings(s: CameraSettings) {
        settings = s
        val b = previewBuilder ?: return
        val ses = session ?: return
        applySettings(b, s)
        runCatching { ses.setRepeatingRequest(b.build(), liveCallback, handler) }
    }

    /** Start an AF scan at the current metering point (call after updateSettings). */
    fun triggerAf() = sendAfTrigger(CameraMetadata.CONTROL_AF_TRIGGER_START)

    /** Return to continuous AF. */
    fun cancelAf() = sendAfTrigger(CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)

    private fun sendAfTrigger(trigger: Int) {
        val b = previewBuilder ?: return
        val ses = session ?: return
        runCatching {
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, trigger)
            ses.capture(b.build(), null, handler)
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
            ses.setRepeatingRequest(b.build(), liveCallback, handler)
        }
    }

    /** Orientation to save photos/videos in, from the sensor mounting angle and how the phone is held. */
    private fun jpegOrientation(): Int {
        val c = chars ?: return 90
        val sensor = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val front = c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        val d = if (front) -deviceOrientation else deviceOrientation
        return (sensor + d + 360) % 360
    }

    /** Rotation between the sensor and what's drawn on screen, for mapping taps. */
    private fun relativeRotation(c: CameraCharacteristics): Int {
        val sensor = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val front = c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        return if (front) (sensor + displayRotation) % 360 else (sensor - displayRotation + 360) % 360
    }

    /** Map a point in the on-screen preview to a sensor-space metering rectangle. */
    private fun meteringRect(c: CameraCharacteristics, p: Point, div: Int, zoom: Float = settings.zoom): MeteringRectangle {
        val a = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!
        val w = a.width()
        val h = a.height()
        val (fx, fy) = when (relativeRotation(c)) {
            90 -> p.y to (1f - p.x)
            180 -> (1f - p.x) to (1f - p.y)
            270 -> (1f - p.y) to p.x
            else -> p.x to p.y
        }
        // when zoomed in, the visible area is the centre 1/zoom of the sensor
        val z = zoom.coerceAtLeast(1f)
        val zx = 0.5f + (fx - 0.5f) / z
        val zy = 0.5f + (fy - 0.5f) / z
        val half = minOf(w, h) / div
        val cx = a.left + (zx * w).toInt()
        val cy = a.top + (zy * h).toInt()
        val x = (cx - half).coerceIn(a.left, a.right - 2 * half)
        val y = (cy - half).coerceIn(a.top, a.bottom - 2 * half)
        return MeteringRectangle(x, y, 2 * half, 2 * half, MeteringRectangle.METERING_WEIGHT_MAX - 1)
    }

    private fun applySettings(b: CaptureRequest.Builder, s: CameraSettings) {
        val cp = _caps.value
        val ch = chars
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        if (cp.zoomMax > cp.zoomMin) b.set(CaptureRequest.CONTROL_ZOOM_RATIO, s.zoom.coerceIn(cp.zoomMin, cp.zoomMax))

        if (s.manualExposure && cp.manualSensor) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, s.iso.coerceIn(cp.isoMin, cp.isoMax))
            b.set(
                CaptureRequest.SENSOR_EXPOSURE_TIME,
                s.shutterNs.coerceIn(cp.shutterMinNs, cp.shutterMaxNs)
                    .let { n -> recFps?.let { f -> n.coerceAtMost(1_000_000_000L / f) } ?: n },
            )
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, s.evIndex.coerceIn(cp.evMin, cp.evMax))
        }

        if (s.manualFocus && cp.minFocusDiopter > 0f) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, s.focusDiopter.coerceIn(0f, cp.minFocusDiopter))
        } else {
            b.set(
                CaptureRequest.CONTROL_AF_MODE,
                if (s.afSingle) CameraMetadata.CONTROL_AF_MODE_AUTO else CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
            )
        }

        // tap-to-focus point (AF) and metering mode (AE); null clears the regions
        val meter = s.meter
        val afRect = if (meter != null && ch != null) meteringRect(ch, meter, 16) else null
        val aeRect = if (ch == null) null else when (s.metering) {
            Metering.MATRIX -> null
            Metering.CENTER -> meteringRect(ch, Point(0.5f, 0.5f), 4)
            Metering.SPOT -> meteringRect(ch, meter ?: Point(0.5f, 0.5f), 16)
        }
        val afOk = ch != null && (ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0
        val aeOk = ch != null && (ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0
        b.set(CaptureRequest.CONTROL_AF_REGIONS, if (afRect != null && afOk && !s.manualFocus) arrayOf(afRect) else null)
        b.set(CaptureRequest.CONTROL_AE_REGIONS, if (aeRect != null && aeOk && !s.manualExposure) arrayOf(aeRect) else null)
        b.set(CaptureRequest.CONTROL_AE_LOCK, s.aeLock)
        b.set(CaptureRequest.CONTROL_AWB_LOCK, s.aeLock)

        // white balance: preset, or approximate Kelvin offset applied on top of what auto-WB chose
        val k = s.kelvin
        val g = autoGains
        val t = autoTransform
        if (k != null && g != null && t != null) {
            val f = (k / 5500f).pow(0.6f)
            b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            b.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            b.set(CaptureRequest.COLOR_CORRECTION_GAINS, RggbChannelVector(g.red * f, g.greenEven, g.greenOdd, g.blue / f))
            b.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, t)
        } else {
            b.set(CaptureRequest.CONTROL_AWB_MODE, s.awb.mode)
            b.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_FAST)
        }

        val ap = s.aperture
        if (cp.apertures.size > 1 && ap != null) b.set(CaptureRequest.LENS_APERTURE, ap)
        b.set(CaptureRequest.FLASH_MODE, if (s.torch) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF)

        // video-only: frame rate, frame duration, stabilization
        val fps = recFps
        if (fps != null) {
            b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, recRange ?: Range(fps, fps))
            if (s.manualExposure && cp.manualSensor) b.set(CaptureRequest.SENSOR_FRAME_DURATION, 1_000_000_000L / fps)
            val eis = ch?.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: IntArray(0)
            val ois = ch?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: IntArray(0)
            if (CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON in eis) b.set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                if (s.stabilize) CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON else CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
            )
            if (CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON in ois) b.set(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                if (s.stabilize) CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON else CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF,
            )
        }
    }

    // ---------- capture ----------

    fun capture(s: CameraSettings) {
        if (_recording.value) return
        val dev = device ?: return
        val ses = session ?: return
        val jr = jpegReader ?: return
        val wantRaw = s.raw && rawReader != null
        val orient = jpegOrientation()
        try {
            val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(jr.surface)
                if (wantRaw) addTarget(rawReader!!.surface)
                applySettings(this, s)
                set(CaptureRequest.JPEG_ORIENTATION, orient)
                set(CaptureRequest.JPEG_QUALITY, 97.toByte())
                setTag(Pair(wantRaw, orient))
            }
            ses.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(sess: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
                    val tag = r.tag as? Pair<*, *>
                    if (tag?.first == true) {
                        val ts = res.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
                        pendingOrientation[ts] = (tag.second as? Int) ?: 90
                        pendingResults[ts] = res
                        tryFinishRaw(ts)
                    }
                }
            }, handler)
        } catch (e: Exception) {
            _events.tryEmit("Capture failed: ${e.message}")
        }
    }

    // ---------- video ----------

    private fun videoFormats(c: CameraCharacteristics): List<VideoFormat> {
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return emptyList()
        val sizes = map.getOutputSizes(MediaRecorder::class.java) ?: return emptyList()
        val ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray<Range<Int>>()
        val out = mutableListOf<VideoFormat>()
        for ((label, w, h) in listOf(Triple("4K", 3840, 2160), Triple("1080p", 1920, 1080), Triple("720p", 1280, 720))) {
            val size = sizes.firstOrNull { it.width == w && it.height == h } ?: continue
            val minFrameNs = map.getOutputMinFrameDuration(MediaRecorder::class.java, size)
            for (fps in listOf(24, 30, 60)) {
                val rangeOk = ranges.any { it.lower <= fps && it.upper >= fps }
                val rateOk = minFrameNs <= 0 || 1_000_000_000L / fps >= minFrameNs
                if (rangeOk && rateOk) out += VideoFormat("$label $fps", w, h, fps)
            }
        }
        return out
    }

    private fun pickFpsRange(c: CameraCharacteristics, fps: Int): Range<Int>? {
        val ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
        return ranges.filter { it.upper == fps }.maxByOrNull { it.lower }
            ?: ranges.filter { it.lower <= fps && it.upper >= fps }.minByOrNull { it.upper - it.lower }
    }

    /** Swaps the still session for a preview + MediaRecorder session and starts recording. */
    fun startRecording(s: CameraSettings) {
        if (_recording.value) return
        val dev = device ?: return
        val h = handler ?: return
        val c = chars ?: return
        val fmt = _caps.value.pickVideo(s.videoLabel) ?: run { _events.tryEmit("No video format available"); return }
        try {
            settings = s
            val codec = s.videoCodec.takeIf { it in _caps.value.videoCodecs } ?: VideoCodec.H264
            val name = "PROCAM_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.${codec.ext}"
            recName = name; recMime = codec.mime
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, codec.mime)
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/ProCam")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return
            val pfd = context.contentResolver.openFileDescriptor(uri, "w") ?: return
            recUri = uri; recPfd = pfd

            val withAudio = s.audio &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            val bitrate = s.bitrateMbps?.let { it * 1_000_000 }
                ?: (((fmt.width.toLong() * fmt.height * fmt.fps) / 10) *
                    when (codec) { VideoCodec.H264 -> 1.0; VideoCodec.HEVC -> 0.6; VideoCodec.VP8 -> 1.1 }
                    ).toLong().coerceIn(3_000_000L, 100_000_000L).toInt()
            val rec = MediaRecorder(context)
            if (withAudio) rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(if (codec == VideoCodec.VP8) MediaRecorder.OutputFormat.WEBM else MediaRecorder.OutputFormat.MPEG_4)
            rec.setOutputFile(pfd.fileDescriptor)
            rec.setVideoEncoder(
                when (codec) {
                    VideoCodec.H264 -> MediaRecorder.VideoEncoder.H264
                    VideoCodec.HEVC -> MediaRecorder.VideoEncoder.HEVC
                    VideoCodec.VP8 -> MediaRecorder.VideoEncoder.VP8
                }
            )
            rec.setVideoSize(fmt.width, fmt.height)
            rec.setVideoFrameRate(fmt.fps)
            rec.setVideoEncodingBitRate(bitrate)
            if (withAudio) {
                val webm = codec == VideoCodec.VP8
                rec.setAudioEncoder(if (webm) MediaRecorder.AudioEncoder.OPUS else MediaRecorder.AudioEncoder.AAC)
                rec.setAudioEncodingBitRate(128_000)
                rec.setAudioSamplingRate(if (webm) 48_000 else 44_100)
            }
            // WebM can't store a rotation flag, so only MP4 gets one
            if (codec != VideoCodec.VP8) rec.setOrientationHint(jpegOrientation())
            rec.prepare()
            recorder = rec
            recFps = fmt.fps
            recRange = pickFpsRange(c, fmt.fps)

            runCatching { session?.stopRepeating() }
            runCatching { session?.close() }; session = null
            val outs = listOf(OutputConfiguration(previewSurface ?: return), OutputConfiguration(rec.surface))
            val cfg = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR, outs, Executor { h.post(it) },
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(sess: CameraCaptureSession) { session = sess; startRecordRequest(dev, rec) }
                    override fun onConfigureFailed(sess: CameraCaptureSession) {
                        _events.tryEmit("Video session failed"); abortRecording()
                    }
                },
            )
            dev.createCaptureSession(cfg)
        } catch (e: Exception) {
            _events.tryEmit("Record failed: ${e.message}")
            abortRecording()
        }
    }

    private fun startRecordRequest(dev: CameraDevice, rec: MediaRecorder) {
        try {
            val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(previewSurface ?: return)
                addTarget(rec.surface)
            }
            previewBuilder = b
            applySettings(b, settings)
            session?.setRepeatingRequest(b.build(), liveCallback, handler)
            rec.start()
            _recording.value = true
        } catch (e: Exception) {
            _events.tryEmit("Record failed: ${e.message}")
            abortRecording()
        }
    }

    fun stopRecording(restart: Boolean = true) {
        val rec = recorder ?: return
        runCatching { session?.stopRepeating() }
        runCatching { session?.abortCaptures() }
        val ok = try { rec.stop(); true } catch (e: RuntimeException) { false }   // throws if clip is too short
        runCatching { rec.reset(); rec.release() }
        recorder = null
        _recording.value = false
        recFps = null; recRange = null
        runCatching { recPfd?.close() }; recPfd = null
        recUri?.let { u ->
            val r = context.contentResolver
            if (ok) {
                val v = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                r.update(u, v, null, null)
                _events.tryEmit("Video saved")
                maybeUpload(u, recName, recMime)
            } else {
                r.delete(u, null, null)
                _events.tryEmit("Clip too short, discarded")
            }
        }
        recUri = null
        runCatching { session?.close() }; session = null
        if (restart) device?.let { startSession(it) }
    }

    private fun abortRecording() {
        runCatching { recorder?.reset(); recorder?.release() }; recorder = null
        runCatching { recPfd?.close() }; recPfd = null
        recUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }; recUri = null
        recFps = null; recRange = null
        _recording.value = false
        runCatching { session?.close() }; session = null
        device?.let { startSession(it) }
    }

    // ---------- saving ----------

    /** Film processing of a 50 MP photo takes a couple of seconds, so it must not run on the camera thread. */
    private val saveExecutor = Executors.newSingleThreadExecutor()

    private fun savePhoto(reader: ImageReader) {
        val img = reader.acquireNextImage() ?: return
        val bytes = img.use { image ->
            val buf = image.planes[0].buffer
            ByteArray(buf.remaining()).also { buf.get(it) }
        }
        val s = settings
        val look = Films.byId(s.film)
        val strength = s.filmStrength
        val keepOriginal = s.keepOriginal
        val src = if (captureHeic) PhotoFormat.HEIC else PhotoFormat.JPEG
        // Android can't encode HEIC from a bitmap, so a filtered HEIC (or HEIC the lens can't capture) is saved as JPEG
        val target = if (s.photoFormat == PhotoFormat.HEIC && (look != null || src != PhotoFormat.HEIC)) PhotoFormat.JPEG else s.photoFormat

        saveExecutor.execute {
            if (look == null && target == src) {
                write(target.ext, target.mime) { it.write(bytes) }
                return@execute
            }
            if (keepOriginal) write(src.ext, src.mime) { it.write(bytes) }
            val converted = try {
                PhotoCodec.transcode(bytes, look, strength, target)
            } catch (e: OutOfMemoryError) {
                null
            } catch (e: Exception) {
                null
            }
            if (converted == null) {
                _events.tryEmit("Couldn't convert the photo, saved the original")
                if (!keepOriginal) write(src.ext, src.mime) { it.write(bytes) }
            } else {
                val suffix = look?.let { "_${it.id}" } ?: ""
                write(
                    target.ext, target.mime, suffix = suffix,
                    after = if (target == PhotoFormat.JPEG) ({ uri: Uri -> copyExif(bytes, uri) }) else null,
                ) { it.write(converted) }
            }
        }
    }

    private val exifTags = listOf(
        ExifInterface.TAG_ORIENTATION, ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_WHITE_BALANCE,
    )

    /** Re-encoding drops the camera's EXIF (including the rotation), so copy the useful tags back. */
    private fun copyExif(original: ByteArray, uri: Uri) {
        runCatching {
            val src = ExifInterface(ByteArrayInputStream(original))
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val dst = ExifInterface(pfd.fileDescriptor)
                exifTags.forEach { t -> src.getAttribute(t)?.let { dst.setAttribute(t, it) } }
                dst.saveAttributes()
            }
        }
    }

    private fun onRawImage(reader: ImageReader) {
        val img = reader.acquireNextImage() ?: return
        pendingRaw[img.timestamp] = img
        tryFinishRaw(img.timestamp)
    }

    @Synchronized
    private fun tryFinishRaw(ts: Long) {
        val img = pendingRaw[ts] ?: return
        val res = pendingResults[ts] ?: return
        pendingRaw.remove(ts); pendingResults.remove(ts)
        val orient = pendingOrientation.remove(ts) ?: 90
        val c = chars
        if (c == null) { img.close(); return }
        img.use { image ->
            DngCreator(c, res).use { dng ->
                dng.setOrientation(
                    when (orient) {
                        90 -> 6   // EXIF ROTATE_90
                        180 -> 3
                        270 -> 8
                        else -> 1
                    }
                )
                write("dng", "image/x-adobe-dng") { out -> dng.writeImage(out, image) }
            }
        }
    }

    /** Shows a short message in the app. */
    fun say(message: String) { _events.tryEmit(message) }

    /** Queues a freshly saved file for the cloud folder, if auto-upload is on. */
    private fun maybeUpload(uri: Uri, name: String, mime: String) {
        val s = settings
        val folder = s.cloudFolder ?: return
        if (!s.cloudAuto) return
        CloudSync.enqueue(context, uri, name, mime, folder, s.cloudWifiOnly)
    }

    private fun write(
        ext: String,
        mime: String,
        suffix: String = "",
        after: ((Uri) -> Unit)? = null,
        body: (OutputStream) -> Unit,
    ): Uri? {
        val name = "PROCAM_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())}$suffix.$ext"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/ProCam")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val r = context.contentResolver
        val uri = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            r.openOutputStream(uri)?.use { body(it) }
            after?.invoke(uri)
            values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
            r.update(uri, values, null, null)
            _events.tryEmit("Saved $name")
            maybeUpload(uri, name, mime)
            uri
        } catch (e: Exception) {
            r.delete(uri, null, null)
            _events.tryEmit("Save failed: ${e.message}")
            null
        }
    }
}
