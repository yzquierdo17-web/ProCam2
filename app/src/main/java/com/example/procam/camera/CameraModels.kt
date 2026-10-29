package com.example.procam.camera

import android.hardware.camera2.CameraMetadata

/** Normalized (0..1) point in the portrait preview, origin top-left. */
data class Point(val x: Float, val y: Float)

data class LensInfo(val id: String, val label: String, val front: Boolean)

data class VideoFormat(val label: String, val width: Int, val height: Int, val fps: Int)

enum class CaptureMode { PHOTO, VIDEO }

enum class PhotoFormat(val label: String, val ext: String, val mime: String) {
    JPEG("JPEG", "jpg", "image/jpeg"),
    HEIC("HEIC", "heic", "image/heic"),
    PNG("PNG", "png", "image/png"),
    WEBP("WebP", "webp", "image/webp"),
}

enum class VideoCodec(val label: String, val ext: String, val mime: String, val codecMime: String) {
    H264("H.264 MP4", "mp4", "video/mp4", "video/avc"),
    HEVC("HEVC MP4", "mp4", "video/mp4", "video/hevc"),
    VP8("VP8 WebM", "webm", "video/webm", "video/x-vnd.on2.vp8"),
}

/** What the currently open camera can do. */
data class CameraCaps(
    val isoMin: Int = 100,
    val isoMax: Int = 800,
    val shutterMinNs: Long = 1_000_000L,
    val shutterMaxNs: Long = 1_000_000_000L,
    val minFocusDiopter: Float = 0f,   // 0 = fixed focus
    val evMin: Int = 0,
    val evMax: Int = 0,
    val evStep: Float = 1f,
    val manualSensor: Boolean = false,
    val raw: Boolean = false,
    val apertures: List<Float> = emptyList(),
    val videoFormats: List<VideoFormat> = emptyList(),
    val zoomMin: Float = 1f,
    val zoomMax: Float = 1f,
    val heic: Boolean = false,
    val videoCodecs: List<VideoCodec> = listOf(VideoCodec.H264),
)

/** The format with this label, else 1080p30, else the first one the lens offers. */
fun CameraCaps.pickVideo(label: String?): VideoFormat? =
    videoFormats.firstOrNull { it.label == label }
        ?: videoFormats.firstOrNull { it.label == "1080p 30" }
        ?: videoFormats.firstOrNull()

enum class Drive(val label: String, val seconds: Int) {
    SINGLE("Single", 0), BURST("Burst", 0), TIMER2("2s", 2), TIMER10("10s", 10),
}

enum class Metering(val label: String) { MATRIX("Matrix"), CENTER("Center"), SPOT("Spot") }

enum class Awb(val label: String, val mode: Int) {
    AUTO("Auto", CameraMetadata.CONTROL_AWB_MODE_AUTO),
    INCANDESCENT("Tungsten", CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT),
    FLUORESCENT("Fluor.", CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT),
    DAYLIGHT("Daylight", CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT),
    CLOUDY("Cloudy", CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT),
    SHADE("Shade", CameraMetadata.CONTROL_AWB_MODE_SHADE),
}

/** All user-facing state. UI edits this; the controller applies it. */
data class CameraSettings(
    val cameraId: String? = null,
    val manualExposure: Boolean = false,
    val iso: Int = 100,
    val shutterNs: Long = 8_000_000L,      // ~1/125 s
    val manualFocus: Boolean = false,
    val focusDiopter: Float = 0f,          // 0 = infinity
    val awb: Awb = Awb.AUTO,
    val evIndex: Int = 0,
    val torch: Boolean = false,
    val raw: Boolean = false,
    val grid: Boolean = false,
    val histogram: Boolean = true,
    val peaking: Boolean = false,
    val zebra: Boolean = false,
    val meter: Point? = null,          // tap-to-focus / metering point
    val aeLock: Boolean = false,       // locks exposure + white balance
    val afSingle: Boolean = false,     // AF-S (focus on tap/shutter) vs AF-C
    val kelvin: Int? = null,           // manual color temperature, null = preset/auto
    val aperture: Float? = null,       // only on lenses with a variable aperture
    val drive: Drive = Drive.SINGLE,
    val metering: Metering = Metering.MATRIX,
    val mode: CaptureMode = CaptureMode.PHOTO,
    val videoLabel: String? = null,
    val bitrateMbps: Int? = null,      // null = automatic
    val stabilize: Boolean = true,
    val audio: Boolean = true,
    val zoom: Float = 1f,              // pinch zoom ratio
    val film: String? = null,          // id of the film look, null = off
    val filmStrength: Float = 1f,
    val keepOriginal: Boolean = false, // also keep the unconverted photo when a look or format change is applied
    val photoFormat: PhotoFormat = PhotoFormat.JPEG,
    val videoCodec: VideoCodec = VideoCodec.H264,
    val cloudFolder: String? = null,   // persisted tree Uri of the cloud folder
    val cloudAuto: Boolean = false,
    val cloudWifiOnly: Boolean = true,
)

data class LiveReadout(val iso: Int? = null, val shutterNs: Long? = null, val aperture: Float? = null)
