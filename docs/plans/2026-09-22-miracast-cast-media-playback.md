# Miracast + Cast Media Playback Implementation Plan

> **For agentic workers:** Use the host's available task-by-task implementation workflow. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add standards-correct Miracast media reception foundations and functional Google TV Cast media playback while preserving Fire TV OS 6+ compatibility.

**Architecture:** Keep protocol discovery/session logic separated from playback. Miracast uses public Wi-Fi P2P APIs where available, a WFD RTSP session state machine, RTP/MPEG-TS ingestion, and the existing MediaCodec/AudioTrack rendering primitives. Google Cast remains Google-TV-only and delegates media playback to Media3 ExoPlayer through Cast Connect's MediaManager. A shared playback coordinator owns the active Surface and protocol lifecycle.

**Tech Stack:** Kotlin, Android SDK 25–35, WifiP2pManager, RTSP/RTP/MPEG-TS, MediaCodec, AudioTrack, Google Cast Connect, AndroidX Media3 1.11.1, JUnit/MockK/Robolectric, GitHub Actions.

## Global Constraints

- Fire TV flavor remains installable on Fire OS 6+ (Android 7.1 / API 25).
- Google TV flavor remains minSdk 29.
- compileSdk/targetSdk remain 35 until API 36 is explicitly validated.
- No unguarded references to APIs newer than API 25 in shared runtime paths.
- Google Play Services / Cast dependencies remain Google-TV-only.
- Miracast must degrade cleanly on devices where app-level WFD advertisement or Wi-Fi Direct is unavailable.
- Do not advertise codecs that the local device cannot decode.
- Unprotected Miracast playback is independent from HDCP-protected-content support.
- Fire TV must continue to build without Google Play Services.

---

### Task 1: SDK-safe receiver capability and permission layer

**Files:**
- Create: `app/src/main/kotlin/com/phairplay/miracast/WifiDirectCompat.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/kotlin/com/phairplay/miracast/MiracastReceiver.kt`
- Test: `app/src/test/kotlin/com/phairplay/miracast/WifiDirectCompatTest.kt`

**Interfaces:**
- Produces: `WifiDirectCompat.requiredRuntimePermissions(apiLevel)`, `WifiDirectCompat.hasRequiredPermission(context)`
- Produces: explicit receiver capability result instead of assuming WFD availability

- [ ] Add tests for API 25–32 location permission behavior and API 33+ NEARBY_WIFI_DEVICES behavior.
- [ ] Guard every newer Wi-Fi API call by SDK level.
- [ ] Limit legacy location permissions to Android 12L and below in the manifest while preserving Fire OS 6 compatibility.
- [ ] Keep target/compile SDK unchanged and verify both flavors compile.

### Task 2: Standards-oriented WFD RTSP session model

**Files:**
- Create: `app/src/main/kotlin/com/phairplay/miracast/WfdSession.kt`
- Modify: `app/src/main/kotlin/com/phairplay/miracast/MiracastReceiver.kt`
- Test: `app/src/test/kotlin/com/phairplay/miracast/WfdSessionTest.kt`

**Interfaces:**
- Consumes: RTSP requests from `RtspRequestReader`
- Produces: negotiated source presentation URL, transport mode, RTP ports, session state, outbound SETUP/PLAY requests

- [ ] Parse M3/M4 WFD parameters and `wfd_trigger_method`.
- [ ] Allocate non-zero media ports before advertising `wfd_client_rtp_ports`.
- [ ] Model OPTIONS → capability exchange → SETUP trigger → sink-originated SETUP/PLAY → streaming → teardown.
- [ ] Reject malformed transitions deterministically and preserve CSeq/session handling.

### Task 3: Miracast RTP/MPEG-TS media ingress

**Files:**
- Create: `app/src/main/kotlin/com/phairplay/miracast/RtpPacket.kt`
- Create: `app/src/main/kotlin/com/phairplay/miracast/MpegTsDemuxer.kt`
- Create: `app/src/main/kotlin/com/phairplay/miracast/MiracastMediaReceiver.kt`
- Test: `app/src/test/kotlin/com/phairplay/miracast/RtpPacketTest.kt`
- Test: `app/src/test/kotlin/com/phairplay/miracast/MpegTsDemuxerTest.kt`

**Interfaces:**
- Consumes: RTP/MP2T datagrams
- Produces: H.264 access-unit bytes and LPCM/AAC elementary payloads with PTS

- [ ] Parse RTP v2 headers, sequence number, timestamp, marker, CSRC/extension/padding.
- [ ] Validate MPEG-TS 188-byte framing and continuity.
- [ ] Parse PAT/PMT and PES boundaries and surface elementary-stream packets with PTS.
- [ ] Drop malformed/lost data without crashing and wait for the next recoverable boundary.

### Task 4: Miracast playback + shared ownership

**Files:**
- Create: `app/src/main/kotlin/com/phairplay/media/PlaybackCoordinator.kt`
- Create: `app/src/main/kotlin/com/phairplay/miracast/MiracastPlayback.kt`
- Modify: `app/src/main/kotlin/com/phairplay/service/PhairPlayService.kt`
- Modify: `app/src/main/kotlin/com/phairplay/MainActivity.kt`
- Test: `app/src/test/kotlin/com/phairplay/media/PlaybackCoordinatorTest.kt`

**Interfaces:**
- Produces: exclusive protocol playback lease and protocol-neutral active playback state
- Consumes: H.264/LPCM/AAC samples from Miracast media ingress

- [ ] Ensure AirPlay/Miracast/Cast cannot simultaneously own the Surface/audio path.
- [ ] Reuse MediaCodec H.264 decode where compatible and add PTS-aware render timing.
- [ ] Add LPCM playback and codec-gated AAC decode.
- [ ] Propagate Miracast CONNECTED/streaming/error state to UI and notifications.

### Task 5: Google TV Cast Media3 playback

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `app/src/googletv/kotlin/com/phairplay/cast/CastMediaController.kt`
- Modify: `app/src/googletv/kotlin/com/phairplay/cast/CastReceiver.kt`
- Modify: `app/src/main/kotlin/com/phairplay/MainActivity.kt`
- Test: `app/src/test/kotlin/com/phairplay/cast/CastReceiverTest.kt`

**Interfaces:**
- Consumes: Cast Connect LOAD intents / MediaLoadRequestData
- Produces: ExoPlayer playback, MediaSession state, MediaManager status updates

- [ ] Add Google-TV-only Media3 ExoPlayer/HLS/DASH dependencies at stable 1.11.1.
- [ ] Register MediaLoadCommandCallback before forwarding load intents.
- [ ] Route launch/load intents from Activity creation and `onNewIntent`.
- [ ] Implement load/play/pause/seek/stop/status/error propagation.
- [ ] Reject unsupported or missing content URLs with Cast media errors.
- [ ] Keep Fire TV flavor free of Cast/Media3 receiver classes.

### Task 6: Compatibility verification and documentation

**Files:**
- Modify: `docs/spec/PROJECT_PLAN.md`
- Modify: `docs/spec/REQUIREMENTS.md`
- Modify: `.github/workflows/ci.yml`

**Interfaces:**
- Produces: explicit API-level compatibility matrix and CI checks for both flavors

- [ ] Run JVM protocol tests.
- [ ] Lint and assemble Google TV debug.
- [ ] Lint and assemble Fire TV debug with minSdk 25.
- [ ] Verify no Google Play Services classes enter the Fire TV variant.
- [ ] Document protected-content/HDCP as a separate capability from unprotected Miracast playback.
- [ ] Document Cast media playback separately from generic system screen mirroring.
