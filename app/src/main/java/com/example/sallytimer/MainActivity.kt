package com.example.sallytimer speak(text: String) {
        if (!voiceEnabled) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sally_${System.currentTimeMillis()}")
    }

    var exercise by remember { mutableStateOf(ExerciseType.PUSH_UP) }

    val hasAudio = audioUri != null
    val hasCues = cues.isNotEmpty()

    var running by remember { mutableStateOf(false) }
    var inCountdown by remember { mutableStateOf(false) }
    var countdown by remember { mutableStateOf(3) }

    var currentMs by remember { mutableStateOf(0L) }

    var reps by remember { mutableStateOf(0) }
    var currentCommand by remember { mutableStateOf(Command.DOWN) }
    var lastCueIndex by remember { mutableStateOf(-1) }

    var flashColor by remember { mutableStateOf(Color.Transparent) }
    val animatedFlash by animateColorAsState(flashColor, label = "flash")

    suspend fun flash(cmd: Command) {
        flashColor = if (cmd == Command.UP) Color(0x334CAF50) else Color(0x33F44336)
        delay(180)
        flashColor = Color.Transparent
    }

    // Keep current time updated
    LaunchedEffect(Unit) {
        while (true) {
            currentMs = player.currentPosition
            delay(50)
        }
    }

    // Countdown -> start playback
    LaunchedEffect(inCountdown) {
        if (!inCountdown) return@LaunchedEffect
        for (i in 3 downTo 1) {
            countdown = i
            delay(1000)
        }
        inCountdown = false
        running = true
        reps = 0
        lastCueIndex = -1
        player.seekTo(0)
        player.play()
    }

    // Drive cues from audio clock
    LaunchedEffect(currentMs, running, hasCues) {
        if (!running || !hasCues) return@LaunchedEffect
        val idx = cues.indexOfLast { it.atMs <= currentMs }
        if (idx != -1 && idx != lastCueIndex) {
            val cue = cues[idx]
            currentCommand = cue.cmd
            if (cue.cmd == Command.UP) reps += 1
            speak(if (cue.cmd == Command.UP) exercise.upVoice else exercise.downVoice)
            flash(cue.cmd)
            lastCueIndex = idx
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(animatedFlash)
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Sally Up Timer (MP3)", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("Reps: $reps", fontWeight = FontWeight.Bold)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { pickAudio.launch(arrayOf("audio/*")) }) {
                    Text(if (hasAudio) "Change audio" else "Pick MP3")
                }

                OutlinedButton(
                    enabled = hasAudio,
                    onClick = {
                        running = false
                        inCountdown = false
                        player.pause()
                        player.seekTo(0)
                        reps = 0
                        lastCueIndex = -1
                    }
                ) { Text("Reset") }

                Spacer(Modifier.weight(1f))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = voiceEnabled, onCheckedChange = { voiceEnabled = it })
                    Spacer(Modifier.width(8.dp))
                    Text("Voice")
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                ExerciseDropdown(exercise) { exercise = it }
                Spacer(Modifier.weight(1f))
                Text("Time: ${formatMs(currentMs)}", fontWeight = FontWeight.SemiBold)
            }

            if (hasAudio && !hasCues) {
                Text("No cues saved yet. Use Calibrate (manual) or Auto-detect (in Calibrate).", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when {
                !hasAudio -> {
                    Text("Pick your MP3 to begin", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("Then calibrate (manual or auto-detect).")
                }
                inCountdown -> {
                    Text(countdown.toString(), fontSize = 120.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.height(8.dp))
                    Text("Get ready…")
                }
                !hasCues -> {
                    Text("Calibrate timing", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("Tap Calibrate to mark UP/DOWN.")
                }
                else -> {
                    val label = if (currentCommand == Command.UP) exercise.upText else exercise.downText
                    Text(label, fontSize = 110.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.height(10.dp))
                    Text(exercise.label, style = MaterialTheme.typography.titleLarge)
                }
            }
        }

        Row(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                modifier = Modifier.weight(1f),
                enabled = hasAudio && !inCountdown,
                onClick = {
                    if (!running) {
                        if (hasCues) inCountdown = true else onGoCalibrate()
                    } else {
                        running = false
                        player.pause()
                    }
                }
            ) { Text(if (!running) "Start (3-2-1)" else "Pause") }

            OutlinedButton(
                modifier = Modifier.weight(1f),
                enabled = hasAudio,
                onClick = onGoCalibrate
            ) { Text("Calibrate") }
        }
    }
}

/* ----------------------- Calibration Screen ------------------------ */

@Composable
private fun CalibrationScreen(
    audioUri: Uri?,
    player: ExoPlayer,
    initialCues: List<Cue>,
    onSave: (List<Cue>) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val hasAudio = audioUri != null

    // Permissions
    val requestMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { /* handled by state below */ }
    var hasMicPermission by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        hasMicPermission = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    // Capture state
    var currentMs by remember { mutableStateOf(0L) }
    var captured by remember { mutableStateOf(initialCues) }

    // Auto-detect state
    var autoDetectRunning by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    // SpeechRecognizer
    val recognizer = remember {
        if (SpeechRecognizer.isRecognitionAvailable(context)) SpeechRecognizer.createSpeechRecognizer(context) else null
    }
    DisposableEffect(Unit) {
        onDispose { recognizer?.destroy() }
    }

    LaunchedEffect(Unit) {
        while (true) {
            currentMs = player.currentPosition
            delay(50)
        }
    }

    val upCount = captured.count { it.cmd == Command.UP }
    val downCount = captured.count { it.cmd == Command.DOWN }

    fun addCue(cmd: Command, at: Long) {
        // De-bounce: ignore if same command within 500ms
        val last = captured.lastOrNull()
        if (last != null && last.cmd == cmd && abs(last.atMs - at) < 500) return
        captured = captured + Cue(at, cmd)
    }

    fun startAutoDetect() {
        if (recognizer == null) {
            status = "Speech recognition not available on this device."
            return
        }
        if (!hasMicPermission) {
            status = "Mic permission required for auto-detect."
            requestMic.launch(android.Manifest.permission.RECORD_AUDIO)
            return
        }

        status = "Auto-detect running… (best in a quiet room, volume up)"
        autoDetectRunning = true
        captured = emptyList()

        player.seekTo(0)
        player.play()

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.ENGLISH.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                if (!autoDetectRunning) return
                // Restart listening on errors while running
                try { recognizer.startListening(intent) } catch (_: Exception) {}
            }

            override fun onResults(results: Bundle?) {
                if (!autoDetectRunning) return
                handleSpeechResults(results)
                // Keep listening continuously by restarting
                try { recognizer.startListening(intent) } catch (_: Exception) {}
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (!autoDetectRunning) return
                handleSpeechResults(partialResults)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
            
            fun handleSpeechResults(bundle: Bundle?) {
                val texts = bundle
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.map { it.lowercase(Locale.ENGLISH) }
                    ?: return

                val joined = texts.joinToString(" ")
                val now = player.currentPosition

                // crude keyword detection
                if (joined.contains("up")) addCue(Command.UP, now)
                if (joined.contains("down")) addCue(Command.DOWN, now)
            }
        })

        // start listening
        try { recognizer.startListening(intent) } catch (e: Exception) {
            status = "Failed to start recognizer: ${e.message}"
            autoDetectRunning = false
        }
    }

    fun stopAutoDetect() {
        autoDetectRunning = false
        status = "Auto-detect stopped."
        try { recognizer?.stopListening() } catch (_: Exception) {}
        player.pause()
    }

    // Update mic permission state after request
    LaunchedEffect(hasMicPermission) { /* no-op */ }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Calibration", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)

        if (!hasAudio) {
            Text("No audio selected. Go back and pick your MP3 first.")
            Button(onClick = onBack) { Text("Back") }
            return@Column
        }

        Text("Time: ${formatMs(currentMs)}   |   UP: $upCount   DOWN: $downCount")
        if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)

        // Manual controls
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(modifier = Modifier.weight(1f), onClick = { player.play() }, enabled = !autoDetectRunning) { Text("Play") }
            Button(modifier = Modifier.weight(1f), onClick = { player.pause() }, enabled = !autoDetectRunning) { Text("Pause") }
            OutlinedButton(modifier = Modifier.weight(1f), onClick = { player.seekTo(0); player.pause() }, enabled = !autoDetectRunning) { Text("Restart") }
        }

        // Manual mark buttons
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                modifier = Modifier.weight(1f),
                enabled = !autoDetectRunning,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                onClick = { addCue(Command.UP, currentMs) }
            ) { Text("Mark UP") }

            Button(
                modifier = Modifier.weight(1f),
                enabled = !autoDetectRunning,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                onClick = { addCue(Command.DOWN, currentMs) }
            ) { Text("Mark DOWN") }
        }

        // Auto-detect controls
        Divider()
        Text("Auto-detect (experimental)", fontWeight = FontWeight.SemiBold)
        Text(
            "Plays the MP3 and listens through your mic for the words 'up'/'down'. " +
                "Works best in a quiet room with volume up. You may still need manual cleanup.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                modifier = Modifier.weight(1f),
                enabled = !autoDetectRunning,
                onClick = {
                    hasMicPermission = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (!hasMicPermission) {
                        requestMic.launch(android.Manifest.permission.RECORD_AUDIO)
                        // update state after a short delay
                        // (launcher callback doesn't return permission result directly here)
                        // we'll re-check on next click if needed
                    } else startAutoDetect()
                }
            ) { Text("Start Auto") }

            OutlinedButton(
                modifier = Modifier.weight(1f),
                enabled = autoDetectRunning,
                onClick = stopAutoDetect
            ) { Text("Stop Auto") }
        }

        // Save / undo / clear
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                modifier = Modifier.weight(1f),
                enabled = captured.isNotEmpty() && !autoDetectRunning,
                onClick = { captured = captured.dropLast(1) }
            ) { Text("Undo") }

            OutlinedButton(
                modifier = Modifier.weight(1f),
                enabled = !autoDetectRunning,
                onClick = { captured = emptyList() }
            ) { Text("Clear") }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                modifier = Modifier.weight(1f),
                enabled = captured.isNotEmpty() && !autoDetectRunning,
                onClick = { onSave(captured.sortedBy { it.atMs }) }
            ) { Text("Save") }

            OutlinedButton(modifier = Modifier.weight(1f), onClick = onBack, enabled = !autoDetectRunning) { Text("Back") }
        }
    }
}

