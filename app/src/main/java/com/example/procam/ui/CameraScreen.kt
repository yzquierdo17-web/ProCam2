package com.example.procam.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.procam.CameraViewModel
import com.example.procam.camera.Awb
import com.example.procam.camera.CaptureMode
import com.example.procam.camera.Drive
import com.example.procam.camera.Metering
import com.example.procam.camera.PhotoFormat
import com.example.procam.camera.VideoCodec
import com.example.procam.cloud.CloudSync
import com.example.procam.camera.pickVideo
import com.example.procam.film.FilmLook
import com.example.procam.film.Films
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import android.util.Size as AndroidSize

private val Amber = Color(0xFFFFB300)

private enum class Panel(val label: String) {
    ISO("ISO"), SHUTTER("S"), FOCUS("F"), APERTURE("f/"), WB("WB"), EV("EV"),
    DRIVE("DRV"), METER("MTR"), FILM("FILM"), FORMAT("FMT"), CLOUD("CLOUD"), VIDEO("VID"),
}

@Composable
fun CameraScreen(vm: CameraViewModel = viewModel()) {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    LaunchedEffect(Unit) {
        vm.controller.events.collect { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) CameraContent(vm) else Column(
            Modifier.align(Alignment.Center).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Camera permission is required.", color = Color.White)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Grant permission") }
        }
    }
}

