package com.example.sallytimer

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import kotlinx.coroutines.delay
import java.util.Locale

private const val VIDEO_ID = "koMp3ei4xJw" // the exact version you linked
private const val PREFS = "sally_prefs"
private const val KEY_CUES = "cues_v1"

enum class Command { UP, DOWN }
enum class ExerciseType(val label: String, val upText: String, val downText: String) {
    PUSH_UP("Push-up", "UP", "DOWN"),
    SQUAT("Squat", "STAND", "SQUAT"),
    PLANK("Plank", "HIGH", "LOW")
}

data class Cue(val atMs: Long, val cmd: Command)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SallyApp() }
    }
}

@Composable
fun SallyApp() {
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            AppNav()
        }
    }
}

private enum class Screen { WORKOUT, CALIBRATE }

@Composable
private fun AppNav() {
    val context = LocalContext.current
    var screen by remember { mutableStateOf(Screen.WORKOUT) }

    val cues = remember { mutableStateOf(loadCues(context)) }

    when (screen) {
        Screen.WORKOUT -> WorkoutScreen(
            cues = cues.value,
            onGoCalibrate = { screen = Screen.CALIBRATE },
            onReload = { cues.value = loadCues(context) }
        )

        Screen.CALIBRATE -> CalibrationScreen(
            onDone = {
                cues.value = loadCues(context)
                screen = Screen.WORKOUT
            }
        )
    }
}

@Composable
private fun CalibrationScreen(onDone: () -> Unit) {
    val context = LocalContext.current

    var ytPlayer by remember { mutableStateOf<YouTubePlayer?>(null) }
    var currentSec by remember { mutableStateOf(0f) }
    var playing by remember { mutableStateOf(false) }

    var captured by remember { mutableStateOf(listOf<Cue>()) }

    val upCount = captured.count { it.cmd == Command.UP }
    val downCount = captured.count { it.cmd == Command.DOWN }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Calibration (tap along once)", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text("Play the YouTube version, then tap UP/DOWN when you hear/see the cue. Save when done (aim: 30 UPs).")

        // YouTube player view
        AndroidView(
            factory = { ctx ->
                YouTubePlayerView(ctx).apply {
                    addYouTubePlayerListener(object : AbstractYouTubePlayerListener() {
                        override fun onReady(player: YouTubePlayer) {
                            ytPlayer = player
                            player.cueVideo(VIDEO_ID, 0f)
                        }

                        override fun onCurrentSecond(player: YouTubePlayer, second: Float) {
                            currentSec = second
                        }
                    })
                }
            },
            modifier = Modifier.fillMaxWidth().height(210.dp)
        )

        Text("Current time: ${"%.1f".format(currentSec)}s   |   Captured: UP $upCount / DOWN $downCount")

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                modifier = Modifier.weight(1f),
                onClick = {
                    val p = ytPlayer ?: return@Button
                    if (!playing) { p.play(); playing = true } else { p.pause(); playing = false }
                }
            ) { Text(if (playing) "Pause" else "Play") }

            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = { captured = captured.dropLast(1) },
                enabled = captured.isNotEmpty()
            ) { Text("Undo") }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                onClick = {
                    captured = captured + Cue((currentSec * 1000).toLong(), Command.UP)
                }
            ) { Text("Mark UP") }

            Button(
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                onClick = {
                    captured = captured + Cue((currentSec * 1000).toLong(), Command.DOWN)
                }
            ) { Text("Mark DOWN") }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = {
                    captured = emptyList()
                    ytPlayer?.seekTo(0f)
                }
            ) { Text("Reset") }

            Button(
                modifier = Modifier.weight(1f),
                enabled = captured.isNotEmpty(),
                onClick = {
                    // Sort + save
                    val sorted = captured.sortedBy { it.atMs }
                    saveCues(context, sorted)
                    onDone()
                }
            ) { Text("Save & Exit") }
        }

        Divider()
        Text("Tip: Most people start in DOWN. Try to capture a DOWN at the start (0–2s).")
    }
}

