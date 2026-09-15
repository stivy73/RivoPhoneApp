# Rivo Personal — third-party notices

Rivo Personal is a **modified fork of RivoPhoneApp**, containing recording components forked from **ShizuCallRecorder**. It is not an official release of either project. The app is not named ShizuCallRecorder and does not use its package or visual branding. Attribution and source links are visible in **Settings → About / Impostazioni → Informazioni**.

## RivoPhoneApp

- Project: https://github.com/user-grinch/RivoPhoneApp
- Baseline: `0876b3b5ed344fb19c3dd4184ae79898a41f6c09`, app version 2.1.
- Copyright: the respective Rivo authors and contributors; original history and notices are retained.
- License: GNU General Public License version 3, as supplied in the upstream LICENSE.
- Complete original text: [LICENSE](LICENSE), also bundled as [Rivo.txt](app/src/main/assets/licenses/Rivo.txt).
- Modified source: https://github.com/stivy73/RivoPhoneApp/tree/codex/shizu-recorder-it

## ShizuCallRecorder

- Project: https://github.com/kitsumed/ShizuCallRecorder
- Baseline: **1.3.3 (versionCode 19)**, tag `v1.3.3`.
- Exact commit: `dd940fe2caa8aa1b4c7143ad5123c9923b343abd`.
- Copyright (C) 2026-present kitsumed (Med).
- License: **GNU GPLv3 or later, with additional Section 7 terms**. Read the [complete unchanged license, including all additional terms](app/src/main/assets/licenses/ShizuCallRecorder.txt). No warranty.
- Additional terms concern trademark protection, app-store publicity, clear distinction of forks, and visible attribution/linking. They are preserved, not replaced by a generic GPL summary.
- This software is a fork of ShizuCallRecorder's recording components, available at https://github.com/kitsumed/ShizuCallRecorder . The app prominently identifies its modified/fork status and links there from About.
- Derived files retain upstream copyright/license headers and identify the modification date and pinned commit. Their mapping and changes are documented in [RECORDER_ARCHITECTURE.md](docs/RECORDER_ARCHITECTURE.md).
- No code from a later Shizu main revision was used. The full Shizu UI, call detector and application package are not included.

## scrcpy

- Project: https://github.com/Genymobile/scrcpy
- Version/tag: **4.0 / v4.0**.
- Source tree: https://github.com/Genymobile/scrcpy/tree/v4.0
- Binary source: https://github.com/Genymobile/scrcpy/releases/download/v4.0/scrcpy-server-v4.0
- SHA256: `84924bd564a1eb6089c872c7521f968058977f91f5ff02514a8c74aff3210f3a`.
- Copyright (C) 2018 Genymobile; Copyright (C) 2018–2026 Romain Vimont, as stated in the supplied license.
- License: Apache License 2.0; [complete original text](app/src/main/assets/licenses/scrcpy.txt), included in the APK. No separate top-level NOTICE exists in the v4.0 source tree (checked).
- The unchanged server binary is committed at `app/src/main/assets/scrcpy-server`; normal builds require no server download. `scripts/fetch-scrcpy.sh` can restore it from the pinned URL and rejects any checksum mismatch before replacing the asset.
- Gradle `verifyScrcpyServer` runs before every build. The application verifies extraction and the elevated shell verifies the file again immediately before launch. Integrity checks remain enabled.

## Distribution and source

The APK is a debug test build of **Rivo Personal**, package `it.stivy.rivo.personal.debug`. Its corresponding source, build scripts, dependency declarations, exact baseline mapping and license texts are available in this repository. The GitHub Actions artifact includes `SOURCE_COMMIT.txt` and `SHA256SUMS.txt`; retrieve the exact source with `git checkout <SOURCE_COMMIT>`.

The combined application is distributed under the supplied GPL terms, retaining the additional terms applicable to the Shizu-derived components and the Apache notices applicable to scrcpy. No upstream trademark endorsement, store publication or warranty is asserted. Other original Rivo dependencies and their versions remain declared in Gradle.

## Optional Cloud OAuth distribution

The `cloud` variant (ad-free) and `play` variant use Google Play services Auth 22.0.0
under the Google APIs terms: https://developers.google.com/terms . This proprietary
SDK is excluded from `foss`. OAuth follows https://developer.android.com/identity/authorization .
WorkManager 2.10.1 (AndroidX, Apache-2.0) schedules backups; OkHttp 4.12.0
(Square, Apache-2.0, https://github.com/square/okhttp) performs cancellable Drive requests.