@Composable
private fun CameraContent(vm: CameraViewModel) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val s by vm.settings.collectAsStateWithLifecycle()
    val caps by vm.controller.caps.collectAsStateWithLifecycle()
    val live by vm.controller.live.collectAsStateWithLifecycle()
    val countdown by vm.countdown.collectAsStateWithLifecycle()
    val recording by vm.controller.recording.collectAsStateWithLifecycle()
    val previewSize by vm.controller.previewSize.collectAsStateWithLifecycle()
    var panel by remember { mutableStateOf(Panel.ISO) }
    val manual = s.manualExposure && caps.manualSensor
    val look = Films.byId(s.film)
    val cloudStatus by vm.cloudStatus.collectAsStateWithLifecycle()
    val cloudReady = remember(s.cloudFolder) { s.cloudFolder?.let { CloudSync.hasAccess(ctx, it) } == true }

    var tv by remember { mutableStateOf<TextureView?>(null) }
    var hist by remember { mutableStateOf<IntArray?>(null) }
    var ring by remember { mutableStateOf<Offset?>(null) }
    var overlay by remember { mutableStateOf<ImageBitmap?>(null) }
    var elapsed by remember { mutableStateOf(0) }

    // Screen rotation (0..3). Rotating 90 -> 270 changes this without a configuration change,
    // so listen to the display directly.
    var rotation by remember { mutableStateOf(view.display?.rotation ?: Surface.ROTATION_0) }
    DisposableEffect(view) {
        val dm = ctx.getSystemService(DisplayManager::class.java)
        val l = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) { rotation = view.display?.rotation ?: rotation }
        }
        dm.registerDisplayListener(l, null)
        onDispose { dm.unregisterDisplayListener(l) }
    }
    LaunchedEffect(rotation) { vm.setDisplayRotation(rotation * 90) }
    val rotationNow by rememberUpdatedState(rotation)
    val previewSizeNow by rememberUpdatedState(previewSize)

    val audioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            vm.connectCloud(uri)
        }
    }

    LaunchedEffect(recording) {
        elapsed = 0
        while (recording) { delay(1000); elapsed++ }
    }
    LaunchedEffect(ring) { if (ring != null) { delay(1500); ring = null } }

    // histogram: sample the preview about 4x per second
    LaunchedEffect(s.histogram, tv, landscape) {
        if (!s.histogram) return@LaunchedEffect
        while (isActive) {
            delay(250)
            val v = tv
            if (v != null && v.isAvailable) {
                val bmp = if (landscape) v.getBitmap(160, 120) else v.getBitmap(120, 160)
                if (bmp != null) hist = withContext(Dispatchers.Default) { lumaHistogram(bmp) }
            }
        }
    }

    // focus peaking + zebra: sample the preview at 270x360 (360x270 in landscape) about 6x per second
    LaunchedEffect(s.peaking, s.zebra, tv, landscape) {
        if (!s.peaking && !s.zebra) { overlay = null; return@LaunchedEffect }
        while (isActive) {
            delay(160)
            val v = tv
            if (v != null && v.isAvailable) {
                val bmp = if (landscape) v.getBitmap(360, 270) else v.getBitmap(270, 360)
                if (bmp != null) {
                    val pk = s.peaking
                    val zb = s.zebra
                    overlay = withContext(Dispatchers.Default) { Analysis.overlay(bmp, pk, zb).asImageBitmap() }
                }
            }
        }
    }

    // ---------- the three blocks, arranged differently in portrait and landscape ----------

    val topBar: @Composable () -> Unit = {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Pill("PHOTO", s.mode == CaptureMode.PHOTO) {
                if (!recording) {
                    vm.update { it.copy(mode = CaptureMode.PHOTO) }
                    if (panel == Panel.VIDEO) panel = Panel.ISO
                }
            }
            Pill("VIDEO", s.mode == CaptureMode.VIDEO) {
                if (!recording) {
                    vm.update { it.copy(mode = CaptureMode.VIDEO) }
                    panel = Panel.VIDEO
                    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        audioLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            }
            Pill("RAW", s.raw && caps.raw) { if (caps.raw) vm.update { it.copy(raw = !it.raw) } }
            Pill("GRID", s.grid) { vm.update { it.copy(grid = !it.grid) } }
            Pill("HIST", s.histogram) { vm.update { it.copy(histogram = !it.histogram) } }
            Pill("PEAK", s.peaking) { vm.update { it.copy(peaking = !it.peaking) } }
            Pill("ZEBRA", s.zebra) { vm.update { it.copy(zebra = !it.zebra) } }
            Pill("TORCH", s.torch) { vm.update { it.copy(torch = !it.torch) } }
            Pill("AE LOCK", s.aeLock) { vm.update { it.copy(aeLock = !it.aeLock) } }
        }
    }

    val previewBox: @Composable (Modifier) -> Unit = { mod ->
        Box(mod) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    TextureView(c).apply {
                        tv = this
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(t: SurfaceTexture, w: Int, h: Int) = vm.onSurface(t)
                            override fun onSurfaceTextureSizeChanged(t: SurfaceTexture, w: Int, h: Int) {}
                            override fun onSurfaceTextureDestroyed(t: SurfaceTexture): Boolean {
                                vm.onSurfaceGone(t); return true
                            }
                            override fun onSurfaceTextureUpdated(t: SurfaceTexture) {}
                        }
                        addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                            configureTransform(v as TextureView, previewSizeNow, rotationNow)
                        }
                    }
                },
                update = {
                    configureTransform(it, previewSize, rotation)
                    applyFilm(it, look, s.filmStrength)
                },
            )
            FilmOverlay(look, s.filmStrength, Modifier.fillMaxSize())
            if (s.grid) GridOverlay()
            overlay?.let {
                Image(
                    bitmap = it, contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                    filterQuality = FilterQuality.None,
                )
            }

            // pinch = zoom, tap = focus + meter, long-press = reset
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTransformGestures { _, _, zoomChange, _ -> if (zoomChange != 1f) vm.zoomBy(zoomChange) }
                }.pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { o ->
                            ring = o
                            vm.tapFocus(o.x / size.width, o.y / size.height)
                        },
                        onLongPress = { ring = null; vm.clearFocus() },
                    )
                },
            )
            ring?.let { p ->
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(Amber, radius = 36.dp.toPx(), center = p, style = Stroke(width = 3f))
                }
            }

            if (s.histogram) hist?.let { Histogram(it, Modifier.align(Alignment.TopStart).padding(8.dp)) }
            Text(
                "${live.aperture?.let { "f/%.1f  ".format(it) } ?: ""}${live.shutterNs?.let(::shutterText) ?: "–"}  ISO ${live.iso ?: "–"}",
                color = Color.White, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            )
            Text(
                "${if (manual) "M" else "P"} · ${if (s.manualFocus) "MF" else if (s.afSingle) "AF-S" else "AF-C"} · ${s.metering.label}",
                color = Color.White, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 62.dp),
            )
            if (s.histogram) hist?.let { ExposureMeter(it, Modifier.align(Alignment.BottomCenter).padding(bottom = 58.dp)) }
            if (s.zoom > 1.01f) Text(
                "%.1f×".format(s.zoom),
                color = Amber, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 58.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0x99000000))
                    .clickable { vm.setZoom(1f) }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
            countdown?.let {
                Text("$it", color = Color.White, fontSize = 96.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
            }
            if (recording) Text(
                "● REC %02d:%02d".format(elapsed / 60, elapsed % 60),
                color = Color(0xFFFF5252), fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
            )
            if (vm.lenses.size > 1) {
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    vm.lenses.forEach { l ->
                        Pill(l.label, s.cameraId == l.id) { vm.update { it.copy(cameraId = l.id) } }
                    }
                }
            }
        }
    }

    val controls: @Composable (Modifier, Arrangement.Vertical) -> Unit = { mod, arrangement ->
        Column(mod, verticalArrangement = arrangement) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Panel.entries.filter {
                    (it != Panel.VIDEO || s.mode == CaptureMode.VIDEO) && (it != Panel.FORMAT || s.mode == CaptureMode.PHOTO)
                }.forEach { t ->
                    val value = when (t) {
                        Panel.ISO -> if (manual) "${s.iso}" else "A"
                        Panel.SHUTTER -> if (manual) shutterText(s.shutterNs) else "A"
                        Panel.FOCUS -> if (s.manualFocus && caps.minFocusDiopter > 0f) "M" else if (s.afSingle) "AF-S" else "AF-C"
                        Panel.WB -> s.kelvin?.let { "${it}K" } ?: s.awb.label
                        Panel.EV -> "%+.1f".format(s.evIndex * caps.evStep)
                        Panel.APERTURE -> live.aperture?.let { "%.1f".format(it) } ?: "–"
                        Panel.DRIVE -> s.drive.label
                        Panel.METER -> s.metering.label
                        Panel.FILM -> look?.label ?: "Off"
                        Panel.FORMAT -> s.photoFormat.label
                        Panel.CLOUD -> if (cloudReady && s.cloudAuto) "On" else "Off"
                        Panel.VIDEO -> caps.pickVideo(s.videoLabel)?.label ?: "–"
                    }
                    Pill("${t.label} $value", panel == t) { panel = t }
                }
            }

            when (panel) {
                Panel.ISO, Panel.SHUTTER -> Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Pill(if (manual) "M · Manual" else "P · Program", manual) {
                            if (!caps.manualSensor) return@Pill
                            vm.update {
                                if (!it.manualExposure) it.copy(
                                    manualExposure = true,
                                    iso = live.iso ?: it.iso,
                                    shutterNs = live.shutterNs ?: it.shutterNs,
                                ) else it.copy(manualExposure = false)
                            }
                        }
                        if (!caps.manualSensor) Text("  Not supported on this lens", color = Color.Gray, fontSize = 12.sp)
                    }
                    if (panel == Panel.ISO) {
                        LogSlider(s.iso.toDouble(), caps.isoMin.toDouble(), caps.isoMax.toDouble(), manual) { v ->
                            vm.update { it.copy(iso = v.roundToInt()) }
                        }
                    } else {
                        LogSlider(s.shutterNs.toDouble(), caps.shutterMinNs.toDouble(), caps.shutterMaxNs.toDouble(), manual) { v ->
                            vm.update { it.copy(shutterNs = v.toLong()) }
                        }
                    }
                }

                Panel.FOCUS -> Column {
                    val can = caps.minFocusDiopter > 0f
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Pill("AF-C", !s.manualFocus && !s.afSingle) { vm.setAfMode(manual = false, single = false) }
                        Spacer(Modifier.width(6.dp))
                        Pill("AF-S", !s.manualFocus && s.afSingle) { vm.setAfMode(manual = false, single = true) }
                        Spacer(Modifier.width(6.dp))
                        Pill("MF", s.manualFocus && can) { if (can) vm.setAfMode(manual = true, single = false) }
                        Text(if (can) "  ∞ ← → macro" else "  Fixed focus", color = Color.Gray, fontSize = 12.sp)
                    }
                    Slider(
                        value = if (can) (s.focusDiopter / caps.minFocusDiopter).coerceIn(0f, 1f) else 0f,
                        onValueChange = { f -> vm.update { it.copy(focusDiopter = f * caps.minFocusDiopter) } },
                        enabled = can && s.manualFocus,
                        colors = SliderDefaults.colors(thumbColor = Amber, activeTrackColor = Amber),
                    )
                }

                Panel.APERTURE -> Column {
                    if (caps.apertures.size > 1) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            caps.apertures.forEach { a ->
                                Pill("f/%.1f".format(a), s.aperture == a) { vm.update { it.copy(aperture = a) } }
                            }
                        }
                    } else {
                        Text(
                            "Fixed aperture" + (caps.apertures.firstOrNull()?.let { " f/%.1f".format(it) } ?: "") +
                                " – this lens can't change it",
                            color = Color.Gray, fontSize = 12.sp,
                        )
                    }
                }

                Panel.WB -> Column {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Awb.entries.forEach { a ->
                            Pill(a.label, s.awb == a && s.kelvin == null) { vm.update { it.copy(awb = a, kelvin = null) } }
                        }
                        Pill(s.kelvin?.let { "${it}K" } ?: "Kelvin", s.kelvin != null) {
                            vm.update { it.copy(kelvin = if (it.kelvin == null) 5500 else null) }
                        }
                    }
                    s.kelvin?.let { k ->
                        Slider(
                            value = k.toFloat(),
                            onValueChange = { v -> vm.update { it.copy(kelvin = v.roundToInt()) } },
                            valueRange = 2500f..9000f,
                            colors = SliderDefaults.colors(thumbColor = Amber, activeTrackColor = Amber),
                        )
                    }
                }

                Panel.EV -> {
                    val can = caps.evMax > caps.evMin
                    val hi = caps.evMax.toFloat().coerceAtLeast(caps.evMin + 1f)
                    Slider(
                        value = s.evIndex.toFloat().coerceIn(caps.evMin.toFloat(), hi),
                        onValueChange = { v -> vm.update { it.copy(evIndex = v.roundToInt()) } },
                        valueRange = caps.evMin.toFloat()..hi,
                        steps = if (can) caps.evMax - caps.evMin - 1 else 0,
                        enabled = can && !manual,
                        colors = SliderDefaults.colors(thumbColor = Amber, activeTrackColor = Amber),
                    )
                }

                Panel.DRIVE -> Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Drive.entries.forEach { d -> Pill(d.label, s.drive == d) { vm.update { it.copy(drive = d) } } }
                    }
                    Text(
                        if (s.drive == Drive.BURST) "Hold the shutter button to shoot"
                        else if (s.drive.seconds > 0) "Tap again to cancel the timer" else "",
                        color = Color.Gray, fontSize = 12.sp,
                    )
                }

                Panel.METER -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Metering.entries.forEach { m -> Pill(m.label, s.metering == m) { vm.update { it.copy(metering = m) } } }
                }

                Panel.FILM -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("Off", look == null) { vm.update { it.copy(film = null) } }
                        Films.all.forEach { f ->
                            Pill(f.label, look?.id == f.id) { vm.update { it.copy(film = f.id) } }
                        }
                    }
                    if (look != null) {
                        Text(look.blurb, color = Color.Gray, fontSize = 12.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Strength", color = Color.White, fontSize = 12.sp)
                            Slider(
                                value = s.filmStrength,
                                onValueChange = { v -> vm.update { it.copy(filmStrength = v) } },
                                valueRange = 0f..1f,
                                modifier = Modifier.weight(1f).padding(start = 8.dp),
                                colors = SliderDefaults.colors(thumbColor = Amber, activeTrackColor = Amber),
                            )
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Pill("+ Original", s.keepOriginal) { vm.update { it.copy(keepOriginal = !it.keepOriginal) } }
                        }
                        Text(
                            if (s.mode == CaptureMode.VIDEO) "Preview only – video is saved without the look"
                            else "Applied to JPEG photos. RAW is saved untouched.",
                            color = Color.Gray, fontSize = 12.sp,
                        )
                    }
                }

                Panel.FORMAT -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PhotoFormat.entries.forEach { f ->
                            val ok = f != PhotoFormat.HEIC || caps.heic
                            Pill(if (ok) f.label else "${f.label} (n/a)", s.photoFormat == f && ok) {
                                if (ok) vm.update { it.copy(photoFormat = f) }
                            }
                        }
                        Pill("+ Original", s.keepOriginal) { vm.update { it.copy(keepOriginal = !it.keepOriginal) } }
                    }
                    Text(
                        when (s.photoFormat) {
                            PhotoFormat.JPEG -> "Works everywhere."
                            PhotoFormat.HEIC -> if (!caps.heic) "This lens can't capture HEIC, so photos are saved as JPEG."
                            else if (look != null) "About half the size of JPEG. Photos with a film look are saved as JPEG."
                            else "About half the size of JPEG at the same quality."
                            PhotoFormat.PNG -> "Lossless, but files are very large and take a while to save."
                            PhotoFormat.WEBP -> "Smaller than JPEG. A few older apps can't open it."
                        },
                        color = Color.Gray, fontSize = 12.sp,
                    )
                    Text("RAW (DNG) is separate: use the RAW switch at the top.", color = Color.Gray, fontSize = 12.sp)
                }

                Panel.CLOUD -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val folder = s.cloudFolder
                    if (folder == null || !cloudReady) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Pill("Choose cloud folder", false) { folderPicker.launch(null) }
                        }
                        Text(
                            "Pick a folder in Google Drive, OneDrive, Dropbox or any app that appears in the file picker. " +
                                "New photos and videos are copied there automatically.",
                            color = Color.Gray, fontSize = 12.sp,
                        )
                        if (folder != null) {
                            Text("ProCam lost access to that folder. Choose it again.", color = Color(0xFFFF8A80), fontSize = 12.sp)
                        }
                    } else {
                        val label = remember(folder) { CloudSync.folderName(ctx, folder) ?: "selected folder" }
                        Text("Folder: $label", color = Color.White, fontSize = 12.sp)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Pill("Auto-upload", s.cloudAuto) { vm.update { it.copy(cloudAuto = !it.cloudAuto) } }
                            Pill("Wi-Fi only", s.cloudWifiOnly) { vm.update { it.copy(cloudWifiOnly = !it.cloudWifiOnly) } }
                            Pill("Back up existing", false) { vm.backUpExisting() }
                            Pill("Change", false) { folderPicker.launch(null) }
                            Pill("Disconnect", false) { vm.disconnectCloud() }
                        }
                        Text(
                            "${cloudStatus.synced} backed up · ${cloudStatus.pending} waiting · ${cloudStatus.failed} failed",
                            color = Color.Gray, fontSize = 12.sp,
                        )
                        if (cloudStatus.failed > 0) {
                            Text("Failed files can be retried with Back up existing.", color = Color.Gray, fontSize = 12.sp)
                        }
                    }
                }

                Panel.VIDEO -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val cur = caps.pickVideo(s.videoLabel)
                    if (caps.videoFormats.isEmpty()) {
                        Text("No video formats reported for this lens", color = Color.Gray, fontSize = 12.sp)
                    }
                    val codec = s.videoCodec.takeIf { it in caps.videoCodecs } ?: VideoCodec.H264
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        caps.videoCodecs.forEach { c ->
                            Pill(c.label, codec == c) { if (!recording) vm.update { it.copy(videoCodec = c) } }
                        }
                    }
                    if (codec == VideoCodec.VP8) {
                        Text("WebM can't store rotation, so hold the phone sideways for it.", color = Color.Gray, fontSize = 12.sp)
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        caps.videoFormats.forEach { f ->
                            Pill(f.label, cur == f) { if (!recording) vm.update { it.copy(videoLabel = f.label) } }
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("Auto", s.bitrateMbps == null) { vm.update { it.copy(bitrateMbps = null) } }
                        listOf(20, 50, 100).forEach { m ->
                            Pill("$m Mbps", s.bitrateMbps == m) { vm.update { it.copy(bitrateMbps = m) } }
                        }
                        Pill("Stabilize", s.stabilize) { vm.update { it.copy(stabilize = !it.stabilize) } }
                        Pill("Audio", s.audio) { vm.update { it.copy(audio = !it.audio) } }
                    }
                }
            }

            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (s.mode == CaptureMode.VIDEO) {
                    Box(
                        Modifier.size(72.dp)
                            .border(4.dp, Color.White, CircleShape)
                            .padding(7.dp)
                            .padding(if (recording) 12.dp else 0.dp)
                            .clip(if (recording) RoundedCornerShape(6.dp) else CircleShape)
                            .background(Color(0xFFE53935))
                            .clickable { vm.toggleRecord() },
                    )
                } else {
                    Box(
                        Modifier.size(72.dp)
                            .border(4.dp, Color.White, CircleShape)
                            .padding(7.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onPress = { vm.burstStart(); tryAwaitRelease(); vm.burstStop() },
                                    onTap = { vm.shoot() },
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        countdown?.let { Text("$it", color = Color.Black, fontSize = 28.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }

    if (landscape) {
        // preview on the left, everything else in a scrolling column on the right
        Row(Modifier.fillMaxSize().safeDrawingPadding()) {
            previewBox(Modifier.fillMaxHeight().aspectRatio(4f / 3f))
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                topBar()
                controls(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), Arrangement.spacedBy(10.dp))
            }
        }
    } else {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            topBar()
            previewBox(Modifier.fillMaxWidth().aspectRatio(3f / 4f))
            controls(Modifier.fillMaxSize().padding(horizontal = 12.dp), Arrangement.SpaceEvenly)
        }
    }
}

