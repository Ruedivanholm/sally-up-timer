package com.example.sallytimer

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.max

private const val VIDEO_ID = "koMp3ei4xJw"
private const val YT_URL = "https://www.youtube.com/watch?v=$VIDEO_ID"
private const val PREFS = "sally_prefs"
private const val KEY_CUES = "cues_v2"

// Set a realistic base URL for the WebView so origin/referrer isn't "null".
// This is commonly required for modern YouTube embeds that enforce embedder identity checks. [1](https://rokslide.com/forums/threads/sally-push-up-challenge.250690/)[2](https://www.youtube.com/watch?v=4xqdCYXOdvs)
private const val BASE_URL = "https://example.com"

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
        setContent { SallyApp() }
    }
}

@Composable
private fun SallyApp() {
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
    var cues by remember { mutableStateOf(loadCues(context)) }

    when (screen) {
        Screen.WORKOUT -> WorkoutScreen(
            cues = cues,
            onGoCalibrate = { screen = Screen.CALIBRATE },
            onReloadCues = { cues = loadCues(context) }
        )

        Screen.CALIBRATE -> CalibrationScreen(
            onDone = {
                cues = loadCues(context)
                screen = Screen.WORKOUT
            }
        )
    }
}

/* ----------------------------- WORKOUT SCREEN ----------------------------- */

@Composable
private fun WorkoutScreen(
    cues: List<Cue>,
    onGoCalibrate: () -> Unit,
    onReloadCues: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current

    // Keep screen on (gym-friendly)
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // TTS for Up/Down (toggleable)
    var voiceEnabled by remember { mutableStateOf(false) }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(Unit) {
        val engine = TextToSpeech(context) { }
        engine.language = Locale.ENGLISH
        tts = engine
        onDispose { engine.shutdown() }
    }
    fun speak(text: String) {
        if (!voiceEnabled) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sally_${System.currentTimeMillis()}")
    }

    var exercise by remember { mutableStateOf(ExerciseType.PUSH_UP) }

    // WebView player state
    var currentSec by remember { mutableStateOf(0f) }
    var playerReady by remember { mutableStateOf(false) }
    var lastPlayerError by remember { mutableStateOf<Int?>(null) }

    // Countdown + control state
    var inCountdown by remember { mutableStateOf(false) }
    var countdown by remember { mutableStateOf(3) }
    var running by remember { mutableStateOf(false) }

    // Cue-driven state
    var reps by remember { mutableStateOf(0) }
    var currentCommand by remember { mutableStateOf(Command.DOWN) }
    var lastCueIndex by remember { mutableStateOf(-1) }

    // Flash background on cue change
    var flashColor by remember { mutableStateOf(Color.Transparent) }
    val animatedFlash by animateColorAsState(flashColor, label = "flash")

    // If no cues saved, force calibrate suggestion
    val hasCues = cues.isNotEmpty()

    suspend fun flash(cmd: Command) {
        flashColor = if (cmd == Command.UP) Color(0x334CAF50) else Color(0x33F44336)
        delay(180)
        flashColor = Color.Transparent
    }

    // Handle countdown start
    LaunchedEffect(inCountdown) {
        if (!inCountdown) return@LaunchedEffect
        for (i in 3 downTo 1) {
            countdown = i
            // Optional spoken countdown (leave off by default; you can turn voice on)
            delay(1000)
        }
        inCountdown = false
        running = true
        reps = 0
        lastCueIndex = -1
        // Seek to start and play (JS calls)
        WebViewController.seekTo(0f)
        WebViewController.play()
    }

    // Drive cues based on currentSec (from the WebView IFrame API clock)
    LaunchedEffect(currentSec, running, hasCues) {
        if (!running || !hasCues) return@LaunchedEffect

        val nowMs = (currentSec * 1000).toLong()
        val idx = cues.indexOfLast { it.atMs <= nowMs }

        if (idx != -1 && idx != lastCueIndex) {
            val cue = cues[idx]
            currentCommand = cue.cmd
            if (cue.cmd == Command.UP) reps += 1

            speak(if (cue.cmd == Command.UP) exercise.upVoice else exercise.downVoice)
            flash(cue.cmd)

            lastCueIndex = idx
        }
    }

    // Build UI
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(animatedFlash)
            .padding(16.dp)
    ) {
        // Top section
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Sally Up Timer (YouTube sync)",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text("Reps: $reps", fontWeight = FontWeight.Bold)
            }

            // YouTube player (WebView + IFrame API)
            YouTubeIFrameWebView(
                videoId = VIDEO_ID,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(210.dp),
                onReady = { playerReady = true },
                onCurrentSecond = { sec -> currentSec = sec },
                onPlayerError = { code -> lastPlayerError = code },
            )

            // Controls row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ExerciseDropdown(current = exercise, onSelect = { exercise = it })

                Spacer(Modifier.weight(1f))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = voiceEnabled, onCheckedChange = { voiceEnabled = it })
                    Spacer(Modifier.width(8.dp))
                    Text("Voice")
                }
            }

            // If embed fails, show a clear fallback
            if (lastPlayerError != null) {
                Text(
                    "Player error: ${lastPlayerError}. If you see 'Watch on YouTube / Error 153', use the button below.",
                    color = MaterialTheme.colorScheme.error
                )
                Button(onClick = { openYouTube(context) }) {
                    Text("Open in YouTube")
                }
            }
        }

        // Big center display
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when {
                !hasCues -> {
                    Text("No timing saved yet", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("Tap Calibrate to record UP/DOWN timing for this video.")
                }
                inCountdown -> {
                    Text(countdown.toString(), fontSize = 120.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.height(8.dp))
                    Text("Get ready…")
                }
                else -> {
                    val label = if (currentCommand == Command.UP) exercise.upText else exercise.downText
                    Text(label, fontSize = 110.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.height(10.dp))
                    Text(exercise.label, style = MaterialTheme.typography.titleLarge)
                }
            }
        }

        // Bottom buttons
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                modifier = Modifier.weight(1f),
                enabled = hasCues && playerReady && !inCountdown,
                onClick = {
                    if (!running) {
                        // fresh start => countdown
                        inCountdown = true
                    } else {
                        // pause
                        running = false
                        WebViewController.pause()
                    }
                }
            ) { Text(if (!running) "Start (3-2-1)" else "Pause") }

            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = {
                    running = false
                    inCountdown = false
                    reps = 0
                    lastCueIndex = -1
                    WebViewController.pause()
                    WebViewController.seekTo(0f)
                }
            ) { Text("Reset") }

            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = {
                    onReloadCues()
                    onGoCalibrate()
                }
            ) { Text("Calibrate") }
        }
    }
}