/* ---------------------- UI helpers ---------------------- */

@Composable
private fun ExerciseDropdown(current: ExerciseType, onSelect: (ExerciseType) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text(current.label) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ExerciseType.entries.forEach { t ->
                DropdownMenuItem(
                    text = { Text(t.label) },
                    onClick = { onSelect(t); open = false }
                )
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = max(0, (ms / 1000).toInt())
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}

/* ---------------------- Persistence ---------------------- */

private fun loadSelectedAudioUri(context: Context): Uri? {
    val s = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_AUDIO_URI, null)
    return s?.let { Uri.parse(it) }
}

private fun saveSelectedAudioUri(context: Context, uri: Uri) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_AUDIO_URI, uri.toString())
        .apply()
}

private fun cuesKey(uri: Uri?): String = KEY_CUES_PREFIX + (uri?.toString()?.hashCode() ?: 0)

private fun saveCues(context: Context, uri: Uri?, cues: List<Cue>) {
    if (uri == null) return
    val s = cues.joinToString(";") { "${it.atMs}:${it.cmd.name}" }
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(cuesKey(uri), s)
        .apply()
}

private fun loadCues(context: Context, uri: Uri?): List<Cue> {
    if (uri == null) return emptyList()
    val s = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(cuesKey(uri), null)
        ?: return emptyList()

    return s.split(";").mapNotNull { part ->
        val bits = part.split(":")
        if (bits.size != 2) return@mapNotNull null
        val ms = bits[0].toLongOrNull() ?: return@mapNotNull null
        val cmd = runCatching { Command.valueOf(bits[1]) }.getOrNull() ?: return@mapNotNull null
        Cue(ms, cmd)
    }.sortedBy { it.atMs }
}
``

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

