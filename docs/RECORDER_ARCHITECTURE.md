# Rivo Personal recording integration

## Fixed source baseline

Rivo `0876b3b5ed344fb19c3dd4184ae79898a41f6c09` hosts the recorder. Every Shizu-derived component was taken from `v1.3.3`, commit `dd940fe2caa8aa1b4c7143ad5123c9923b343abd`, version 1.3.3 (19). Shizu main was not mixed in. scrcpy-server remains 4.0 with SHA256 `84924bd564a1eb6089c872c7521f968058977f91f5ff02514a8c74aff3210f3a`.

Paths below are relative to each project's `app/src/main/java`. Shizu uses prefix `com/kitsumed/shizucallrecorder`; Rivo uses `com/grinch/rivo4`.

| Pinned Shizu source | Rivo destination | Adaptation |
| --- | --- | --- |
| `integrations/scrcpy/ScrcpyConfig.kt` | `controller/shizuku/ScrcpyConfig.kt` | Namespace/BuildConfig only; same launch parameters, socket IDs, 48 kHz/2 channels |
| `integrations/scrcpy/ScrcpyAudioSource.kt` | `controller/shizuku/ScrcpyAudioSource.kt` | Original supported source list/API requirements and resource references |
| `integrations/scrcpy/ScrcpyAudioCodec.kt` | `controller/shizuku/ScrcpyAudioCodec.kt` | Original codecs, containers, MIME, defaults and FourCC |
| `integrations/scrcpy/ServerExtractor.kt` | `controller/shizuku/ServerExtractor.kt` | Pinned asset extraction/hash; Rivo namespace/log adapter |
| `integrations/scrcpy/ScrcpyClient.kt` | `controller/shizuku/ScrcpyClient.kt` | Original framing; injectable stream, strict codec/config/size/session checks and propagated EOF/errors |
| `integrations/scrcpy/ScrcpyAudioMuxer.kt` | `controller/shizuku/ScrcpyAudioMuxer.kt` | Original CSD conversion and wall-clock timing; synchronized write/finalization with failures propagated |
| `services/shell/ShellAudioPipeline.kt` | `controller/shizuku/ShellAudioPipeline.kt` | Original socket/pipe/process relay; synchronized ownership, bounded process exit, late-frame drain, no relay self-join |
| `services/shell/ShellService.kt`, AIDL | `controller/shizuku/ShellService.kt`, generated AIDL | Recording-only interface; removed unrelated commands, retained recording transaction IDs |
| `integrations/shizuku/ShizukuConnectionManager.kt` | `controller/shizuku/ShizukuConnectionManager.kt` | Cancellable Rivo binding, death/permission checks, explicit authorization, late-callback rejection |
| `services/recording/AudioRecordingEngine.kt` | `controller/recording/AudioRecordingEngine.kt` | Adapted orchestration/release to Rivo storage, foreground service and first-media confirmation |
| `services/recording/RecordingForegroundService.kt`, `RecordingServiceState.kt` | Rivo `CallRecordingService` and `RecordingCoordinator` | Behavior reviewed; full Shizu service/call-detection/UI deliberately not imported |
| `data/AppPreferences.kt` | Rivo `rivo_prefs`/settings | Only source, codec and bitrate defaults/options carried over |

## One owner and one audio path

`CallService` (Android InCallService) continues to own real calls, contact lookup and automatic-recording filters. `CallScreen` issues manual commands. Both use the **single** `CallRecorder` facade → `RecordingCoordinator` → `AudioRecordingEngine` → Shizuku shell service → scrcpy app_process → abstract LocalSocket → kernel pipe → ScrcpyClient → ScrcpyAudioMuxer → Rivo file.

No MediaRecorder recorder or automatic source/codec/microphone fallback remains. Simulated calls do not have a Telecom voice-call stream; their recording button is unavailable and they cannot stop a real call's recording. No Shizu app installation, alternate dialer or duplicate call detector is needed.

## State and lifetime

`IDLE → STARTING → RECORDING → STOPPING → IDLE`, or `STOPPING → ERROR` after an actual failure. A session has a unique monotonically increasing token. Duplicate commands are ignored, and callbacks from old tokens cannot change a new session. Starting creates no successful recording state merely because Binder or a pipe is available.

`RECORDING` requires a matching FourCC, valid framing, codec configuration accepted by the muxer, and the first non-configuration packet successfully written. The duration timer starts then, uses a monotonic clock, and stops/resets on cleanup. This proves a functioning encoded stream, **not that both speakers are audible**; that requires the OPPO listening tests.

Foreground initialization is acknowledged before capture. The default dialer's phoneCall service type is used, not an app microphone recorder. Shizuku authorization is requested explicitly in recording settings. Live permission/death checks stop the pipeline on revocation. Ordinary microphone permission is not presented as a recorder prerequisite.

Limits: foreground acknowledgement 5 s; Shizuku connection 10 s; shell start transaction 8 s; total first-audio startup 20 s. During stop: shell request (caller wait up to 6 s), shell process graceful/forced exit, relay drain; then app drain up to 2 s, client/pipe close, final join up to 1 s, muxer finalization, output descriptor close, shell unbind and foreground teardown. Binder waits are bounded independently because Binder transactions ignore coroutine cancellation; late returned descriptors are disposed. A new automatic call can wait for the preceding session's bounded cleanup.

## Audio parameters preserved

Default **voice-call / Opus / 16000 bps**; AAC default **32000 bps**. These are release defaults, not a claim about the user's current OPPO configuration. All v1.3.3 source enums are retained with their API checks; Opus and AAC are selectable and bitrate choices include all baseline presets. Experimental/microphone/single-direction sources remain explicitly selectable; they are never fallback substitutions.

Opus uses an Ogg container, `.ogg`, sharing MIME `audio/ogg`. AAC uses an MPEG-4 container, `.m4a`, sharing MIME `audio/mp4`. Codec input MIME stays the MediaFormat MIME from Shizu. Opus CSD handling, monotonic timestamp correction and the baseline's 400 ms gap / 25 ms slack compression are preserved. Consequently saved media duration can be shorter than elapsed time for discontinuous sources, as in v1.3.3.

## Storage and Rivo library

Rivo filenames keep the contact/number label and timestamp; path separators are sanitized. Persistent folder name **Rivo Recordings** never depends on locale. Settings offer the original automatic folder selection, app external, app internal and public Recordings. An explicitly selected unavailable folder produces an error. Existing public/OEM/Rivo folders remain scanned subject to actual Android storage access; a separate package cannot access original Rivo's private sandbox.

New files are hidden `.pending` files while recording. Only a successfully finalized recording that passes the existing minimum-duration preference is renamed into the visible library. Failed/empty recordings are deleted; a rename failure preserves the recoverable pending file and reports an error. Existing files are not migrated or renamed by localization. Sorting, contact association, duration, playback, share and delete remain in Rivo. Player preparation errors are visible and descriptors/retrievers are released on failure.

## Diagnostics and limitations

Settings display Shizuku availability/authorization, state/error, server version, selected source/codec/bitrate and resolved folder. Recorder logs include transitions, connection, configuration, EOF and error classes; no audio, full contact/phone label or output file path is logged by the new recorder. scrcpy stdout is drained without echoing raw lines.

Unit tests use synthetic encoded-stream framing and fake pipeline failure boundaries; they do not prove Android MediaMuxer, Binder, ColorOS power management or acoustic quality. See [OPPO_TEST_PLAN.md](OPPO_TEST_PLAN.md). There was no connected ADB device during implementation. No installation or call was performed, and the user's working Shizu preferences were not read or guessed.
