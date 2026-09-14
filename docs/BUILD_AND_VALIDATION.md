# Build and verification

## Reproduce

Use JDK 21, the committed Gradle 8.13 wrapper, AGP 8.13.2, Android SDK platform 36 and build-tools 35.0.0. Set ANDROID_HOME or an untracked local.properties sdk.dir. No private signing secret is needed for fossDebug.

```sh
python3 scripts/check-translations.py
./gradlew clean
./gradlew :app:testFossDebugUnitTest
./gradlew :app:lintFossDebug
./gradlew :app:assembleFossDebug
```

The unchanged scrcpy asset is included in source and verified by preBuild. To restore it, run `scripts/fetch-scrcpy.sh`; the expected checksum is fixed in the script, Gradle and runtime BuildConfig.

`app/build/outputs/apk/foss/debug/RivoPhone-2.1-foss.apk` is the installable debug APK. Application ID is `it.stivy.rivo.personal.debug`; namespace remains `com.grinch.rivo4`. Authorities and app-scoped broadcast actions use the actual application ID. Rivo original can remain installed, but Android permits only one default dialer at a time.

Release signing accepts KEYSTORE_PATH, KEYSTORE_PASSWORD, KEY_ALIAS and KEY_PASSWORD only when all are provided; there is no debug-key release fallback. No release APK, keystore, password or store upload is supplied. Debug signing certificates differ between an independent local build and an ephemeral CI runner: use the same signing key for an in-place update, or export recordings before uninstalling a differently signed test build.

## Upstream build findings and narrow corrections

The pristine Rivo baseline requests SDK 37 using a platform identifier unavailable to the installed SDK/AGP combination. The first assemble actually failed on `android-37`; this fork targets/compiles the available API 36. No audio library or scrcpy version was upgraded.

The baseline also references missing `SwipeActionType`, `RivoSwipeToActionBox`, `RivoInteractiveFloatingBarSlider`, `AvatarSettingsScreen` and `SwipeActionsScreen`. Minimal compatible components/settings using the existing preference keys and UI components were added so the dialer compiles. Source inspection and the first compiler run identified these independently of the recording port.

Lint initially found incomplete upstream locales, non-observable Compose resources/locales, an overbroad removed-permission marker, key dispatch through an AndroidX-restricted member and unused privileged device-ID probes. Resource reads are observable, normal key callbacks preserve the volume shortcut, the unused probe is removed, and package visibility uses queries. The one narrow MissingPermission annotation on flip-to-silence documents Android's default-dialer exemption and is guarded by an actual default-dialer package check; it is not a disabled lint category.

The Personal edition ships **English and Italian** with automatic locale config generation. Incomplete original translations are preserved verbatim under `app/src/upstreamLocales/res`, not presented as supported languages. The checker still validates their XML and duplicate keys. It requires complete Italian coverage, valid XML, consistent resource types, plural forms and exact printf placeholder contracts. No generated English copies masquerade as Italian translations.

## CI and tests

The FOSS workflow runs translation verification, clean, all unit tests, lint and assemble. It uploads the APK, SOURCE_COMMIT.txt and SHA256SUMS.txt as **Rivo-Personal-fossDebug** and test/lint reports separately. There is no automatic GitHub release, store build, signing-secret requirement or main merge. Crowdin explicitly runs only in the original author's repository.

Tests exercise lifecycle transitions, first media packet, initialization stop, duplicate start/stop, connection and first-audio timeouts, null pipe, errors during capture, permission loss, cleanup, stale callbacks, elapsed timer and consecutive sessions. ScrcpyClient tests synthesize FourCC/framing/config/media, invalid codecs, sizes, truncated headers/payloads, unexpected EOF, downstream write failures and close behavior. Binder tests exercise timeout and exactly-once disposal of late descriptors. No real calls or personal audio fixtures are in the repository.

Final executed outcomes and artifact identity are recorded in the PR and its CI run. A build/test/lint PASS does not imply hardware capture compatibility.
