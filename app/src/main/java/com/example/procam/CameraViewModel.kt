package com.example.procam

import android.app.Application
import android.content.Intent
import android.graphics.SurfaceTexture
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.procam.camera.CameraController
import com.example.procam.camera.CameraSettings
import com.example.procam.camera.Drive
import com.example.procam.camera.PhotoFormat
import com.example.procam.camera.Point
import com.example.procam.cloud.CloudSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CameraViewModel(app: Application) : AndroidViewModel(app) {
    val controller = CameraController(app)
    val lenses = controller.lenses()

    private val store = SettingsStore(app)
    private val _settings = MutableStateFlow(store.load(CameraSettings(cameraId = controller.defaultCameraId())))
    val settings = _settings.asStateFlow()

    val cloudStatus = CloudSync.status(app).stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), CloudSync.Status(0, 0, 0)
    )

    private val _countdown = MutableStateFlow<Int?>(null)
    val countdown = _countdown.asStateFlow()

    private var texture: SurfaceTexture? = null
    private var timerJob: Job? = null
    private var burstJob: Job? = null

    fun onSurface(t: SurfaceTexture) { texture = t; reopen() }
    /** Only closes if this is still the current surface (rotating can create a new one first). */
    fun onSurfaceGone(t: SurfaceTexture) {
        if (texture === t) { texture = null; controller.close() }
    }

    fun setDeviceOrientation(deg: Int) { controller.deviceOrientation = deg }
    fun setDisplayRotation(deg: Int) { controller.displayRotation = deg }

    private fun reopen() {
        val t = texture ?: return
        val s = _settings.value
        val id = s.cameraId ?: return
        controller.open(t, id, s)
    }

    fun update(block: (CameraSettings) -> CameraSettings) {
        val old = _settings.value
        var new = block(old)
        // switching to or from HEIC changes the photo stream, which needs the camera session rebuilt
        val heicChanged = (old.photoFormat == PhotoFormat.HEIC) != (new.photoFormat == PhotoFormat.HEIC)
        // lens, photo/video mode and the photo stream can't change while recording
        if (controller.recording.value && (old.cameraId != new.cameraId || old.mode != new.mode || heicChanged)) return
        if (old.cameraId != new.cameraId) {
            new = new.copy(meter = null, aeLock = false, kelvin = null, aperture = null, zoom = 1f)
        }
        _settings.value = new
        store.save(new)
        if (old.cameraId != new.cameraId || heicChanged) reopen() else controller.updateSettings(new)
    }

    fun connectCloud(folder: Uri) {
        update { it.copy(cloudFolder = folder.toString(), cloudAuto = true) }
    }

    fun disconnectCloud() {
        val folder = _settings.value.cloudFolder
        CloudSync.cancelAll(getApplication<Application>())
        if (folder != null) runCatching {
            getApplication<Application>().contentResolver.releasePersistableUriPermission(
                Uri.parse(folder), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        update { it.copy(cloudFolder = null, cloudAuto = false) }
    }

    fun backUpExisting() {
        val s = _settings.value
        val folder = s.cloudFolder ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val n = CloudSync.backUpExisting(getApplication<Application>(), folder, s.cloudWifiOnly)
            controller.say(if (n == 0) "Everything is already backed up" else "Queued $n files for upload")
        }
    }

    /** Pinch zoom. Skips the settings store: it changes dozens of times per second. */
    fun zoomBy(factor: Float) = setZoom(_settings.value.zoom * factor)

    fun setZoom(z: Float) {
        val caps = controller.caps.value
        val old = _settings.value
        val nz = z.coerceIn(caps.zoomMin, caps.zoomMax)
        if (nz == old.zoom) return
        val new = old.copy(zoom = nz)
        _settings.value = new
        controller.updateSettings(new)
    }

    fun tapFocus(x: Float, y: Float) {
        update { it.copy(meter = Point(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))) }
        if (!_settings.value.manualFocus) controller.triggerAf()
    }

    fun clearFocus() {
        update { it.copy(meter = null, aeLock = false) }
        controller.cancelAf()
    }

    fun setAfMode(manual: Boolean, single: Boolean) {
        update { it.copy(manualFocus = manual, afSingle = single) }
        if (single && !manual) controller.triggerAf() else controller.cancelAf()
    }

    /** Tap on the shutter button. Burst is handled by burstStart/burstStop (hold). */
    fun shoot() {
        val s = _settings.value
        when (s.drive) {
            Drive.BURST -> {}
            Drive.SINGLE -> viewModelScope.launch {
                if (s.afSingle && !s.manualFocus) { controller.triggerAf(); delay(500) }
                controller.capture(_settings.value)
            }
            else -> if (timerJob?.isActive == true) {
                timerJob?.cancel(); _countdown.value = null
            } else {
                timerJob = viewModelScope.launch {
                    for (i in s.drive.seconds downTo 1) { _countdown.value = i; delay(1000) }
                    _countdown.value = null
                    controller.capture(_settings.value)
                }
            }
        }
    }

    fun burstStart() {
        if (_settings.value.drive != Drive.BURST) return
        burstJob?.cancel()
        burstJob = viewModelScope.launch {
            while (true) { controller.capture(_settings.value); delay(300) }
        }
    }

    fun burstStop() { burstJob?.cancel(); burstJob = null }

    fun toggleRecord() {
        if (controller.recording.value) controller.stopRecording() else controller.startRecording(_settings.value)
    }

    override fun onCleared() { controller.close() }
}
