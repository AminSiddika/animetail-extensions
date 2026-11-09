# @JalebiBae Extensions — Handoff / Status

Last updated: **2026-11-09**

Self-hosted Aniyomi/Animetail extension repo. Signing, CI and the store index are wired up; adding a new site is just a new module + one index entry.

- Repo: `https://github.com/AminSiddika/animetail-extensions`
- Store index (paste this in Animetail):
  `https://raw.githubusercontent.com/AminSiddika/animetail-extensions/main/repo/index.min.json`
- Store name shown in app: `Animetail Extensions` (`repo/repo.json`)

---

## Current extensions

| Source (Browse name) | Module | Package | Base URL | Version |
|---|---|---|---|---|
| `@JalebiBae - Watch Hentai` | `extension/` | `...en.watchhentai` | `https://watchhentai.net` | 14.4 |
| `@JalebiBae - HentaiCity` | `extension-hentaicity/` | `...en.hentaicity` | `https://www.hentaicity.com` | 14.3 |
| `@JalebiBae - MuchoHentai` | `extension-muchohentai/` | `...en.muchohentai` | `https://muchohentai.com` | 14.3 |
| `@JalebiBae - Ohentai` | `extension-ohentai/` | `...en.ohentai` | `https://ohentai.org` | 14.3 |
| `@JalebiBae - HentaiPlay` | `extension-hentaiplay/` | `...en.hentaiplay` | `https://hentaiplay.net` | 14.3 |

All are NSFW (`nsfw = 1`), language `en`, extension API **14** (`versionName = "14.<code>"`).

Pinned source IDs (do **not** change these, or library entries orphan):

| Source | `override val id` |
|---|---|
| Watch Hentai | `135440030259600165` |
| HentaiCity | `6807862366543739110` |
| MuchoHentai | `564716588487801074` |
| Ohentai | `7247837649602913976` |
| HentaiPlay | `3436274373013547001` |

---

## Scraping notes per site

### Watch Hentai (series + episodes)
- Anime = `/series/<slug>/`, episode = `/videos/<slug>/`
- Popular `/trending/page/N/`, latest `/series/page/N/`, search `/?s=<q>&type=series`, genre `/genre/<slug>/page/N/`
- Episodes: `#episodes a.tv-ep-card-link`
- Player: `[data-primary-player-url]` → player page → JSON-LD `contentUrl` (fallback: base64/XOR `whJwSources`)
- MP4 on `hstorage.xyz` needs `Referer: https://watchhentai.net/` → extension runs a **local NanoHTTPD proxy** (`/v/<base64url>`) that forwards `Range`, Referer and UA. Keeps a "Direct" fallback video.

### HentaiCity (video per entry)
- Popular `/videos/straight/all-popular[-N].html`, latest `/videos/straight/all-recent[-N].html`
- Search `/search/video/<query>`
- Listing items `div.item`; links are `/click/<x>-<y>/video/<slug>.html` → strip `/click/x-y`
- Video: `meta[property=og:video:url]` (MP4) + `video source[src]` (HLS). MP4 works without Referer.

### MuchoHentai (video per entry, WordPress detube theme)
- Latest `/latest-hentai-posts/page/N`, search `/?s=<q>`
- Items `div.item.item-video`, `a.clip-link`, `h2.entry-title a`
- Video is HLS from a random edge host:
  `var servers = ['va01','va02']` + `var files = [{"file":"/wp-content/uploads/.../xx.m3u8"}]`
  → `https://<server>.edge.tmncdn.io<file>` (works without Referer)
- **Cloudflare protected** — relies on the host app's WebView/Cloudflare interceptor; first request may show a challenge.

### Ohentai (video per entry)
- Latest `index.php?p=N`, popular `index.php?sort=most_viewed&p=N`, search `search.php?k=<q>`
- Items `div.videobrick` → `a.interlink[href]` (`detail.php?vid=<base64>`), title `.videotitle a`
- Video: JW Player `"sources":[{"file":"https://videos.ohentai.org/...mp4?token=...&expires=..."}]`
- Token expires; always re-parse the detail page. No Referer needed.
- **Cloudflare protected.**

