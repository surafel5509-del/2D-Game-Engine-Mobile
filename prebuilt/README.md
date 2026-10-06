# Prebuilt APKs

The installable artefacts produced by `.github/workflows/android.yml`, committed here so they can
be fetched without going through the Actions artefact store:

| File | What it is |
| --- | --- |
| `SEngine-debug.apk` | The S Engine editor + player (debug signed). Install on Android 8.0+ and open a project. |
| `SpaceRun-game.apk` | A standalone game exported by the engine's own APK builder from the "Space Shooter" template, rebuilt and re-signed with a throwaway test key (`com.sengine.game.spacerun`, version 1.2.3). |

CI refreshes these from the current source on every push that changes them; the file name and the
rolling `sengine-latest` release carry the same bytes. Install with:

```
adb install -r prebuilt/SEngine-debug.apk
```

Both APKs are debug/test signed - do not ship them to a store. Release builds use the keystore
configured in the project's build settings.
