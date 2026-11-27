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
./gradlew :core:test
```

## License

Licensed under the **PolyForm Shield License 1.0.0** — see [LICENSE](LICENSE) and [NOTICE](NOTICE).
