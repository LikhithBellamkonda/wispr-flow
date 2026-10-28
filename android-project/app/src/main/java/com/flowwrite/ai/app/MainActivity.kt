package com.flowwrite.ai.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.flowwrite.ai.llm.DiscoveredLocalModel
import com.flowwrite.ai.llm.LlamaBridge
import com.flowwrite.ai.personalization.LanguageStyle
import com.flowwrite.ai.settings.FlowWriteAppSettings
import com.flowwrite.ai.settings.SettingsRepository
import kotlinx.coroutines.launch

/**
 * MainActivity:
 * One-time setup screen ONLY.
 * Users DO NOT speak, type, or copy-paste inside this app.
 * Once enabled, FlowWrite AI works strictly as a one-tap Voice Button inside
 * WhatsApp, Instagram, Gmail, and Messages that sends speech to the local Qwen2.5 LLM
 * and injects the auto-corrected text directly back into the messaging app in <5 seconds.
 */
class MainActivity : ComponentActivity() {

    private lateinit var settingsRepo: SettingsRepository
    private lateinit var llamaBridge: LlamaBridge

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsRepo = SettingsRepository(applicationContext)
        llamaBridge = LlamaBridge(applicationContext)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF4F46E5),
                    background = Color(0xFF0F172A),
                    surface = Color(0xFF1E293B)
                )
            ) {
                FlowWriteZeroTypingSetupScreen(
                    settingsRepo = settingsRepo,
                    llamaBridge = llamaBridge
                )
            }
        }
    }
}

@Composable
fun FlowWriteZeroTypingSetupScreen(
    settingsRepo: SettingsRepository,
    llamaBridge: LlamaBridge
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val settings by settingsRepo.settingsFlow.collectAsState(initial = FlowWriteAppSettings())
    var discoveredModels by remember { mutableStateOf<List<DiscoveredLocalModel>>(emptyList()) }
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var modelStatusText by remember { mutableStateOf("Scanning device for local Qwen2.5 GGUF model...") }

    LaunchedEffect(Unit) {
        // Ensure auto-insert is always ON so the user never has to copy-paste or review inside FlowWrite
        settingsRepo.updateAutoInsertAfterProcessing(true)
        val found = llamaBridge.discoverAccessibleLocalModels()
        discoveredModels = found
        if (found.isNotEmpty() && settings.importedModelPath.isNullOrBlank()) {
            val first = found.first()
            settingsRepo.updateImportedModel(first.absolutePath, first.sizeBytes)
            llamaBridge.ensureModelLoaded(
                modelPath = first.absolutePath,
                contextWindowTokens = settings.contextWindowTokens,
                cpuThreads = settings.cpuThreads
            )
            modelStatusText = "Auto-linked local LLM: ${first.fileName}"
        } else if (found.isNotEmpty()) {
            modelStatusText = "Local LLM active: ${found.first().fileName}"
        } else {
            modelStatusText = "Select your local Qwen2.5-0.5B-Instruct-Q4_K_M.gguf file once below."
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
    }

    val ggufPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                modelStatusText = "Linking GGUF model..."
                val importResult = llamaBridge.importGgufModelFromUri(uri)
                importResult.fold(
                    onSuccess = { file ->
                        settingsRepo.updateImportedModel(file.absolutePath, file.length())
                        llamaBridge.ensureModelLoaded(
                            modelPath = file.absolutePath,
                            contextWindowTokens = settings.contextWindowTokens,
                            cpuThreads = settings.cpuThreads
                        )
                        modelStatusText = "Ready: ${file.name} (<5s auto-correction active)"
                    },
                    onFailure = { err ->
                        modelStatusText = "Model error: ${err.message}"
                    }
                )
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                text = "FlowWrite AI — Voice Button Setup",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "No typing in this app. No copy & paste. Speak directly inside WhatsApp, Instagram, or Gmail using the FlowWrite button — auto-corrected in <5s.",
                color = Color(0xFF34D399),
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("1. Microphone Access", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = if (hasMicPermission) "Ready — Microphone permission granted" else "Required so the voice button can hear you in messaging apps",
                        color = if (hasMicPermission) Color(0xFF34D399) else Color(0xFFFBBF24),
                        fontSize = 13.sp
                    )
                    if (!hasMicPermission) {
                        Button(onClick = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }) {
                            Text("Grant Microphone Access")
                        }
                    }
                }
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("2. Activate FlowWrite Voice Button", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Once enabled, tap any chat box in WhatsApp or Instagram and press the FlowWrite Mic button. It sends your speech to Qwen2.5 and writes the corrected reply straight into the chat box.",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                        }) {
                            Text("Enable Voice Button")
                        }
                        OutlinedButton(onClick = {
                            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                            imm.showInputMethodPicker()
                        }) {
                            Text("Switch to FlowWrite Button")
                        }
                    }
                }
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("3. Local Qwen2.5 LLM (<5s Auto-Correction)", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(text = modelStatusText, color = Color(0xFF818CF8), fontSize = 12.sp)
                    Button(onClick = { ggufPickerLauncher.launch(arrayOf("*/*")) }) {
                        Text("Link Local Qwen2.5 GGUF File")
                    }
                }
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("4. Default Spoken Language Style", color = Color.White, fontWeight = FontWeight.SemiBold)
                    LanguageStyle.values().forEach { style ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = settings.languageStyle == style,
                                onClick = { scope.launch { settingsRepo.updateLanguageStyle(style) } }
                            )
                            Text(style.name, color = Color.White, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}
