/*
 * BeaconAskScreen - the Ask-Muse sheet for the companion app.
 *
 * Opened from the camera screen ("Ask Muse" button) or the home screen
 * ("Ask Muse without glasses"). Frame sources:
 *  - glasses: a photo just captured from the glasses (set before opening);
 *  - gallery: any image the user picks — including a snapshot saved from the
 *    MetaLensAI OBS/RTMP stream, which is the DAT fallback path;
 *  - sample: the bundled sample asset, for a no-glasses smoke test.
 *
 * Question input is typed, or spoken via the system speech recogniser
 * (no extra permission: the recogniser UI handles the mic).
 * The answer is shown here, broadcast toward the watch, and also lives on
 * the backend at GET /latest. Nothing here touches an API key.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.beacon

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun BeaconAskScreen(
    viewModel: BeaconViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val ui by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val scrollState = rememberScrollState()

  val pickImageLauncher = rememberLauncherForActivityResult(
      ActivityResultContracts.GetContent(),
  ) { uri ->
    if (uri == null) return@rememberLauncherForActivityResult
    try {
      val opened = context.contentResolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input)?.let { viewModel.setGalleryFrame(it) }
      }
      if (opened == null) {
        Toast.makeText(context, "Could not open that image.", Toast.LENGTH_SHORT).show()
      }
    } catch (e: Exception) {
      Toast.makeText(context, "Could not open that image: ${e.message}", Toast.LENGTH_SHORT).show()
    }
  }

  val speakLauncher = rememberLauncherForActivityResult(
      ActivityResultContracts.StartActivityForResult(),
  ) { result ->
    if (result.resultCode == Activity.RESULT_OK) {
      val spoken = result.data
          ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
          ?.firstOrNull()
      if (!spoken.isNullOrBlank()) viewModel.setQuestion(spoken)
    }
  }

  Dialog(
      onDismissRequest = onDismiss,
      properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
      Column(
          modifier = Modifier
              .fillMaxSize()
              .verticalScroll(scrollState)
              .padding(20.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
          Text("Ask Muse", style = MaterialTheme.typography.titleLarge)
          TextButton(onClick = onDismiss) { Text("Close") }
        }
        Text(
            "Frame + question go to the home-box backend (port 8901). " +
                "The backend asks Muse Wonder; the answer comes back here " +
                "and toward your watch. No key lives in this app.",
            style = MaterialTheme.typography.bodySmall,
        )

        OutlinedTextField(
            value = ui.backendUrl,
            onValueChange = { viewModel.setBackendUrl(it) },
            label = { Text("Backend URL") },
            placeholder = { Text("http://100.99.129.53:8901") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(
              onClick = { viewModel.checkHealth() },
              enabled = !ui.healthChecking,
          ) { Text(if (ui.healthChecking) "Checking…" else "Check backend") }
          OutlinedButton(onClick = { viewModel.refreshLatest() }) { Text("Refresh /latest") }
          ui.healthOk?.let { ok ->
            Text(
                if (ok) "Backend OK" else "Backend unreachable",
                color = if (ok) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
          }
        }

        Text("Frame source: ${ui.frameSource}", style = MaterialTheme.typography.bodyMedium)
        ui.pendingBitmap?.let { bitmap ->
          Image(
              bitmap = bitmap.asImageBitmap(),
              contentDescription = "Frame to send",
              modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
          )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(onClick = { viewModel.useSampleFrame() }) {
            Text("Use sample frame")
          }
          OutlinedButton(onClick = { pickImageLauncher.launch("image/*") }) {
            Text("Pick image (OBS snapshot)")
          }
          if (ui.pendingBitmap != null) {
            TextButton(onClick = { viewModel.clearFrame() }) { Text("Clear") }
          }
        }

        OutlinedTextField(
            value = ui.question,
            onValueChange = { viewModel.setQuestion(it) },
            label = { Text("Question") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(onClick = {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
              putExtra(
                  RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                  RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
              )
              putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask Muse about what the glasses see")
            }
            runCatching { speakLauncher.launch(intent) }.onFailure {
              Toast.makeText(context, "Speech input is not available on this phone.", Toast.LENGTH_SHORT).show()
            }
          }) { Text("Speak question") }
          Button(
              onClick = { viewModel.ask() },
              enabled = !ui.isAsking && ui.pendingBitmap != null,
          ) { Text(if (ui.isAsking) "Asking…" else "Ask Muse") }
        }
        if (ui.isAsking) {
          Row(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalAlignment = Alignment.CenterVertically,
          ) {
            CircularProgressIndicator()
            Text("Waiting on the backend…")
          }
        }
        ui.error?.let { error ->
          Text(error, color = MaterialTheme.colorScheme.error)
          TextButton(onClick = { viewModel.clearError() }) { Text("Dismiss error") }
        }
        ui.answer?.let { answer ->
          Text("Answer", style = MaterialTheme.typography.titleMedium)
          Text(answer)
          ui.sam?.let { sam ->
            Spacer(modifier = Modifier.height(4.dp))
            Text("Seen in frame (SAM): $sam", style = MaterialTheme.typography.bodySmall)
          }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
              val clipboard =
                  context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
              clipboard.setPrimaryClip(ClipData.newPlainText("Muse answer", answer))
              Toast.makeText(context, "Answer copied.", Toast.LENGTH_SHORT).show()
            }) { Text("Copy answer") }
            OutlinedButton(onClick = { viewModel.resendToWatch() }) {
              Text("Send to watch again")
            }
          }
        }
        val latestAnswer = ui.latestAnswer
        if (latestAnswer != null) {
          Text("Latest on backend", style = MaterialTheme.typography.titleMedium)
          ui.latestQuestion?.let { Text("Q: $it", style = MaterialTheme.typography.bodySmall) }
          Text(latestAnswer, style = MaterialTheme.typography.bodySmall)
          ui.latestAt?.let { Text("At: $it", style = MaterialTheme.typography.bodySmall) }
        }
        Spacer(modifier = Modifier.height(24.dp))
      }
    }
  }
}
