# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

An IntelliJ Platform plugin (Java 17, Gradle, `org.jetbrains.intellij` 1.17.3) that uploads the Groovy file in the
active editor to a Hubitat hub as an app or device driver. It talks to the hub's *web IDE* HTTP endpoints directly —
there is no official API — so requests imitate a browser/HttpClient (cookies, `Referer`, `Origin`, `User-Agent`).

## Commands

```bash
./gradlew build              # compile + build plugin jar into build/libs/
./gradlew runIde             # launch a sandbox IntelliJ (build/idea-sandbox) with the plugin installed
./gradlew buildPlugin        # distributable zip in build/distributions/
```

`scripts/build.sh` and `scripts/run.sh` are thin wrappers (they `rm build/libs/*.jar` first and call `gradlew`, not
`./gradlew` — assumes a `gradlew` on PATH).

There are **no tests** in this repo (`src/test` does not exist) and no lint task beyond `javac`.

### Debugging

Runtime logging goes through slf4j (provided by the IntelliJ platform). To see it, enable trace logging in the target
IDE via *Help → Diagnostic Tools → Debug → Log Settings…* with `com.jpage4500.hubitat:trace:separate`, which routes
output to its own `idea_com.jpage4500.hubitat.log`. `scripts/logs.sh` tails that file (paths are hardcoded for this
machine's IDE version).

### Versioning / release

`version` is generated in `build.gradle` from the build timestamp (`yy.M.d-Hmm`) — do not hand-edit it. Pushing to
`main` triggers `.github/workflows/release.yml`, which builds, reads the version from `gradlew properties`, and
creates a GitHub release with `build/libs/hubitat-intellij-plugin-*.jar`.

`sinceBuild`/`untilBuild` (233.0–299.*) and the target IDE version live in `build.gradle`'s `intellij`/`patchPluginXml`
blocks, not in `plugin.xml`.

## Architecture

Single action, no tool window, no services beyond persisted settings. `plugin.xml` registers exactly two things:
`HubitatAction` (added to `MainToolbarRight`) and `HubitatSettingsState` as an application service.

`HubitatAction.actionPerformed` is the whole flow:

1. **Detect** — reads the selected editor's `Document` text. `parseDefinition()` locates `definition` + `(` and
   returns the balanced paren contents (quote-aware, so parens inside strings don't fool it); no definition block
   means it's not a Hubitat file. **Every lookup is scoped**, and that scoping is load-bearing:
   - `name`/`namespace` are read **only from inside the `definition(...)` block**. Scanning the whole file finds
     unrelated Groovy map keys — `[name: "Netflix"]` in a `@Field` map above `metadata` used to win, so the plugin
     looked up the wrong driver and installed a duplicate.
   - `hub`/`type`/`id` are read **only from comment lines** (`//`, `*`, `/*`), preferring the
     `// hubitat start … // hubitat end` block when present, so code like `[id: 5]` can't be mistaken for a hub id.
   - `parseValue(scope, key)` does the actual match via a cached regex, `(?<![A-Za-z0-9_])key\s*:` plus a quoted or
     bare value. The lookbehind is what stops `name` matching `fileName:`; single and double quotes both work.
2. **Classify app vs. driver** — cascade, first hit wins: `type:` comment → regex heuristics in `isApp()`
   (`capability "…"` / `metadata {` ⇒ driver; `page(` / `dynamicPage(` / `section(` ⇒ app) → filename containing
   "driver"/"app" (driver checked first — "appliance-driver.groovy" contains both) → the path→isApp map cached in
   settings → whatever the user picks in the dialog. These patterns must stay anchored: a bare `page` substring
   matches the namespace `jpage4500`, which appears in every one of these files.
3. **Prompt** — `HubitatInstallDialog` (hand-built Swing `GridBagLayout`, not a `.form`) collects IP + type and doubles
   as the progress/results log via `addResult()`. It does *not* close on OK: `doOKAction` delegates to an
   `InstallListener`; returning `true` disables the OK button and the background work streams status into the results
   area until `done()` flips the button to "Close".
4. **Upload** — spawned on a raw `new Thread(...)` because HTTP on the EDT would freeze the IDE. Everything after the
   dialog callback runs off-EDT, so `addResult`/`done`/`failed` hop back via `invokeLater` with
   `ModalityState.any()` — plain `invokeLater` would not run while the modal dialog is up. Anything else touching
   Swing from that thread must do the same (see `showWarning`).

### Hub endpoints (all plain `http://<hubIp>`, no auth beyond a session cookie)

| Purpose | Request |
| --- | --- |
| List existing drivers / apps | `GET /hub2/userDeviceTypes`, `GET /hub2/userAppTypes` → `List<UserDeviceType>` |
| Update existing | `POST /device/ideUpdate?id=N` or `/app/ideUpdate?id=N`, body = raw source, `text/plain; charset=ISO-8859-1` |
| Install new | `GET /driver/create` (to obtain `HUBSESSION` cookie + valid Referer), then `POST /driver/saveOrUpdateJson` or `/app/saveOrUpdateJson` with JSON `InstallRequest{source, version}` |

Note the endpoint asymmetry: update uses `/device/…` while create uses `/driver/…`. Both return
`InstallResult{success, message}`. Sample captured request/response pairs are in `docs/doc_install.txt` and
`docs/doc_update.txt` — the canonical reference when an endpoint's shape is in question.

If no `id:` is in the source, `lookupAppId()` fetches the list and matches on exact `name` + `namespace`; a miss falls
through to `installApp()` rather than erroring, which is what makes "just hit Install on a brand-new file" work.

### Supporting pieces

- `NetworkHelper` — `HttpURLConnection` wrapper with its own flat cookie store (`HUBSESSION` must survive the
  `create` → `saveOrUpdateJson` pair), gzip/deflate decoding, 5s timeouts, redirects disabled (a 302 with no body is
  a normal outcome). Never throws: errors come back as `HttpResponse{status:-1, body:message}`, and `body` is null
  when there was no content — check before printing it. The cookie store has no notion of host, so a fresh instance
  is created per install (`DriverDetails.networkHelper`); reusing one across hubs would replay the wrong session.
  Note `getHeaders()` sets `Host: <ip>:8080`, which `HttpURLConnection` silently drops as a restricted header.
- `HubitatSettingsState` — `PersistentStateComponent` in `HubitatPlugin.xml`; stores the last hub IP and a
  `filePath → isApp` map so a file's type is remembered.
- `GsonHelper` — lazily built singleton `Gson` with `LOWER_CASE_WITH_UNDERSCORES` naming and an
  `@ExcludeFromSerialization` exclusion strategy (used to keep the whole file body out of debug log lines).
- `TextUtils` — null-safe string helpers; prefer these over raw `String` methods to match surrounding code.

## Conventions

- New user-visible progress goes through `dialog.addResult(...)`, using the existing 🔹/✅/❌ prefixes.
- Keep all network calls off the EDT.
- No `null`-returning surprises: helpers return empty collections / sentinel values instead.
