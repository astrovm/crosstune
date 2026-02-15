package com.example.musicurlhandler

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.musicurlhandler.ui.theme.MusicURLHandlerTheme
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class MainActivity : ComponentActivity() {

    private val client = OkHttpClient()
    private val ogDescriptionRegex = Regex("""<meta property=\"og:description\" content=\"([^\"]+)\"""")
    private val ogTitleRegex = Regex("""<meta property=\"og:title\" content=\"([^\"]+)\"""")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)

        setContent {
            MusicURLHandlerTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SpotifyLinkInput(
                        onLinkSubmit = ::handleSpotifyLink,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_VIEW) {
            val trackId = intent.data?.lastPathSegment
            if (trackId != null) {
                fetchSpotifyTrack(trackId)
            } else {
                println("Error: Could not extract track ID from URI")
            }
        }
    }

    private fun handleSpotifyLink(link: String) {
        val uri = Uri.parse(link)
        if (uri.host == "open.spotify.com" && uri.pathSegments.firstOrNull() == "track") {
            uri.lastPathSegment?.let(::fetchSpotifyTrack)
        }
    }

    private fun fetchSpotifyTrack(trackId: String) {
        val request = Request.Builder()
            .url("https://open.spotify.com/track/$trackId")
            .get()
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                println("Error: ${e.message}")
                e.printStackTrace()
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                try {
                    val html = response.body?.string() ?: return
                    val metadata = extractTrackAndArtist(html)

                    if (metadata != null) {
                        val (trackName, artistName) = metadata
                        println("Track: $trackName by $artistName")
                        runOnUiThread {
                            openYouTubeMusic(trackName, artistName)
                        }
                        return
                    }

                    val trackTitle = extractTrackTitle(html)
                    if (trackTitle != null) {
                        runOnUiThread {
                            openYouTubeMusic(trackTitle, "")
                        }
                        return
                    }

                    println("Error: Could not parse public Spotify metadata")
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    response.close()
                }
            }
        })
    }

    private fun extractTrackAndArtist(html: String): Pair<String, String>? {
        val description = ogDescriptionRegex.find(html)?.groupValues?.getOrNull(1) ?: return null
        val parts = description.split(" · ")
        if (parts.size < 2) return null

        val artistName = parts[0].trim()
        val trackName = parts[1].trim()

        if (artistName.isBlank() || trackName.isBlank()) return null
        return trackName to artistName
    }

    private fun extractTrackTitle(html: String): String? {
        return ogTitleRegex.find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun openYouTubeMusic(trackName: String, artistName: String) {
        val query = listOf(trackName, artistName)
            .filter { it.isNotBlank() }
            .joinToString(" ")

        val targetUri = Uri.parse("https://music.youtube.com/search?q=${Uri.encode(query)}")

        try {
            startActivity(Intent(Intent.ACTION_VIEW, targetUri))
        } catch (_: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, targetUri))
        }
    }
}

@Composable
private fun SpotifyLinkInput(
    onLinkSubmit: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var linkText by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        OutlinedTextField(
            value = linkText,
            onValueChange = { linkText = it },
            label = { Text("Paste Spotify Track URL") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        )

        Button(
            onClick = {
                if (linkText.isNotBlank()) {
                    onLinkSubmit(linkText)
                    linkText = ""
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Open in YouTube Music")
        }
    }
}
