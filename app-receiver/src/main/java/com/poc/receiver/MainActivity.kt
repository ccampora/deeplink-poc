package com.poc.receiver

import android.content.ActivityNotFoundException
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
import androidx.compose.material3.Button
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.util.UUID

// ---------------------------------------------------------------------------
// ReceiverApp — MainActivity
//
// Flow:
//   1. User taps "Request Large File From Sender"
//   2. We open SenderApp via senderpoc://generate deep link
//   3. SenderApp generates a ~5 MB file, exposes it via FileProvider
//   4. SenderApp sends receiverpoc://callback?fileUri=... deep link back here
//   5. We read the content URI with ContentResolver and display the result
// ---------------------------------------------------------------------------

class MainActivity : ComponentActivity() {

    // ── Compose-observable state held at activity level so that onNewIntent
    //    updates trigger recomposition without rebuilding the content tree.
    private val statusText          = mutableStateOf("Ready. Tap the button to start the flow.")
    private val requestIdState      = mutableStateOf("")
    private val receivedUriState    = mutableStateOf("")
    private val fileSizeState       = mutableLongStateOf(0L)
    private val filePreviewState    = mutableStateOf("")
    private val resultMessageState  = mutableStateOf("")
    private val hasResultState      = mutableStateOf(false)

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)               // handle cold-start deep link
        setContent { ReceiverScreen() }
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
        if (data.scheme == "receiverpoc" && data.host == "callback") {
            processCallback(data)
        }
    }

    // ── Callback processing ───────────────────────────────────────────────

    private fun processCallback(uri: Uri) {
        Log.d(TAG_CALLBACK, "Callback received: $uri")
        statusText.value = "Callback received from SenderApp…"

        val requestId = uri.getQueryParameter("requestId")
        if (requestId.isNullOrBlank()) {
            Log.e(TAG_CALLBACK, "Missing requestId in callback URI")
            statusText.value = "Error: Missing requestId in callback"
            return
        }

        // Android's Uri.getQueryParameter() already URL-decodes the value,
        // so no extra URLDecoder call is needed here.
        val fileUriString = uri.getQueryParameter("fileUri")
        if (fileUriString.isNullOrBlank()) {
            Log.e(TAG_CALLBACK, "Missing fileUri in callback URI")
            statusText.value = "Error: Missing fileUri in callback"
            return
        }

        Log.d(TAG_URI, "requestId=$requestId")
        Log.d(TAG_URI, "fileUri (decoded by Uri parser) = $fileUriString")

        requestIdState.value   = requestId
        receivedUriState.value = fileUriString

        val contentUri = try {
            Uri.parse(fileUriString)
        } catch (e: Exception) {
            Log.e(TAG_URI, "Failed to parse URI: $fileUriString", e)
            statusText.value = "Error: Invalid file URI — ${e.message}"
            return
        }

        readFileFromUri(requestId, contentUri)
    }

    // ── File reading ──────────────────────────────────────────────────────

    private fun readFileFromUri(requestId: String, fileUri: Uri) {
        Log.d(TAG_READ_START, "Reading from URI: $fileUri")
        statusText.value = "Reading file from SenderApp…"

        lifecycleScope.launch {
            try {
                // I/O on a background thread to avoid ANR on a ~5 MB read.
                val bytes = withContext(Dispatchers.IO) {
                    val stream = contentResolver.openInputStream(fileUri)
                        ?: throw IllegalStateException(
                            "openInputStream returned null for URI: $fileUri"
                        )
                    stream.use { it.readBytes() }
                }

                val totalBytes = bytes.size.toLong()
                val preview    = String(bytes.copyOf(minOf(500, bytes.size)), Charsets.UTF_8)

                Log.d(TAG_READ_SUCCESS, "Read $totalBytes bytes from $fileUri")

                fileSizeState.longValue      = totalBytes
                filePreviewState.value       = preview
                resultMessageState.value     = "Success — read ${formatBytes(totalBytes)}"
                hasResultState.value         = true
                statusText.value             = "File read successfully!"

            } catch (e: SecurityException) {
                Log.e(TAG_READ_ERROR, "SecurityException reading URI: $fileUri", e)
                resultMessageState.value = "SecurityException: ${e.message}"
                statusText.value         = "Permission denied reading file — see Logcat TAG=$TAG_READ_ERROR"
                hasResultState.value     = true

            } catch (e: Exception) {
                Log.e(TAG_READ_ERROR, "Exception reading URI: $fileUri", e)
                resultMessageState.value = "Exception: ${e.message}"
                statusText.value         = "Error reading file — see Logcat TAG=$TAG_READ_ERROR"
                hasResultState.value     = true
            }
        }
    }

    // ── Deep link launch ──────────────────────────────────────────────────

    private fun sendRequestToSender() {
        val requestId = UUID.randomUUID().toString()

        // Reset result state
        requestIdState.value     = requestId
        hasResultState.value     = false
        fileSizeState.longValue  = 0L
        filePreviewState.value   = ""
        resultMessageState.value = ""
        receivedUriState.value   = ""

        // URL-encode the callback scheme so it travels safely as a query param.
        val encodedCallback = URLEncoder.encode("receiverpoc://callback", "UTF-8")
        val deepLink        = buildSenderRequestDeepLink(requestId, encodedCallback)

        Log.d(TAG_REQUEST, "Launching SenderApp with: $deepLink")
        statusText.value = "Sending request to SenderApp…"

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLink)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Target SenderApp explicitly — more robust than relying on scheme alone.
            setPackage("com.poc.sender")
        }

        try {
            startActivity(intent)
            statusText.value = "Request sent to SenderApp\nrequestId: $requestId"
            Log.d(TAG_REQUEST, "Request launched. requestId=$requestId")
        } catch (e: ActivityNotFoundException) {
            statusText.value =
                "Error: SenderApp not installed or deep link not matched.\n" +
                "Install com.poc.sender first."
            Log.e(TAG_REQUEST, "ActivityNotFoundException — com.poc.sender not found", e)
        } catch (e: Exception) {
            statusText.value = "Error launching SenderApp: ${e.message}"
            Log.e(TAG_REQUEST, "Exception launching SenderApp", e)
        }
    }

    // ── Compose UI ────────────────────────────────────────────────────────

    @Composable
    private fun ReceiverScreen() {
        val status        by statusText
        val hasResult     by hasResultState
        val requestId     by requestIdState
        val receivedUri   by receivedUriState
        val fileSize      by fileSizeState
        val filePreview   by filePreviewState
        val resultMessage by resultMessageState

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
                        text  = "Receiver App",
                        style = MaterialTheme.typography.headlineMedium
                    )

                    Button(
                        onClick  = { sendRequestToSender() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Request Large File From Sender")
                    }

                    // ── Status card ──────────────────────────────────────
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Status", style = MaterialTheme.typography.labelLarge)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(status, style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    // ── Result card (shown after callback) ───────────────
                    if (hasResult) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier            = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("Result", style = MaterialTheme.typography.titleMedium)
                                HorizontalDivider()
                                LabelValue("Request ID",  requestId)
                                LabelValue("File URI",    receivedUri)
                                LabelValue("File Size",   formatBytes(fileSize))
                                LabelValue("Message",     resultMessage)
                                HorizontalDivider()
                                Text(
                                    "Preview (first 500 chars):",
                                    style = MaterialTheme.typography.labelMedium
                                )
                                Text(
                                    text  = filePreview,
                                    style = MaterialTheme.typography.bodySmall
                                )
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
        private const val TAG_REQUEST      = "REQUEST_SENT"
        private const val TAG_CALLBACK     = "CALLBACK_RECEIVED"
        private const val TAG_URI          = "URI_DECODED"
        private const val TAG_READ_START   = "FILE_READ_STARTED"
        private const val TAG_READ_SUCCESS = "FILE_READ_SUCCESS"
        private const val TAG_READ_ERROR   = "FILE_READ_ERROR"
    }
}

// ── Top-level helpers ──────────────────────────────────────────────────────

/** Builds the deep link that opens SenderApp and requests file generation. */
fun buildSenderRequestDeepLink(requestId: String, encodedCallback: String): String =
    "senderpoc://generate?requestId=$requestId&callback=$encodedCallback"

/** Human-readable byte count (B / KB / MB). */
fun formatBytes(bytes: Long): String = when {
    bytes == 0L         -> "0 B"
    bytes < 1_024L      -> "$bytes B"
    bytes < 1_048_576L  -> "${"%.2f".format(bytes / 1_024.0)} KB"
    else                -> "${"%.2f".format(bytes / 1_048_576.0)} MB"
}
