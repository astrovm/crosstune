package com.example.musicurlhandler

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.example.musicurlhandler.model.TrackResponse
import com.example.musicurlhandler.network.SpotifyService
import com.example.musicurlhandler.ui.theme.MusicURLHandlerTheme
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class MainActivity : ComponentActivity() {

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
        val credentials = "27cd528dad064d1cb4e31c8d103f9689:20116ca336244f2984a7eacb74c09f99"
        val auth = "Basic " + Base64.encodeToString(credentials.toByteArray(), Base64.NO_WRAP)

        val client = OkHttpClient()
        val requestBody = "grant_type=client_credentials"
            .toRequestBody("application/x-www-form-urlencoded".toMediaType())

        val tokenRequest = Request.Builder()
            .url("https://accounts.spotify.com/api/token")
            .post(requestBody)
            .addHeader("Authorization", auth)
            .build()

        client.newCall(tokenRequest).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                e.printStackTrace()
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                try {
                    val responseBody = response.body?.string() ?: return
                    val accessToken = JSONObject(responseBody).getString("access_token")

                    val service = Retrofit.Builder()
                        .baseUrl("https://api.spotify.com/")
                        .addConverterFactory(GsonConverterFactory.create())
                        .build()
                        .create(SpotifyService::class.java)

                    service.getTrack("Bearer $accessToken", trackId)
                        .enqueue(object : Callback<TrackResponse> {
                            override fun onResponse(
                                call: Call<TrackResponse>,
                                response: Response<TrackResponse>
                            ) {
                                if (!response.isSuccessful) {
                                    println("Error: ${response.code()} - ${response.message()}")
                                    return
                                }

                                val track = response.body() ?: return
                                val artistName = track.artists.joinToString(", ") { it.name }
                                println("Track: ${track.name} by $artistName")
                                runOnUiThread {
                                    openYouTubeMusic(track.name, artistName)
                                }
                            }

                            override fun onFailure(call: Call<TrackResponse>, t: Throwable) {
                                println("Error: ${t.message}")
                                t.printStackTrace()
                            }
                        })
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    response.close()
                }
            }
        })
    }

    private fun openYouTubeMusic(trackName: String, artistName: String) {
        val query = Uri.encode("$trackName $artistName")
        val targetUri = Uri.parse("https://music.youtube.com/search?q=$query")

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
