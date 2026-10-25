**English** · [Español](README.es.md)

# Mihon extensions: Google Drive and Nyaa

Two extensions for [Mihon](https://mihon.app), published in the same extension repository:

- **Google Drive**: opens folders shared by link and finds manga in all their subfolders.
- **Nyaa (Literature)**: searches the Literature category of nyaa.si and reads torrents with a
  built-in torrent client.

Both read **CBZ** (and ZIP) and **PDF** files; Nyaa also reads folders of loose images.
Every text can be shown in English or Spanish (setting **Language** in each source; by default
it follows the phone's language).

## Install

**One tap:** open <https://kirougc.github.io/Mihon-GDrive-Extension/> on your phone and choose
*Open in Mihon*.

**Or by hand:** in Mihon go to **More → Settings → Browse → Extension repos → Add** and paste:

```
https://raw.githubusercontent.com/KirouGC/Mihon-GDrive-Extension/repo/index.min.json
```

Then install the extensions from **Browse → Extensions** and trust them when Mihon asks.

## Google Drive

| In Drive | In Mihon |
| --- | --- |
| A folder with CBZ/PDF files (`Berserk/Vol 01.cbz`, `Vol 02.cbz`…) | One manga; each file is a chapter |
| A loose CBZ/PDF in the main folder, next to other folders | A one-chapter manga |
| The main folder only has files (no subfolders) | A single manga with all those chapters |

1. Open **Browse → Sources → Google Drive → ⚙️** and paste your folder link
   (`https://drive.google.com/drive/folders/...`). You can add several, one per line.
2. Open the source. The first time it scans every folder; the result is saved and rescanned every
   24 hours (configurable) or when you tick the *Rescan* filter.

Subfolders are scanned up to 15 levels deep. The cover is an image called `cover`, `folder`,
`poster` or `portada` if the folder has one, otherwise the first image, otherwise the first page
of the first PDF. When a chapter is opened the whole file is downloaded to the phone's cache;
PDFs are rendered page by page on the phone.

**API key (recommended).** Without a key the extension reads each folder's public view, which may
not list every file in very large folders. With a free Drive API key the listing is complete:
create a project at <https://console.cloud.google.com/>, enable **Google Drive API**, create an
**API key** under *Credentials* and paste it in the extension settings.

## Nyaa (Literature)

- Searches only Literature, with filters for category (English-translated, non-English or raw),
  remakes/trusted and sort order. *Popular* is sorted by seeders.
- Each torrent is a manga. Chapters come from the `.torrent` file: every CBZ/PDF is a chapter, and
  so is every folder of loose images.
- Opening a manga already adds the torrent (without downloading anything) so it starts finding peers.
- CBZ files are read **streaming**: first only the end of the file (the ZIP index) is downloaded,
  then each page asks for the pieces that contain it at top priority while the rest of the chapter
  keeps downloading in order in the background. Image folders work the same way, image by image.
  PDFs are downloaded completely before opening.
- Only one torrent is active and only the files of open chapters are requested. After 5 minutes
  without reading, the torrent leaves the session and the client shuts down.
- While downloading, the phone also uploads to others (that is how torrents work). The upload can
  be limited in the settings (100 KB/s by default).
- If a download stalls for 3 minutes (configurable) it is cancelled with a message; this usually
  happens with old torrents without seeders.
- Downloaded chapters are kept in the cache (2 GB by default) and the oldest are deleted.

## Building

Every push to `main` builds both extensions with GitHub Actions and publishes the extension
repository to the `repo` branch. The APKs are also attached to each Actions run.

**Signing.** Mihon only updates an extension if every version is signed with the same key. The
workflow needs these repository secrets: `KEYSTORE_BASE64` (the `.jks` file in base64),
`KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`. To create a key:
`keytool -genkeypair -v -keystore signing.jks -alias mihon -keyalg RSA -keysize 2048 -validity 10000`
and then `base64 -w0 signing.jks`.

**One-tap page.** The *Add to Mihon* page is published with GitHub Pages: in **Settings → Pages**
set *Source* to **GitHub Actions** once.

**Locally** (JDK 17 and the Android SDK):

```
./gradlew :src:googledrive:assembleRelease :src:nyaa:assembleRelease
```

## Technical notes

- Built against extensions-lib 1.4 (version `1.4.x`), which both stable and newer Mihon load. The
  library interfaces are copied in `stubs/` (Apache 2.0) and are not packaged in the APK.
- `lib/archive` holds the CBZ/PDF reader and the translations helper shared by both extensions.
- Nyaa bundles libtorrent4j with its native libraries (arm64, arm and x86_64). Since Mihon loads
  extensions without a native library path, `NativeLoader` finds `libtorrent4j.so` (or extracts it
  from the APK) and hands it to libtorrent4j.
- Pages are served with internal URLs (`http://gdrive.local/...`, `http://nyaa.local/...`) that an
  OkHttp interceptor answers without going to the network.
