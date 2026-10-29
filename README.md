# ProCam – manual camera app (Android 13+, Kotlin, Camera2, Compose) – v0.9

Open this folder in Android Studio (Koala or newer), let it sync (it generates the Gradle wrapper), and run on a **real device** (emulators lack manual sensor controls and RAW).

## Building the APK without Android Studio (GitHub Actions)
1. Create a free account at github.com, then **New repository** (name `ProCam`, set it to Private).
2. Unzip this project, click **uploading an existing file** on the new repo's page, and drag in everything inside the `ProCam` folder, including the hidden `.github` folder (on a Mac press Cmd+Shift+. in Finder to see it). Click **Commit changes**. If `.github` didn't upload, use **Add file → Create new file**, type `.github/workflows/build.yml` as the name and paste the contents of `build.yml`.
3. Open the **Actions** tab. The **Build APK** run starts by itself (or press **Run workflow**). It takes roughly 5–10 minutes.
4. When it shows a green tick, open the run and download **ProCam-debug-apk** under Artifacts. It contains `app-debug.apk`.
5. Get the APK onto the phone (download it there, or send it by email or Drive), open it, and allow "Install unknown apps" when asked. Needs Android 13 or newer.
6. If the run shows a red cross, open it, click the **Build debug APK** step, and copy the first error lines.
Note: each cloud build signs with a fresh debug key, so to install a newer build over an older one you must uninstall the old one first.

Not compile-tested (no Android SDK where this was written) – expect a small fix or two on the first build.

## Architecture
- `camera/CameraController` – all Camera2 logic: lens discovery, preview session, manual controls, JPEG + RAW(DNG) capture, video recording, MediaStore saving (Pictures/ProCam, Movies/ProCam). No UI code.
- `camera/CameraModels` – `CameraCaps` (what the lens supports), `CameraSettings` (everything the user can change), `LiveReadout`.
- `CameraViewModel` – holds settings, owns the controller, self-timer/burst, reopens the camera on lens/surface change.
- `SettingsStore` – remembers settings between launches (SharedPreferences).
- `ui/CameraScreen` – Compose UI: TextureView preview, control panels, shutter/record button.
- `ui/Analysis` – focus peaking and zebra overlay pixel code.

Data flow: UI → `vm.update{}` → `CameraSettings` → `controller.updateSettings()` → repeating request.

## Features
- **Exposure:** P (auto) and M (manual ISO + shutter), EV compensation, AE lock (also locks white balance), torch.
- **Focus:** AF-C, AF-S (focuses on tap / shutter press), manual focus (∞ to macro), tap-to-focus, long-press to reset.
- **Metering:** Matrix, Center, Spot (at the tap point).
- **White balance:** presets plus an approximate Kelvin slider (an offset on top of what auto-WB chose, not a calibrated scale).
- **Aperture:** selectable only on lenses reporting several apertures; otherwise shows the fixed f-number.
- **Photo:** JPEG, RAW (DNG) toggle, Single / Burst (hold) / 2 s / 10 s drive modes with countdown.
- **Video:** PHOTO/VIDEO switch, 4K/1080p/720p at 24/30/60 fps (only what the lens reports), bitrate Auto/20/50/100 Mbps, stabilization and audio toggles, REC timer. H.264 + AAC. Photos are unavailable while recording.
- **Aids:** live histogram, estimated −3..+3 EV meter, rule-of-thirds grid, focus peaking (green), zebra (red stripes above ~94%).
- **Lens switching** with 35mm-equivalent labels, live aperture/shutter/ISO readout.
- **Settings persistence:** RAW, grid, histogram, peaking, zebra, AF-S/C, white-balance preset, metering, drive, last ISO/shutter, video format, bitrate, stabilize, audio. Lens, manual mode, focus point, AE lock, torch, Kelvin and photo/video mode start fresh each launch.

## Added in v0.7
- **Landscape shooting:** the app follows the phone's auto-rotate setting. In landscape the preview sits on the left and the controls scroll on the right. The preview is rotated to match the screen, and tap-to-focus is mapped for the rotation.
- **Photos and video are saved the right way up** (JPEG, DNG and video orientation come from the orientation sensor), even if auto-rotate is locked.
- **App icon:** adaptive vector icon (lens + aperture on charcoal) with an Android 13 themed-icon variant. Edit `res/drawable/ic_launcher_foreground.xml` and `res/values/colors.xml` to restyle it.
- Not yet tested on a real phone: check that the preview isn't upside down in either landscape direction (front camera especially) and tell me what you see.