### HentaiPlay (video per entry, detube theme)
- Latest/popular `/hentai/episodes/page/N`, search `/?s=<q>`
- Items `div.item.item-post`, pagination `.wp-pagenavi .pages` ("Page X of Y")
- Video: `<video><source src="https://hentaiplanet.info/...mp4">`. No Referer needed.

### Not included: animeidhentai.com
- Player is `nhplayer.com`: PoW (`SHA-256` first byte 0) + randomized DOM parts + browser fingerprint, answered by `get-video-url-v2.php`.
- Direct HTTP replay returns `{"error":"Access denied","code":"V5"}`. Requires a real browser/JS runtime, so no reliable extension extraction.
- Details/search alone are scrapeable, but playback would fail.

---

## Repo / build facts

- Keystore: `signingkey.jks` (gitignored), alias `watchhentai`, password `changeit`
- Cert SHA-256 (in `repo/repo.json`):
  `d2874ca125feb5b59f32cfdb566ce4e901d0545925797fb8ff45ec4ae8e0b450`
- GitHub Actions secrets already set: `KEYSTORE_B64`, `ALIAS`, `KEY_STORE_PASSWORD`, `KEY_PASSWORD`
- CI (`build.yml`) builds **all** modules, copies every `*/build/outputs/apk/release/*.apk` into `repo/apk/`, commits with `[skip ci]`
- Local build: `./scripts/build.sh` (or `./gradlew assembleRelease`)
- Toolchain: AGP 8.2.1, Kotlin 1.8.22, Gradle 8.7, compileSdk 34, minSdk 23

---

## Adding a new site (checklist)

1. Create `extension-<site>/` by copying an existing module (e.g. `extension-hentaicity/`).
2. In `extension-<site>/build.gradle` change:
   - `namespace` and `applicationId` → `eu.kanade.tachiyomi.animeextension.<lang>.<site>`
   - `versionCode` = 1, `versionName` = `14.1`
   - `outputFileName` → `aniyomi-<lang>.<site>-v${variant.versionName}.apk`
3. In `AndroidManifest.xml` change `android:label` and
   `tachiyomi.animeextension.class` (`.YourSourceClass`), keep `nsfw=1`.
4. Put the Kotlin source in
   `src/main/java/eu/kanade/tachiyomi/animeextension/<lang>/<site>/YourSource.kt`.
   Set `name`, `baseUrl`, `lang`, `supportsLatest`, and **pin `override val id`** if
   renaming an existing source later.
5. Add `include ':extension-<site>'` to `settings.gradle`.
6. Add icons:
   - `src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png`
   - `repo/icon/eu.kanade.tachiyomi.animeextension.<lang>.<site>.png` (512px)
   - Prefer the site's real icon/logo; generate from a 1024 master with Lanczos.
7. Add the object to `repo/index.min.json`:
   `name`, `pkg`, `apk`, `lang`, `code`, `version`, `nsfw`, `sources[{name,lang,id,baseUrl}]`.
8. Commit + push. CI builds all modules and updates `repo/apk/`.
9. In Animetail: refresh the repo → update / install.

### Version bump rule
Every change to an extension needs `versionCode + 1` and `versionName "14.<code>"`, and the
same values in `repo/index.min.json`. Otherwise the app won't offer the update.

### ID rule
Source IDs are derived from `name + lang` (plus the host app's `versionId`). Renaming a source
without `override val id` orphans library entries. The pinned IDs above were computed with
Animetail's `versionId = 1`.

---

## Known caveats

- Cloudflare sites: MuchoHentai, Ohentai. The app's Cloudflare/WebView interceptor may prompt once.
- Site redesigns break selectors; each module keeps parsing in a few small functions.
- Video URLs with tokens/expiry (Ohentai) must be resolved at playback time, not cached.
- Ohentai/hentaicity icons came from official site assets; WatchHentai/HentaiPlay only ship 16–32px favicons, so their marks were redrawn at high resolution in the same colors.
- The repo is public (required for the store URL to work without auth).
