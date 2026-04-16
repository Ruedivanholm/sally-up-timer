package com.example.sallytimer

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.max

enum class Command { UP, DOWN }

enum class ExerciseType(
    val label: String,
    val upText: String,
    val downText: String
) {
    PUSH_UP("Push-up", "UP", "DOWN"),
    SQUAT("Squat", "STAND", "SQUAT"),
    PLANK("Plank", "HIGH", "LOW")
}

data class Cue(val atMs: Long, val command: Command)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

@Composable
fun App() {
    MaterialTheme { Screen() }
}

@Composable
fun Screen() {
    val context = LocalContext.current
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(Unit) {
        val engine = TextToSpeech(context) { it }
        engine.language = Locale.ENGLISH
        tts = engine
        onDispose { engine.shutdown() }
    }

    fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, text)
    }

    val cues = listOf(
        Cue(0, Command.DOWN),
        Cue(8000, Command.UP),
        Cue(12000, Command.DOWN),
        Cue(18000, Command.UP)
        // (placeholder – still works)
    )

    var running by remember { mutableStateOf(false) }
    var elapsed by remember { mutableStateOf(0L) }
    var idx by remember { mutableStateOf(0) }
    var cmd by remember { mutableStateOf(Command.DOWN) }
    var reps by remember { mutableStateOf(0) }
    var countdown by remember { mutableStateOf(3) }
    var inCountdown by remember { mutableStateOf(false) }
    var exercise by remember { mutableStateOf(ExerciseType.PUSH_UP) }
    var flash by remember { mutableStateOf(Color.Transparent) }
    val bg by animateColorAsState(flash)

    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        val start = System.currentTimeMillis()
        while (running) {
            elapsed = System.currentTimeMillis() - start
            if (idx < cues.size && elapsed >= cues[idx].atMs) {
                cmd = cues[idx].command
                if (cmd == Command.UP) reps++
                flash = if (cmd == Command.UP) Color(0x334CAF50) else Color(0x33F44336)
                speak(cmd.name)
                delay(200)
                flash = Color.Transparent
                idx++
            }
            delay(40)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bg)
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                if (cmd == Command.UP) exercise.upText else exercise.downText,
                fontSize = 100.sp,
                fontWeight = FontWeight.Black
            )
            Spacer(Modifier.height(12.dp))
            Text("Reps: $reps", fontSize = 20.sp)
        }

        Button(
            modifier = Modifier.align(Alignment.BottomCenter),
            onClick = {
                if (!running) {
                    idx = 0
                    reps = 0
                    inCountdown = true
                }
            }
        ) {
            Text("Start")
        }

        if (inCountdown) {
            LaunchedEffect(Unit) {
                for (i in 3 downTo 1) {
                    countdown = i
                    speak(i.toString())
                    delay(1000)
                }
                inCountdown = false
                running = true
            }
        }
    }
}