/**
 * Camera2 doesn't rotate a TextureView for you when the phone is turned sideways.
 * This is the standard fix from Google's camera2basic sample.
 */
private fun configureTransform(v: TextureView, size: AndroidSize?, rotation: Int) {
    val ps = size ?: return
    val vw = v.width.toFloat()
    val vh = v.height.toFloat()
    if (vw == 0f || vh == 0f) return
    val m = Matrix()
    val viewRect = RectF(0f, 0f, vw, vh)
    val bufferRect = RectF(0f, 0f, ps.height.toFloat(), ps.width.toFloat())
    val cx = viewRect.centerX()
    val cy = viewRect.centerY()
    if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
        bufferRect.offset(cx - bufferRect.centerX(), cy - bufferRect.centerY())
        m.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
        val scale = maxOf(vh / ps.height, vw / ps.width)
        m.postScale(scale, scale, cx, cy)
        m.postRotate(90f * (rotation - 2), cx, cy)
    } else if (rotation == Surface.ROTATION_180) {
        m.postRotate(180f, cx, cy)
    }
    v.setTransform(m)
}

/** Live colour grade on the preview: a colour matrix on the TextureView's layer. */
private fun applyFilm(v: TextureView, look: FilmLook?, strength: Float) {
    if (look == null) {
        v.setLayerPaint(null)
        return
    }
    v.setLayerPaint(Paint().apply { colorFilter = ColorMatrixColorFilter(look.colorMatrix(strength)) })
}

