package com.example.musicurlhandler.model

data class TrackResponse(
    val name: String,
    val artists: List<Artist>
)

data class Artist(
    val name: String
)
