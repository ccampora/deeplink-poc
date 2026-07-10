package com.poc.sender

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random
import java.util.UUID

// ---------------------------------------------------------------------------
// SenderApp — MainActivity
//
// Flow:
//   1. Receives senderpoc://generate?requestId=...&callback=... deep link
//   2. Generates a ~5 MB random JSON file in cacheDir (background thread)
//   3. Wraps the file with FileProvider → content:// URI
//   4. Grants ReceiverApp read permission via grantUriPermission() + ClipData
//   5. Launches receiverpoc://callback?fileUri=... to return to ReceiverApp
// ---------------------------------------------------------------------------

class MainActivity : ComponentActivity() {

    // ── Compose-observable state ──────────────────────────────────────────
    private val statusText             = mutableStateOf("Ready — waiting for deep link request.")
    private val lastRequestIdState     = mutableStateOf("")
    private val generatedFileNameState = mutableStateOf("")
    private val generatedFileSizeState = mutableLongStateOf(0L)
    private val contentUriTextState    = mutableStateOf("")

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent { SenderScreen() }
    }

    /** Called when this singleTask activity is re-used instead of re-created. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    // ── Intent routing ────────────────────────────────────────────────────

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "senderpoc" && data.host == "generate") {
            processGenerateRequest(data)
        }
    }

    // ── Request processing ────────────────────────────────────────────────

    private fun processGenerateRequest(uri: Uri) {
        Log.d(TAG_RECEIVE, "Generate request received: $uri")

        val requestId = uri.getQueryParameter("requestId")
        if (requestId.isNullOrBlank()) {
            Log.e(TAG_RECEIVE, "Missing requestId in deep link")
            statusText.value = "Error: Missing requestId in deep link"
            return
        }

        // Uri.getQueryParameter() automatically URL-decodes the value.
        val callbackUrl = uri.getQueryParameter("callback")
        if (callbackUrl.isNullOrBlank()) {
            Log.e(TAG_RECEIVE, "Missing callback parameter in deep link")
            statusText.value = "Error: Missing callback in deep link"
            return
        }

        Log.d(TAG_RECEIVE, "requestId=$requestId  callbackUrl=$callbackUrl")
        lastRequestIdState.value = requestId
        statusText.value = "Generating large file for requestId=${requestId.take(8)}…"

        lifecycleScope.launch {
            try {
                // ── 1. Generate file on IO thread ──────────────────────
                Log.d(TAG_GEN_START, "File generation started, requestId=$requestId")
                val file = withContext(Dispatchers.IO) {
                    generateLargeJsonFile(requestId)
                }
                Log.d(TAG_GEN_SUCCESS, "File generated: ${file.name}  size=${file.length()} bytes")

                generatedFileNameState.value     = file.name
                generatedFileSizeState.longValue = file.length()

                // ── 2. Create FileProvider content URI ─────────────────
                val contentUri = FileProvider.getUriForFile(
                    this@MainActivity,
                    FILE_PROVIDER_AUTHORITY,
                    file
                )
                Log.d(TAG_URI_CREATED, "Content URI: $contentUri")
                contentUriTextState.value = contentUri.toString()

                // ── 3. Grant explicit read permission to ReceiverApp ───
                //    This is the primary permission grant; ClipData below
                //    is a belt-and-suspenders approach for robustness.
                grantUriPermission(
                    "com.poc.receiver",
                    contentUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )

                // ── 4. Build callback deep link ────────────────────────
                val encodedUri      = URLEncoder.encode(contentUri.toString(), "UTF-8")
                val encodedFileName = URLEncoder.encode(file.name, "UTF-8")
                val callbackDeepLink = buildReceiverCallbackDeepLink(
                    callbackUrl,
                    requestId,
                    encodedUri,
                    encodedFileName
                )

                // ── 5. Launch ReceiverApp ──────────────────────────────
                Log.d(TAG_CALLBACK, "Launching callback: $callbackDeepLink")

                val callbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(callbackDeepLink)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    // Explicit package ensures only ReceiverApp handles this.
                    setPackage("com.poc.receiver")
                    // Attaching the content URI via ClipData propagates the
                    // permission grant even though the intent data is a custom
                    // scheme (not the content:// URI itself).
                    clipData = ClipData.newRawUri("fileUri", contentUri)
                }

                startActivity(callbackIntent)
                statusText.value =
                    "Returned to ReceiverApp ✓\n${file.name}  (${formatBytes(file.length())})"
                Log.d(TAG_CALLBACK, "Callback launched successfully")

            } catch (e: android.content.ActivityNotFoundException) {
                val msg = "ReceiverApp not found — is com.poc.receiver installed?"
                Log.e(TAG_CALLBACK_ERROR, msg, e)
                statusText.value = "Error: $msg"
            } catch (e: Exception) {
                Log.e(TAG_CALLBACK_ERROR, "Unexpected error in processGenerateRequest", e)
                statusText.value = "Error: ${e.message}"
            }
        }
    }

    // ── File generation ───────────────────────────────────────────────────

    /**
     * Generates a ~5 MB JSON file in cacheDir.
     * Uses raw StringBuilder (not JSONObject) for speed; the random character
     * set is chosen to require no JSON escaping.
     */
    private fun generateLargeJsonFile(requestId: String): File {
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())
        val random    = Random()
        val chars     = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789 ,.-_"

        fun randomText(len: Int): String {
            val sb = StringBuilder(len)
            repeat(len) { sb.append(chars[random.nextInt(chars.length)]) }
            return sb.toString()
        }

        // Pre-allocate generously: 8 000 records × ~620 chars ≈ ~5 MB
        val sb = StringBuilder(6_000_000)
        sb.append("{")
        sb.append("\"requestId\":\"$requestId\",")
        sb.append("\"generatedAt\":\"$timestamp\",")
        sb.append("\"sourceApp\":\"SenderApp\",")
        sb.append("\"payloadType\":\"RandomGeneratedData\",")
        sb.append("\"records\":[")

        val recordCount = 8_000
        for (i in 0 until recordCount) {
            if (i > 0) sb.append(",")
            val id          = UUID.randomUUID().toString()
            val name        = "Record-${randomText(8)}"
            val description = randomText(500)
            sb.append("{")
            sb.append("\"id\":\"$id\",")
            sb.append("\"name\":\"$name\",")
            sb.append("\"description\":\"$description\"")
            sb.append("}")
        }

        sb.append("]}")

        val fileName = "payload_${requestId.take(8)}_${System.currentTimeMillis()}.json"
        val file     = File(cacheDir, fileName)
        file.writeText(sb.toString(), Charsets.UTF_8)
        return file
    }

    // ── Compose UI ────────────────────────────────────────────────────────

    @Composable
    private fun SenderScreen() {
        val status        by statusText
        val lastRequestId by lastRequestIdState
        val fileName      by generatedFileNameState
        val fileSize      by generatedFileSizeState
        val contentUri    by contentUriTextState

        MaterialTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text  = "Sender App",
                        style = MaterialTheme.typography.headlineMedium
                    )

                    // ── Status card ──────────────────────────────────────
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Status", style = MaterialTheme.typography.labelLarge)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(status, style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    // ── Last request card (shown after first request) ─────
                    if (lastRequestId.isNotEmpty()) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier            = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("Last Request", style = MaterialTheme.typography.titleMedium)
                                HorizontalDivider()
                                LabelValue("Request ID", lastRequestId)
                                if (fileName.isNotEmpty()) {
                                    LabelValue("File Name",   fileName)
                                    LabelValue("File Size",   formatBytes(fileSize))
                                    LabelValue("Content URI", contentUri)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun LabelValue(label: String, value: String) {
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }

    // ── Constants ─────────────────────────────────────────────────────────

    companion object {
        private const val FILE_PROVIDER_AUTHORITY = "com.poc.sender.fileprovider"
        private const val TAG_RECEIVE             = "GENERATE_REQUEST_RECEIVED"
        private const val TAG_GEN_START           = "FILE_GENERATION_STARTED"
        private const val TAG_GEN_SUCCESS         = "FILE_GENERATION_SUCCESS"
        private const val TAG_URI_CREATED         = "CONTENT_URI_CREATED"
        private const val TAG_CALLBACK            = "CALLBACK_LAUNCHED"
        private const val TAG_CALLBACK_ERROR      = "CALLBACK_ERROR"
    }
}

// ── Top-level helpers ──────────────────────────────────────────────────────

/**
 * Builds the callback deep link that returns to ReceiverApp.
 * [encodedFileUri] and [encodedFileName] must already be URLEncoder-encoded
 * because they are embedded as query parameter values.
 */
fun buildReceiverCallbackDeepLink(
    callbackBase:    String,
    requestId:       String,
    encodedFileUri:  String,
    encodedFileName: String
): String = "$callbackBase?requestId=$requestId&fileUri=$encodedFileUri&fileName=$encodedFileName"

/** Human-readable byte count (B / KB / MB). */
fun formatBytes(bytes: Long): String = when {
    bytes == 0L        -> "0 B"
    bytes < 1_024L     -> "$bytes B"
    bytes < 1_048_576L -> "${"%.2f".format(bytes / 1_024.0)} KB"
    else               -> "${"%.2f".format(bytes / 1_048_576.0)} MB"
}
