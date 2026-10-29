package com.example.procam

import android.content.Context
import com.example.procam.camera.Awb
import com.example.procam.camera.CameraSettings
import com.example.procam.camera.Drive
import com.example.procam.camera.Metering
import com.example.procam.camera.PhotoFormat
import com.example.procam.camera.VideoCodec

/**
 * Remembers the settings you'd expect to keep between launches.
 * Session-only things (lens, zoom, manual mode, focus point, AE lock, torch, Kelvin, photo/video mode)
 * always start fresh.
 */
class SettingsStore(context: Context) {
    private val p = context.getSharedPreferences("procam", Context.MODE_PRIVATE)

    private inline fun <reified E : Enum<E>> enumOr(key: String, fallback: E): E =
        runCatching { enumValueOf<E>(p.getString(key, null) ?: "") }.getOrDefault(fallback)

    fun load(base: CameraSettings): CameraSettings = base.copy(
        raw = p.getBoolean("raw", base.raw),
        grid = p.getBoolean("grid", base.grid),
        histogram = p.getBoolean("histogram", base.histogram),
        peaking = p.getBoolean("peaking", base.peaking),
        zebra = p.getBoolean("zebra", base.zebra),
        afSingle = p.getBoolean("afSingle", base.afSingle),
        awb = enumOr<Awb>("awb", base.awb),
        metering = enumOr<Metering>("metering", base.metering),
        drive = enumOr<Drive>("drive", base.drive),
        iso = p.getInt("iso", base.iso),
        shutterNs = p.getLong("shutterNs", base.shutterNs),
        videoLabel = p.getString("videoLabel", null),
        bitrateMbps = p.getInt("bitrate", 0).takeIf { it > 0 },
        stabilize = p.getBoolean("stabilize", base.stabilize),
        audio = p.getBoolean("audio", base.audio),
        film = p.getString("film", null),
        filmStrength = p.getFloat("filmStrength", base.filmStrength),
        keepOriginal = p.getBoolean("keepOriginal", base.keepOriginal),
        photoFormat = enumOr<PhotoFormat>("photoFormat", base.photoFormat),
        videoCodec = enumOr<VideoCodec>("videoCodec", base.videoCodec),
        cloudFolder = p.getString("cloudFolder", null),
        cloudAuto = p.getBoolean("cloudAuto", base.cloudAuto),
        cloudWifiOnly = p.getBoolean("cloudWifiOnly", base.cloudWifiOnly),
    )

    fun save(s: CameraSettings) {
        p.edit()
            .putBoolean("raw", s.raw)
            .putBoolean("grid", s.grid)
            .putBoolean("histogram", s.histogram)
            .putBoolean("peaking", s.peaking)
            .putBoolean("zebra", s.zebra)
            .putBoolean("afSingle", s.afSingle)
            .putString("awb", s.awb.name)
            .putString("metering", s.metering.name)
            .putString("drive", s.drive.name)
            .putInt("iso", s.iso)
            .putLong("shutterNs", s.shutterNs)
            .putString("videoLabel", s.videoLabel)
            .putInt("bitrate", s.bitrateMbps ?: 0)
            .putBoolean("stabilize", s.stabilize)
            .putBoolean("audio", s.audio)
            .putString("film", s.film)
            .putFloat("filmStrength", s.filmStrength)
            .putBoolean("keepOriginal", s.keepOriginal)
            .putString("photoFormat", s.photoFormat.name)
            .putString("videoCodec", s.videoCodec.name)
            .putString("cloudFolder", s.cloudFolder)
            .putBoolean("cloudAuto", s.cloudAuto)
            .putBoolean("cloudWifiOnly", s.cloudWifiOnly)
            .apply()
    }
}