private const val PREFS = "sally_audio_prefs"
private const val KEY_AUDIO_URI = "audio_uri"
private const val KEY_CUES_PREFIX = "cues_for_"

enum class Command { UP, DOWN }

enum class ExerciseType(
    val label: String,
    val upText: String,
    val downText: String,
    val upVoice: String,
    val downVoice: String
) {
    PUSH_UP("Push-up", "UP", "DOWN", "Up", "Down"),
    SQUAT("Squat", "STAND", "SQUAT", "Up", "Down"),
    PLANK("Plank", "HIGH", "LOW", "Up", "Down")
}

data class Cue(val atMs: Long, val cmd: Command)

private enum class Screen { WORKOUT, CALIBRATE }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

@Composable
private fun App() {
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            AppNav()
        }
    }
}

@Composable
private fun AppNav() {
    val context = LocalContext.current
    var screen by remember { mutableStateOf(Screen.WORKOUT) }

    var audioUri by remember { mutableStateOf(loadSelectedAudioUri(context)) }
    var cues by remember(audioUri) { mutableStateOf(loadCues(context, audioUri)) }

    val player = rememberExoPlayer(audioUri)

    when (screen) {
        Screen.WORKOUT -> WorkoutScreen(
            audioUri = audioUri,
            onPickAudio = { uri ->
                audioUri = uri
                saveSelectedAudioUri(context, uri)
                cues = loadCues(context, uri)
            },
            cues = cues,
            player = player,
            onGoCalibrate = { screen = Screen.CALIBRATE }
        )

        Screen.CALIBRATE -> CalibrationScreen(
            audioUri = audioUri,
            player = player,
            initialCues = cues,
            onSave = { newCues ->
                saveCues(context, audioUri, newCues)
                cues = newCues
                screen = Screen.WORKOUT
            },
            onBack = { screen = Screen.WORKOUT }
        )
    }
}

/* ------------------------- Media3 Player ------------------------- */
/**
 * Media3 ExoPlayer is the default Media3 Player implementation for audio/video playback. 
 */
@Composable
private fun rememberExoPlayer(audioUri: Uri?): ExoPlayer {
    val context = LocalContext.current

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = false
        }
    }

    LaunchedEffect(audioUri) {
        player.stop()
        player.clearMediaItems()
        if (audioUri != null) {
            player.setMediaItem(MediaItem.fromUri(audioUri))
            player.prepare()
            player.pause()
            player.seekTo(0)
        }
    }

    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    return player
}

/* ------------------------- Workout Screen ------------------------- */

@Composable
private fun WorkoutScreen(
    audioUri: Uri?,
    onPickAudio: (Uri) -> Unit,
    cues: List<Cue>,
    player: ExoPlayer,
    onGoCalibrate: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current

    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {}
            onPickAudio(uri)
        }
    }

    // TTS
    var voiceEnabled by remember { mutableStateOf(false) }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(Unit) {
        val engine = TextToSpeech(context) {}
        engine.language = Locale.ENGLISH
        tts = engine
        onDispose { engine.shutdown() }
    }
