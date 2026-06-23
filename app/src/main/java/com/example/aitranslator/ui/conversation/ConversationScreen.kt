package com.example.aitranslator.ui.conversation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aitranslator.model.Language

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    onOpenModels: () -> Unit,
    viewModel: ConversationViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) viewModel.toggleListening() }

    fun onMicClick() {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.toggleListening() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Converse") },
                actions = {
                    TextButton(onClick = onOpenModels) { Text("Models") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            LanguageBar(
                source = state.sourceLanguage,
                target = state.targetLanguage,
                onSource = viewModel::setSourceLanguage,
                onTarget = viewModel::setTargetLanguage,
                onSwap = viewModel::swapLanguages,
            )

            Spacer(Modifier.height(16.dp))

            ConversationHistory(
                history = state.history,
                partial = state.partialTranscript,
                modifier = Modifier.weight(1f),
            )

            state.error?.let { message ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(message, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.voiceToInstall != null) {
                                Button(onClick = { installVoiceData(context) }) { Text("Install voice") }
                            }
                            OutlinedButton(onClick = viewModel::clearError) { Text("Dismiss") }
                        }
                    }
                }
            }

            VuMeter(amplitude = state.amplitude, active = state.isListening)
            Spacer(Modifier.height(8.dp))
            Text(state.statusLabel, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = ::onMicClick,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.isListening) "Stop" else "Start listening")
            }
        }
    }
}

@Composable
private fun LanguageBar(
    source: Language,
    target: Language,
    onSource: (Language) -> Unit,
    onTarget: (Language) -> Unit,
    onSwap: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LanguagePicker(selected = source, onSelected = onSource, modifier = Modifier.weight(1f))
        IconButton(onClick = onSwap) { Text("⇄") }
        LanguagePicker(selected = target, onSelected = onTarget, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun LanguagePicker(
    selected: Language,
    onSelected: (Language) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(selected.displayName)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Language.ALL.forEach { language ->
                DropdownMenuItem(
                    text = { Text(language.displayName) },
                    onClick = {
                        onSelected(language)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ConversationHistory(
    history: List<ConversationTurn>,
    partial: String,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(history) { turn ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(turn.sourceText, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        turn.translatedText,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        if (partial.isNotBlank()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        partial,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun VuMeter(amplitude: Float, active: Boolean) {
    val level by animateFloatAsState(
        targetValue = if (active) amplitude.coerceIn(0f, 1f) else 0f,
        label = "vu",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(12.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(level)
                .height(12.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp)),
        )
    }
}

/** Opens the system TTS voice-data installer so the user can download the missing voice. */
private fun installVoiceData(context: Context) {
    val intent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val launched = runCatching { context.startActivity(intent) }.isSuccess
    if (!launched) {
        Toast.makeText(context, "No voice-data installer available", Toast.LENGTH_LONG).show()
    }
}
