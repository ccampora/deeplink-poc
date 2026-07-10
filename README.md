# DeepLink PoC — Android Inter-App File Sharing via FileProvider

## What this PoC proves

Two completely independent Android apps on the same device can exchange a
**large binary payload** without embedding it in a deep link URL, without a
backend, and without shared external storage, using only:

* Custom-scheme deep links to pass **control parameters** (requestId, callback URL)
* `FileProvider` (androidx) to expose a private file as a `content://` URI
* `ContentResolver.openInputStream()` to stream the file into the receiving app

---

## Why the payload is NOT passed inside the deep link

Android Intents have a practical size limit of **~1 MB** for the transaction
buffer (Binder transaction limit). Embedding a 5 MB payload in a URI query
string would cause a `TransactionTooLargeException` and crash the app.

The correct production pattern is:

```
sender generates file  →  FileProvider URI  →  deep-link carries only the URI
receiver opens URI     →  ContentResolver   →  streams the bytes
```

This mirrors how the Android system itself shares files between apps (share
sheets, email attachments, camera captures, etc.).

---

## Architecture

```
User
 │  taps "Request Large File From Sender"
 ▼
ReceiverApp (com.poc.receiver)
 │  Intent.ACTION_VIEW
 │  senderpoc://generate?requestId={uuid}&callback=receiverpoc%3A%2F%2Fcallback
 ▼
SenderApp (com.poc.sender)
 │  generates ~5 MB random JSON → cacheDir/payload_*.json
 │  FileProvider.getUriForFile()
 │  → content://com.poc.sender.fileprovider/cache/payload_*.json
 │  grantUriPermission("com.poc.receiver", contentUri, READ)
 │  Intent.ACTION_VIEW  (FLAG_GRANT_READ_URI_PERMISSION + ClipData)
 │  receiverpoc://callback?requestId={uuid}&fileUri={encodedContentUri}
 ▼
ReceiverApp (com.poc.receiver)  ← onNewIntent (singleTask)
 │  contentResolver.openInputStream(contentUri)
 │  reads bytes on IO coroutine
 ▼
UI: requestId | file URI | byte count | first 500 chars | success message
```

---

## Project structure

```
deeplink/
├── settings.gradle.kts          ← includes :app-receiver  :app-sender
├── build.gradle.kts             ← AGP 8.2.2 + Kotlin 1.9.22 (apply false)
├── gradle.properties
├── gradle/wrapper/
│   └── gradle-wrapper.properties
│
├── app-receiver/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml  ← intent-filter: receiverpoc://callback
│       ├── java/com/poc/receiver/
│       │   └── MainActivity.kt  ← Compose UI + deep-link handling
│       └── res/values/
│           ├── strings.xml
│           └── themes.xml
│
└── app-sender/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml  ← intent-filter: senderpoc://generate
        │                           + FileProvider declaration
        ├── java/com/poc/sender/
        │   └── MainActivity.kt  ← file generation + FileProvider + callback
        └── res/
            ├── values/
            │   ├── strings.xml
            │   └── themes.xml
            └── xml/
                └── file_paths.xml   ← FileProvider path config
```

---

## Prerequisites

| Tool | Minimum version |
|------|----------------|
| Android Studio | Hedgehog (2023.1.1) or later |
| Android SDK | API 34 (compile), API 24 (min) |
| Kotlin | 1.9.22 (set in root build.gradle.kts) |
| Gradle | 8.4 (set in gradle-wrapper.properties) |
| AGP | 8.2.2 |

> **Gradle wrapper:** The `gradle-wrapper.jar` binary is not included in this
> repository. Android Studio downloads it automatically when you open the
> project for the first time. Alternatively, if you have Gradle ≥ 8.4 installed
> globally, run `gradle wrapper` once in the project root.

---

## How to run on Android Emulator

1. **Open the project** in Android Studio.
   _File → Open → select the `deeplink/` folder._

2. **Sync Gradle** — Studio will prompt automatically.

3. **Create a Pixel emulator** (API 34 recommended):
   _Device Manager → Create Device → Pixel 6 → API 34_.

4. **Install ReceiverApp**:
   - In the top toolbar, select the `app-receiver` configuration.
   - Click ▶ Run.

5. **Install SenderApp**:
   - Switch to the `app-sender` configuration.
   - Click ▶ Run.
   - The app will show _"Ready — waiting for deep link request."_
   - You can minimize it; it will be launched automatically.

6. **Trigger the flow**:
   - Switch back to the `app-receiver` configuration.
   - The ReceiverApp is already installed; just bring it to the foreground
     (or re-run without rebuilding if you like).
   - Tap **"Request Large File From Sender"**.
   - SenderApp opens, generates the file (~2–4 seconds), then automatically
     switches back to ReceiverApp.
   - ReceiverApp displays the file size and a 500-character preview.

---

## How to run on a physical Android device

1. Enable **Developer Options** on the device:
   _Settings → About phone → tap "Build number" 7 times_.

