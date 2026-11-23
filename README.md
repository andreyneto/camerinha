<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp" width="128" alt="Camerinha">
</p>

<h1 align="center">Camerinha</h1>

<p align="center">RTSP cameras on Android TV / Google TV, with as little delay as possible.</p>

<p align="center">
  <img src="docs/screenshots/grid.webp" width="49%" alt="Grid with four cameras">
  <img src="docs/screenshots/fullscreen.webp" width="49%" alt="Fullscreen, switching to the fourth camera">
  <img src="docs/screenshots/floating.webp" width="49%" alt="Floating window over another app">
  <img src="docs/screenshots/settings.webp" width="49%" alt="Camera list and settings">
</p>

<p align="center"><sub>The app running on the Android TV emulator. The camera feeds are stock clips from <a href="https://www.pexels.com">Pexels</a> (Curtis Adams, Kindel Media), not real cameras. Behind the floating window, the TV home screen is blurred.</sub></p>

## What it does

- **Grid and fullscreen** views that reconnect on their own. Video goes from RTP straight into the hardware decoder, with no buffer.
- **Automatic discovery** of ONVIF cameras on the network: pick a camera, confirm its username and password, and the stream URLs are filled in for you.
- **Main and light streams**: the main stream plays in fullscreen; a lighter one, when the camera has it, plays in the grid and the floating window. Discovery and "Find streams" list what the camera offers, including substreams it does not report properly (such as SONOFF's `av_stream/ch1`), and the fields become a pick list.
- **Floating window** on top of any app, for TVs without system picture-in-picture (such as Chromecast with Google TV). It sits in the top-right corner and reacts to motion: large and opaque while something moves, small and slightly see-through after 10 s of a still scene.
- **Shortcut button**: a spare button on the remote (the Netflix one, for example) becomes a Camerinha key.

The interface is in English and Portuguese, following the TV's language, and plays the TV's own navigation and click sounds.

## Remote control

| Where | Key | Action |
|---|---|---|
| Grid | OK | Camera in fullscreen |
| Grid | Hold OK | Cameras and settings |
| Fullscreen | Left / right | Previous / next camera |
| Fullscreen | OK or Home | Floating window |
| Fullscreen | Back | Grid |
| Anywhere | Shortcut button | Floating window on/off (off opens the app) |
| Floating window | Hold the shortcut button | Size mode: automatic → small → medium → large |

The app opens on the camera last watched in fullscreen. The floating window opens with the camera it showed last.

## Install

Download the APK from [Releases](../../releases) and, with the TV connected over adb:

```bash
adb install camerinha-<version>.apk
```

Or build it yourself (needs the Android SDK and JDK 17):

```bash
./gradlew installRelease
```

Two permissions have no settings screen on TV and are granted over adb, once:

```bash
# Floating window
adb shell appops set dev.andreyneto.camerinha SYSTEM_ALERT_WINDOW allow

# Shortcut button (accessibility service)
adb shell settings put secure enabled_accessibility_services \
  dev.andreyneto.camerinha/dev.andreyneto.camerinha.ShortcutService
adb shell settings put secure accessibility_enabled 1
```

Then, in the app: hold OK on the grid → **Remote shortcut button** → press the button you want.

The accessibility service receives every key on the remote but only acts on the chosen one. Uninstalling the app removes both permissions.

## Adding cameras

- **On the TV**: hold OK on the grid → **Search the network** (ONVIF) or **Add manually**.
- **Over adb**, without typing on the remote:

  ```bash
  adb shell am start -n dev.andreyneto.camerinha/.MainActivity \
    --es name Porch --es url 'rtsp://user:password@192.168.0.10:554/stream1' \
    --es sub 'rtsp://user:password@192.168.0.10:554/stream2'
  ```

  `sub` is optional: it is the low-resolution stream used in the grid. Repeating a name replaces that camera.

To pre-fill the login in the network search, add this to `local.properties` (not versioned):

```properties
onvif.user=user
onvif.password=password
```

That login is compiled into the APK. Build with `-PpublicBuild` to leave it out of an APK you intend to share.

## Limitations

- RTSP over TCP only; H.264, H.265 and AV1.
- Camera URLs, password included, are stored as plain text in the app's private storage.
- The floating window takes no focus: to close it, open the app or use the shortcut button.
- Recordings on a camera's SD card are not reachable; only live video is.

## Icons

The original artwork is not in this repository, because its license does not allow redistributing it. To regenerate the launcher icons and the TV banner, download the ["CCTV" icon](https://www.thiings.co/things/cctv) from Thiings, save it as `art/icon.png`, and run:

```bash
python3 art/make_icons.py   # needs Pillow and the Product Sans font
```

## Credits

- Icon: ["CCTV"](https://www.thiings.co/things/cctv) from [thiings.co](https://www.thiings.co).
- RTSP player: [rtsp-client-android](https://github.com/alexeyvasilyev/rtsp-client-android), by Alexey Vasilyev.
- Typeface: [Google Sans Flex](https://github.com/googlefonts/googlesans-flex), under the [SIL Open Font License 1.1](fonts/GoogleSansFlex-OFL.txt). The app bundles a subset: Latin characters, weights 400 to 700, other axes fixed at their defaults.

## License

[MIT](LICENSE). The icon artwork comes from Thiings, is used under their terms, and is not covered by this license. The bundled Google Sans Flex font keeps its own licence, the SIL OFL 1.1.
