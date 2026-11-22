package com.astrovm.crosstune

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DestinationStoreTest {

    private val preferences = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("destinations_test", Context.MODE_PRIVATE)
    // No patched builds installed, unless a test says so.
    private val store = DestinationStore(preferences) { false }
    private val deezer = Destination.Service(MusicService.DEEZER)

    @Test
    fun aBuildIsToldFromItsServicesAppByAWordOfItsOwn() {
        assertEquals("TV", MusicService.buildName(MusicService.YOUTUBE_TV))
        assertEquals("ReVanced", MusicService.buildName("app.revanced.android.youtube"))
        assertEquals("RVX", MusicService.buildName("app.rvx.android.apps.youtube.music"))
        assertEquals("Morphe", MusicService.buildName("app.morphe.android.youtube"))
        // One no list knows goes by its patcher's part of the package.
        assertEquals("Piko", MusicService.buildName("app.piko.android.youtube"))
    }

    @Test
    fun frontendAppsAreOfferedOnlyWhenInstalledAndWebFrontendsOnTheirSite() {
        val onlyNewPipe = DestinationStore(preferences) { it == "org.schabi.newpipe" }
        val alternatives = onlyNewPipe.allDestinations().filterIsInstance<Destination.Alternative>()
        assertEquals(
            listOf(
                Destination.Alternative(Frontend.NEWPIPE),
                // NewPipe also plays SoundCloud and Bandcamp.
                Destination.Alternative(Frontend.NEWPIPE_SOUNDCLOUD),
                Destination.Alternative(Frontend.NEWPIPE_BANDCAMP),
                Destination.Alternative(Frontend.INVIDIOUS, "https://yewtu.be"),
                Destination.Alternative(Frontend.PIPED, "https://piped.video")
            ),
            alternatives
        )

        assertTrue(onlyNewPipe.setInstance(Frontend.INVIDIOUS, " inv.example.org/feed/popular "))
        assertEquals("https://inv.example.org", onlyNewPipe.instance(Frontend.INVIDIOUS))
        assertFalse(onlyNewPipe.setInstance(Frontend.INVIDIOUS, "not a site"))
        assertEquals("https://inv.example.org", onlyNewPipe.instance(Frontend.INVIDIOUS))

        // A chosen frontend survives as the default by its key.
        onlyNewPipe.setDefault(Destination.Alternative(Frontend.NEWPIPE))
        assertEquals(Destination.Alternative(Frontend.NEWPIPE), onlyNewPipe.defaultDestination())
    }

    @Test
    fun aBuildFoundByTheLinksItOpensIsAnAppOfItsOwnAsTheBuildsWithANameAre() {
        val piko = "app.piko.android.apps.youtube.music"
        val pikoYouTube = "app.piko.android.youtube"
        val morphe = "app.morphe.android.apps.youtube.music"
        val installed = mutableSetOf(piko, pikoYouTube, morphe)
        // Morphe's is found by its links too, and is still one row; Spotify's own app is no build.
        val found = DestinationStore(preferences, { listOf(piko, morphe, pikoYouTube, MusicService.SPOTIFY.packageName) }) { it in installed }
        fun rows(service: MusicService) = found.allDestinations().filterIsInstance<Destination.Service>().filter { it.service == service }
        val inPiko = Destination.Service(MusicService.YOUTUBE_MUSIC, piko)
        val inMorphe = Destination.Service(MusicService.YOUTUBE_MUSIC, morphe)
        assertEquals(listOf(inMorphe, inPiko), rows(MusicService.YOUTUBE_MUSIC))
        assertEquals(listOf(Destination.Service(MusicService.YOUTUBE, pikoYouTube)), rows(MusicService.YOUTUBE))
        assertEquals(listOf(Destination.Service(MusicService.SPOTIFY)), rows(MusicService.SPOTIFY))

        // The only build, it stands for its service, which is what the only music app is saved as.
        installed -= morphe
        found.setDefault(Destination.Service(MusicService.YOUTUBE_MUSIC))
        assertEquals(inPiko, found.defaultDestination())
        assertEquals(piko, found.defaultDestination().packageName)
        // With YouTube Music's own app there too, each is a row.
        installed += MusicService.YOUTUBE_MUSIC.packageName
        assertEquals(listOf(Destination.Service(MusicService.YOUTUBE_MUSIC), inPiko), rows(MusicService.YOUTUBE_MUSIC))
        // Uninstalled since it was found, it's gone at once, before Android is asked about links again.
        installed -= piko
        assertEquals(listOf(Destination.Service(MusicService.YOUTUBE_MUSIC)), rows(MusicService.YOUTUBE_MUSIC))
    }

    @Test
    fun aPatchedBuildIsAnAppOfItsOwnOnlyWhileInstalledAndFallsBackToItsServiceOnceGone() {
        val morphe = "app.morphe.android.apps.youtube.music"
        val installed = mutableSetOf(morphe, "app.revanced.android.youtube")
        val patched = DestinationStore(preferences) { it in installed }
        val music = Destination.Service(MusicService.YOUTUBE_MUSIC)
        val inMorphe = Destination.Service(MusicService.YOUTUBE_MUSIC, morphe)

        // Without the service's own app, which would open the build anyway, the build alone stands for it.
        val services = patched.allDestinations().filterIsInstance<Destination.Service>()
        assertEquals(listOf(inMorphe), services.filter { it.service == MusicService.YOUTUBE_MUSIC })
        assertEquals(listOf(Destination.Service(MusicService.YOUTUBE, "app.revanced.android.youtube")), services.filter { it.service == MusicService.YOUTUBE })
        // The service chosen before, or as the first default, is that build now.
        assertEquals(inMorphe, patched.defaultDestination())
        preferences.edit().putString("default_target", "YOUTUBE_MUSIC").commit()
        assertEquals(inMorphe, patched.defaultDestination())
        // Chosen again as it stands, it keeps the service's key, as saving the default again in settings does.
        patched.setDefault(inMorphe)
        assertEquals("YOUTUBE_MUSIC", preferences.getString("default_target", null))
        // With the own app there too, each build comes right after its service, and plain services keep their own key and app.
        installed += MusicService.YOUTUBE_MUSIC.packageName
        assertEquals(listOf(music, inMorphe), patched.allDestinations().filterIsInstance<Destination.Service>().filter { it.service == MusicService.YOUTUBE_MUSIC })
        // So the service chosen before is its own app now, and the build is a choice of its own.
        assertEquals(music, patched.defaultDestination())
        patched.setDefault(inMorphe)
        assertEquals(inMorphe.key, preferences.getString("default_target", null))
        installed -= MusicService.YOUTUBE_MUSIC.packageName
        assertEquals(morphe, inMorphe.packageName)
        assertEquals(MusicService.YOUTUBE_MUSIC.packageName, music.packageName)
        assertEquals("YOUTUBE_MUSIC@$morphe", inMorphe.key)
        assertEquals("YOUTUBE_MUSIC", music.key)

        // Chosen as the default over another app, and as a rule, it comes back as itself.
        patched.setDefault(deezer)
        patched.setDefault(inMorphe)
        assertEquals(inMorphe.key, preferences.getString("default_target", null))
        patched.setRule(MusicService.SPOTIFY, inMorphe)
        assertEquals(inMorphe, patched.defaultDestination())
        assertEquals(inMorphe, patched.rule(MusicService.SPOTIFY))
        assertTrue(patched.hasDefault())
        // A rule chosen again as it stands keeps the service's key the same way, for a service's links and a frontend's.
        patched.setRule(MusicService.DEEZER, music)
        patched.setRule(MusicService.DEEZER, inMorphe)
        assertEquals("YOUTUBE_MUSIC", preferences.getString("rule_DEEZER", null))
        patched.setRule(Frontend.entries.first(), music)
        patched.setRule(Frontend.entries.first(), inMorphe)
        assertEquals("YOUTUBE_MUSIC", preferences.getString("rule_frontend_${Frontend.entries.first().name}", null))
        assertEquals(inMorphe, patched.rule(Frontend.entries.first()))

        // Uninstalled, the choice is its service again, not nothing.
        installed.clear()
        assertEquals(music, patched.defaultDestination())
        assertEquals(music, patched.rule(MusicService.SPOTIFY))
        assertTrue(patched.hasDefault())
        assertEquals(emptyList<Destination>(), patched.allDestinations().filterIsInstance<Destination.Service>().filter { it.build != null })
    }

    @Test
    fun defaultsToYouTubeMusicAndKeepsTheOldPreferenceFormat() {
        assertEquals(Destination.Service(MusicService.YOUTUBE_MUSIC), store.defaultDestination())
        // Older versions stored the service name under the same key.
        preferences.edit { putString("default_target", "TIDAL") }
        assertEquals(Destination.Service(MusicService.TIDAL), store.defaultDestination())
        preferences.edit { putString("default_target", "custom:missing") }
        assertEquals(Destination.Service(MusicService.YOUTUBE_MUSIC), store.defaultDestination())
    }

    @Test
    fun customDestinationsCanBeDefaultsAndRulesUntilRemoved() {
        val invidious = store.addCustom("  Invidious ", " https://yewtu.be/search?q={query} ")
        assertEquals("Invidious", invidious.name)
        assertEquals(MusicService.entries.map(Destination::Service) + invidious, store.allDestinations().filterNot { it is Destination.Alternative })

        store.setDefault(invidious)
        store.setRule(MusicService.YOUTUBE, invidious)
        store.setRule(Frontend.PIPED, invidious)
        assertEquals(invidious, store.rule(Frontend.PIPED))
        store.setRule(MusicService.SPOTIFY, deezer)
        assertEquals(invidious, store.defaultDestination())
        assertEquals(invidious, store.rule(MusicService.YOUTUBE))

        store.removeCustom(invidious)
        assertTrue(store.customDestinations().isEmpty())
        assertEquals(Destination.Service(MusicService.YOUTUBE_MUSIC), store.defaultDestination())
        assertNull(store.rule(MusicService.YOUTUBE))
        assertEquals(deezer, store.rule(MusicService.SPOTIFY))
        assertNull(store.rule(Frontend.PIPED))
        store.setRule(Frontend.PIPED, deezer)
        store.setRule(Frontend.PIPED, null)
        assertNull(store.rule(Frontend.PIPED))

        store.setRule(MusicService.SPOTIFY, null)
        assertNull(store.rule(MusicService.SPOTIFY))
    }

    @Test
    fun customSearchUrlsEncodeTheQuery() {
        val custom = Destination.Custom("1", "Web", "https://duckduckgo.com/?q={query}+lyrics")
        assertEquals("https://duckduckgo.com/?q=Rock%20%26%20Roll%20AC%2FDC+lyrics", custom.searchUrl("Rock & Roll AC/DC"))
        assertEquals("custom:1", custom.key)
        assertEquals("DEEZER", deezer.key)
        assertEquals("https://www.deezer.com/search/Song", deezer.searchUrl("Song"))
    }

    @Test
    fun aSlashInASongsNameDoesntBreakTheSearchesThatPutItInThePath() {
        // Deezer and Amazon Music answer 404 for a "/" in the path even written %2F (measured 2026-11-08), so the song would
        // have no search; a space finds it as it does without the slash. Spotify answers either way, and is kept alike.
        listOf(MusicService.SPOTIFY, MusicService.DEEZER, MusicService.AMAZON_MUSIC).forEach { service ->
            val search = service.searchUrl("Back In Black AC/DC")
            assertEquals(service.name, "Back%20In%20Black%20AC%20DC", search.substringAfterLast("/search/"))
            assertEquals(service.name, "Me%20You%20(always%20and%20forever)", service.searchUrl("Me / You (always and forever)").substringAfterLast("/search/"))
        }
        // Where a query is a parameter, the slash is just text.
        assertEquals("https://music.youtube.com/search?q=AC%2FDC", MusicService.YOUTUBE_MUSIC.searchUrl("AC/DC"))
    }

    @Test
    fun templatesNeedAPlaceholderAndAScheme() {
        assertTrue(Destination.isValidTemplate("https://example.com/?q={query}"))
        assertTrue(Destination.isValidTemplate("vlc://search/{query}"))
        assertFalse(Destination.isValidTemplate("https://example.com/"))
        assertFalse(Destination.isValidTemplate("example.com/?q={query}"))
    }

    @Test
    fun corruptOrInvalidCustomEntriesAreIgnored() {
        preferences.edit { putString("custom_destinations", "{broken") }
        assertTrue(store.customDestinations().isEmpty())

        preferences.edit {
            putString(
                "custom_destinations",
                """[{"id":"","name":"n","template":"https://x/{query}"},{"id":"a","name":"","template":"https://x/{query}"},
                   {"id":"b","name":"n","template":"https://x/"},"text",{"id":"c","name":"Kept","template":"https://x/{query}"}]"""
            )
        }
        assertEquals(listOf(Destination.Custom("c", "Kept", "https://x/{query}")), store.customDestinations())
    }
}