/* --------------------------- CALIBRATION SCREEN --------------------------- */

@Composable
private fun CalibrationScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    var currentSec by remember { mutableStateOf(0f) }
    var playerReady by remember { mutableStateOf(false) }
    var lastPlayerError by remember { mutableStateOf<Int?>(null) }

    var captured by remember { mutableStateOf(listOf<Cue>()) }
    val upCount = captured.count { it.cmd == Command.UP }
    val downCount = captured.count { it.cmd == Command.DOWN }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Calibration", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            "Press Play, then tap Mark UP/DOWN on each cue. Save when done. Aim for 30 UP taps.",
            style = MaterialTheme.typography.bodyMedium
        )

        YouTubeIFrameWebView(
            videoId = VIDEO_ID,
            modifier = Modifier.fillMaxWidth().height(210.dp),
            onReady = { playerReady = true },
            onCurrentSecond = { currentSec = it },
            onPlayerError = { code -> lastPlayerError = code }
        )

        if (lastPlayerError != null) {
            Text(
                "Player error: ${lastPlayerError}. If you see Error 153, embed is blocked. Use Open in YouTube and calibrate manually by time.",
                color = MaterialTheme.colorScheme.error
            )
            Button(onClick = { openYouTube(context) }) { Text("Open in YouTube") }
        }

        Text("Time: ${"%.1f".format(currentSec)}s   |   UP: $upCount   DOWN: $downCount")

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                modifier = Modifier.weight(1f),
                enabled = playerReady,
                onClick = { WebViewController.play() }
            ) { Text("Play") }

            Button(
                modifier = Modifier.weight(1f),
                enabled = playerReady,
                onClick = { WebViewController.pause() }
            ) { Text("Pause") }

            OutlinedButton(
                modifier = Modifier.weight(1f),
                enabled = playerReady,
                onClick = { WebViewController.seekTo(0f) }
            ) { Text("Restart") }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                enabled = playerReady,
                onClick = {
                    captured = captured + Cue((currentSec * 1000).toLong(), Command.UP)
                }
            ) { Text("Mark UP") }

            Button(
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                enabled = playerReady,
                onClick = {
                    captured = captured + Cue((currentSec * 1000).toLong(), Command.DOWN)
                }
            ) { Text("Mark DOWN") }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                modifier = Modifier.weight(1f),
                enabled = captured.isNotEmpty(),
                onClick = { captured = captured.dropLast(1) }
            ) { Text("Undo") }

            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = { captured = emptyList() }
            ) { Text("Clear") }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                modifier = Modifier.weight(1f),
                enabled = captured.isNotEmpty(),
                onClick = {
                    val sorted = captured.sortedBy { it.atMs }
                    saveCues(context, sorted)
                    onDone()
                }
            ) { Text("Save & Exit") }

            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = { onDone() }
            ) { Text("Back") }
        }
    }
}

/* -------------------------- YOUTUBE IFRAME WEBVIEW ------------------------- */

