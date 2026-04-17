package com.example.sallytimer

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
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

// ✅ Your chosen YouTube version
private const val VIDEO_ID = "koMp3ei4xJw"
private const val YT_URL = "https://www.youtube.com/watch?v=$VIDEO_ID"

// Prefs for saved cues
private const val PREFS = "sally_prefs"
private const val KEY_CUES = "cues_v3"

// Important for embed identity / referrer checks in some WebView embeds
// (does not need to be a real site, but should be a valid https origin string)
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

/* ----------------------------- Activity ----------------------------- */

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SallyApp() }
    }
}

/* ----------------------------- App Shell ---------------------------- */

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

/* ------------------------- Workout Screen -------------------------- */

@Composable
private fun WorkoutScreen(
    cues: List<Cue>,
    onGoCalibrate: () -> Unit,
    onReloadCues: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current

    // Keep screen awake during workout
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // TTS
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

    // Player state
    var playerReady by remember { mutableStateOf(false) }
    var currentSec by remember { mutableStateOf(0f) }
    var playerError by remember { mutableStateOf<Int?>(null) }

    // Workout state
    var exercise by remember { mutableStateOf(ExerciseType.PUSH_UP) }
    var running by remember { mutableStateOf(false) }
    var inCountdown by remember { mutableStateOf(false) }
    var countdown by remember { mutableStateOf(3) }

    var reps by remember { mutableStateOf(0) }
    var currentCommand by remember { mutableStateOf(Command.DOWN) }
    var lastCueIndex by remember { mutableStateOf(-1) }

    // Flash background on cue changes
    var flashColor by remember { mutableStateOf(Color.Transparent) }
    val animatedFlash by animateColorAsState(flashColor, label = "flash")

    val hasCues = cues.isNotEmpty()

    suspend fun flash(cmd: Command) {
        flashColor = if (cmd == Command.UP) Color(0x334CAF50) else Color(0x33F44336)
        delay(180)
        flashColor = Color.Transparent
    }

    // Countdown -> start playback manually
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

        // Manual start: ensure we start from 0
        WebViewController.seekTo(0f)
        delay(200)
        WebViewController.play()
    }

    // Drive cues from currentSec (only while running)
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
                    "Sally Up Timer",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text("Reps: $reps", fontWeight = FontWeight.Bold)
            }

            // ✅ 16:9 frame: video matches app frame
            YouTubeIFrameWebView(
                videoId = VIDEO_ID,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16 / 9f),
                onReady = { playerReady = true },
                onCurrentSecond = { currentSec = it },
                onPlayerError = { playerError = it }
            )

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

            if (playerError != null) {
                Text(
                    "YouTube error: $playerError (embed may be blocked).",
                    color = MaterialTheme.colorScheme.error
                )
                Button(onClick = { openYouTube(context) }) { Text("Open in YouTube") }
            }
        }

        // Big center display
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when {
                inCountdown -> {
                    Text(countdown.toString(), fontSize = 120.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.height(8.dp))
                    Text("Get ready…")
                }
                !hasCues -> {
                    Text("No timing saved yet", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("Tap Calibrate to record UP/DOWN timing.")
                }
                else -> {
                    val label = if (currentCommand == Command.UP) exercise.upText else exercise.downText
                    Text(label, fontSize = 110.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.height(10.dp))
                    Text(exercise.label, style = MaterialTheme.typography.titleLarge)
                }
            }
        }

        // Bottom controls
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                modifier = Modifier.weight(1f),
                enabled = playerReady && !inCountdown,
                onClick = {
                    if (!running) {
                        // ✅ Manual start
                        inCountdown = true
                    } else {
                        // Pause workout + video
                        running = false
                        WebViewController.pause()
                    }
                }
            ) {
                Text(if (!running) "Start (3-2-1)" else "Pause")
            }

            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = {
                    running = false
                    inCountdown = false
                    reps = 0
                    lastCueIndex = -1

                    // Reset video to start, keep paused (manual start)
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

/* ----------------------- Calibration Screen ------------------------ */

@Composable
private fun CalibrationScreen(onDone: () -> Unit) {
    val context = LocalContext.current

    var playerReady by remember { mutableStateOf(false) }
    var currentSec by remember { mutableStateOf(0f) }
    var playerError by remember { mutableStateOf<Int?>(null) }

    var captured by remember { mutableStateOf(listOf<Cue>()) }
    val upCount = captured.count { it.cmd == Command.UP }
    val downCount = captured.count { it.cmd == Command.DOWN }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Calibration", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text("Press Play, then tap Mark UP/DOWN on each cue. Save when done. Aim for 30 UP taps.")

        YouTubeIFrameWebView(
            videoId = VIDEO_ID,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16 / 9f),
            onReady = { playerReady = true },
            onCurrentSecond = { currentSec = it },
            onPlayerError = { playerError = it }
        )

        if (playerError != null) {
            Text("YouTube error: $playerError (embed may be blocked).", color = MaterialTheme.colorScheme.error)
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
                enabled = playerReady,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                onClick = { captured = captured + Cue((currentSec * 1000).toLong(), Command.UP) }
            ) { Text("Mark UP") }

            Button(
                modifier = Modifier.weight(1f),
                enabled = playerReady,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                onClick = { captured = captured + Cue((currentSec * 1000).toLong(), Command.DOWN) }
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
                    saveCues(context, captured.sortedBy { it.atMs })
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

/* ------------------- YouTube IFrame WebView ----------------------- */

// Simple controller so buttons can play/pause/seek the currently attached WebView player
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
                    autoplay: 0,                 // ✅ MANUAL START (no autoplay)
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

                      // ✅ BONUS: park the video at 0 and force pause
                      try { e.target.seekTo(0, true); } catch (err) {}
                      try { e.target.pauseVideo(); } catch (err) {}

                      // Time callback ~5x/sec
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
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

                // Optional: more reliable on some devices
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                webChromeClient = WebChromeClient()
                webViewClient = WebViewClient()

                addJavascriptInterface(object {
                    @JavascriptInterface fun onReady() { onReady() }
                    @JavascriptInterface fun onTime(seconds: Float) { onCurrentSecond(seconds) }
                    @JavascriptInterface fun onError(code: Int) { onPlayerError(code) }
                }, "AndroidBridge")

                // Base URL provides a valid origin context for the iframe
                loadDataWithBaseURL(BASE_URL, html, "text/html", "UTF-8", null)
            }
        },
        onRelease = {
            WebViewController.detach()
            it.destroy()
        }
    )
}

/* ---------------------- Small UI helpers -------------------------- */

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
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(YT_URL)))
}

/* ----------------------- Save/Load cues --------------------------- */

private fun saveCues(context: Context, cues: List<Cue>) {
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
