# Rivo Personal — integration plan

Prepared 2026-09-14, before implementation.

## Baselines and boundaries

- Rivo: `0876b3b5ed344fb19c3dd4184ae79898a41f6c09`, upstream `user-grinch/RivoPhoneApp`.
- Recording reference: ShizuCallRecorder **1.3.3 (19)**, tag `v1.3.3`, commit `dd940fe2caa8aa1b4c7143ad5123c9923b343abd`.
- No later Shizu main code is used. Preserve scrcpy **4.0**, SHA256 `84924bd564a1eb6089c872c7521f968058977f91f5ff02514a8c74aff3210f3a`.
- Work only in `stivy73/RivoPhoneApp`, branch `codex/shizu-recorder-it`. No upstream push, main merge, store publication or automatic release.
- No AGENTS.md found in workspace ancestors or either checkout. Initial checkout clean; GitHub identity verified as stivy73. No ADB device attached.

## Detected architecture

Rivo is a Compose dialer with CallService (InCallService) owning Android Telecom calls, CallScreen/CallScreenControls for manual actions, device-protected rivo_prefs managed by PreferenceManager, and CallRecorder as the single recording facade. CallRecordingService currently supplies a microphone foreground notification. CallRecordingsScreen scans existing public/app file directories and provides playback, contact association, ordering, sharing and deletion.

The existing controller/shizuku package already contains a shell user service, connection manager, local socket-to-pipe relay, scrcpy parser and muxer. IShellService is manually generated Java. Gradle has play/foss flavors, Android resources and generateLocaleConfig; Italian has only a small regional translation. Existing CI requires upstream signing secrets and publishes preview releases; Crowdin runs on a schedule.

## Differences and replacements

- Rivo tries voice-call/AAC then another source, then MediaRecorder. Remove these silent fallbacks; use the exact selected Shizu source/codec/bitrate and explicit errors.
- Rivo sets recording on pipe creation and only logs stream failures. Add a session-aware IDLE/STARTING/RECORDING/STOPPING/ERROR coordinator; enter RECORDING only after validated config and successful first media write.
- Port the v1.3.3 ScrcpyConfig, AudioSource, AudioCodec, Client, Muxer, ServerExtractor and shell pipeline with original headers and source provenance. Keep protocol, audio arguments, Opus CSD handling and muxer timing. Document all robustness adaptations.
- Preserve v1.3.3 shell-side SHA verification as well as application/build verification. Bundle the pinned official binary reproducibly.
- Replace manual Binder boilerplate with the necessary AIDL recording interface; adapt the v1.3.3 Shizuku binding lifecycle to Rivo.
- Adapt AudioRecordingEngine's start/release sequence to Rivo file storage and call ownership, without importing Shizu UI, call detection, overlays or its full foreground service.
- Keep Rivo recording library and filename association; select Ogg/Opus or M4A/AAC consistently and keep legacy formats readable.

## Reused components

Rivo dialer, contacts, history, keypad, call management, preferences, auto-record filters, recording library/player and file sharing remain the integration points. Shizuku remains an external prerequisite; ShizuCallRecorder does not need to be installed.

## Risks and safeguards

- Actual two-sided capture depends on OPPO routing/firmware and selected source. Hardware validation remains DA VERIFICARE until tested; do not guess the user's configuration.
- Blocking Binder/pipe I/O and late callbacks require bounded shutdown, descriptor ownership, session identity and idempotent start/stop.
- Stop during initialization, permission loss, empty/truncated stream and write failures must update UI and remove unsuccessful files without claiming success.
- Container finalization errors must surface; do not swallow muxer writes. Drain before closing resources.
- Side-by-side application ID must cover authorities and app-scoped actions, while retaining the Kotlin namespace.
- Preserve GPLv3 and Shizu Section 7 terms, notices, visible fork distinction and attribution; include scrcpy Apache license.
- Existing SDK/dependency/lint problems will be recorded separately from integration regressions, with no disabled checks.

## Operational plan

1. Finish targeted source/license inspection and create baseline provenance/third-party notices.
2. Port pinned recording components and generated AIDL, implement lifecycle adapter and deterministic state tests.
3. Wire manual/automatic call actions, foreground lifetime and failure UI; add settings for Shizuku, source, codec, bitrate and storage.
4. Configure Rivo Personal application identity and complete Italian resources including hardcoded visible text; add resource validation.
5. Add synthetic parser, failure, timing and cleanup fixtures; document OPPO checklist.
6. Run clean, testFossDebugUnitTest, lintFossDebug and assembleFossDebug. Fix introduced failures and record pre-existing issues.
7. Create secret-free FOSS CI with tests/lint/translation verification/APK artifact/checksum; guard upstream Crowdin and remove automatic releases.
8. Review diff and logical commits, push only the user fork branch, open PR against its main, follow CI and report actual artifact/checksum and remaining hardware checks.
