# Hermes for Android

Jon's personal AI, the mind behind his own JARVIS, running on his Pixel. It started from the
ideas in Nous Research's Hermes Agent (memory, self-written skills, subagents, scheduled bots,
messaging gateways) and replaces the earlier Wisp phone agent.

## What it does

- **One mind, many ways in:** the home screen, voice, Telegram, Discord, scheduled bots and Jon's
  other apps all share the same memory, skills, personas and connections.
- **Knows Jon:** a core profile it always carries, plus long-term memories pulled in by relevance.
  Seeded on first launch with general context; everything is editable in the Hub.
- **Learns:** writes reusable procedures down as skills and loads them when they apply.
- **Bots:** subagents for parallel work, named personas with their own role, tools and voice, and
  scheduled bots that run on their own and report back.
- **Reaches the world:** phone control (direct tools plus screen control through accessibility),
  Home Assistant, and any MCP server over HTTP.
- **Voice:** hands-free conversation with the Fibonacci sphere showing what it's doing.
- **Cost control:** a cheap model for everyday requests and the main model only when a request looks
  hard, prompt caching, compacted history, and a monthly budget with a live estimate.
- **Doesn't hang:** step and time limits, per-tool timeouts, loop detection, retries with backoff,
  and an error log in Settings.

## Install

1. Open the latest release on the phone: https://github.com/Jondude1/Hermes-for-Android-/releases/latest
2. Install `Hermes-v1.0.N.apk`. From then on Hermes updates itself.
3. Settings: paste the Anthropic API key, then set it as home screen and assistant, and grant the
   permissions it lists. Screen control needs "Allow restricted settings" in App info first.

## Ask Hermes (for Jon's other apps)

Other apps send a broadcast; Hermes answers with another broadcast.

```kotlin
context.sendBroadcast(
    Intent("com.jondude1.hermes.ASK").setPackage("com.jondude1.hermes")
        .putExtra("key", ASK_KEY)                 // from Hermes Settings, Connections
        .putExtra("prompt", "Turn the office lights to 40%")
        .putExtra("source", "DeskGlass")
        .putExtra("request_id", "42")
        .putExtra("reply_package", context.packageName)
        .putExtra("reply_action", "com.jondude1.deskglass.HERMES_REPLY")
)
// Reply intent extras: request_id (String), ok (Boolean), reply (String)
```

Register a receiver for `reply_action` in the calling app (exported, since Hermes is a different app).

## Build

Every push to `main` builds a signed APK in GitHub Actions and publishes it as the latest release.
The signing key in `keystore/` is committed on purpose: every build must be signed with the same
permanent key for updates to install.
