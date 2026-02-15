package com.example.musicurlhandler.network

import com.example.musicurlhandler.model.TrackResponse
import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path

interface SpotifyService {
    @GET("v1/tracks/{id}")
    fun getTrack(
        @Header("Authorization") authHeader: String,
        @Path("id") trackId: String
    ): Call<TrackResponse>
}
