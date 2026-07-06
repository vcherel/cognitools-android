# CogniTools Android

Personal Android app (Jetpack Compose), sideloaded on Valentin's phone.

## Workflow
After implementing a change, end with a summary of what changed and wait for Valentin's ok. Do not build, install, commit, or push at that point. Shipping happens only through /wrap-up or an explicit request.

## Deploy method (used by /wrap-up)
Build the release APK and install it on the connected phone:

```
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Always install release builds, never debug (performance matters on device). Debug is only for `run-as` access, e.g. DB inspection.
