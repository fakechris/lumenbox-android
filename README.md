# lumenbox-android

Native Android client (Kotlin / Jetpack Compose) for **LumenBox** (`fakechris/lumenbox`, `docs/106` & `docs/110`).

Supports both **Personal mode** (one paired Mac over direct LAN/tailnet or untrusted E2EE relay using `Noise_NK` + `Noise_IK`) and **Organisation mode** (any number of enterprise servers using DingTalk / Feishu OAuth + PKCE, rotating hardware-backed Keystore device tokens, and automatic remote wipe) inside a single multi-installation app.

## Modules

| Module | Responsibility |
|---|---|
| `:core` | Pure Kotlin multi-transport core: `/api/v1` contract + `openapi-generator` (`kotlinx.serialization`) config, forward-compatible `.Other(rawKind)` decoders, RFC 7748 + `ChaCha20-Poly1305` `Noise_NK` / `Noise_IK` & `65,509`-byte AEAD chunk framing, multi-installation store + `401 access-removed` remote wipe, and two-phase Wake-Only push coordinator (immediate generic notification -> authoritative pull update). |
| `:app` | Android Jetpack Compose UI shell (`MainActivity`), Custom Tabs PKCE callback entry, and `LumenBoxMessagingService` background wake handler. |

## Verification

```bash
./gradlew :core:test                      # JVM unit tests (OpenJDK's crypto providers)
./gradlew :app:connectedDebugAndroidTest  # instrumented tests on a running emulator or device
```

`:core` runs on the JVM in unit tests but on Android's providers (Conscrypt) and Android's
`java.*` in the app, and the two differ: OpenJDK's `ChaCha20-Poly1305` name is unknown on API 28,
and JDK APIs such as `BigInteger.TWO` are absent below API 33. The instrumented tests in
`app/src/androidTest` (the crypto is in `:core`, a plain JVM module without an Android test source
set) run the shared AEAD against RFC 8439, the Noise NK / IK handshakes against the server's vectors
(`core/src/test/resources/relay-test-vectors.json`), and the offline cache through close and reopen.

To run them locally on a headless emulator (an AVD of your own; API 28 is `minSdk`):

```bash
sdkmanager "system-images;android-28;default;arm64-v8a"   # x86_64 on an Intel host
avdmanager create avd -n lbx-test-28 -k "system-images;android-28;default;arm64-v8a"
emulator -avd lbx-test-28 -no-window -no-audio -no-boot-anim -no-snapshot &
adb wait-for-device
./gradlew :app:connectedDebugAndroidTest
adb emu kill && avdmanager delete avd -n lbx-test-28
```

`./gradlew :core:test` first runs `:core:animalsnifferMain`, which checks `:core`'s bytecode
against the Android API 28 surface (the Gummy Bears signature, `com.toasttab.android:gummy-bears-api-28`)
and fails the build on any JDK call that `minSdk` does not have, such as `BigInteger.TWO` or
`URLDecoder.decode(String, Charset)`. If it fires, use the older overload; do not raise the check.

CI runs the same task on API 28 and API 33 emulators (`instrumented` job in
`.github/workflows/ci.yml`).

## License

Licensed under the **PolyForm Shield License 1.0.0** — see [LICENSE](LICENSE) and [NOTICE](NOTICE).
