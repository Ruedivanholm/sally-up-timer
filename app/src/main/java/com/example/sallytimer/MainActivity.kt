package com.example.sallytimer

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.max

private const val PREFS = "sally_step1_prefs"
private const val KEY_AUDIO_URI = "audio_uri"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Mp3PlayerApp() }
    }
}

@Composable
private fun Mp3PlayerApp() {
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Step1Screen()
        }
    }
}

/**
 * Holds MediaPlayer + all diagnostic state.
 * This avoids “silent failure” and makes it obvious if start() is being called / failing.
 */
private class PlayerHolder {
    private var mp: MediaPlayer? = null

    var prepared: Boolean = false
        private set

    var lastError: String? = null
        private set

    var lastEvent: String = "Pick MP3 to begin"
        private set

    fun isPlaying(): Boolean = mp?.isPlaying == true
    fun currentPositionMs(): Long = mp?.currentPosition?.toLong() ?: 0L
    fun durationMs(): Long = mp?.duration?.toLong() ?: 0L

    fun load(context: Context, uri: Uri?) {
        release()
        prepared = false
        lastError = null
        lastEvent = if (uri == null) "Pick MP3 to begin" else "Loading…"

        if (uri == null) return

        try {
            mp = MediaPlayer().apply {
                // Make sure Android treats this as MEDIA playback (helps Samsung quirks)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )

                setOnErrorListener { _, what, extra ->
                    prepared = false
                    lastError = "MediaPlayer error what=$what extra=$extra"
                    lastEvent = "Error"
                    true
                }

                setOnPreparedListener {
                    prepared = true
                    lastError = null
                    lastEvent = "Prepared ✓ (paused at start)"
                    pause()
                    seekTo(0)
                }

                setOnCompletionListener {
                    lastEvent = "Finished"
                }

                setDataSource(context, uri)
                prepareAsync()
            }
        } catch (e: Exception) {
            prepared = false
            lastError = "Load exception: ${e.javaClass.simpleName}: ${e.message}"
            lastEvent = "Error"
        }
    }

    /**
     * Returns true if we successfully started playback.
     */
    fun playFromStart(): Boolean {
        val p = mp
        if (p == null) {
            lastError = "Start requested but MediaPlayer is null"
            lastEvent = "Error"
            return false
        }
        if (!prepared) {
            lastError = "Start requested but not prepared yet"
            lastEvent = "Error"
            return false
        }

        return try {
            lastError = null
            lastEvent = "Starting…"
            p.seekTo(0)
            p.start()
            lastEvent = "Playing…"
            true
        } catch (e: Exception) {
            lastError = "Start exception: ${e.javaClass.simpleName}: ${e.message}"
            lastEvent = "Error"
            prepared = false
            false
        }
    }

    fun play(): Boolean {
        val p = mp
        if (p == null || !prepared) {
            lastError = "Resume requested but player not ready"
            lastEvent = "Error"
            return false
        }
        return try {
            lastError = null
            p.start()
            lastEvent = "Playing…"
            true
        } catch (e: Exception) {
            lastError = "Resume exception: ${e.javaClass.simpleName}: ${e.message}"
            lastEvent = "Error"
            false
        }
    }

    fun pause() {
        val p = mp ?: return
        if (p.isPlaying) {
            try {
                p.pause()
                lastEvent = "Paused"
            } catch (e: Exception) {
                lastError = "Pause exception: ${e.javaClass.simpleName}: ${e.message}"
                lastEvent = "Error"
            }
        }
    }

    fun resetToStartPaused() {
        val p = mp
        if (p == null || !prepared) {
            lastError = "Reset requested but player not ready"
            lastEvent = "Error"