@Composable
private fun WorkoutScreen(
    cues: List<Cue>,
    onGoCalibrate: () -> Unit,
    onReload: () -> Unit
) {
    val context = LocalContext.current

    // TTS
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var voiceEnabled by remember { mutableStateOf(false) } // default off because music is playing
    DisposableEffect(Unit) {
        val engine = TextToSpeech(context) { }
        engine.language = Locale.ENGLISH
        tts = engine
        onDispose { engine.shutdown() }
    }
    fun speak(text: String) { if (voiceEnabled) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sally") }

    var exercise by remember { mutableStateOf(ExerciseType.PUSH_UP) }

    var ytPlayer by remember { mutableStateOf<YouTubePlayer?>(null) }
    var currentSec by remember { mutableStateOf(0f) }

    var started by remember { mutableStateOf(false) }
    var inCountdown by remember { mutableStateOf(false) }
    var countdown by remember { mutableStateOf(3) }

    var currentCommand by remember { mutableStateOf(Command.DOWN) }
    var reps by remember { mutableStateOf(0) }
    var lastCueIndex by remember { mutableStateOf(-1) }

    // Flash color
    var flash by remember { mutableStateOf(Color.Transparent) }
    val bg by animateColorAsState(flash, label = "flash")

    val hasCues = cues.isNotEmpty()

    suspend fun flashNow(cmd: Command) {
        flash = if (cmd == Command.UP) Color(0x334CAF50) else Color(0x33F44336)
        delay(180)
        flash = Color.Transparent
    }

    // Update command based on YouTube current second
    LaunchedEffect(currentSec, started, hasCues) {
        if (!started || !hasCues) return@LaunchedEffect

        val nowMs = (currentSec * 1000).toLong()
        val idx = cues.indexOfLast { it.atMs <= nowMs }
        if (idx != -1 && idx != lastCueIndex) {
            val cue = cues[idx]
            currentCommand = cue.cmd
            if (cue.cmd == Command.UP) reps += 1
            speak(if (cue.cmd == Command.UP) "Up" else "Down")
            flashNow(cue.cmd)
            lastCueIndex = idx
        }
    }

    // Countdown flow
    LaunchedEffect(inCountdown) {
        if (!inCountdown) return@LaunchedEffect
        for (i in 3 downTo 1) {
            countdown = i
            delay(1000)
        }
        inCountdown = false
        started = true
        reps = 0
        lastCueIndex = -1
        ytPlayer?.seekTo(0f)
        ytPlayer?.play()
    }

    Box(
        modifier = Modifier.fillMaxSize().background(bg).padding(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Sally Up (YouTube sync)", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("Reps: $reps", fontWeight = FontWeight.Bold)
            }

            // YouTube player visible (avoid “audio-only” separation concerns)
            AndroidView(
                factory = { ctx ->
                    YouTubePlayerView(ctx).apply {
                        addYouTubePlayerListener(object : AbstractYouTubePlayerListener() {
                            override fun onReady(player: YouTubePlayer) {
                                ytPlayer = player
                                player.cueVideo(VIDEO_ID, 0f)
                            }

                            override fun onCurrentSecond(player: YouTubePlayer, second: Float) {
                                currentSec = second
                            }
                        })
                    }
                },
                modifier = Modifier.fillMaxWidth().height(210.dp)
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                ExerciseDropdown(exercise) { exercise = it }
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = voiceEnabled, onCheckedChange = { voiceEnabled = it })
                    Spacer(Modifier.width(8.dp))
                    Text("Voice")
                }
            }
        }

        // Big center display
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            if (!hasCues) {
                Text("No cue timing saved.", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Text("Tap Calibrate to record UP/DOWN timing for this YouTube version.")
            } else if (inCountdown) {
                Text(countdown.toString(), fontSize = 120.sp, fontWeight = FontWeight.Black)
            } else {
                val label = if (currentCommand == Command.UP) exercise.upText else exercise.downText
                Text(label, fontSize = 110.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(8.dp))
                Text(exercise.label, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // Bottom buttons
        Row(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                modifier = Modifier.weight(1f),
                onClick = {
                    if (!hasCues) return@Button
                    if (!started) inCountdown = true
                    else ytPlayer?.pause().also { started = false } // pause stops updates
                },
                enabled = hasCues
            ) { Text(if (!started) "Start (3-2-1)" else "Pause") }

            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = {
                    started = false
                    inCountdown = false
                    reps = 0
                    lastCueIndex = -1
                    ytPlayer?.pause()
                    ytPlayer?.seekTo(0f)
                }
            ) { Text("Reset") }

            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = {
                    onReload()
                    onGoCalibrate()
                }
            ) { Text("Calibrate") }
        }
    }
}

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

/**
 * Simple serialization: "ms:UP;ms:DOWN;..."
 */
private fun saveCues(context: Context, cues: List<Cue>) {
    val s = cues.joinToString(";") { "${it.atMs}:${it.cmd.name}" }
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_CUES, s)
        .apply()
}

private fun loadCues(context: Context): List<Cue> {
    val s = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CUES, null) ?: return emptyList()
    return s.split(";")
        .mapNotNull { part ->
            val bits = part.split(":")
            if (bits.size != 2) return@mapNotNull null
            val ms = bits[0].toLongOrNull() ?: return@mapNotNull null
            val cmd = runCatching { Command.valueOf(bits[1]) }.getOrNull() ?: return@mapNotNull null
            Cue(ms, cmd)
        }
        .sortedBy { it.atMs }
}