@Composable
private fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = if (selected) Color.Black else Color.White,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Amber else Color(0x33FFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun LogSlider(value: Double, min: Double, max: Double, enabled: Boolean, onChange: (Double) -> Unit) {
    if (max <= min) return
    Slider(
        value = (ln(value.coerceIn(min, max) / min) / ln(max / min)).toFloat().coerceIn(0f, 1f),
        onValueChange = { onChange(min * (max / min).pow(it.toDouble())) },
        enabled = enabled,
        colors = SliderDefaults.colors(thumbColor = Amber, activeTrackColor = Amber),
    )
}

@Composable
private fun GridOverlay() {
    Canvas(Modifier.fillMaxSize()) {
        val c = Color(0x88FFFFFF)
        for (i in 1..2) {
            val x = size.width * i / 3
            val y = size.height * i / 3
            drawLine(c, Offset(x, 0f), Offset(x, size.height), 1.5f)
            drawLine(c, Offset(0f, y), Offset(size.width, y), 1.5f)
        }
    }
}

@Composable
private fun Histogram(bins: IntArray, modifier: Modifier) {
    Canvas(modifier.size(width = 96.dp, height = 44.dp).background(Color(0x66000000))) {
        val max = (bins.maxOrNull() ?: 1).coerceAtLeast(1).toFloat()
        val bw = size.width / bins.size
        bins.forEachIndexed { i, v ->
            val h = size.height * sqrt(v / max)   // sqrt so tiny bins stay visible
            drawRect(Color.White.copy(alpha = 0.85f), Offset(i * bw, size.height - h), Size(bw, h))
        }
    }
}

/** Estimated exposure offset (-3..+3 EV) from the preview's mean brightness vs. 18% grey. */
@Composable
private fun ExposureMeter(bins: IntArray, modifier: Modifier) {
    val total = bins.sum().coerceAtLeast(1)
    val mean = bins.indices.sumOf { (it * 4 + 2) * bins[it].toLong() }.toDouble() / total
    val lin = (mean / 255.0).pow(2.2).coerceAtLeast(1e-4)
    val ev = (ln(lin / 0.18) / ln(2.0)).coerceIn(-3.0, 3.0)
    Canvas(modifier.size(width = 200.dp, height = 18.dp)) {
        val y = size.height / 2
        drawLine(Color(0x99FFFFFF), Offset(0f, y), Offset(size.width, y), 2f)
        for (i in -3..3) {
            val x = size.width * (i + 3) / 6f
            val h = if (i == 0) size.height else size.height / 2
            drawLine(Color.White, Offset(x, y - h / 2), Offset(x, y + h / 2), 2f)
        }
        drawCircle(Amber, 6.dp.toPx(), Offset(size.width * ((ev + 3) / 6).toFloat(), y))
    }
}

/** 64-bin luma histogram of a small preview frame. */
private fun lumaHistogram(bmp: Bitmap): IntArray {
    val w = bmp.width
    val h = bmp.height
    val px = IntArray(w * h)
    bmp.getPixels(px, 0, w, 0, 0, w, h)
    val bins = IntArray(64)
    for (p in px) {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        bins[((r * 299 + g * 587 + b * 114) / 1000) shr 2]++
    }
    return bins
}

private fun shutterText(ns: Long): String =
    if (ns >= 400_000_000L) "%.1fs".format(ns / 1e9) else "1/${(1e9 / ns).roundToInt()}"
