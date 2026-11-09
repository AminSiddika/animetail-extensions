# Animetail Extensions

An Aniyomi/Animetail-compatible extension repository ("library") with a self-hosted store, so the extensions can be installed and updated from inside Animetail/Mihon-style apps. Signing, CI and the repo index are already wired up.

## Sources in this repo

| Source | Module | Notes |
|---|---|---|
| Watch Hentai | `extension/` | series + episodes, local proxy for the MP4s |
| HentaiCity | `extension-hentaicity/` | video-per-entry, direct MP4 + HLS |
| MuchoHentai | `extension-muchohentai/` | video-per-entry, HLS via `tmncdn.io` (Cloudflare) |
| Ohentai | `extension-ohentai/` | video-per-entry, BunnyCDN token MP4s (Cloudflare) |
| HentaiPlay | `extension-hentaiplay/` | video-per-entry, direct MP4 |

Cloudflare-protected sites (`muchohentai.com`, `ohentai.org`) rely on the host app's Cloudflare/WebView interceptor; the first request may show a challenge.

Browse names are prefixed with `@JalebiBae - ...`. Each source pins its old numeric ID, so existing library entries survive the rename.

`animeidhentai.com` is **not** included: its player (`nhplayer.com`) runs a proof-of-work + browser-fingerprint gate whose `get-video-url-v2.php` rejects non-browser clients (`{"error":"Access denied","code":"V5"}`). Details/search could be scraped, but video playback would not work.

```
extension/                          Watch Hentai (series + episodes)
extension-hentaicity/               HentaiCity (video per entry)
extension-muchohentai/              MuchoHentai (video per entry)
extension-ohentai/                  Ohentai (video per entry)
extension-hentaiplay/               HentaiPlay (video per entry)
  src/main/AndroidManifest.xml      extension metadata (class, NSFW flag)
  src/main/java/.../                the scraper
  src/main/res/mipmap-*/            launcher icons
repo/
  index.min.json                    extension index the app reads
  repo.json                         store metadata + signing fingerprint
  apk/                              built APKs live here
  icon/                             store icons
.github/workflows/build.yml         CI build + auto-commit APK
scripts/build.sh                    local build helper
signingkey.jks                      signing key (gitignored — back it up!)
```

## What the scraper maps

| App concept | Site |
|---|---|
| Anime (series) | `/series/<slug>/` |
| Browse "Popular" | `/trending/page/N/` |
| Browse "Latest" | `/series/page/N/` (recently added) |
| Search | `/?s=<query>&type=series` |
| Genre filter | `/genre/<slug>/page/N/` |
| Episode | `/videos/<slug>/` inside `#episodes a.tv-ep-card-link` |
| Video | `[data-primary-player-url]` → player page → JSON-LD `contentUrl`, with base64/XOR fallback |

The MP4 files on `hstorage.xyz` are hotlink-protected and return **403** without a `Referer: https://watchhentai.net/` header, so the extension does two things:

1. Attaches that header to every `Video` (for the player's own HTTP stack).
2. Serves the MP4 through a **local NanoHTTPD proxy** started inside the extension (`http://127.0.0.1:<port>/v/<base64url>`), which replays every request upstream with the Referer, a browser UA and the original `Range` header. This is what makes seeking work and keeps playback independent of how the host app handles per-video headers. A "Direct" entry is still exposed as a fallback.

The player page also obfuscates its JW Player source. The fallback decoder mirrors the site's `whDecodeMediaUrl`: base64url → XOR each byte with `(13 + i % 17) & 0xFF` → reverse → base64 decode.

## Build

Requirements: JDK 17, Android SDK (compileSdk 34), Gradle 8.2+.

Local:

```bash
./scripts/build.sh
# or directly
gradle :extension:assembleRelease
```

Output: `extension/build/outputs/apk/release/aniyomi-en.watchhentai-v14.1.apk`

GitHub Actions: push to `main`. Add these repository secrets first:

| Secret | Value |
|---|---|
| `KEYSTORE_B64` | `base64 -w0 signingkey.jks` |
| `ALIAS` | `watchhentai` |
| `KEY_STORE_PASSWORD` | keystore password |
| `KEY_PASSWORD` | key password |

## Signing

The included `signingkey.jks` is a throwaway key generated for this scaffold:

- alias: `watchhentai`
- store/key password: `changeit`
- SHA-256 cert fingerprint: `d2874ca125feb5b59f32cfdb566ce4e901d0545925797fb8ff45ec4ae8e0b450`

It is listed in `.gitignore`. **Back it up.** If you lose it, users must uninstall the extension before they can install a differently-signed update.

If you generate a new key, update `repo.json`:

```bash
keytool -list -v -keystore signingkey.jks -alias watchhentai | grep SHA256
# strip colons, lowercase -> "signingKeyFingerprint"
```

Modern Mihon/Aniyomi builds check the APK signature against this fingerprint. A mismatch makes installs fail with a signature error.

## Publish the repo

1. Repo is at `https://github.com/AminSiddika/animetail-extensions` (`meta.website` already points there).
2. Build once so `repo/apk/aniyomi-en.watchhentai-v14.1.apk` exists (CI does this automatically).
3. In Animetail: **Settings → Browse → Extension repos → Add**, and paste:

```
https://raw.githubusercontent.com/AminSiddika/animetail-extensions/main/repo/index.min.json
```

(or the GitHub Pages equivalent). The app fetches `repo.json`, `index.min.json`, and APKs from `.../apk/<file>` relative to that URL.

## Releasing an update

1. Edit the source.
2. Bump `versionCode` / `versionName` in `extension/build.gradle` (e.g. `2` / `14.2`).
3. Update `repo/index.min.json`: `code`, `version`, and `apk` filename.
4. Rebuild and copy the APK into `repo/apk/`.
5. Keep the same package name and signing key.

## Adding another site

1. Duplicate `extension/` to `extension-<site>/` and change in its `build.gradle`:
   - `namespace` / `applicationId` → `eu.kanade.tachiyomi.animeextension.<lang>.<site>`
   - `versionCode` = 1, `versionName` = `14.1`
   - output filename string
2. Move the Kotlin class into `.../animeextension/<lang>/<site>/`.
3. Add `include ':extension-<site>'` to `settings.gradle`.
4. Add a new object to `repo/index.min.json` with that package, APK name, source name/id/baseUrl.
5. Push — CI builds every module and copies all APKs into `repo/apk/`.

Each extension must keep a unique `applicationId`; two sources in the same package require an `AnimeSourceFactory` instead.

## Notes / maintenance

- The site is WordPress + LiteSpeed with lazy-loaded images; the scraper reads `data-src` and falls back to `src`.
- Search returns a single page of series (the site does not paginate `?s=`), so `hasNextPage` is false there.
- Dubbed episodes are separate pages and appear as separate episodes ("Episode 1 (Dubbed)").
- If the site changes its theme/player, the selectors at the top of each parse function are the only places to fix.