/**
 * WebView + YouTube IFrame API approach.
 * This allows us to set a base URL + referrer policy which can mitigate embedder identity/config errors
 * seen in modern YouTube embeds (e.g. Error 153 / 152-4). [1](https://rokslide.com/forums/threads/sally-push-up-challenge.250690/)[2](https://www.youtube.com/watch?v=4xqdCYXOdvs)[3](https://www.youtube.com/watch?v=ngWF3dqBI44)
 *
 * Note: This uses YouTube’s IFrame API, which is designed for embedded playback controlled via JS. [3](https://www.youtube.com/watch?v=ngWF3dqBI44)
 */
private object WebViewController {
    private var webView: WebView? = null
    fun attach(wv: WebView) { webView = wv }
    fun detach() { webView = null }

    fun play() {
        webView?.evaluateJavascript("try{player && player.playVideo();}catch(e){}", null)
    }

    fun pause() {
        webView?.evaluateJavascript("try{player && player.pauseVideo();}catch(e){}", null)
    }

    fun seekTo(seconds: Float) {
        webView?.evaluateJavascript("try{player && player.seekTo($seconds,true);}catch(e){}", null)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun YouTubeIFrameWebView(
    videoId: String,
    modifier: Modifier = Modifier,
    onReady: () -> Unit = {},
    onCurrentSecond: (Float) -> Unit = {},
    onPlayerError: (Int) -> Unit = {}
) {
    val html = remember(videoId) {
        // Use YouTube IFrame API script. [3](https://www.youtube.com/watch?v=ngWF3dqBI44)
        """
        <!DOCTYPE html>
        <html>
          <head>
            <meta name="referrer" content="strict-origin-when-cross-origin">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <style>
              html, body { margin:0; padding:0; background:#000; height:100%; overflow:hidden; }
              #player { position:absolute; top:0; left:0; right:0; bottom:0; }
            </style>
          </head>
          <body>
            <div id="player"></div>

            <script src="https://www.youtube.com/iframe_api"></script>
            <script>
              var player;

              function onYouTubeIframeAPIReady() {
                player = new YT.Player('player', {
                  videoId: '$videoId',
                  playerVars: {
                    autoplay: 1,
                    controls: 1,
                    playsinline: 1,
                    enablejsapi: 1,
                    rel: 0,
                    origin: '$BASE_URL',
                    widget_referrer: '$BASE_URL'
                  },
                  events: {
                    onReady: function(e) {
                      try { AndroidBridge.onReady(); } catch (err) {}
                      e.target.playVideo();

                      setInterval(function() {
                        try {
                          var t = player.getCurrentTime();
                          AndroidBridge.onTime(t);
                        } catch (err) {}
                      }, 200);
                    },
                    onError: function(err) {
                      try { AndroidBridge.onError(err.data); } catch (e) {}
                    }
                  }
                });
              }
            </script>
          </body>
        </html>
        """.trimIndent()
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                WebViewController.attach(this)

                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true

                webChromeClient = WebChromeClient()
                webViewClient = WebViewClient()

                addJavascriptInterface(object {
                    @JavascriptInterface fun onReady() { onReady() }
                    @JavascriptInterface fun onTime(seconds: Float) { onCurrentSecond(seconds) }
                    @JavascriptInterface fun onError(code: Int) { onPlayerError(code) }
                }, "AndroidBridge")

                // KEY: load with a base URL so the iframe has a meaningful origin context. [1](https://rokslide.com/forums/threads/sally-push-up-challenge.250690/)[2](https://www.youtube.com/watch?v=4xqdCYXOdvs)
                loadDataWithBaseURL(BASE_URL, html, "text/html", "UTF-8", null)
            }
        },
        onRelease = {
            WebViewController.detach()
            it.destroy()
        }
    )
}

/* ---------------------------- SMALL UI HELPERS ---------------------------- */

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

private fun openYouTube(context: Context) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(YT_URL))
    context.startActivity(intent)
}

/* ------------------------- CUE SAVE/LOAD (Prefs) -------------------------- */

private fun saveCues(context: Context, cues: List<Cue>) {
    // Format: "ms:UP;ms:DOWN;..."
    val s = cues.joinToString(";") { "${it.atMs}:${it.cmd.name}" }
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_CUES, s)
        .apply()
}

private fun loadCues(context: Context): List<Cue> {
    val s = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CUES, null)
        ?: return emptyList()

    return s.split(";").mapNotNull { part ->
        val bits = part.split(":")
        if (bits.size != 2) return@mapNotNull null
        val ms = bits[0].toLongOrNull() ?: return@mapNotNull null
        val cmd = runCatching { Command.valueOf(bits[1]) }.getOrNull() ?: return@mapNotNull null
        Cue(ms, cmd)
    }.sortedBy { it.atMs }
}