## Added in v0.8
- **Pinch to zoom:** pinch on the preview; a zoom badge appears (tap it to return to 1x). Uses Camera2's zoom ratio, so the range depends on the lens. Zoom resets when you switch lens. Tap-to-focus accounts for the zoom.
- **Film looks (FILM tab):** nine original looks (Warm Portrait, Vivid Slide, Cine Teal, Golden Hour, Faded Matte, Vintage 70s, Cross Process, Noir, Silver 400), each a colour grade plus vignette and grain, with a strength slider.
  - The preview shows the grade live (a colour matrix on the preview layer) plus a vignette and animated grain overlay.
  - JPEG photos are processed at full resolution after capture, on a background thread (a few seconds for very large photos), and saved as `..._<look>.jpg`. The camera's EXIF (rotation, exposure data) is copied across. `+ Original` also keeps the unfiltered JPEG.
  - RAW (DNG) is always saved untouched. Video is not filtered: the look is preview-only in video mode (filtering video needs a GPU pipeline).
  - The saved result is close to, but not pixel-identical with, the preview (the live grain and vignette are approximations). If memory runs out on a huge photo, the original is saved instead and a message is shown.
  - Add looks in `film/FilmLook.kt`.

## Added in v0.9
- **Photo formats (FMT tab):** JPEG, HEIC, PNG, WebP (plus RAW/DNG with the RAW switch).
  - HEIC is captured natively by the camera, so it's only offered when the lens supports it. Android has no HEIC encoder for edited pictures, so a photo with a film look is saved as JPEG even when HEIC is selected.
  - PNG (lossless, very large) and WebP (lossy, about the size of a good JPEG) are converted from the captured photo after you shoot, in the background. They are rotated upright because neither carries a reliable rotation tag.
  - `+ Original` keeps the unconverted photo as well. If a conversion runs out of memory the original is saved instead.
- **Video codecs (VID tab):** H.264 MP4, HEVC MP4 and VP8 WebM, listed only if the phone has an encoder for them. HEVC files are about 40% smaller at the same quality (the automatic bitrate is lowered to match). WebM can't store a rotation flag, so portrait WebM clips play sideways: shoot those with the phone held sideways.
- **Cloud backup (CLOUD tab):** choose a folder with the system file picker. Google Drive, OneDrive, Dropbox and similar apps appear there, and the file is copied into that app's folder, which syncs it. No sign-in or API keys are needed in ProCam.
  - Auto-upload copies each new photo, RAW and video in the background (WorkManager), with retries and an optional Wi-Fi-only rule. `Back up existing` queues everything in Pictures/ProCam and Movies/ProCam that hasn't been uploaded. Backed-up files are remembered by file name.
  - The copy is local-to-provider, so the cloud app does the actual internet transfer afterwards. Whether it needs a connection to accept the file depends on the app.
  - Access is lost if you revoke it or clear the app's data. The tab then asks you to choose the folder again.
  - It never deletes anything from your phone.

## Known limits
- Preview is 4:3; video records 16:9, so framing differs.
- Histogram, EV meter, peaking and zebra analyse a downscaled preview sample (a few times a second), so they are approximate and lag fast movement. Thresholds are constants in `Analysis.kt`.
- Some phones hide the ultra-wide/tele behind the main camera ID and won't list them as separate lenses.
- 24 fps may run inside a 15–30 range on some phones. No HEVC / 10-bit yet.
- A phone can't have interchangeable lenses, a mechanical shutter or (usually) a physical aperture. Shutter/aperture-priority modes aren't implemented (Camera2 has none).

## Roadmap
1. Full-resolution analysis (YUV stream on GPU/AGSL), adjustable peaking/zebra thresholds.
2. Multi-camera / physical lens IDs.
3. Bracketing (AEB), interval shooting.
4. Landscape orientation + correct EXIF/UI rotation.
5. Proper settings screen, geotag, JPEG quality.
6. HEVC / 10-bit video, log-style profiles.
7. Device-compatibility testing (Samsung/Pixel quirks), tests, crash reporting.
