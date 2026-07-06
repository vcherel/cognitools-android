# CogniTools Android

Personal Android app (Jetpack Compose), sideloaded on Valentin's phone.

## Workflow
After implementing a change, deploy it on the phone (see below) so Valentin can try it, then end with a summary of what changed. Never commit or push at that point: git operations happen only through /wrap-up or an explicit request.

## Deploy method
Build the release APK and install it on the connected phone:

```
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Always install release builds, never debug (performance matters on device). Debug is only for `run-as` access, e.g. DB inspection.