2. Enable **USB Debugging**:
   _Settings → Developer options → USB debugging → On_.

3. Connect via USB; accept the "Allow USB debugging?" prompt on the device.

4. Follow steps 4–6 from the emulator instructions above.
   Android Studio will automatically detect the physical device.

---

## How to inspect logs in Logcat

Open **Logcat** in Android Studio (_View → Tool Windows → Logcat_) or run:

```bash
# Filter all PoC tags at once
adb logcat -s REQUEST_SENT:D CALLBACK_RECEIVED:D URI_DECODED:D \
           FILE_READ_STARTED:D FILE_READ_SUCCESS:D FILE_READ_ERROR:D \
           GENERATE_REQUEST_RECEIVED:D FILE_GENERATION_STARTED:D \
           FILE_GENERATION_SUCCESS:D CONTENT_URI_CREATED:D \
           CALLBACK_LAUNCHED:D CALLBACK_ERROR:D
```

### ReceiverApp log tags

| Tag | Meaning |
|-----|---------|
| `REQUEST_SENT` | Deep link built and Intent fired toward SenderApp |
| `CALLBACK_RECEIVED` | `receiverpoc://callback` intent received |
| `URI_DECODED` | `fileUri` query param extracted and parsed |
| `FILE_READ_STARTED` | `openInputStream` called |
| `FILE_READ_SUCCESS` | All bytes read; byte count logged |
| `FILE_READ_ERROR` | Exception or SecurityException during read |

### SenderApp log tags

| Tag | Meaning |
|-----|---------|
| `GENERATE_REQUEST_RECEIVED` | `senderpoc://generate` intent received |
| `FILE_GENERATION_STARTED` | Background coroutine started |
| `FILE_GENERATION_SUCCESS` | File written; name + size logged |
| `CONTENT_URI_CREATED` | `FileProvider.getUriForFile()` succeeded |
| `CALLBACK_LAUNCHED` | Callback intent fired toward ReceiverApp |
| `CALLBACK_ERROR` | Any exception in the generate-and-return flow |

---

## Troubleshooting

### App not opening when deep link is fired

* Confirm both apps are **installed** (check via `adb shell pm list packages | grep poc`).
* Verify the `intent-filter` in the target app's `AndroidManifest.xml` matches
  the exact scheme/host.
* Check that `android:exported="true"` is set on the activity — required for
  API 31+ when an intent-filter is present.

### Deep link not matched

```
adb shell am start -W -a android.intent.action.VIEW \
    -d "senderpoc://generate?requestId=test123&callback=receiverpoc%3A%2F%2Fcallback" \
    com.poc.sender
```

If this fails, the intent-filter is not registered correctly. Re-check
`AndroidManifest.xml` in `app-sender`.

### FileProvider permission denied / SecurityException

* Confirm the `android:authorities` in the `<provider>` tag exactly matches
  the string `"com.poc.sender.fileprovider"` (same in code and manifest).
* Confirm `android:grantUriPermissions="true"` is set on the `<provider>`.
* Confirm the file lives under a path covered by `file_paths.xml`
  (`<cache-path name="cache" path="." />`).
* Confirm `grantUriPermission("com.poc.receiver", contentUri, READ)` is called
  before `startActivity`.

### ReceiverApp cannot read the URI

* The permission grant is temporary. If ReceiverApp is **killed** between the
  grant and the read, the permission expires. Run the full flow end-to-end
  without killing ReceiverApp.
* Check `FILE_READ_ERROR` in Logcat for the full stack trace.
* Try increasing the Logcat buffer:
  `adb logcat -G 16M`

### ActivityNotFoundException (app not found)

`setPackage("com.poc.sender")` / `setPackage("com.poc.receiver")` makes the
intent explicit. If the target app is not installed you will see
`ActivityNotFoundException` in Logcat (`REQUEST_SENT` / `CALLBACK_ERROR` tags).
Install the missing app first.

### File too large / OOM on very old devices

The default ~5 MB file size is well within the typical device RAM budget.
If you hit OOM, reduce `recordCount` in `generateLargeJsonFile()` (e.g. 4 000).

---

## Key design decisions

| Decision | Rationale |
|----------|-----------|
| `launchMode="singleTask"` | Ensures a single instance per app; callback re-uses the existing activity via `onNewIntent` instead of stacking a new one. |
| `FileProvider` instead of external storage | No `READ_EXTERNAL_STORAGE` permission needed; follows scoped-storage best practices; works on API 29+. |
| `grantUriPermission()` + `ClipData` | Belt-and-suspenders: explicit grant covers the case where the FLAG alone does not propagate through a custom-scheme intent. |
| Background coroutine for I/O | File generation and reading happen on `Dispatchers.IO` to avoid ANR on the main thread. |
| URL-encoded `fileUri` in query param | `Uri.getQueryParameter()` automatically decodes it; no custom decoding needed in the receiver. |
