# Redowansky Library — Android app

A native Android app for https://redowansky.bond (v0.1 was a WebView
wrapper; v0.2 is fully native). Catalog comes from the site's own JSON API
(GET /api/movies — the same feed the Aniyomi extension uses). Native poster
grid + search + Movies/Songs filters, native detail screen, native
VideoView player streaming the direct mp4, DownloadManager downloads to
the Downloads folder. Mondiad banner (320×50) at the bottom of the home
and detail screens. Black/green, matching the site.

- Package: `app.pepper.library` · version 0.2.0 (code 2) · minSdk 26, targetSdk 34
- No secrets in the APK (unlike the VPN app): the site is public, the
  Mondiad zone id is public by design.
- Downloads from the site are handed to the real browser (WebView can't save files).

## Mondiad pilot state

**Demo mode is ON, zone id is a placeholder (`ZONE_ID = "0"` in MainActivity.kt).**
Demo mode renders Mondiad test ads so the integration can be device-tested
before the app exists in Mondiad's system. No revenue in demo mode.

To go live:
1. In the Mondiad publisher panel: Applications → Add Application —
   Name `Redowansky Library`, not in a store, APK link = wherever this APK
   is hosted (site/R2), DAU `10`, Category **Mainstream**.
2. Create a **Banner** ad zone for the app (320×50 / Smartphone banner).
3. Set `DEMO_MODE = false` and `ZONE_ID = "<zone id>"` in
   `app/src/app/pepper/library/MainActivity.kt`, rebuild with `app/build.sh`,
   redistribute the APK (same keystore = installs as an update).
4. Push ads were deliberately **not** integrated: they need Firebase and
   handing Mondiad Firebase Admin credentials.

## Build (manual, no Gradle — Gradle does not work on this VM)

```
app/build.sh
```

Pipeline: aapt2 compile/link (app res + Mondiad SDK res merged) →
`gen_sdk_r.py` emits `com.mndiad.android.sdk.R` from the app R (the AAR's
classes reference their own library R) → kotlinc → d8 → zipalign → apksigner.

Dependencies (in `app/prebuilt/`, public Maven artifacts):
- Mondiad Android SDK 1.1.0 (`com.mndiad:android-sdk`, Maven Central) — classes.jar from the AAR; its res is in `sdk/mondiad-res/`
- AndroidX Core + Core-KTX 1.13.1 (Google Maven) — WindowInsetsCompat/ViewCompat used by the banner
- kotlinx-coroutines 1.8.1 (Maven Central)
- kotlin-stdlib from the local Kotlin distro

Signing: dedicated keystore `~/workspace/.secrets/library.keystore`,
password in `~/workspace/.secrets/library-ks.pass` (600). Keep both —
losing them means updates can't install over the top.
