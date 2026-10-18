package com.astrovm.crosstune

import android.content.SharedPreferences
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.astrovm.crosstune.ui.theme.Palette
import com.astrovm.crosstune.ui.theme.wallpaperColors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** The notice key for Crosstune's own links set to open in the browser. */
internal const val OWN_LINKS_OFF = "own-links-off"

internal data class UiState(
    val linkText: String = "",
    val isLoading: Boolean = false,
    val isMatching: Boolean = false,
    /** Set while the microphone is listening for a song playing nearby. */
    val listening: Boolean = false,
    val result: MusicMetadata? = null,
    /** Set when [result] lists songs like the one it's named after, as ChatGPT found them. */
    val songsLike: Boolean = false,
    /** Prepared direct links or search fallbacks, reused by Open, Copy and Share for this result. */
    val destinationUrls: Map<Destination, PreparedLink> = emptyMap(),
    /** The link [result] came from, or the incoming link that failed, so it can still be opened as is. */
    val link: MusicLink? = null,
    val error: AppError? = null,
    val canRetry: Boolean = false,
    val selectedDestination: Destination? = null,
    val defaultDestination: Destination = Destination.Service(MusicService.YOUTUBE_MUSIC),
    val destinations: List<Destination> = MusicService.entries.map(Destination::Service),
    /** Per-source destinations; sources without an entry use [defaultDestination]. */
    val rules: Map<MusicService, Destination> = emptyMap(),
    /** Per-frontend destinations for links from Invidious's or Piped's sites; without one, YouTube's rule or the default applies. */
    val frontendRules: Map<Frontend, Destination> = emptyMap(),
    val intercepted: Set<MusicService> = emptySet(),
    /** Web frontends, like Invidious, whose popular sites' links Crosstune opens: sources of their own. */
    val frontendSources: Set<Frontend> = emptySet(),
    /** Each one's sites Android doesn't let Crosstune open yet; null before Android 12. */
    val unapprovedFrontendHosts: Map<Frontend, List<String>>? = null,
    val askEachTime: Boolean = false,
    val exactMatch: Boolean = true,
    /** Drops tracking parameters from links Crosstune opens, copies or shares. */
    val cleanLinks: Boolean = true,
    /** Whether the share sheet offers "Open in" an app; Android shows those for any shared text. */
    val shareSheetApps: Boolean = true,
    /** Whether a tapped or shared link shows here first instead of opening; see [linkMode]. */
    val showSongFirst: Boolean = false,
    /** While Play all matches a collection's songs: how many have been looked up, out of how many. */
    val queueProgress: Pair<Int, Int>? = null,
    /** Where in the list the songs Play all is matching start, so a later part shows its progress by itself. */
    val queueFrom: Int = 0,
    /** What happens once a shared link is looked up, when a share sheet action asked for something other than opening. */
    val afterLookup: AfterLookup = AfterLookup.OPEN,
    /** YouTube, Invidious and Piped links reach the user's app only when they're music; other videos open as usual. */
    val onlyMusicVideos: Boolean = true,
    val showDestinationPicker: Boolean = false,
    /** A service the picker leaves out: the one the link came from, when opening it there would just go back. */
    val pickerHides: MusicService? = null,
    /** What a song's lyrics are being fetched for, or null when they aren't shown. */
    val lyricsFor: MusicMetadata? = null,
    /** The words fetched, empty while they're on the way or when the service has none. */
    val lyrics: String = "",
    /** Set while the words are being fetched. */
    val isLoadingLyrics: Boolean = false,
    /** Set when the service wouldn't answer, which isn't the same as the song having no words. */
    val lyricsFailed: Boolean = false,
    /** The words with when each is sung, so they can follow the song; empty when they aren't timed. */
    val lyricLines: List<LyricLine> = emptyList(),
    /** Whether the user put any of [lyricLines] in time by hand. */
    val lyricsSynced: Boolean = false,
    /** Where the song is as it plays, while something says so: a music app, or what Crosstune heard. */
    val following: Following? = null,
    /** Whether the music app playing the song shown can go on to its next song, or back to the one before. */
    val canSkipNext: Boolean = false,
    val canSkipPrevious: Boolean = false,
    /** Whether Android lets Crosstune see what music apps play, which timed words follow. */
    val canFollowApps: Boolean = false,
    /** Whether Android holds that access back first, for an app installed from a file, so it takes more steps. */
    val followRestricted: Boolean = false,
    /**
     * Set while the microphone keeps listening along with the words, to follow the song, or the next
     * one; it stays set while that's paused with Crosstune out of sight.
     */
    val listeningAlong: Boolean = false,
    /** Set while showing how to let Crosstune see what music apps play, step by step. */
    val followHelp: Boolean = false,
    /** Light, dark, or as the phone is. */
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** The app's color: the wallpaper's, or one of the app's own. */
    val palette: Palette = defaultPalette(),
    /** True black grounds in the dark. */
    val pureBlack: Boolean = false,
    /** Whether MilkDrop visuals move behind the words, to what the phone plays. */
    val visuals: Boolean = false,
    /** How dark the ground over the visuals is, from 0, none, to 1, black. */
    val visualsShade: Float = DEFAULT_VISUALS_SHADE,
    /** How many times the next visual was asked for, so each ask moves on once. */
    val visualsSkips: Int = 0,
    /** What helps read the words in another language, and what's been worked out for them. */
    val learning: Learning = Learning(),
    /** A song's chords over its words, while switched on. */
    val chords: ChordsState = ChordsState(),
    /** Lines kept to study later, newest first. */
    val savedLines: List<SavedLine> = emptyList(),
    /** Saved lines being practised, while they are. */
    val practice: Practice? = null,
    /** The ChatGPT account whose plan the AI features run on. */
    val chatGpt: ChatGptState = ChatGptState(),
    /** The line the music app keeps going back to, while it does. */
    val repeating: Int? = null,
    /** A word of the words being looked up, or looked up. */
    val word: WordMeaning? = null,
    /** How the words floating over other apps look, and where they are. */
    val floating: FloatingOptions = FloatingOptions(),
    /** Whether Android lets Crosstune show over other apps, which see-through floating words need. */
    val canFloatOverApps: Boolean = false,
    /** What happens when the app a song goes to doesn't have it. */
    val notFoundAction: NotFoundAction = NotFoundAction.ASK,
    /** A song not found where it was going, from Recent or a playlist, with what can be done instead. */
    val notFoundOffer: NotFoundOffer? = null,
    /** Songs found for typed text that wasn't a link, offered to pick from. */
    val songSearch: List<MusicMetadata> = emptyList(),
    /** What was searched for, so an empty list can say what it found nothing for. */
    val songSearchQuery: String = "",
    /** Set while the songs for typed text are being looked up. */
    val isSearchingSongs: Boolean = false,
    /** Set while ChatGPT is asked what songs the typed text describes. */
    val isAskingForSongs: Boolean = false,
    /** How many of [songSearch], from the first, ChatGPT named. */
    val songsFromChatGpt: Int = 0,
    val showLinkSettingsHelper: Boolean = false,
    /** What the main screen's notices were about when the user closed them, by [noticeKeys]. */
    val dismissedNotices: Set<String> = emptySet(),
    /** The song a music app on the phone plays as Crosstune comes into sight, one tap from showing. */
    val playingNow: MusicMetadata? = null,
    val history: List<HistoryEntry> = emptyList(),
    /** False until the user finishes first-run setup; nothing is intercepted or chosen before that. */
    val setupComplete: Boolean = true,
    val hasDefault: Boolean = true,
    val installed: Set<MusicService> = emptySet(),
    /** Each source's hosts Android doesn't let Crosstune open yet, or null before Android 12, which can't tell. */
    val unapprovedHosts: Map<MusicService, List<String>>? = null,
    /** Crosstune's own links are set to open in the browser, so none reach it; see [LinkState.ownLinksOff]. */
    val ownLinksOff: Boolean = false,
    /**
     * Installed apps that still open links Crosstune intercepts, until changed in their own
     * settings; null before Android 12, which can't tell.
     */
    val blockingApps: Set<LinkApp>? = null,
    /** Installed apps that claim links Crosstune intercepts, blocking or not; null before Android 12. */
    val claimingApps: Set<LinkApp>? = null,
    /** For every source, by [Destination.key], installed apps that claim its links and whether each still opens them. */
    val claimingAppsBySource: Map<String, Map<LinkApp, Boolean>>? = null,
    /** The intercepted services' own installed apps, which may take their links when Android can't say. */
    val installedSourceApps: List<LinkApp> = emptyList(),
    /**
     * False until Android has first been asked about installed apps and links, which takes a moment.
     * Until then the fields above don't flag anything, so nothing is shown as a problem before it's known.
     */
    val systemStateKnown: Boolean = true,
    /** Whether setup can be done in one tap, through Shizuku, which the user runs. */
    val canSetUpInOneTap: Boolean = false,
    /** Set while setup is being done in one tap. */
    val settingUpInOneTap: Boolean = false,
    /** Set when setup in one tap didn't do it all, left to the user to finish. */
    val oneTapFailed: Boolean = false,
    /** What's being fixed in one tap after setup, through Shizuku, as during it. */
    val fixingInOneTap: OneTapFix? = null,
    /** What one tap couldn't fix after setup, left to the steps by hand, which open once. */
    val fixByHand: OneTapFix? = null,
    /** Set while handling a link from another app, which takes priority over setup. */
    val handlingIncomingLink: Boolean = false,
    /**
     * Set while a link from another app is on its way to an app, so the screen shows just that
     * instead of all of Crosstune; cleared once the user has something to do here.
     */
    val handingOff: Boolean = false,
    /** Set right after history is cleared, or one item removed, while it can still be brought back. */
    val canUndoClearHistory: Boolean = false,
    /** Whether what can be undone is one item removed rather than all of it cleared. */
    val removedOneFromHistory: Boolean = false,
    /** Counts removals and clears, so each one offers its own undo even while the last offer shows. */
    val historyUndoId: Int = 0,
    /** Set for every link from another app until the screen has left settings, even for a repeated link. */
    val leaveSettings: Boolean = false
) {
    /**
     * A playlist with its songs listed, which play from here: elsewhere than its own service, or
     * YouTube's, where it opens as itself, a search for its name rarely finds it.
     */
    val isSongList: Boolean
        get() = result?.type == ItemType.PLAYLIST && result.tracks.isNotEmpty() && link?.service != resultDestination.matchService &&
            link?.youtubePlaylistOn(resultDestination.matchService) == null

    /**
     * Whether a collection's songs can play as one queue where it goes: YouTube Music and YouTube
     * make one for any list of videos. A YouTube playlist opens as itself there instead.
     */
    val canPlayAll: Boolean
        get() = result?.tracks.orEmpty().isNotEmpty() &&
            resultDestination.matchService.let { it == MusicService.YOUTUBE_MUSIC || it == MusicService.YOUTUBE } &&
            link?.youtubePlaylistOn(resultDestination.matchService) == null

    val linkMode: LinkMode
        get() = if (showSongFirst) LinkMode.SHOW else if (askEachTime) LinkMode.ASK else LinkMode.OPEN

    /** Whether the share sheet's top row offers Crosstune's entries; Android shows them for any shared text. */
    val shareSheetEntries: Boolean
        get() = setupComplete && hasDefault && shareSheetApps

    /** The app the top row offers to open a shared link in, when the default is one. */
    val shareSheetApp: MusicService?
        get() = (defaultDestination as? Destination.Service)?.service

    /** Whether Crosstune opens any links at all. */
    val interceptsAnything: Boolean get() = intercepted.isNotEmpty() || frontendSources.isNotEmpty()

    /** Whether some link Crosstune is set to open isn't allowed yet; false before Android 12, which can't tell. */
    val someLinksNotAllowed: Boolean
        get() = (ownLinksOff && interceptsAnything) || intercepted.any { unapprovedHosts?.get(it).orEmpty().isNotEmpty() } ||
            frontendSources.any { unapprovedFrontendHosts?.get(it).orEmpty().isNotEmpty() }

    /** What keeps links from Crosstune now: each host not allowed, links set to the browser. */
    val notAllowedKeys: Set<String>
        get() = buildSet {
            if (ownLinksOff && interceptsAnything) add(OWN_LINKS_OFF)
            intercepted.forEach { service -> unapprovedHosts?.get(service).orEmpty().forEach { add("host:$it") } }
            frontendSources.forEach { frontend -> unapprovedFrontendHosts?.get(frontend).orEmpty().forEach { add("host:$it") } }
        }

    /**
     * Set when the app the result goes to was searched for it and doesn't have it, e.g. a song only on
     * SoundCloud, as opposed to an app Crosstune can't search, which may well have it.
     */
    val resultMissing: Boolean
        get() = result != null && destinationUrls[resultDestination]?.let { !it.exact && it.matchingEnabled } == true &&
            resultDestination.matchService?.let { canBeMissing(it, result) } == true

    /** A one-time choice takes precedence over the source rule and global default. */
    val resultDestination: Destination
        get() = selectedDestination ?: link?.let(::ruleFor) ?: defaultDestination

    /** Where links like [link] go unless changed for one result: their frontend's rule, then their service's. */
    fun ruleFor(link: MusicLink): Destination? = link.frontend?.let { frontendRules[it] } ?: rules[link.service]
}

/** One-shot requests for the Activity, delivered even if they arrive while it is being recreated. */
internal enum class AfterLookup { OPEN, SHARE }

/** The wallpaper's colors where Android shares them, from 12 on; violet before that. */
internal fun defaultPalette(): Palette = if (wallpaperColors) Palette.WALLPAPER else Palette.VIOLET

/**
 * The color picked before, by name: "CROSSTUNE" is what violet was called, and the wallpaper's, picked
 * on a phone since moved back to an Android that doesn't share it, is the default there.
 */
internal fun savedPalette(name: String?): Palette {
    val saved = if (name == "CROSSTUNE") Palette.VIOLET else Palette.entries.firstOrNull { it.name == name }
    return saved?.takeIf { it != Palette.WALLPAPER || wallpaperColors } ?: defaultPalette()
}

/** When a song isn't in the app it goes to: say so and offer both, open it where it's from, or search anyway. */
internal enum class NotFoundAction { ASK, ORIGINAL, SEARCH }

/** How dark the ground over the visuals is to start with: enough for the words to read. */
internal const val DEFAULT_VISUALS_SHADE = 0.45f

/**
 * A [word] and its [meaning], once found; null while [looking], or when it [failed] or needs none.
 * [type] is what kind of word it is, e.g. "noun", when the dictionary says.
 */
internal data class WordMeaning(val word: Word, val meaning: String? = null, val looking: Boolean = false, val failed: Boolean = false, val type: String? = null)

/** Signing in with ChatGPT: [email] once signed in, and how the last try went. */
internal data class ChatGptState(
    val email: String? = null,
    /** Set while the browser's open to sign in. */
    val signingIn: Boolean = false,
    /** How the last sign-in ended when it didn't. */
    val problem: ChatGptSignInResult? = null,
    /** Set after the first sign-in, to say the AI features now use the user's plan. */
    val welcome: Boolean = false,
    /** The models the account offers, and the one asked. */
    val models: List<ChatGptModel> = emptyList(),
    val model: String? = null
)

/**
 * Saved lines being practised as cards, [queue] the ones left, the one shown first. Each asks the
 * line as written, which plays in the music app as [sound] says, and shows how it reads and what it
 * means once [turned]. With none left, the session's done; with none at all, none was due: all are
 * caught up.
 */
internal data class Practice(
    val queue: List<SavedLine> = emptyList(),
    /** How many cards the session has, and how many are done, for how far along it is. */
    val total: Int = 0,
    val done: Int = 0,
    /** How many times one was missed, so a card missed comes back as a new one. */
    val missed: Int = 0,
    val turned: Boolean = false,
    val sound: LineSound = LineSound.NONE,
    /** The last card's line shown, for the song practice left off in. */
    val lastSong: SavedLine? = null,
    /** What the music app played before, to go back to once done. */
    val before: Before? = null
) {
    val line: SavedLine? get() = queue.firstOrNull()

    /** All done: there were cards, and none are left. */
    val finished: Boolean get() = total > 0 && queue.isEmpty()
}

/** How a card's line sounds: not at all, on its way in the music app, playing, or ready to play (again). */
internal enum class LineSound { NONE, LOADING, PLAYING, PLAYED }

/** A [song] the music app [app] played, at [positionMs], before practice took it over. */
internal data class Before(val song: MusicMetadata, val positionMs: Long, val app: String?)

/** A question about a line, and ChatGPT's [answer] so far, until [done]; or why there's none. */
internal data class LineQuestion(val question: String, val answer: String = "", val done: Boolean = false, val failed: Boolean = false, val limitReached: Boolean = false) {
    val writing get() = !done && !failed && !limitReached
}

/** Line [index] explained by ChatGPT: [text] so far, until [done]; or why it couldn't be. */
internal data class LineExplanation(val index: Int, val text: String = "", val done: Boolean = false, val failed: Boolean = false, val limitReached: Boolean = false) {
    val writing get() = !done && !failed && !limitReached
}

/** A song's chords, shown over its words while [on]; [tried] once looked up, whether or not any were found. */
internal data class ChordsState(
    val on: Boolean = false,
    val loading: Boolean = false,
    val tried: Boolean = false,
    val sheet: ChordSheet? = null
) {
    /** For another song's words, or the same words written otherwise: switched on as it was, nothing looked up yet. */
    fun fresh() = ChordsState(on = on)
}

/**
 * What's switched on to help read a song's words in another language: [readings] over them, kana
 * over kanji or pinyin over hanzi; the lines in Latin letters, [romanized]; and their [translation].
 * Then what's been worked out for the words shown, line by line.
 */
internal data class Learning(
    val readings: Boolean = false,
    val romanized: Boolean = false,
    val translation: Boolean = false,
    /** Signed in, whether ChatGPT translates, rather than MyMemory. */
    val chatGptTranslation: Boolean = true,
    /** The language picked to translate into, as Settings lists it; null for the app's own. */
    val into: String? = null,
    /** The languages, by code, e.g. "en", the user reads, whose songs aren't translated. */
    val untranslated: Set<String> = emptySet(),
    /** How big readings are beside the words they're over. */
    val readingScale: Float = READING_SCALE,
    /** The script the words are in, when it reads differently from how it looks. */
    val script: Script? = null,
    /** How each line reads, once worked out. */
    val lines: List<LineReading> = emptyList(),
    /** Each line translated, or null where it needs none, once done. */
    val translations: List<String?> = emptyList(),
    val translating: Boolean = false,
    val translationFailed: Boolean = false,
    /** A line ChatGPT is explaining, or has. */
    val explanation: LineExplanation? = null,
    /** What the whole song is about, as ChatGPT is explaining it, or has. */
    val songExplanation: LineExplanation? = null,
    /** Questions asked about line [questionsAbout], in order, with their answers. */
    val questionsAbout: Int? = null,
    val questions: List<LineQuestion> = emptyList(),
    /** How the words show: as written, or in kana or Latin letters for a script that reads otherwise. */
    val wordsAs: WordsAs = WordsAs.WRITTEN,
    /** Set when the words were found in romaji, and ChatGPT wrote them in Japanese. */
    val fromRomaji: Boolean = false,
    /** The words most worth learning from the song, as ChatGPT finds them. */
    val wordsToKnow: Asking<List<KeyWord>>? = null,
    /** How hard the song's words are, and what it covers or samples, once ChatGPT found out. */
    val songFacts: SongFacts? = null,
    /** Questions asked about the whole song, in order, with their answers. */
    val songQuestions: List<LineQuestion> = emptyList(),
    /** Who the artist is, as ChatGPT is writing it, or has. */
    val artist: LineExplanation? = null,
    /** Sentences using the word tapped, as ChatGPT writes them. */
    val examples: WordExamples? = null
) {
    /** Only what's switched on, ready to work out for new words. */
    fun fresh(script: Script? = null) = Learning(readings, romanized, translation, chatGptTranslation, into, untranslated, readingScale, script, wordsAs = wordsAs)
}

/** How the words show: as written, only in kana, for Japanese, or only in Latin letters. */
internal enum class WordsAs { WRITTEN, KANA, LATIN }

/**
 * What shows instead of line [index] as the words are set to show: its kana, or its Latin letters,
 * once its reading's worked out; null as written.
 */
internal fun Learning.insteadOf(index: Int): String? {
    val reading = lines.getOrNull(index) ?: return null
    if (showsAs == WordsAs.KANA) return reading.reading
    if (showsAs == WordsAs.LATIN) return reading.romanized.takeIf { it.isNotBlank() }
    return null
}

/** How the words show for the script they're in: only Japanese has kana, so the others show as written. */
internal val Learning.showsAs: WordsAs get() = if (wordsAs == WordsAs.KANA && script != Script.JAPANESE) WordsAs.WRITTEN else wordsAs

/** Something asked of ChatGPT: [value] once it's come, or that it [failed]; on its way until either. */
internal data class Asking<T>(val value: T? = null, val failed: Boolean = false) {
    val loading get() = value == null && !failed
}

/** Sentences using [word], from line [index], once they've come, or that they couldn't be had. */
internal data class WordExamples(val word: Word, val index: Int, val asking: Asking<List<Example>> = Asking())

/**
 * A song not found in [destination], with its [original] link, which opens it where it is, when it has
 * one, and a search there to try anyway.
 */
internal data class NotFoundOffer(val song: MusicMetadata, val destination: Destination, val original: MusicLink?, val searchUrl: String)

/** The app's look: as the phone is set, or always light or dark. */
internal enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** What a tapped or shared link does: opens in the default app, asks which app, or shows here first. */
internal enum class LinkMode { OPEN, ASK, SHOW }

/** What's fixed in one tap after setup, from where it's found wrong: the links, the apps that take them, or following. */
internal enum class OneTapFix { LINKS, APPS, FOLLOW }

internal sealed interface Effect {
    /**
     * [packageName] is null for custom destinations, which open in whatever app handles the URL.
     * [atMs] is when it was asked, so one that waited while Crosstune was away isn't opened long after.
     */
    data class Open(val url: String, val packageName: String?, val finishAfterOpen: Boolean, val atMs: Long = SystemClock.elapsedRealtime()) : Effect
    data class Share(val url: String) : Effect
}

/** Holds screen state across configuration changes and owns in-flight network work. */
internal class MainViewModel(
    private val resolver: LinkResolver,
    private val matcher: ExactMatcher,
    /** Where a song's words come from; nullable so tests need no lyrics service. */
    private val lyricsFinder: LyricsFinder? = null,
    /** Where a song typed by name is looked up; nullable so tests need no catalogue. */
    private val songSearcher: SongSearcher? = null,
    /** What music apps are playing, which a song's timed words follow; nullable so tests need none. */
    private val playback: PlaybackSource? = null,
    private val preferences: SharedPreferences,
    private val interception: LinkInterception,
    /** Lives here so loaded covers survive configuration changes. */
    val artwork: ArtworkLoader,
    /** Names a song playing nearby. */
    private val listener: SongHearing,
    /** Where Android is asked about apps and links: many slow calls that mustn't hold up the screen. */
    private val systemDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** How long listening along waits after naming a song before it listens again. */
    private val listenAlongPauseMs: Long = LISTEN_ALONG_PAUSE_MS,
    /**
     * How long listening along rests after hearing nothing, doubled each time it hears nothing again,
     * up to [QUIET_PAUSE_DOUBLINGS] times, so a paused song doesn't keep the microphone on.
     */
    private val quietPauseMs: Long = LISTEN_ALONG_PAUSE_MS,
    /** The clock a song's place is kept on, the one that counts since the phone started. */
    private val now: () -> Long = SystemClock::elapsedRealtime,
    /** Where a song's words are translated; nullable so tests need no translation service. */
    private val translator: Translator? = null,
    /** The language words are translated into: the app's. */
    private val translationLanguage: () -> String = { Translator.languageOf(java.util.Locale.getDefault()) },
    /** The ChatGPT account the AI features use; nullable so tests need none. */
    private val chatGpt: ChatGpt? = null,
    /** What the AI features ask ChatGPT, with that account. */
    private val tutor: ChatGptTutor? = null,
    /** The time of day, for when saved lines are next practised. */
    private val wallClock: () -> Long = System::currentTimeMillis,
    /** Where a song's chords come from; nullable so tests need no chord sites. */
    private val chordFinder: ChordFinder? = null,
    /** Which language a song's words are in; nullable so tests need none. */
    private val lyricsLanguage: LyricsLanguage? = null,
    /** Setup in one tap, for people who run Shizuku; nullable so tests need none. */
    private val oneTap: OneTapSetup? = null,
    /** How a Japanese song's kanji are sung, which a dictionary can't say; nullable so tests need none. */
    private val sungReadings: SungReadings? = null
) : ViewModel() {

    private val historyStore = HistoryStore(preferences)
    private val savedLinesStore = SavedLinesStore(preferences)
    private val syncStore = LyricsSyncStore(preferences)

    /** The timed words as found, before the lines put in time by hand, [syncPoints], move them. */
    private var foundLines: List<LyricLine> = emptyList()

    /** The words found in romaji, written in Japanese by ChatGPT, line by line, once they are. */
    private var japanese: List<String>? = null

    /** [lines] as they show: in Japanese when found in romaji and written so, timed as found. */
    private fun shown(lines: List<LyricLine>): List<LyricLine> =
        // Written otherwise, the line's words are no longer the ones timed.
        japanese?.let { written -> lines.mapIndexed { index, line -> written.getOrNull(index)?.let { line.copy(text = it, words = emptyList()) } ?: line } } ?: lines
    private var syncPoints: List<SyncPoint> = emptyList()
    private val destinationStore = DestinationStore(preferences, interception::isInstalled)

    init {
        migrateExistingInstall()
    }

    /** The last look at what Android says about apps and links, null until the first one finishes. */
    private var linkState: LinkState? = null
    private var systemJob: Job? = null
    /** What [UiState.playingNow] was heard as, with where in the song it was. */
    private var playingHeard: Heard.Song? = null

    var uiState by mutableStateOf(
        UiState(
            askEachTime = preferences.getBoolean(KEY_ASK_EACH_TIME, false),
            exactMatch = preferences.getBoolean(KEY_EXACT_MATCH, true),
            cleanLinks = preferences.getBoolean(KEY_CLEAN_LINKS, true),
            shareSheetApps = preferences.getBoolean(KEY_SHARE_SHEET_APPS, true),
            showSongFirst = preferences.getBoolean(KEY_SHOW_SONG_FIRST, false),
            onlyMusicVideos = preferences.getBoolean(KEY_ONLY_MUSIC_VIDEOS, true),
            theme = ThemeMode.entries.firstOrNull { it.name == preferences.getString(KEY_THEME, null) } ?: ThemeMode.SYSTEM,
            palette = savedPalette(preferences.getString(KEY_PALETTE, null)),
            pureBlack = preferences.getBoolean(KEY_PURE_BLACK, false),
            visuals = preferences.getBoolean(KEY_VISUALS, true),
            visualsShade = preferences.getFloat(KEY_VISUALS_SHADE, DEFAULT_VISUALS_SHADE),
            canFollowApps = playback?.hasAccess() == true,
            followRestricted = playback?.restricted() == true,
            notFoundAction = NotFoundAction.entries.firstOrNull { it.name == preferences.getString(KEY_NOT_FOUND, null) } ?: NotFoundAction.ASK,
            chords = ChordsState(on = preferences.getBoolean(KEY_CHORDS, false)),
            learning = Learning(
                readings = preferences.getBoolean(KEY_READINGS, true),
                romanized = preferences.getBoolean(KEY_ROMANIZED, false),
                translation = preferences.getBoolean(KEY_TRANSLATION, false),
                chatGptTranslation = preferences.getBoolean(KEY_TRANSLATION_CHATGPT, true),
                into = preferences.getString(KEY_TRANSLATE_INTO, null)?.let(AppLanguage::translationTag),
                untranslated = preferences.getStringSet(KEY_UNTRANSLATED, null).orEmpty().filter { it in AppLanguage.untranslatedTags }.toSet(),
                readingScale = preferences.getFloat(KEY_READING_SCALE, READING_SCALE).coerceIn(READING_SCALE, MAX_READING_SCALE),
                wordsAs = WordsAs.entries.firstOrNull { it.name == preferences.getString(KEY_WORDS_AS, null) } ?: WordsAs.WRITTEN
            ),
            floating = FloatingOptions(
                background = preferences.getFloat(KEY_FLOATING_BACKGROUND, FloatingOptions().background),
                scale = preferences.getFloat(KEY_FLOATING_SCALE, 1f),
                previousLine = preferences.getBoolean(KEY_FLOATING_PREVIOUS_LINE, false),
                nextLine = preferences.getBoolean(KEY_FLOATING_NEXT_LINE, true),
                locked = preferences.getBoolean(KEY_FLOATING_LOCKED, false),
                left = preferences.getInt(KEY_FLOATING_LEFT, -1),
                top = preferences.getInt(KEY_FLOATING_TOP, -1),
                visuals = preferences.getBoolean(KEY_FLOATING_VISUALS, false)
            ),
            showLinkSettingsHelper = !preferences.getBoolean(KEY_LINK_SETTINGS_HELPER_DISMISSED, false),
            dismissedNotices = preferences.getStringSet(KEY_DISMISSED_NOTICES, null).orEmpty().toSet(),
            history = historyStore.load(),
            savedLines = savedLinesStore.load(),
            setupComplete = preferences.getBoolean(KEY_SETUP_COMPLETE, false),
            systemStateKnown = false,
            canSetUpInOneTap = oneTap?.available() == true
        ).withDestinations()
    )
        private set

    /** Shizuku can start or stop at any time, which takes setting up in one tap with it. */
    private val stopWatchingOneTap = oneTap?.watch { uiState = uiState.copy(canSetUpInOneTap = oneTap.available()) }

    override fun onCleared() {
        stopWatchingOneTap?.invoke()
    }

    /**
     * Does what setup asks of the user, through Shizuku: stops the apps in the way, allows the
     * links and lets lyrics follow music apps. What it couldn't do is still there to fix by hand.
     */
    fun setUpInOneTap() {
        val setup = oneTap ?: return
        if (uiState.settingUpInOneTap) return
        val state = uiState
        uiState = uiState.copy(settingUpInOneTap = true, oneTapFailed = false)
        viewModelScope.launch {
            val worked = inOneTap(setup, apps = state.blockingApps.orEmpty().map { it.packageName }, links = state.interceptsAnything, follow = !state.canFollowApps)
            uiState = uiState.copy(settingUpInOneTap = false, oneTapFailed = !worked)
            refreshSystemState()
            refreshFollowing()
        }
    }

    /**
     * Fixes [fix] in one tap, through Shizuku, after setup as during it, rather than by hand; what it
     * couldn't is left to the steps, in [UiState.fixByHand]. Whether Shizuku is there to try.
     */
    fun fixInOneTap(fix: OneTapFix): Boolean {
        val setup = oneTap ?: return false
        if (!uiState.canSetUpInOneTap) return false
        if (uiState.fixingInOneTap != null) return true
        val apps = if (fix == OneTapFix.APPS) uiState.blockingApps.orEmpty().map { it.packageName } else emptyList()
        uiState = uiState.copy(fixingInOneTap = fix, fixByHand = null)
        viewModelScope.launch {
            val worked = inOneTap(setup, apps = apps, links = fix == OneTapFix.LINKS, follow = fix == OneTapFix.FOLLOW)
            uiState = uiState.copy(fixingInOneTap = null, fixByHand = fix.takeUnless { worked })
            refreshSystemState()
            refreshFollowing()
        }
        return true
    }

    /** The steps for what one tap couldn't fix are open. */
    fun fixByHandShown() {
        uiState = uiState.copy(fixByHand = null)
    }

    private suspend fun inOneTap(setup: OneTapSetup, apps: List<String>, links: Boolean, follow: Boolean): Boolean {
        val worked = setup.setUp(apps = apps, links = links, follow = follow)
        // Android lets Crosstune follow music apps a moment after it's told to.
        var waits = 0
        while (worked && follow && playback?.hasAccess() != true && waits++ < FOLLOW_WAITS) delay(FOLLOW_WAIT_MS)
        return worked
    }

    /**
     * Versions before first-run setup always intercepted Spotify links. People updating from one
     * skip setup and keep that, so their links don't silently stop opening in Crosstune.
     */
    private fun migrateExistingInstall() {
        if (preferences.contains(KEY_SETUP_COMPLETE)) return
        // Recorded on a fresh install's first launch too, so data created before finishing setup
        // (e.g. history from a link opened right away) is never mistaken for an old install.
        val isExistingInstall = LEGACY_KEYS.any(preferences::contains)
        if (isExistingInstall) interception.setEnabled(MusicService.SPOTIFY, true)
        preferences.edit { putBoolean(KEY_SETUP_COMPLETE, isExistingInstall) }
    }

    private val effectChannel = Channel<Effect>(Channel.BUFFERED)
    val effects: Flow<Effect> = effectChannel.receiveAsFlow()

    init {
        chatGpt?.let { account ->
            account.picked = preferences.getString(KEY_CHATGPT_MODEL, null)
            viewModelScope.launch {
                // Read before the state is, which may change while the account's read.
                val email = account.email() ?: return@launch
                uiState = uiState.copy(chatGpt = uiState.chatGpt.copy(email = email))
                loadModels(account)
            }
        }
    }

    private var chatGptJob: Job? = null

    /** Opens the browser to sign in with ChatGPT, and waits for it to come back; [done] once it's over. */
    fun signInWithChatGpt(done: () -> Unit) {
        val account = chatGpt ?: return
        chatGptJob?.cancel()
        uiState = uiState.copy(chatGpt = uiState.chatGpt.copy(signingIn = true, problem = null))
        chatGptJob = viewModelScope.launch {
            try {
                val result = account.signIn(open = { url -> effectChannel.send(Effect.Open(url, packageName = null, finishAfterOpen = false)) })
                val signedIn = result == ChatGptSignInResult.SIGNED_IN
                val email = if (signedIn) account.email() else null
                // Said once, the first time the plan's used.
                val welcome = signedIn && !preferences.getBoolean(KEY_CHATGPT_WELCOMED, false)
                uiState = uiState.copy(chatGpt = ChatGptState(email = email, problem = result.takeUnless { signedIn }, welcome = welcome))
                if (signedIn) loadModels(account)
            } finally {
                // Unless another sign-in took over, which still needs Crosstune kept running.
                if (chatGptJob === coroutineContext[Job]) done()
            }
        }
    }

    /** Stops waiting for the browser, which won't be listened to anymore. */
    fun cancelChatGptSignIn() {
        chatGptJob?.cancel()
        uiState = uiState.copy(chatGpt = uiState.chatGpt.copy(signingIn = false))
    }

    fun dismissChatGptWelcome() {
        preferences.edit { putBoolean(KEY_CHATGPT_WELCOMED, true) }
        uiState = uiState.copy(chatGpt = uiState.chatGpt.copy(welcome = false))
    }

    /** The models [account] offers to pick from in Settings, and the one asked. */
    private suspend fun loadModels(account: ChatGpt) {
        val models = account.models() ?: return
        val model = account.model()
        uiState = uiState.copy(chatGpt = uiState.chatGpt.copy(models = models, model = model))
    }

    /** Asks [slug] from now on: the words are translated again, and lines explained again, by it. */
    fun pickChatGptModel(slug: String) {
        val account = chatGpt ?: return
        preferences.edit { putString(KEY_CHATGPT_MODEL, slug) }
        account.picked = slug
        translationJob?.cancel()
        explanationJob?.cancel()
        songExplanationJob?.cancel()
        uiState = uiState.copy(
            chatGpt = uiState.chatGpt.copy(model = slug),
            learning = uiState.learning.copy(translations = emptyList(), translating = false, translationFailed = false, explanation = null, songExplanation = null)
        )
        learn()
    }

    fun signOutOfChatGpt() {
        val account = chatGpt ?: return
        chatGptJob?.cancel()
        uiState = uiState.copy(chatGpt = ChatGptState())
        viewModelScope.launch { account.signOut() }
    }

    private var job: Job? = null
    private var lastRequest: Triple<LinkInput, Boolean, Destination?>? = null
    private var pendingOpen = false

    /**
     * The user's choices and which sources Crosstune opens, which are quick to read, so anything
     * reading them right after a change sees it. What Android says about other apps and links comes
     * from the last look at it, see [loadSystemState].
     */
    private fun UiState.withDestinations(): UiState {
        val destinations = destinationStore.allDestinations()
        return copy(
            defaultDestination = destinationStore.defaultDestination(destinations),
            destinations = destinations,
            selectedDestination = selectedDestination?.takeIf { it in destinations },
            rules = MusicService.entries.mapNotNull { source -> destinationStore.rule(source, destinations)?.let { source to it } }.toMap(),
            frontendRules = Frontend.SOURCES.mapNotNull { frontend -> destinationStore.rule(frontend, destinations)?.let { frontend to it } }.toMap(),
            intercepted = MusicService.entries.filter { it.canBeSource && interception.isEnabled(it) }.toSet(),
            frontendSources = Frontend.SOURCES.filter(interception::isEnabled).toSet(),
            hasDefault = destinationStore.hasDefault(destinations)
        ).withLinkState()
    }

    /** What Android last said about apps and links, as it concerns the sources Crosstune opens now. */
    private fun UiState.withLinkState(): UiState {
        val state = linkState ?: return this
        val claiming = state.claimingApps(intercepted, frontendSources)
        return copy(
            systemStateKnown = true,
            installed = state.serviceApps.keys,
            unapprovedHosts = state.unapprovedHosts,
            ownLinksOff = state.ownLinksOff,
            unapprovedFrontendHosts = state.unapprovedFrontendHosts,
            blockingApps = claiming?.filterValues { it }?.keys,
            claimingApps = claiming?.keys,
            claimingAppsBySource = state.claimingAppsBySource,
            installedSourceApps = state.installedSourceApps(intercepted)
        )
    }

    /**
     * Re-reads what can change outside this screen. A share can be handled by another Crosstune
     * instance that saves the item and finishes, leaving this one with a stale Recent list.
     */
    fun refreshSystemState() {
        val previousDestination = uiState.resultDestination
        uiState = uiState.withDestinations().copy(canSetUpInOneTap = oneTap?.available() == true)
        if (previousDestination != uiState.resultDestination) prepareResultDestination()
        loadSystemState()
    }

    /**
     * Asks Android about installed apps and links, and reloads Recent, away from the main thread:
     * it takes long enough to hold up the screen every time the app comes back. Until it's done
     * the screen keeps what it had. A newer look replaces one still running, so an older answer
     * never overwrites it.
     */
    private fun loadSystemState() {
        systemJob?.cancel()
        val history = uiState.history
        systemJob = viewModelScope.launch {
            val (stored, state) = withContext(systemDispatcher) { historyStore.load() to interception.linkState() }
            linkState = state
            // Anything looked up or cleared meanwhile is newer than what was read.
            val unchanged = uiState.history === history
            uiState = uiState.withLinkState().let { if (unchanged) it.copy(history = stored) else it }
        }
    }

    fun completeSetup() {
        preferences.edit {
            putBoolean(KEY_SETUP_COMPLETE, true)
            // Setup already walked through allowing links, so the reminder isn't needed.
            putBoolean(KEY_LINK_SETTINGS_HELPER_DISMISSED, true)
        }
        uiState = uiState.copy(setupComplete = true, showLinkSettingsHelper = false)
    }

    fun onUrlChange(text: String) {
        uiState = uiState.copy(linkText = text, error = null)
    }

    /** Resolves what the user typed, leaving the text field as typed. */
    fun resolveTypedInput() {
        uiState = uiState.copy(handlingIncomingLink = false, handingOff = false)
        val input = parse(uiState.linkText)
        if (input == null) {
            // Not a link, so it may be a song's name. One with a catalogue behind it gets a list to
            // pick from; anything else is still not something Crosstune can open.
            searchSongs(uiState.linkText)
            return
        }
        resolve(input, openWhenReady = false)
    }

    private var searchJob: Job? = null

    /**
     * Looks [query] up as a song's name and offers what was found to pick from, so a song can be
     * converted without its link. Nothing is searched for a link, which is already all it needs.
     */
    fun searchSongs(query: String) {
        val searcher = songSearcher
        val words = query.trim()
        // A link Crosstune can't open is reported as one, not looked up as a song named after it.
        if (searcher == null || words.isEmpty() || MusicLinks.looksLikeALink(words)) return rejectInput()
        cancelContentWork()
        searchJob = viewModelScope.launch {
            // Whatever was shown before is gone, so the songs found are what the screen is about.
            uiState = uiState.copy(
                isSearchingSongs = true,
                // The sheet says what it's looking for while it looks.
                songSearchQuery = words,
                songSearch = emptyList(),
                result = null,
                destinationUrls = emptyMap(),
                link = null
            )
            val songs = searcher.search(words)
            // The sheet says what was found, an empty list included, so it is the one place that
            // does. NOT_FOUND reads as a link that went missing, and would sit over the songs of
            // the next search, so any error from before is cleared with it.
            // Signed in, a description, more than a name, is asked of ChatGPT too.
            val describes = usesChatGpt() && words.split(Regex("\\s+")).size >= DESCRIPTION_WORDS
            uiState = uiState.copy(
                isSearchingSongs = false,
                isAskingForSongs = describes,
                songSearch = songs,
                songsFromChatGpt = 0,
                songSearchQuery = words,
                error = null
            )
            if (!describes) return@launch
            val named = try {
                found(searcher, tutor?.songsFor(words).orEmpty())
            } finally {
                if (isActive) uiState = uiState.copy(isAskingForSongs = false)
            }
            val rest = songs.filterNot { song -> named.any { SongNames.same(it.title, song.title) && SongNames.sameArtist(it.artist, song.artist) } }
            uiState = uiState.copy(songSearch = named + rest, songsFromChatGpt = named.size)
        }
    }

    /**
     * The songs [named], as title to artist, each looked up all at once, so they're real songs with
     * their links, in the order named; those not found are left out.
     */
    private suspend fun found(searcher: SongSearcher, named: List<Pair<String, String>>): List<MusicMetadata> = coroutineScope {
        named.map { (title, artist) ->
            async {
                // Titles and artists swapped happen.
                searcher.search("$title $artist").firstOrNull { found ->
                    (SongNames.same(found.title, title) && SongNames.sameArtist(found.artist, artist)) ||
                        (SongNames.same(found.title, artist) && SongNames.sameArtist(found.artist, title))
                }
            }
        }.awaitAll().filterNotNull().distinctBy { SongNames.normalize(it.title) + "|" + SongNames.normalize(it.artist) }
    }

    /** The songs ChatGPT named for the description searched, as a playlist to play or share. */
    fun playlistFromSearch() {
        val songs = uiState.songSearch.take(uiState.songsFromChatGpt).takeIf { it.size >= MIN_PLAYLIST } ?: return
        val title = uiState.songSearchQuery
        cancelContentWork()
        showSongList(title, songs)
    }

    /** Songs like [song], from ChatGPT, each found in the catalogue, as a playlist. */
    fun songsLike(song: MusicMetadata) {
        val searcher = songSearcher ?: return
        val tutor = tutor ?: return
        cancelContentWork()
        uiState = uiState.copy(isLoading = true, result = null, link = null, error = null, linkText = "")
        job = viewModelScope.launch {
            val songs = tutor.songsLike(song)?.let { found(searcher, it) }.orEmpty()
            uiState = uiState.copy(isLoading = false)
            if (songs.isEmpty()) return@launch showError(AppError.NO_SONGS_LIKE)
            showSongList(song.title, songs, like = true)
        }
    }

    /**
     * [songs] shown as a list named [title], to play all at once, open one by one, copy or share,
     * like a playlist; [like] when they're songs like the one [title] names. Not kept in Recent:
     * there's no link to open it again by.
     */
    private fun showSongList(title: String, songs: List<MusicMetadata>, like: Boolean = false) {
        uiState = uiState.copy(
            result = MusicMetadata(title, "", ItemType.PLAYLIST, songs.firstNotNullOfOrNull { it.artworkUrl }, songs),
            link = null,
            songsLike = like,
            linkText = "",
            destinationUrls = emptyMap(),
            error = null
        )
    }

    /** Closes the songs offered, leaving the typed text as it was. */
    fun dismissSongSearch() {
        searchJob?.cancel()
        uiState = uiState.copy(isSearchingSongs = false, isAskingForSongs = false, songSearch = emptyList(), songsFromChatGpt = 0, songSearchQuery = "")
    }

    /**
     * Opens the chosen song where the result goes, matched like a song of its own. Its own link,
     * where it has one, is what gets resolved, so it opens in the default app exactly as if that
     * link had been pasted.
     */
    fun chooseSong(song: MusicMetadata) {
        dismissSongSearch()
        // The field shows the link about to be resolved, so a chosen song reads like a pasted one.
        song.url?.let { MusicLinks.parse(it) }?.let { parsed ->
            uiState = uiState.copy(linkText = (parsed as? LinkInput.Link)?.link?.url ?: song.url)
            resolve(parsed, openWhenReady = false)
            return
        }
        val input = MusicLinks.recognizedSong(song)
        uiState = uiState.copy(linkText = input.text)
        resolve(input, openWhenReady = false)
    }

    /** Opens a song ChatGPT named, by [title] and [artist], like one picked from a search. */
    fun openNamedSong(title: String, artist: String) = chooseSong(MusicMetadata(title, artist))

    /** A link stays in the box; a song found by its name leaves it empty, as the song shows right under it. */
    private fun fieldText(link: MusicLink) = if (link.service == null) "" else link.url

    /**
     * A recognized song shows only its name in the field, so the name reads back as the song just
     * looked up, or one in Recent. Recent and the widget also reopen it from its link alone.
     */
    private fun parse(text: String): LinkInput? {
        MusicLinks.parse(text)?.let { return it }
        val typed = text.trim()
        (lastRequest?.first as? LinkInput.RecognizedSong)?.takeIf { it.text == typed }?.let { return it }
        return uiState.history.asSequence().filter { it.link.service == null }
            .map { LinkInput.RecognizedSong(it.link.url, it.metadata) }
            .firstOrNull { it.url == typed || it.text == typed }
    }

    fun settingsLeft() {
        uiState = uiState.copy(leaveSettings = false)
    }

    /**
     * Resolves a link from another app and opens it (or offers destinations) as soon as it is
     * ready; in [destination] when the user already chose one, e.g. with a share sheet target.
     * With [show], it only shows the result, for the user to pick what to do.
     */
    fun resolveIncoming(text: String?, destination: Destination? = null, show: Boolean = false, after: AfterLookup = AfterLookup.OPEN) {
        cancelContentWork()
        val incoming = text?.let { MusicLinks.extractFirstUrl(it) ?: it }?.trim().orEmpty()
        uiState = uiState.copy(
            linkText = incoming,
            handlingIncomingLink = true,
            handingOff = !show,
            afterLookup = after,
            leaveSettings = true
        )
        val input = parse(text.orEmpty())
        if (input == null) {
            // Intercepted services' links include pages Crosstune can't convert, such as a
            // SoundCloud feed; hand those straight to the service's app.
            val service = MusicLinks.serviceFor(incoming) ?: return rejectInput()
            effectChannel.trySend(Effect.Open(incoming, service.packageName, finishAfterOpen = true))
            return
        }
        if (input is LinkInput.Link) uiState = uiState.copy(linkText = input.link.url)
        if (input is LinkInput.RecognizedSong) uiState = uiState.copy(linkText = input.text)
        resolve(input, openWhenReady = !show, destination)
    }

    /**
     * Text that isn't a music link replaces the previous result. Otherwise the error would sit above an
     * old song and offer to open it, as if that were the link that failed.
     */
    private fun rejectInput() {
        cancelContentWork()
        uiState = uiState.copy(
            result = null, destinationUrls = emptyMap(), selectedDestination = null,
            link = null, showDestinationPicker = false
        )
        showError(AppError.INVALID_URL)
    }

    /** Clipboard text pasted with the field's Paste button: looked up, but only opened on request. */
    fun pasteLink(text: String?) {
        if (text.isNullOrBlank()) return showError(AppError.CLIPBOARD_EMPTY)
        val recognized = MusicLinks.parse(text) as? LinkInput.RecognizedSong
        uiState = uiState.copy(linkText = recognized?.text ?: MusicLinks.extractFirstUrl(text) ?: text.trim(), error = null)
        // The box shows only the song's name, so the song itself is looked up, not the name.
        if (recognized != null) {
            uiState = uiState.copy(handlingIncomingLink = false, handingOff = false)
            return resolve(recognized, openWhenReady = false)
        }
        resolveTypedInput()
    }

    /** Clipboard text from the Quick Settings tile or a launcher shortcut, which may name the app to open it in. */
    fun resolveClipboard(text: String?, destination: Destination?, show: Boolean, after: AfterLookup) {
        if (text.isNullOrBlank()) return showError(AppError.CLIPBOARD_EMPTY)
        resolveIncoming(text, destination, show, after)
    }

    fun retry() {
        val (input, openWhenReady, destination) = lastRequest ?: return
        resolve(input, openWhenReady, destination)
    }

    /** Whether Try again listens again, since listening failed before a song was found. */
    var retryListens = false
        private set

    /**
     * Listens for a song playing nearby, then shows it as a pasted link would: by its Apple Music
     * link when Shazam knows it, like a Shazam link, or else by its name, like a Now Playing song.
     * With [lyrics], its words open as soon as it's named. The microphone permission is already granted.
     */
    fun listen(lyrics: Boolean = false) {
        cancelContentWork()
        heardSong = null
        heardClock = null
        retryListens = true
        uiState = uiState.copy(
            listening = true, isLoading = false, isMatching = false, error = null, linkText = "", result = null,
            destinationUrls = emptyMap(), selectedDestination = null, link = null, showDestinationPicker = false,
            handlingIncomingLink = false, handingOff = false
        )
        job = viewModelScope.launch {
            val heard = try {
                listener.listen()
            } finally {
                if (isActive) uiState = uiState.copy(listening = false)
            }
            when (heard) {
                is Heard.Song -> showHeard(heard, lyrics)
                Heard.Nothing -> showListenError(AppError.NO_MATCH)
                is Heard.Failed -> showListenError(heard.error)
            }
        }
    }

    /**
     * Offers what music apps play while Crosstune is in sight, fetching each song's words ahead.
     */
    suspend fun watchPlayingNow() {
        val source = playback?.takeIf { it.hasAccess() }
        if (source == null) {
            playingHeard = null
            uiState = uiState.copy(playingNow = null)
            return
        }
        // Registration, callbacks and cancellation share the main thread, so none can reattach
        // a controller while the lifecycle is releasing its listeners.
        source.nowPlayingUpdates()
            .onEach { heard ->
                playingHeard = heard
                uiState = uiState.copy(playingNow = heard?.metadata)
            }
            // Clock updates keep the offer current without restarting its lyrics lookup.
            .distinctUntilChangedBy { it?.metadata }
            .collectLatest { heard -> heard?.let { lyricsFinder?.firstLook(it.metadata) } }
    }

    /** Shows the song playing now as if just heard, with its words when [lyrics], in time with the app. */
    fun showPlayingNow(lyrics: Boolean) {
        val heard = playingHeard ?: return
        cancelContentWork()
        uiState = uiState.copy(
            error = null, result = null, destinationUrls = emptyMap(), selectedDestination = null, link = null,
            showDestinationPicker = false, handlingIncomingLink = false, handingOff = false
        )
        showHeard(heard, lyrics)
    }

    private fun showHeard(heard: Heard.Song, lyrics: Boolean) {
        retryListens = false
        val input = heard.appleMusicId?.let { MusicLinks.parse("https://www.shazam.com/song/$it") }
            ?: MusicLinks.recognizedSong(heard.metadata)
        uiState = uiState.copy(linkText = (input as? LinkInput.RecognizedSong)?.text ?: (input as LinkInput.Link).link.url)
        resolve(input, openWhenReady = false)
        // Once it's shown, as showing it forgets what was heard for whatever was shown before.
        heardSong = heard.metadata
        heardClock = heard.clock
        heardAtMs = now()
        // Asked for its words, they open straight away, and keep listening along, song after song.
        if (lyrics) showLyricsFor(heard.metadata)
    }

    fun stopListening() {
        job?.cancel()
        uiState = uiState.copy(listening = false)
    }

    private fun showListenError(error: AppError) {
        uiState = uiState.copy(error = error, canRetry = error.canRetry)
    }

    private fun resolve(input: LinkInput, openWhenReady: Boolean, destination: Destination? = null) {
        cancelContentWork()
        lastRequest = Triple(input, openWhenReady, destination)
        pendingOpen = openWhenReady
        // A newer request always wins; the older call is cancelled rather than left to overwrite it.
        uiState = uiState.copy(
            isLoading = true,
            isMatching = false,
            error = null,
            result = null,
            destinationUrls = emptyMap(),
            selectedDestination = destination,
            link = null,
            showDestinationPicker = false
        )
        // A link already in Recent is shown from there rather than looked up again. A short link's
        // target isn't known until it's followed, so it's always looked up.
        val link = (input as? LinkInput.Link)?.link
        // The link as parsed is kept, since Recent doesn't save which frontend's site it came from.
        val saved = link?.let { wanted -> uiState.history.firstOrNull { it.link.url == wanted.url }?.copy(link = wanted) }
            // An album or playlist kept without its songs, e.g. by a version that couldn't read them, is looked up again for them.
            ?.takeUnless { (it.link.type == ItemType.ALBUM || it.link.type == ItemType.PLAYLIST) && it.metadata.tracks.isEmpty() }
        job = viewModelScope.launch {
            // A video from another app that isn't music, like a tutorial, opens as it would without Crosstune.
            val video = link?.takeIf {
                openWhenReady && destination == null && uiState.onlyMusicVideos && it.service == MusicService.YOUTUBE
            }
            // Looked up while asking whether it's music, so a music video costs one wait, not two.
            val lookup = async { saved?.let { Resolution.Resolved(it.link, it.metadata) } ?: withCover(resolver.resolve(input)) }
            if (video != null && matcher.isMusicVideo(video.id) == false) {
                lookup.cancel()
                return@launch openAsIs(video)
            }
            when (val resolution = lookup.await()) {
                is Resolution.Failed -> uiState = uiState.copy(
                    isLoading = false,
                    error = resolution.error,
                    canRetry = resolution.error.canRetry,
                    link = resolution.link,
                    handingOff = false
                )
                // A search page saved while exact matching was on means matching failed that time,
                // e.g. on a flaky network, so a new request for the link tries it again.
                is Resolution.Resolved -> onResolved(resolution, saved?.destinationLinks.orEmpty().filterValues { it.exact || !it.matchingEnabled })
            }
        }
    }

    /**
     * The album cover, for songs that don't come with one: none from Now Playing, and a video frame
     * from YouTube. A YouTube video keeps its frame when no song matches, e.g. a tutorial, and only
     * waits briefly for the cover, since it has a picture either way.
     */
    private suspend fun withCover(resolution: Resolution): Resolution {
        if (resolution !is Resolution.Resolved || resolution.link.type != ItemType.TRACK) return resolution
        val timeoutMs = when (resolution.link.service) {
            null -> if (resolution.metadata.artworkUrl == null) COVER_TIMEOUT_MS else return resolution
            MusicService.YOUTUBE, MusicService.YOUTUBE_MUSIC -> VIDEO_COVER_TIMEOUT_MS
            else -> return resolution
        }
        val cover = matcher.cover(resolution.metadata, timeoutMs) ?: return resolution
        return resolution.copy(metadata = resolution.metadata.copy(artworkUrl = cover))
    }

    /** [prepared] are the links already found for it, when it came from Recent. */
    private suspend fun onResolved(resolution: Resolution.Resolved, prepared: Map<String, PreparedLink>) {
        val entry = HistoryEntry(resolution.link, resolution.metadata, prepared)
        val history = historyStore.add(entry)
        // The box shows the clean link Crosstune works with, e.g. without "?si=" or a short link's redirect.
        uiState = uiState.copy(
            isLoading = false,
            result = resolution.metadata,
            songsLike = false,
            link = resolution.link,
            linkText = fieldText(resolution.link),
            destinationUrls = savedDestinations(entry),
            history = history
        )
        loadTrackCovers()
        prepareResult()
    }

    private var coverJob: Job? = null

    /**
     * Covers for a playlist's songs that came without one, shown as they're found and kept with
     * it in Recent, so it shows them all straight away next time.
     */
    private fun loadTrackCovers() {
        coverJob?.cancel()
        val link = uiState.link ?: return
        val result = uiState.result?.takeIf { it.type == ItemType.PLAYLIST } ?: return
        val missing = result.tracks.indices.filter { result.tracks[it].artworkUrl == null }.take(MAX_COVER_LOOKUPS)
        if (missing.isEmpty()) return
        coverJob = viewModelScope.launch {
            val tracks = result.tracks.toMutableList()
            try {
                matcher.covers(missing.map(tracks::get)) { found, cover ->
                    tracks[missing[found]] = tracks[missing[found]].copy(artworkUrl = cover)
                    // Only while it's still the one on screen.
                    if (uiState.link?.url == link.url) uiState = uiState.copy(result = uiState.result?.copy(tracks = tracks.toList()))
                }
            } finally {
                // Stopped for another link, what was found is still kept with this one.
                withContext(NonCancellable) {
                    uiState = uiState.copy(history = historyStore.update(link.url, result.copy(tracks = tracks.toList())))
                }
            }
        }
    }

    /** The links saved with [entry] that are still good: found with exact matching as it's set now. */
    private fun savedDestinations(entry: HistoryEntry): Map<Destination, PreparedLink> =
        uiState.destinations.mapNotNull { destination ->
            entry.destinationLinks[destination.key]?.takeIf { it.matchingEnabled == uiState.exactMatch }?.let { destination to it }
        }.toMap()

    /** A destination or exact-match preference change prepares the current result again. */
    private fun prepareResultDestination() {
        cancelTrackWork()
        if (uiState.result == null || uiState.showDestinationPicker) return
        job?.cancel()
        uiState = uiState.copy(isMatching = false)
        job = viewModelScope.launch { prepareResult() }
    }

    private suspend fun prepareResult() {
        // A link shared from the app the user listens in would only open back in that app, so ask
        // where else it should go instead.
        // A frontend's link, e.g. Invidious's, is worth opening in the service's own app.
        val ownService = uiState.link?.takeUnless { it.viaFrontend }?.service?.takeIf {
            uiState.hasDefault && (uiState.resultDestination as? Destination.Service)?.service == it
        }
        // A share sheet action asked to share the converted link rather than open it.
        if (pendingOpen && uiState.afterLookup != AfterLookup.OPEN) {
            pendingOpen = false
            val url = prepareDestination(uiState.resultDestination) ?: return
            effectChannel.send(Effect.Share(url))
            return
        }
        // A playlist can't open as one in another app, so its songs show here to pick from,
        // unless the user already picked where it goes.
        val opensWhole = uiState.link?.youtubePlaylistOn(uiState.resultDestination.matchService) != null
        if (pendingOpen && uiState.selectedDestination == null && !opensWhole &&
            uiState.result?.type == ItemType.PLAYLIST && uiState.result?.tracks.orEmpty().isNotEmpty()
        ) {
            pendingOpen = false
            uiState = uiState.copy(handingOff = false)
        }
        // Incoming links without a chosen destination must not search the default before asking.
        val ask = uiState.selectedDestination == null &&
            (uiState.askEachTime || !uiState.setupComplete || ownService != null)
        if (pendingOpen && ask) {
            pendingOpen = false
            uiState = uiState.copy(showDestinationPicker = true, pickerHides = ownService, handingOff = false)
            return
        }
        prepareDestination(uiState.resultDestination)
        if (!pendingOpen) return
        pendingOpen = false
        open(uiState.resultDestination, finishAfterOpen = true)
    }

    private var trackJob: Job? = null

    /**
     * Plays the result's songs as one queue in YouTube Music or YouTube, [MAX_QUEUE] at most, which
     * is all YouTube takes: a longer list plays in parts, [from] the first song of one.
     */
    fun playAll(from: Int) {
        val destination = uiState.resultDestination
        val service = destination.matchService ?: return
        val tracks = uiState.result?.tracks.orEmpty().drop(from).take(MAX_QUEUE)
        cancelTrackWork()
        trackJob = viewModelScope.launch {
            uiState = uiState.copy(queueProgress = 0 to tracks.size, queueFrom = from)
            try {
                val url = matcher.youtubeQueue(service, tracks) { looked -> uiState = uiState.copy(queueProgress = looked to tracks.size) }
                if (url == null) showError(AppError.NOT_FOUND) else effectChannel.send(Effect.Open(url, destination.packageName, finishAfterOpen = false))
            } finally {
                if (isActive) uiState = uiState.copy(queueProgress = null)
            }
        }
    }

    /** Opens one of a playlist's songs where the result goes, matched like a song of its own. */
    fun openTrack(track: MusicMetadata) {
        val destination = uiState.resultDestination
        cancelTrackWork()
        trackJob = viewModelScope.launch {
            // A song from a playlist on the same service is opened by its own link.
            track.url?.takeIf { destination.matchService != null && MusicLinks.serviceFor(it) == destination.matchService }?.let { url ->
                effectChannel.send(Effect.Open(destination.adapt(url).forSharing(), destination.packageName, finishAfterOpen = false))
                return@launch
            }
            // YouTube and YouTube Music play the same videos, so one opens as itself in the other.
            track.url?.let { MusicLinks.linkFor(it) }?.youtubeVideoOn(destination.matchService)?.let { url ->
                effectChannel.send(Effect.Open(destination.adapt(url).forSharing(), destination.packageName, finishAfterOpen = false))
                return@launch
            }
            val match = destination.matchService?.takeIf { uiState.exactMatch }?.let { service ->
                uiState = uiState.copy(isMatching = true)
                try {
                    matcher.lookFor(service, track)
                } finally {
                    if (isActive) uiState = uiState.copy(isMatching = false)
                }
            }
            val exactUrl = (match as? ExactMatcher.Match.Found)?.url
            val url = exactUrl?.let(destination::adapt) ?: destination.searchUrl(searchQuery(track))
            val service = destination.matchService
            // Not found there, as opposed to no answer, e.g. offline, which just searches.
            if (match == ExactMatcher.Match.Missing && service != null && canBeMissing(service, track)) {
                return@launch openMissing(track, track.url?.let(MusicLinks::linkFor), destination, url, finishAfterOpen = false)
            }
            effectChannel.send(Effect.Open(url.forSharing(), destination.packageName, finishAfterOpen = false))
        }
    }

    /** Opens the result from a button tap; the picker passes the destination chosen for this link. */
    fun openResult(destination: Destination? = null, finishAfterOpen: Boolean = false) {
        val chosen = destination ?: uiState.resultDestination
        destination?.let(::rememberLastApp)
        pendingOpen = false
        uiState = uiState.copy(
            showDestinationPicker = false, isMatching = false,
            selectedDestination = destination ?: uiState.selectedDestination
        )
        job?.cancel()
        job = viewModelScope.launch { open(chosen, finishAfterOpen, tapped = true) }
    }

    /** [tapped] is a tap on its button or in the picker, rather than a link that came in opening on its own. */
    private suspend fun open(destination: Destination, finishAfterOpen: Boolean, tapped: Boolean = false) {
        val url = prepareDestination(destination) ?: return
        val original = uiState.link?.takeIf { it.service != null }
        if (uiState.resultMissing) {
            when (uiState.notFoundAction) {
                // It stays here, saying it isn't there, with both ways on. With nowhere else to open,
                // e.g. a song typed in, a tap on "Search in" searches.
                NotFoundAction.ASK -> if (!tapped || original != null) {
                    pendingOpen = false
                    // Still the link that came in, so it shows over first-run setup.
                    uiState = uiState.copy(handingOff = false, selectedDestination = destination)
                    return
                }
                NotFoundAction.ORIGINAL -> if (original != null) {
                    effectChannel.send(Effect.Open(original.url, original.service?.packageName, finishAfterOpen))
                    return
                }
                NotFoundAction.SEARCH -> Unit
            }
        }
        effectChannel.send(Effect.Open(url, destination.packageName, finishAfterOpen))
    }

    /** Searches where the result goes, though it wasn't found there, e.g. under another name. */
    fun searchAnyway() {
        val destination = uiState.resultDestination
        val url = uiState.destinationUrls[destination]?.url ?: return
        effectChannel.trySend(Effect.Open(url.forSharing(), destination.packageName, finishAfterOpen = false))
    }

    /**
     * A song from Recent or a playlist that [destination] doesn't have: searched, opened where it's
     * from, or offered both, as set.
     */
    private suspend fun openMissing(song: MusicMetadata, original: MusicLink?, destination: Destination, searchUrl: String, finishAfterOpen: Boolean) {
        val opensWhere = original?.takeIf { it.service != null }
        when {
            uiState.notFoundAction == NotFoundAction.SEARCH || (uiState.notFoundAction == NotFoundAction.ORIGINAL && opensWhere == null) ->
                effectChannel.send(Effect.Open(searchUrl.forSharing(), destination.packageName, finishAfterOpen))
            uiState.notFoundAction == NotFoundAction.ORIGINAL ->
                effectChannel.send(Effect.Open(opensWhere!!.url, opensWhere.service?.packageName, finishAfterOpen))
            else -> uiState = uiState.copy(
                notFoundOffer = NotFoundOffer(song, destination, opensWhere, searchUrl), handingOff = false
            )
        }
    }

    /** The offer's way: where the song is, or a search where it was going. */
    fun takeNotFoundOffer(original: Boolean) {
        val offer = uiState.notFoundOffer ?: return
        uiState = uiState.copy(notFoundOffer = null)
        val link = offer.original
        effectChannel.trySend(
            if (original && link != null) Effect.Open(link.url, link.service?.packageName, finishAfterOpen = false)
            else Effect.Open(offer.searchUrl.forSharing(), offer.destination.packageName, finishAfterOpen = false)
        )
    }

    fun dismissNotFoundOffer() {
        uiState = uiState.copy(notFoundOffer = null)
    }

    fun selectNotFoundAction(action: NotFoundAction) {
        preferences.edit { putString(KEY_NOT_FOUND, action.name) }
        uiState = uiState.copy(notFoundAction = action)
    }

    private var lyricsJob: Job? = null
    private var followJob: Job? = null

    /** The last song Crosstune heard, so its words can follow on from where it was heard. */
    private var heardSong: MusicMetadata? = null

    /** Where [heardSong] is, as far as hearing it can tell, kept steady against what one hearing gets wrong. */
    private var heardClock: PlaybackClock? = null

    /** A place heard far from where the words are, held until a second hearing agrees with it. */
    private var unconfirmed: PlaybackClock? = null

    /** When the song was last heard, so a hearing long ago isn't held against an app's say. */
    private var heardAtMs = 0L

    /** Where the app playing the song says it is, before taking off how far its video is ahead. */
    private var appFollowing: Following? = null

    /** How far the video an app plays is ahead of its song, as last heard; null until it's been heard. */
    private var videoLeadMs: Long? = null

    private var nextJob: Job? = null

    /** Set once the song shown was playing while its words were open, so the next song an app plays takes over. */
    private var shownPlayed = false

    /** Set while Crosstune is out of sight with no words floating, when the words don't go on to the next song. */
    private var nextPaused = false

    /**
     * Fetches the shown song's words and shows them. A song with no words of its own says so
     * rather than showing nothing, so it isn't read as a lookup that hasn't answered. Timed words
     * follow the song while something says where it is.
     */
    fun showLyrics() {
        // Retry asks again for the words shown, which after listening along may be another song than the result's.
        showLyricsFor(uiState.lyricsFor ?: uiState.result ?: return)
    }

    private fun showLyricsFor(song: MusicMetadata, playing: Boolean = false) {
        // Only a song has words, and one whose artist is unknown can't be looked up safely.
        val finder = lyricsFinder ?: return
        if (song.type != ItemType.TRACK || song.artist.isBlank()) return
        // Already showing these words, or on the way, so a second tap doesn't fetch them again. A lookup
        // that failed is worth asking again, so Retry is the one case that does fetch.
        if (uiState.lyricsFor == song && (uiState.lyrics.isNotEmpty() || uiState.isLoadingLyrics)) return
        // A song just heard, or playing as it's shown, is followed on to the next one; one picked to read stays.
        shownPlayed = playing || heardSong?.let { sameSong(it, song) } == true
        lyricsJob?.cancel()
        stopLearning()
        stopFollowing()
        foundLines = emptyList()
        japanese = null
        syncPoints = emptyList()
        lyricsJob = viewModelScope.launch {
            var first: LyricsFinder.FirstLook? = null
            uiState = uiState.copy(
                lyricsFor = song, lyrics = "", lyricLines = emptyList(), lyricsSynced = false, lyricsFailed = false, isLoadingLyrics = true, learning = uiState.learning.fresh(), chords = uiState.chords.fresh(),
                canFollowApps = playback?.hasAccess() == true
            )
            try {
                // A lookup that failed says so, rather than claiming the song has no words. The words
                // show as soon as they're found, and their timing may get better after.
                first = finder.firstLook(song)
                when (val answer = first.lyrics) {
                    is LyricsFinder.Lyrics.Found -> {
                        foundLines = answer.lines
                        syncPoints = syncStore.load(song, answer.lines)
                        uiState = uiState.copy(
                            lyrics = answer.words, lyricLines = LyricsSync.adjust(answer.lines, syncPoints), lyricsSynced = syncPoints.isNotEmpty(), lyricsFailed = false
                        )
                        uiState = uiState.copy(learning = uiState.learning.fresh(Readings.scriptOf(wordLines())))
                        timeSavedLines(song)
                        learn()
                        chords()
                        // Japanese in romaji is written back in Japanese, so it reads like any Japanese song.
                        if (usesChatGpt() && isRomaji(answer.words)) writeInJapanese(song)
                    }
                    LyricsFinder.Lyrics.None -> uiState = uiState.copy(lyrics = "", lyricsFailed = false)
                    LyricsFinder.Lyrics.Unavailable -> uiState = uiState.copy(lyrics = "", lyricsFailed = true)
                }
            } finally {
                if (isActive) uiState = uiState.copy(isLoadingLyrics = false)
            }
            follow(song)
            first?.takeIf { !it.final }?.let { retime(song, it.lyrics) }
        }
        watchNext(song)
        // A song Crosstune just heard is still playing nearby, so it keeps listening, to stay in time with it.
        if (heardSong?.let { sameSong(it, song) } == true) listenAlong()
    }

    /**
     * [found], [song]'s words as shown, timed better once that's worked out, e.g. from another copy
     * of the song; kept as they are when they're put in time by hand meanwhile.
     */
    private suspend fun retime(song: MusicMetadata, found: LyricsFinder.Lyrics) {
        val better = lyricsFinder?.retimed(song, found) as? LyricsFinder.Lyrics.Found ?: return
        if (found !is LyricsFinder.Lyrics.Found || better.lines == found.lines || uiState.lyricsFor?.let { sameSong(it, song) } != true || syncPoints.isNotEmpty()) return
        foundLines = better.lines
        uiState = uiState.copy(lyricLines = better.lines)
    }

    private fun sameSong(one: MusicMetadata, other: MusicMetadata) =
        SongNames.same(one.title, other.title) && SongNames.sameArtist(one.artist, other.artist)

    /** Where [song] is, as Crosstune last heard it, when that's the song it heard. */
    private fun heardFollowing(song: MusicMetadata): Following? =
        heardClock?.takeIf { heardSong?.let { sameSong(it, song) } == true }?.let { Following(it, app = null, canSeek = false) }

    private var alongJob: Job? = null

    /** Told when the app playing the song shown starts playing it, e.g. after a pause, so listening along wakes. */
    private val appPlaysAgain = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * Keeps the microphone listening along with the words: every little while it asks what's
     * playing nearby and how far into it, so a pause, a skip or the next song is followed. Only
     * while the words are on screen and Crosstune is in sight.
     */
    fun listenAlong() {
        if (alongJob?.isActive == true) return
        uiState = uiState.copy(listeningAlong = true)
        alongJob = viewModelScope.launch {
            var quiet = 0
            while (isActive) {
                when (val heard = listener.listen()) {
                    is Heard.Song -> {
                        quiet = 0
                        heardAlong(heard)
                        delay(listenAlongPauseMs)
                    }
                    // Nothing heard, the music has most likely stopped, so the words stop where they are,
                    // and the microphone rests, longer each time, until the app playing it plays again.
                    Heard.Nothing -> {
                        heardClock?.takeIf { it.playing }?.let { clock ->
                            heardClock = clock.copy(positionMs = clock.positionAt(now()), atMs = now(), playing = false)
                            keepInTime()
                        }
                        val rest = quietPauseMs * (1L shl minOf(quiet, QUIET_PAUSE_DOUBLINGS))
                        quiet++
                        withTimeoutOrNull(rest) { appPlaysAgain.first() }
                    }
                    is Heard.Failed -> {
                        // Without the microphone there's nothing to listen with; anything else, e.g. the
                        // network or Shazam being busy, is tried again in a while.
                        if (heard.error == AppError.MICROPHONE) {
                            stopListeningAlong()
                            uiState = uiState.copy(error = AppError.MICROPHONE, canRetry = false)
                            return@launch
                        }
                        delay(listenAlongPauseMs * 2)
                    }
                }
            }
        }
    }

    /**
     * The song heard: the one shown keeps in time with it; another one takes over the words, once
     * the one shown was heard playing, as the next song. A song picked to read stays.
     */
    private fun heardAlong(heard: Heard.Song) {
        val song = heard.metadata
        val shown = uiState.lyricsFor ?: return
        if (!sameSong(song, shown) && heardSong?.let { sameSong(it, shown) } != true) return
        // While a music app plays the song shown, it says exactly what plays: a hearing naming it
        // otherwise, as Shazam calls "Song (2018 Remix)" just "Song (Remix)", is the same song.
        if (!sameSong(song, shown) && appFollowing?.clock?.playing == true) return
        if (heard.clock != null) heardAtMs = now()
        // Heard by another name right where the song shown is, well into it, it's the same recording
        // on another release, as Shazam sometimes names it, not the next song, which starts over.
        val clock = heardClock
        val at = heard.clock
        if (at != null && clock != null && clock.playing && heardSong?.let { sameSong(it, shown) } == true &&
            clock.positionAt(now()) >= SAME_RECORDING_AFTER_MS && abs(at.positionAt(now()) - clock.positionAt(now())) <= SAME_RECORDING_MS
        ) {
            heardClock = steadied(clock, at)
            return keepInTime()
        }
        if (heardSong?.let { sameSong(it, song) } != true || !sameSong(song, shown)) {
            heardSong = song
            heardClock = heard.clock
            unconfirmed = null
            if (!sameSong(song, shown)) {
                remember(heard)
                return showLyricsFor(song)
            }
        } else {
            heardClock = steadied(heardClock, heard.clock ?: return)
        }
        keepInTime()
    }

    /**
     * A song heard along the way joins Recent, as one Crosstune listened for does: by its Apple Music
     * link when Shazam knows it, or else by its name, its cover found by its name when it came with none.
     */
    private fun remember(heard: Heard.Song) {
        val link = heard.appleMusicId?.let { (MusicLinks.parse("https://www.shazam.com/song/$it") as? LinkInput.Link)?.link }
            ?: MusicLinks.recognizedSong(heard.metadata).let { MusicLink(null, ItemType.TRACK, it.url, it.url) }
        uiState = uiState.copy(history = historyStore.add(HistoryEntry(link, heard.metadata)))
        // Named by an app that gave no cover, it's found by its name, so Recent and its words show one.
        if (heard.metadata.artworkUrl != null) return
        viewModelScope.launch {
            val cover = withContext(systemDispatcher) { matcher.cover(heard.metadata, COVER_TIMEOUT_MS) } ?: return@launch
            val shown = uiState.lyricsFor?.takeIf { it == heard.metadata }
            uiState = uiState.copy(
                history = historyStore.update(link.url, heard.metadata.copy(artworkUrl = cover)),
                lyricsFor = shown?.copy(artworkUrl = cover) ?: uiState.lyricsFor
            )
        }
    }

    /**
     * Where the song is, from a new hearing of it. Each hearing is off by a moment, so one close to
     * where the words already are changes nothing; one far off, e.g. the chorus heard as its other
     * time, is only believed once a second hearing agrees, as a skip would. A song that had stopped
     * starts again from where it's heard.
     */
    private fun steadied(current: PlaybackClock?, heard: PlaybackClock): PlaybackClock {
        if (current == null || !current.playing) return heard.also { unconfirmed = null }
        val at = now()
        if (abs(heard.positionAt(at) - current.positionAt(at)) <= STEADY_MS) return current.also { unconfirmed = null }
        val earlier = unconfirmed
        if (earlier != null && abs(heard.positionAt(at) - earlier.positionAt(at)) <= STEADY_MS) return heard.also { unconfirmed = null }
        unconfirmed = heard
        return current
    }

    /**
     * The words shown keep to where the song was heard, unless a music app playing it says, more
     * exactly. A video the app plays may open with more than the song, so how far it's ahead is
     * taken from where the song was just heard, and taken off.
     */
    private fun keepInTime() {
        val shown = uiState.lyricsFor ?: return
        appFollowing?.takeIf { it.video }?.let { app -> leadOf(app, shown)?.let { videoLeadMs = it } }
        uiState = uiState.copy(following = inSong(appFollowing) ?: heardFollowing(shown))
    }

    /** How far [app]'s video is ahead of [song], by where the song was heard a moment ago, both playing. */
    private fun leadOf(app: Following, song: MusicMetadata): Long? {
        val heard = heardClock?.takeIf { it.playing && app.clock.playing && now() - heardAtMs <= HEARD_LATELY_MS } ?: return null
        if (heardSong?.let { sameSong(it, song) } != true) return null
        val at = now()
        return app.clock.positionAt(at) - heard.positionAt(at)
    }

    /**
     * Where the song is in what [app] plays: a video's place less how far it's ahead of the song.
     * One not yet heard, so not lined up, gives way to where the song was heard, if it was.
     */
    private fun inSong(app: Following?): Following? {
        if (app == null || !app.video) return app
        val lead = videoLeadMs ?: return uiState.lyricsFor?.let(::heardFollowing) ?: app
        return app.copy(clock = app.clock.copy(positionMs = app.clock.positionMs - lead))
    }

    /** [positionMs] in the song, where the app playing it has it: later, in a video ahead of it. */
    private fun inApp(positionMs: Long) = positionMs + (videoLeadMs?.takeIf { appFollowing?.video == true } ?: 0L)

    fun stopListeningAlong() {
        pauseListeningAlong()
        uiState = uiState.copy(listeningAlong = false)
    }

    /** Crosstune went out of sight: the microphone stops, and starts again when it's back. */
    fun pauseListeningAlong() {
        alongJob?.cancel()
        alongJob = null
    }

    fun resumeListeningAlong() {
        if (uiState.listeningAlong) listenAlong()
    }

    /**
     * Follows [song] as it plays, for its timed words: in the music app playing it, or else from
     * where Crosstune heard it, which can't tell a pause or a skip but is close while it plays on.
     * Words that aren't timed still follow the app, to play and pause it from them.
     */
    private fun follow(song: MusicMetadata) {
        stopFollowing()
        uiState = uiState.copy(following = heardFollowing(song))
        val source = playback?.takeIf { it.hasAccess() } ?: return
        followJob = viewModelScope.launch {
            source.follow(song).collect { playing ->
                if (playing?.clock?.playing == true && appFollowing?.clock?.playing != true) appPlaysAgain.tryEmit(Unit)
                // The app stopped it, was closed or went on to another song: the words stop where it left
                // them, rather than run on from where the song was heard before, until it's heard again.
                val left = inSong(appFollowing)?.clock?.takeIf { playing == null }?.let { PlaybackClock(it.positionAt(now()), now(), playing = false) }
                if (left != null && heardSong?.let { sameSong(it, song) } == true) heardClock = left
                appFollowing = playing
                if (playing?.video == true && videoLeadMs == null) leadOf(playing, song)?.let { videoLeadMs = it }
                uiState = uiState.copy(following = inSong(playing) ?: heardFollowing(song) ?: left?.let { Following(it, app = null, canSeek = false) })
            }
        }
    }

    /**
     * Once the song shown was playing, the next song a music app plays takes over its words: when the
     * app goes on to it, or it's skipped to, or another app plays it instead. Until then, e.g. for a
     * song picked to read while another plays, the words stay. Not while Crosstune is out of sight,
     * with no words floating; back in sight, it catches up.
     */
    private fun watchNext(song: MusicMetadata) {
        nextJob?.cancel()
        nextJob = null
        val source = playback?.takeIf { it.hasAccess() && !nextPaused } ?: return
        nextJob = viewModelScope.launch {
            combine(source.follow(song), source.nowPlayingUpdates(), ::Pair).collect { (app, playing) ->
                if (app?.clock?.playing == true) shownPlayed = true
                // Practice moves the music app from song to song, which the words stay out of.
                if (uiState.practice != null) return@collect
                val skips = (app?.canSkipNext == true) to (app?.canSkipPrevious == true)
                if (skips != (uiState.canSkipNext to uiState.canSkipPrevious)) uiState = uiState.copy(canSkipNext = skips.first, canSkipPrevious = skips.second)
                // An app still playing the song shown, by another name, e.g. "Song - Remastered", plays no other.
                val next = playing?.takeIf { shownPlayed && app?.clock?.playing != true && !sameSong(it.metadata, song) } ?: return@collect
                remember(next)
                showLyricsFor(next.metadata, playing = true)
            }
        }
    }

    /** Crosstune went out of sight with no words floating: the words stay on the song they show until it's back. */
    fun pauseFollowingNext() {
        nextPaused = true
        nextJob?.cancel()
        nextJob = null
    }

    fun resumeFollowingNext() {
        if (!nextPaused) return
        nextPaused = false
        uiState.lyricsFor?.let(::watchNext)
    }

    /** Has the music app the words follow go on to its [next] song, or back to the one before, whose words then show. */
    fun skipSong(next: Boolean) {
        val song = uiState.lyricsFor ?: return
        if (!(if (next) uiState.canSkipNext else uiState.canSkipPrevious)) return
        shownPlayed = true
        playback?.skip(song, next)
    }

    private fun stopFollowing() {
        followJob?.cancel()
        followJob = null
        appFollowing = null
        videoLeadMs = null
        uiState = uiState.copy(following = null)
    }

    /** Set once Android's page for the access was opened, so coming back without it can say what to try. */
    private var askedForAccess = false

    /**
     * Back from Android's settings, where following music apps may have just been allowed. Back
     * without it, the steps show, since Android may have kept the switch from turning on.
     */
    fun refreshFollowing() {
        val access = playback?.hasAccess() == true
        if (access) {
            askedForAccess = false
            uiState = uiState.copy(followHelp = false)
        } else if (askedForAccess) {
            uiState = uiState.copy(followHelp = true)
        }
        if (access == uiState.canFollowApps) return
        uiState = uiState.copy(canFollowApps = access)
        uiState.lyricsFor?.takeIf { !uiState.isLoadingLyrics }?.let(::follow)
    }

    /**
     * Allow, for the words to follow music apps: true when Android's page for it can just open.
     * Where Android restricts it first, the steps show instead, that one included.
     */
    fun allowFollowing(): Boolean {
        if (playback?.restricted() == true) {
            uiState = uiState.copy(followHelp = true)
            return false
        }
        askedForAccess = true
        return true
    }

    /** Android's page for the access is opening, from the steps. */
    fun openingFollowAccess() {
        askedForAccess = true
    }

    fun dismissFollowHelp() {
        askedForAccess = false
        uiState = uiState.copy(followHelp = false)
    }

    fun selectPalette(palette: Palette) {
        preferences.edit { putString(KEY_PALETTE, palette.name) }
        uiState = uiState.copy(palette = palette)
    }

    fun setPureBlack(on: Boolean) {
        preferences.edit { putBoolean(KEY_PURE_BLACK, on) }
        uiState = uiState.copy(pureBlack = on)
    }

    /** Kept only once let go, [save], rather than at every step of the slider. */
    fun setVisualsShade(shade: Float, save: Boolean) {
        if (save) preferences.edit { putFloat(KEY_VISUALS_SHADE, shade) }
        uiState = uiState.copy(visualsShade = shade)
    }

    fun nextVisual() {
        uiState = uiState.copy(visualsSkips = uiState.visualsSkips + 1)
    }

    fun setVisuals(on: Boolean) {
        preferences.edit { putBoolean(KEY_VISUALS, on) }
        uiState = uiState.copy(visuals = on)
    }

    /**
     * How the floating words look and where they are, as changed on them; kept once a change is done,
     * e.g. at the end of a drag, rather than at every step of it.
     */
    fun setFloating(options: FloatingOptions, save: Boolean) {
        uiState = uiState.copy(floating = options)
        if (save) preferences.edit {
            putFloat(KEY_FLOATING_BACKGROUND, options.background)
            putFloat(KEY_FLOATING_SCALE, options.scale)
            putBoolean(KEY_FLOATING_PREVIOUS_LINE, options.previousLine)
            putBoolean(KEY_FLOATING_NEXT_LINE, options.nextLine)
            putBoolean(KEY_FLOATING_LOCKED, options.locked)
            putInt(KEY_FLOATING_LEFT, options.left)
            putInt(KEY_FLOATING_TOP, options.top)
            putBoolean(KEY_FLOATING_VISUALS, options.visuals)
        }
    }

    fun setCanFloatOverApps(can: Boolean) {
        uiState = uiState.copy(canFloatOverApps = can)
    }

    fun selectTheme(theme: ThemeMode) {
        preferences.edit { putString(KEY_THEME, theme.name) }
        uiState = uiState.copy(theme = theme)
    }

    /** Moves the music app playing the song to a line of its words. */
    fun seekLyrics(positionMs: Long) {
        val song = uiState.lyricsFor ?: return
        if (uiState.following?.canSeek != true) return
        playback?.seekTo(song, inApp(positionMs))
    }

    /**
     * Line [index] is being sung right now, the user says: it's put there, and the lines around it
     * move with it. Only while something says where the song is.
     */
    fun syncLine(index: Int) {
        val song = uiState.lyricsFor ?: return
        val line = foundLines.getOrNull(index) ?: return
        val position = uiState.following?.clock?.positionAt(now()) ?: return
        setSync(song, LyricsSync.with(syncPoints, SyncPoint(line.timeMs, position.coerceAtLeast(0L))))
    }

    /** Plays or pauses the song in the music app the words follow. */
    fun playPause() {
        val song = uiState.lyricsFor ?: return
        val app = uiState.following?.takeIf { it.app != null } ?: return
        playback?.playPause(song, !app.clock.playing)
    }

    /** The words back in time as they were found. */
    fun resetSync() {
        setSync(uiState.lyricsFor ?: return, emptyList())
    }

    private fun setSync(song: MusicMetadata, points: List<SyncPoint>) {
        syncPoints = points
        // Kept for the words as found, which is how they're found next time.
        syncStore.save(song, foundLines, points)
        uiState = uiState.copy(lyricLines = shown(LyricsSync.adjust(foundLines, points)), lyricsSynced = points.isNotEmpty())
        // Lines kept to practise play from where they're heard now.
        timeSavedLines(song, again = true)
    }

    private var romajiJob: Job? = null

    /**
     * Has ChatGPT write [song]'s words, found in romaji, in Japanese, each line timed as found; then
     * they're worked out again as Japanese, with readings, kana and romaji of their own.
     */
    private fun writeInJapanese(song: MusicMetadata) {
        val lines = wordLines()
        romajiJob = viewModelScope.launch {
            val written = tutor?.fromRomaji(song, lines) ?: return@launch
            if (uiState.lyricsFor != song || wordLines() != lines) return@launch
            japanese = written
            stopLearning()
            uiState = uiState.copy(
                lyrics = written.joinToString("\n"),
                lyricLines = shown(uiState.lyricLines),
                learning = uiState.learning.fresh(Readings.scriptOf(written)).copy(fromRomaji = true),
                chords = uiState.chords.fresh()
            )
            learn()
            chords()
        }
    }

    /**
     * Closes the words. The next song starts without them, rather than showing the last one's, and
     * what was heard is forgotten, so words opened later don't listen along to what plays by then.
     */
    fun dismissLyrics() {
        heardSong = null
        lyricsJob?.cancel()
        lyricsJob = null
        nextJob?.cancel()
        nextJob = null
        shownPlayed = false
        uiState = uiState.copy(canSkipNext = false, canSkipPrevious = false)
        stopLearning()
        stopFollowing()
        stopListeningAlong()
        askedForAccess = false
        uiState = uiState.copy(
            lyricsFor = null, lyrics = "", lyricLines = emptyList(), lyricsSynced = false, lyricsFailed = false, isLoadingLyrics = false, followHelp = false,
            learning = uiState.learning.fresh(), chords = uiState.chords.fresh()
        )
    }

    private var readingsJob: Job? = null
    private var translationJob: Job? = null

    /** What was being worked out is for words no longer shown. */
    private fun stopLearning() {
        chordsJob?.cancel()
        readingsJob?.cancel()
        translationJob?.cancel()
        romajiJob?.cancel()
        wordJob?.cancel()
        explanationJob?.cancel()
        songExplanationJob?.cancel()
        songFactsJob?.cancel()
        questionJob?.cancel()
        songQuestionJob?.cancel()
        wordsToKnowJob?.cancel()
        artistJob?.cancel()
        examplesJob?.cancel()
        stopRepeating()
        uiState = uiState.copy(word = null)
    }

    /** Keeps line [index] of the words shown, with what helps read it, or lets it go when it's kept. */
    fun toggleSavedLine(index: Int) {
        val line = savedLineAt(index) ?: return
        uiState = uiState.copy(savedLines = savedLinesStore.toggle(line))
    }

    /**
     * The lines of a song chosen to learn: lines [add] of the words shown kept, each with what helps
     * read it, and [remove] let go; then, when asked to [practise], the song's practised on its own.
     */
    fun chooseLines(add: List<Int>, remove: List<SavedLine>, practise: Boolean) {
        val lines = add.sorted().mapNotNull(::savedLineAt)
        if (lines.isNotEmpty()) uiState = uiState.copy(savedLines = savedLinesStore.addAll(lines))
        if (remove.isNotEmpty()) uiState = uiState.copy(savedLines = savedLinesStore.removeAll(remove))
        if (practise) practiseSong()
    }

    /** Keeps [lines] let go again, as they were, practised as far as they had been. */
    fun keepAgain(lines: List<SavedLine>) {
        uiState = uiState.copy(savedLines = savedLinesStore.addAll(lines))
    }

    /** Practises the lines of the song whose words show, and no other's. */
    fun practiseSong() {
        startPractice(uiState.lyricsFor ?: return)
    }

    /**
     * Line [index] of the words shown, as it's kept: with what helps read it, where it's sung, to
     * the next line, and the app playing it, to play it again.
     */
    private fun savedLineAt(index: Int): SavedLine? {
        val song = uiState.lyricsFor ?: return null
        val text = wordLines().getOrNull(index) ?: return null
        val learning = uiState.learning
        val reading = learning.lines.getOrNull(index)
        return SavedLine(
            text,
            reading?.reading?.takeIf { it != text },
            reading?.romanized?.takeIf { it.isNotBlank() && it != reading.reading },
            learning.translations.getOrNull(index),
            song.title,
            song.artist,
            learning.explanation?.takeIf { it.index == index && it.done }?.text,
            ruby = reading?.parts?.takeIf { parts -> parts.any { it.reading != null } }
        ).timedAt(index)
    }

    /** This line, timed as line [index] of the timed words shown, when they are, with the app playing them. */
    private fun SavedLine.timedAt(index: Int): SavedLine {
        val lines = uiState.lyricLines
        val start = lines.getOrNull(index)?.timeMs ?: return copy(artworkUrl = uiState.lyricsFor?.artworkUrl)
        // Timed again, it stays with the app it was kept from, when none follows the words now.
        val app = appFollowing
        return copy(
            startMs = start, endMs = endOf(lines, index), leadMs = videoLeadMs?.takeIf { app?.video == true } ?: leadMs.takeIf { app == null } ?: 0L,
            app = app?.appPackage ?: this.app, artworkUrl = uiState.lyricsFor?.artworkUrl ?: artworkUrl
        )
    }

    /**
     * Where line [index] of [lines] ends: where the words say, when they're timed that closely, or
     * else where the next line starts; the last line, with no next one, is given a few seconds.
     */
    private fun endOf(lines: List<LyricLine>, index: Int): Long {
        val line = lines[index]
        return line.endMs ?: lines.drop(index + 1).firstOrNull { it.timeMs > line.timeMs }?.timeMs ?: (line.timeMs + LAST_LINE_MS)
    }

    /**
     * Lines of [song] kept are read as its lines are now, put right, but for the ones put right by
     * hand; only those with no reading yet when [unread].
     */
    private fun rereadSavedLines(song: MusicMetadata, unread: Boolean = false) {
        val lines = wordLines()
        val again = uiState.savedLines.filter { it.title == song.title && it.artist == song.artist && it.line == null && !it.readByHand && (!unread || it.ruby == null) }
            .mapNotNull { saved -> lines.indexOf(saved.text).takeIf { it >= 0 }?.let(::savedLineAt) }
        if (again.isNotEmpty()) uiState = uiState.copy(savedLines = savedLinesStore.reread(again))
    }

    /**
     * Lines of [song] kept before they could be timed are timed now, by the timed words found for it;
     * [again], all its lines are, as when the words were put in time by hand.
     */
    private fun timeSavedLines(song: MusicMetadata, again: Boolean = false) {
        val lines = uiState.lyricLines.takeIf { it.isNotEmpty() } ?: return
        val untimed = uiState.savedLines.filter { it.title == song.title && it.artist == song.artist && (again || !it.timed) }
        untimed.forEach { saved ->
            val index = lines.indexOfFirst { it.text == (saved.line ?: saved.text) }.takeIf { it >= 0 } ?: return@forEach
            uiState = uiState.copy(savedLines = savedLinesStore.timed(saved.timedAt(index), again))
        }
    }

    /**
     * Practises the lines due, as cards, each song's together; or only [song]'s, all of them when none
     * is due, to learn it. What the music app plays now is kept, to go back to once done.
     */
    fun startPractice(song: MusicMetadata? = null) {
        stopRepeating()
        val queue = if (song == null) sessionOf(uiState.savedLines, wallClock()) else {
            val its = uiState.savedLines.filter { it.title == song.title && it.artist == song.artist }
            sessionOf(its, wallClock()).ifEmpty { sessionOf(its, Long.MAX_VALUE) }
        }
        // The song the words follow, in its app; or else whatever plays, which the last app played has.
        val playing = uiState.following?.takeIf { it.clock.playing && it.appPackage != null }
        val before = uiState.lyricsFor?.let { song -> playing?.let { Before(song, inApp(it.clock.positionAt(now())), it.appPackage) } }
            ?: playback?.nowPlaying()?.let { heard -> heard.clock?.let { Before(heard.metadata, it.positionAt(now()), app = null) } }
        practiceSong = null
        uiState = uiState.copy(practice = Practice(queue, total = queue.size, before = before))
        showCard()
    }

    /** The song the music app has for practice, ready to be moved around in, once it does. */
    private var practiceSong: SavedLine? = null
    private var soundJob: Job? = null

    /**
     * Shows the next card, with its line ready in the music app, not played: heard, it would say how
     * it reads, which is what's asked. It plays once the card's turned.
     */
    private fun showCard() {
        soundJob?.cancel()
        val practice = uiState.practice ?: return
        val line = practice.line ?: return
        val playable = line.timed && playback?.hasAccess() == true
        uiState = uiState.copy(practice = practice.copy(turned = false, lastSong = line, sound = if (playable) LineSound.LOADING else LineSound.NONE))
        if (playable) sound(line, play = false)
    }

    /**
     * Has the music app play [line], from where it's sung to the next line, then pause; or, when not
     * [play] and the card isn't turned yet, only get there. A song it isn't playing it's asked to find first.
     */
    private fun sound(line: SavedLine, play: Boolean = true) {
        val source = playback ?: return
        soundJob?.cancel()
        soundJob = viewModelScope.launch {
            val song = MusicMetadata(line.title, line.artist)
            val reached = inApp(source, song, line.app) { app ->
                practiceSong = line
                val lead = if (app()?.video == true) line.leadMs else 0L
                val start = (line.startMs ?: 0L) + lead
                // A moment past where the words say it ends, so its last word isn't cut short.
                val end = (line.endMs ?: 0L) + lead + LINE_TAIL_MS
                source.seekTo(song, start)
                // Turned while the app got there, it plays now.
                val now = play || uiState.practice?.turned == true
                source.playPause(song, now)
                if (!now) return@inApp setSound(line, LineSound.PLAYED)
                setSound(line, LineSound.PLAYING)
                // Once the app says it's there, it plays to the end of the line.
                withTimeoutOrNull(SEEK_WAIT_MS) { while (app()?.clock?.positionAt(now())?.let { it >= start - SEEK_SLACK_MS && it < end } != true) delay(SOUND_CHECK_MS) }
                withTimeoutOrNull(end - start + SEEK_WAIT_MS) { while ((app()?.clock?.positionAt(now()) ?: end) < end) delay(SOUND_CHECK_MS) }
                source.playPause(song, false)
                setSound(line, LineSound.PLAYED)
            }
            if (reached == null) setSound(line, LineSound.NONE)
        }
    }

    /**
     * Follows [song] in the music app, asking [app] for it by name when no app has it, then does
     * [block] once it can be moved around in, with where it is as that changes; null when it never can.
     */
    private suspend fun <T> inApp(source: PlaybackSource, song: MusicMetadata, app: String?, block: suspend (() -> Following?) -> T): T? = coroutineScope {
        var latest: Following? = null
        val following = launch { source.follow(song).collect { latest = it } }
        try {
            val ready: suspend () -> Unit = { while (latest?.canSeek != true) delay(SOUND_CHECK_MS) }
            // Not already there, it's asked for by name, which takes the app a moment. There, but where
            // it can't be moved, e.g. during an ad, asking again would only start it over.
            val found = withTimeoutOrNull(FIND_SONG_MS) { while (latest == null) delay(SOUND_CHECK_MS) } != null
            var opened = false
            val there = if (found) withTimeoutOrNull(FIND_SONG_MS) { ready() } != null else {
                val link = youTubeMusicLink(song, app)
                // YouTube Music not running has nothing to ask in the background, so it's opened on
                // the song, as it is with no app to ask at all, and left again once it has it.
                val asked = (link == null || app == null || source.runs(app)) && source.playSong(song, app, link)
                opened = !asked && link != null && source.openSong(MusicService.YOUTUBE_MUSIC.packageName, link)
                (asked || opened) && withTimeoutOrNull(LOAD_SONG_MS) { ready() } != null
            }
            if (opened) source.comesBack()
            if (there) block { latest } else null
        } finally {
            following.cancel()
        }
    }

    /** [song]'s link on YouTube Music when that's the app to ask, or may be; null when it isn't, or isn't there. */
    private suspend fun youTubeMusicLink(song: MusicMetadata, app: String?): String? =
        if (app == null || app == MusicService.YOUTUBE_MUSIC.packageName) matcher.find(MusicService.YOUTUBE_MUSIC, song) else null

    /** How [line] sounds, unless another card's shown by now. */
    private fun setSound(line: SavedLine, sound: LineSound) {
        val practice = uiState.practice?.takeIf { it.line?.isSame(line) == true } ?: return
        uiState = uiState.copy(practice = practice.copy(sound = sound))
    }

    /** Plays the card's line again. */
    fun replayLine() {
        val practice = uiState.practice ?: return
        val line = practice.line?.takeIf { practice.sound != LineSound.NONE } ?: return
        sound(line)
    }

    /** Turns the card, to say whether it was known, and plays its line, now that it's the answer. */
    fun turnPractice() {
        val practice = uiState.practice?.takeIf { !it.turned } ?: return
        uiState = uiState.copy(practice = practice.copy(turned = true))
        if (practice.sound == LineSound.PLAYED) replayLine()
    }

    /** The card's line read as [readings] say, one for each of its kanji, put right by hand. */
    fun readCardAs(readings: List<String>) {
        val practice = uiState.practice ?: return
        val line = practice.line ?: return
        val read = line.readAs(readings).takeIf { it != line } ?: return
        uiState = uiState.copy(
            savedLines = savedLinesStore.readAs(read),
            practice = practice.copy(queue = practice.queue.map { if (it.isSame(read)) it.copy(reading = read.reading, romanized = read.romanized, ruby = read.ruby, readByHand = true) else it })
        )
    }

    /** Says how well the card turned was known, and goes on to the next; one missed comes back after its song's others. */
    fun gradePractice(grade: Grade) {
        val practice = uiState.practice?.takeIf { it.turned } ?: return
        val line = practice.line ?: return
        uiState = uiState.copy(savedLines = savedLinesStore.practised(line, grade, wallClock()))
        uiState = uiState.copy(
            practice = if (grade == Grade.AGAIN) practice.copy(queue = missedAgain(practice.queue), missed = practice.missed + 1)
            else practice.copy(queue = practice.queue.drop(1), done = practice.done + 1)
        )
        showCard()
    }

    /** Stops practising, the line playing, or the song on its way, paused. */
    fun stopPractice() {
        val playing = uiState.practice?.takeIf { it.sound == LineSound.PLAYING || it.sound == LineSound.LOADING }?.line
        soundJob?.cancel()
        soundJob = null
        playing?.let { playback?.playPause(MusicMetadata(it.title, it.artist), false) }
        uiState = uiState.copy(practice = null)
    }

    /** Done, the music app goes back to what it played before, where it was, and practice closes. */
    fun backToBefore() {
        val before = uiState.practice?.before ?: return
        val source = playback ?: return
        stopPractice()
        soundJob = viewModelScope.launch {
            inApp(source, before.song, before.app) {
                source.seekTo(before.song, before.positionMs)
                source.playPause(before.song, true)
            }
        }
    }

    private var wordJob: Job? = null

    /**
     * Finds what [word], in line [index], means in the app's language: as used in that line, from
     * ChatGPT when signed in, or else the way the words are translated.
     */
    fun lookUpWord(word: Word, index: Int) {
        val translator = translator ?: return
        val line = wordLines().getOrNull(index)
        wordJob?.cancel()
        examplesJob?.cancel()
        uiState = uiState.copy(word = WordMeaning(word, looking = true), learning = uiState.learning.copy(examples = null))
        wordJob = viewModelScope.launch {
            val language = translateInto()
            val inLine = if (usesChatGpt() && line != null) tutor?.meaning(word.lookup, line, language) else null
            if (inLine != null) {
                uiState = uiState.copy(word = WordMeaning(word, inLine.meaning, type = inLine.type))
                return@launch
            }
            val translated = translator.translate(listOf(word.lookup), language)
            // Already in the app's language, a word means what the dictionary says.
            val definition = if (translated != null && translated.firstOrNull() == null) translator.define(word.lookup, language) else null
            val meaning = translated?.firstOrNull() ?: definition?.meaning
            uiState = uiState.copy(word = WordMeaning(word, meaning, failed = translated == null, type = definition?.type))
        }
    }

    fun dismissWord() {
        wordJob?.cancel()
        examplesJob?.cancel()
        uiState = uiState.copy(word = null, learning = uiState.learning.copy(examples = null))
    }

    /** Signed in with ChatGPT, which the AI features ask. */
    private fun usesChatGpt() = tutor != null && uiState.chatGpt.email != null

    private var explanationJob: Job? = null

    /** Has ChatGPT explain line [index]: what it means in the song, its grammar and references. */
    fun explainLine(index: Int) {
        val tutor = tutor ?: return
        val song = uiState.lyricsFor ?: return
        val lines = wordLines()
        val line = lines.getOrNull(index) ?: return
        explanationJob?.cancel()
        setExplanation(LineExplanation(index))
        explanationJob = viewModelScope.launch {
            val reply = tutor.explain(song, lines, index, translateInto()) { text ->
                // Written as it comes, unless it's done by the time this is shown.
                viewModelScope.launch { if (uiState.learning.explanation?.let { it.index == index && it.writing } == true) setExplanation(LineExplanation(index, text)) }
            }
            val explanation = explained(index, reply)
            setExplanation(explanation)
            // A line kept already keeps its explanation too.
            if (explanation.done) uiState = uiState.copy(savedLines = savedLinesStore.explain(line, song.title, song.artist, explanation.text))
        }
    }

    private var questionJob: Job? = null

    /** Asks ChatGPT [question] about line [index], after its explanation and what was asked before. */
    fun askAboutLine(index: Int, question: String) {
        val tutor = tutor ?: return
        val song = uiState.lyricsFor ?: return
        val asked = question.trim().takeIf { it.isNotEmpty() } ?: return
        val learning = uiState.learning
        // Questions about another line start over.
        val before = if (learning.questionsAbout == index) learning.questions.filter { it.done } else emptyList()
        val explanation = learning.explanation?.takeIf { it.index == index && it.done }?.text
        questionJob?.cancel()
        setQuestions(index, before + LineQuestion(asked))
        questionJob = viewModelScope.launch {
            val reply = tutor.askAboutLine(song, wordLines(), index, explanation, before.map { it.question to it.answer }, asked, translateInto()) { text ->
                viewModelScope.launch {
                    if (uiState.learning.questions.lastOrNull()?.writing == true) setQuestions(index, before + LineQuestion(asked, text))
                }
            }
            setQuestions(index, before + answered(asked, reply))
        }
    }

    private fun setQuestions(index: Int, questions: List<LineQuestion>) {
        uiState = uiState.copy(learning = uiState.learning.copy(questionsAbout = index, questions = questions))
    }

    private var songExplanationJob: Job? = null

    /** Has ChatGPT explain what the whole song is about. */
    fun explainSong() {
        val tutor = tutor ?: return
        val song = uiState.lyricsFor ?: return
        val lines = wordLines().takeIf { it.isNotEmpty() } ?: return
        findSongFacts(song)
        // Already explained, or on its way, it's just shown.
        if (uiState.learning.songExplanation?.let { it.done || it.writing } == true) return
        songExplanationJob?.cancel()
        setSongExplanation(LineExplanation(SONG))
        songExplanationJob = viewModelScope.launch {
            val reply = tutor.explainSong(song, lines, translateInto()) { text ->
                viewModelScope.launch { if (uiState.learning.songExplanation?.writing == true) setSongExplanation(LineExplanation(SONG, text)) }
            }
            setSongExplanation(explained(SONG, reply))
        }
    }

    private var songFactsJob: Job? = null

    /** Finds what the song covers or samples, unless it's known. */
    private fun findSongFacts(song: MusicMetadata) {
        val tutor = tutor ?: return
        if (uiState.learning.songFacts != null || songFactsJob?.isActive == true) return
        songFactsJob = viewModelScope.launch {
            val facts = tutor.songFacts(song) ?: return@launch
            if (uiState.lyricsFor?.let { sameSong(it, song) } == true) uiState = uiState.copy(learning = uiState.learning.copy(songFacts = facts))
        }
    }

    private var songQuestionJob: Job? = null

    /** Asks ChatGPT [question] about the whole song, after what it said about it and what was asked before. */
    fun askAboutSong(question: String) {
        val tutor = tutor ?: return
        val song = uiState.lyricsFor ?: return
        val asked = question.trim().takeIf { it.isNotEmpty() } ?: return
        val before = uiState.learning.songQuestions.filter { it.done }
        val explanation = uiState.learning.songExplanation?.takeIf { it.done }?.text
        songQuestionJob?.cancel()
        setSongQuestions(before + LineQuestion(asked))
        songQuestionJob = viewModelScope.launch {
            val reply = tutor.askAboutSong(song, wordLines(), explanation, before.map { it.question to it.answer }, asked, translateInto()) { text ->
                viewModelScope.launch { if (uiState.learning.songQuestions.lastOrNull()?.writing == true) setSongQuestions(before + LineQuestion(asked, text)) }
            }
            setSongQuestions(before + answered(asked, reply))
        }
    }

    private fun setSongQuestions(questions: List<LineQuestion>) {
        uiState = uiState.copy(learning = uiState.learning.copy(songQuestions = questions))
    }

    private var wordsToKnowJob: Job? = null

    /** Has ChatGPT pick the song's words most worth learning, unless it has or is. */
    fun findWordsToKnow() {
        val tutor = tutor ?: return
        val song = uiState.lyricsFor ?: return
        val lines = wordLines().takeIf { it.isNotEmpty() } ?: return
        if (uiState.learning.wordsToKnow?.failed == false) return
        setWordsToKnow(Asking())
        wordsToKnowJob = viewModelScope.launch {
            val words = tutor.wordsToKnow(song, lines, translateInto())
            setWordsToKnow(Asking(words, failed = words == null))
        }
    }

    private fun setWordsToKnow(asking: Asking<List<KeyWord>>) {
        uiState = uiState.copy(learning = uiState.learning.copy(wordsToKnow = asking))
    }

    /** Keeps [word], one of the song's words to know, to practise with the saved lines, or lets it go. */
    fun toggleSavedWord(word: KeyWord) {
        val song = uiState.lyricsFor ?: return
        // With the line it's first sung in, to practise it there, played.
        val index = wordLines().indexOfFirst { it.contains(word.word, ignoreCase = true) }
        val kept = SavedLine(word.word, word.reading, translation = word.meaning, title = song.title, artist = song.artist, line = wordLines().getOrNull(index))
        uiState = uiState.copy(savedLines = savedLinesStore.toggle(if (index >= 0) kept.timedAt(index) else kept))
    }

    private var artistJob: Job? = null

    /** Has ChatGPT say who the song's artist is, unless it has or is. */
    fun aboutArtist() {
        val tutor = tutor ?: return
        val song = uiState.lyricsFor ?: return
        if (uiState.learning.artist?.let { it.done || it.writing } == true) return
        setArtist(LineExplanation(SONG))
        artistJob = viewModelScope.launch {
            val reply = tutor.aboutArtist(song, translateInto()) { text ->
                viewModelScope.launch { if (uiState.learning.artist?.writing == true) setArtist(LineExplanation(SONG, text)) }
            }
            setArtist(explained(SONG, reply))
        }
    }

    private fun setArtist(explanation: LineExplanation) {
        uiState = uiState.copy(learning = uiState.learning.copy(artist = explanation))
    }

    private var examplesJob: Job? = null

    /** Has ChatGPT write sentences using the word tapped in line [index], the way the line does. */
    fun findExamples(index: Int) {
        val tutor = tutor ?: return
        val song = uiState.lyricsFor ?: return
        val word = uiState.word?.word ?: return
        val line = wordLines().getOrNull(index) ?: return
        examplesJob?.cancel()
        setExamples(WordExamples(word, index))
        examplesJob = viewModelScope.launch {
            val examples = tutor.examples(word.lookup, line, song, translateInto())
            setExamples(WordExamples(word, index, Asking(examples, failed = examples == null)))
        }
    }

    private fun setExamples(examples: WordExamples?) {
        uiState = uiState.copy(learning = uiState.learning.copy(examples = examples))
    }

    /** A question [asked], answered as [reply] says. */
    private fun answered(asked: String, reply: ChatGptReply) = when (reply) {
        is ChatGptReply.Answer -> LineQuestion(asked, reply.text.trim(), done = true)
        ChatGptReply.LimitReached -> LineQuestion(asked, limitReached = true)
        ChatGptReply.Failed -> LineQuestion(asked, failed = true)
    }

    /** Line [index], or the song, explained as [reply] says. */
    private fun explained(index: Int, reply: ChatGptReply) = when (reply) {
        is ChatGptReply.Answer -> LineExplanation(index, reply.text.trim(), done = true)
        ChatGptReply.LimitReached -> LineExplanation(index, limitReached = true)
        ChatGptReply.Failed -> LineExplanation(index, failed = true)
    }

    private fun setSongExplanation(explanation: LineExplanation) {
        uiState = uiState.copy(learning = uiState.learning.copy(songExplanation = explanation))
    }

    private fun setExplanation(explanation: LineExplanation) {
        uiState = uiState.copy(learning = uiState.learning.copy(explanation = explanation))
    }

    private var repeatJob: Job? = null

    /**
     * Has the music app play line [index] over and over: back to its start each time it reaches the
     * next line, until stopped. Only while the words follow an app that can be moved.
     */
    fun repeatLine(index: Int) {
        val song = uiState.lyricsFor ?: return
        val lines = uiState.lyricLines
        val start = lines.getOrNull(index)?.timeMs ?: return
        if (uiState.following?.canSeek != true) return
        val end = endOf(lines, index) + LINE_TAIL_MS
        repeatJob?.cancel()
        uiState = uiState.copy(repeating = index)
        playback?.seekTo(song, inApp(start))
        var sought = now()
        repeatJob = viewModelScope.launch {
            while (isActive) {
                delay(REPEAT_CHECK_MS)
                val position = uiState.following?.clock?.positionAt(now()) ?: continue
                // The app takes a moment to say where it went, so it isn't sent back again meanwhile.
                if (position >= end && now() - sought >= SEEK_SETTLE_MS) {
                    playback?.seekTo(song, inApp(start))
                    sought = now()
                }
            }
        }
    }

    fun stopRepeating() {
        repeatJob?.cancel()
        repeatJob = null
        uiState = uiState.copy(repeating = null)
    }

    /** The words shown, line by line: the timed ones, or else the plain ones. */
    private fun wordLines(): List<String> = uiState.lyricLines.map { it.text }.ifEmpty { uiState.lyrics.lines() }

    /** Works out what's switched on for the words shown, and isn't worked out yet. */
    private fun learn() {
        val learning = uiState.learning
        val lines = wordLines()
        val script = learning.script
        if ((learning.readings || learning.romanized || learning.wordsAs != WordsAs.WRITTEN) && script != null && learning.lines.isEmpty() && readingsJob?.isActive != true) {
            readingsJob = viewModelScope.launch {
                // Japanese loads its dictionary the first time, a moment better spent away from the screen.
                var read = withContext(systemDispatcher) { Readings.of(lines, script) }
                uiState = uiState.copy(learning = uiState.learning.copy(lines = read))
                val song = uiState.lyricsFor
                // Lines kept with no reading, as ones once written otherwise, are read as they are now.
                song?.let { rereadSavedLines(it, unread = true) }
                if (script != Script.JAPANESE || song == null) return@launch
                // Sung readings, 都会 as まち, from a site that writes them over the words.
                sungReadings?.of(song, lines, read)?.takeIf { it.isNotEmpty() }?.let { fixes ->
                    if (wordLines() != lines || uiState.learning.lines != read) return@launch
                    read = Readings.corrected(read, fixes)
                    uiState = uiState.copy(learning = uiState.learning.copy(lines = read))
                    rereadSavedLines(song)
                }
                // Signed in, ChatGPT puts right the readings the dictionary gets wrong in this song.
                if (usesChatGpt()) {
                    val checked = tutor?.checkReadings(song, lines, read) ?: return@launch
                    if (wordLines() == lines && uiState.learning.lines == read) {
                        uiState = uiState.copy(learning = uiState.learning.copy(lines = checked))
                        rereadSavedLines(song)
                    }
                }
            }
        }
        val translator = translator ?: return
        if (learning.translation && uiState.lyrics.isNotEmpty() && learning.translations.isEmpty() && !learning.translating) {
            translationJob = viewModelScope.launch {
                uiState = uiState.copy(learning = uiState.learning.copy(translating = true, translationFailed = false))
                // A song in a language the user reads needs none, line by line.
                if (learning.untranslated.isNotEmpty() && lyricsLanguage?.of(lines) in learning.untranslated) {
                    uiState = uiState.copy(learning = uiState.learning.copy(translations = lines.map { null }, translating = false))
                    return@launch
                }
                val language = translateInto()
                // ChatGPT, signed in, translates the song as a whole; the usual way when it can't.
                val translated = (
                    if (usesChatGpt() && learning.chatGptTranslation) {
                        tutor?.translate(lines, language, uiState.lyricsFor) { so ->
                            // Shown line by line as they come, while it's still translating these words.
                            viewModelScope.launch {
                                if (uiState.learning.translating && wordLines() == lines) uiState = uiState.copy(learning = uiState.learning.copy(translations = so))
                            }
                        }
                    } else {
                        null
                    }
                    ) ?: translator.translate(lines, language)
                uiState = uiState.copy(
                    learning = uiState.learning.copy(
                        translations = translated.orEmpty(), translating = false, translationFailed = translated == null
                    )
                )
            }
        }
    }

    private var chordsJob: Job? = null

    /** Looks up the song's chords, while they're switched on and haven't been. */
    private fun chords() {
        val finder = chordFinder ?: return
        val song = uiState.lyricsFor ?: return
        val chords = uiState.chords
        val lines = wordLines()
        if (!chords.on || uiState.lyrics.isEmpty() || chords.loading || chords.tried) return
        chordsJob = viewModelScope.launch {
            uiState = uiState.copy(chords = uiState.chords.copy(loading = true))
            val answer = finder.chordsOf(song, lines)
            uiState = uiState.copy(chords = uiState.chords.copy(loading = false, tried = true, sheet = (answer as? ChordFinder.Chords.Found)?.sheet))
        }
    }

    /**
     * Shows the song's chords over its words, from now on, or stops, and stops looking them up.
     * Switched on again, a song with none found is looked up again.
     */
    fun setChords(on: Boolean) {
        preferences.edit { putBoolean(KEY_CHORDS, on) }
        if (!on) chordsJob?.cancel()
        val chords = uiState.chords
        uiState = uiState.copy(chords = chords.copy(on = on, loading = on && chords.loading, tried = chords.tried && chords.sheet != null))
        chords()
    }

    fun setReadings(on: Boolean) {
        preferences.edit { putBoolean(KEY_READINGS, on) }
        uiState = uiState.copy(learning = uiState.learning.copy(readings = on))
        learn()
    }

    /** Kept only once let go, [save], rather than at every step of the slider. */
    fun setReadingScale(scale: Float, save: Boolean) {
        if (save) preferences.edit { putFloat(KEY_READING_SCALE, scale) }
        uiState = uiState.copy(learning = uiState.learning.copy(readingScale = scale))
    }

    fun setRomanized(on: Boolean) {
        preferences.edit { putBoolean(KEY_ROMANIZED, on) }
        uiState = uiState.copy(learning = uiState.learning.copy(romanized = on))
        learn()
    }

    fun setTranslation(on: Boolean) {
        preferences.edit { putBoolean(KEY_TRANSLATION, on) }
        uiState = uiState.copy(learning = uiState.learning.copy(translation = on))
        learn()
    }

    /** Shows the words as written, or only in kana or Latin letters, from now on. */
    fun setWordsAs(wordsAs: WordsAs) {
        preferences.edit { putString(KEY_WORDS_AS, wordsAs.name) }
        uiState = uiState.copy(learning = uiState.learning.copy(wordsAs = wordsAs))
        learn()
    }

    /** Asks for the translation again, after it couldn't be done. */
    fun retryTranslation() {
        uiState = uiState.copy(learning = uiState.learning.copy(translationFailed = false))
        learn()
    }

    /** Translates with ChatGPT, signed in, or with MyMemory, from now on. */
    fun setChatGptTranslation(on: Boolean) {
        preferences.edit { putBoolean(KEY_TRANSLATION_CHATGPT, on) }
        // What the other said is no answer from this one.
        translationJob?.cancel()
        uiState = uiState.copy(learning = uiState.learning.copy(chatGptTranslation = on, translations = emptyList(), translating = false, translationFailed = false))
        learn()
    }

    /** The language lyrics and words are translated into: the one picked, or else the app's. */
    private fun translateInto(): String =
        uiState.learning.into?.let { Translator.languageOf(java.util.Locale.forLanguageTag(it)) } ?: translationLanguage()

    /** Leaves songs in [languages], of [AppLanguage.untranslatedTags], untranslated from now on. */
    fun setUntranslated(languages: Set<String>) {
        val untranslated = languages.filter { it in AppLanguage.untranslatedTags }.toSet()
        preferences.edit { putStringSet(KEY_UNTRANSLATED, untranslated) }
        // The song shown may be in one of them now, or not any more.
        translationJob?.cancel()
        uiState = uiState.copy(learning = uiState.learning.copy(untranslated = untranslated, translations = emptyList(), translating = false, translationFailed = false))
        learn()
    }

    /** Translates into [tag], one of [AppLanguage.translationTags], from now on, or into the app's own with null. */
    fun setTranslateInto(tag: String?) {
        val into = tag?.takeIf { it in AppLanguage.translationTags }
        preferences.edit { if (into == null) remove(KEY_TRANSLATE_INTO) else putString(KEY_TRANSLATE_INTO, into) }
        // Lines translated into the last language are no use in this one.
        translationJob?.cancel()
        uiState = uiState.copy(word = null, learning = uiState.learning.copy(into = into, translations = emptyList(), translating = false, translationFailed = false))
        learn()
    }

    private suspend fun prepareDestination(destination: Destination): String? {
        val metadata = uiState.result ?: return null
        val service = destination.matchService
        // The link already belongs to the destination's service: open it as is instead of searching.
        uiState.link?.takeIf { service != null && it.service == service }?.let { link ->
            return destination.adapt(link.url)
        }
        // YouTube and YouTube Music share playlists and videos, so one opens as itself in the other.
        uiState.link?.youtubePlaylistOn(service)?.let { return destination.adapt(it) }
        uiState.link?.takeIf { metadata.type == ItemType.TRACK }?.youtubeVideoOn(service)?.let { return destination.adapt(it) }
        uiState.destinationUrls[destination]?.let { return it.url.forSharing() }
        val match = if (uiState.exactMatch && service != null) {
            uiState = uiState.copy(isMatching = true)
            matcher.lookFor(service, metadata).also { uiState = uiState.copy(isMatching = false) }
        } else {
            null
        }
        val exactUrl = (match as? ExactMatcher.Match.Found)?.url
        val url = exactUrl?.let(destination::adapt) ?: destination.searchUrl(searchQuery(metadata))
        rememberDestination(destination, PreparedLink(url, exactUrl != null, matched(match)))
        return url.forSharing()
    }

    /**
     * Whether a link was prepared with exact matching: not when the lookup got no answer, e.g.
     * offline, so it isn't taken as not being there, and is looked for again next time.
     */
    private fun matched(match: ExactMatcher.Match?) = uiState.exactMatch && match != ExactMatcher.Match.Failed

    /** Opens the link in the app it came from, e.g. when it can't be resolved or the user prefers it. */
    fun openOriginal() {
        val link = uiState.link ?: return
        // An exact match may still be running; it must not open a second app when it finishes.
        job?.cancel()
        cancelTrackWork()
        pendingOpen = false
        uiState = uiState.copy(showDestinationPicker = false, isMatching = false)
        val finishAfterOpen = lastRequest?.second == true
        effectChannel.trySend(Effect.Open(link.url, link.service?.packageName, finishAfterOpen))
    }

    fun dismissDestinationPicker() {
        uiState = uiState.copy(showDestinationPicker = false)
        // The result is now available for manual actions using its displayed destination.
        prepareResultDestination()
    }

    private fun selectHistoryEntry(entry: HistoryEntry) {
        cancelContentWork()
        // The entry replaces whatever was being looked up, including a link from another app.
        lastRequest = null
        pendingOpen = false
        uiState = uiState.copy(
            linkText = fieldText(entry.link),
            isLoading = false,
            isMatching = false,
            result = entry.metadata,
            destinationUrls = savedDestinations(entry),
            selectedDestination = null,
            link = entry.link,
            error = null,
            canRetry = false,
            showDestinationPicker = false,
            handlingIncomingLink = false,
            handingOff = false
        )
    }

    fun showHistoryEntry(entry: HistoryEntry) {
        selectHistoryEntry(entry)
        loadTrackCovers()
        prepareResultDestination()
    }

    fun openHistoryEntry(entry: HistoryEntry) = openSaved(entry, finishAfterOpen = false)

    /**
     * A Recent song from the widget's ▶: opened in the user's app like Recent's own ▶, from what
     * was saved, with Crosstune going away after. Gone from Recent meanwhile, it's looked up again.
     */
    fun openRecent(url: String?) {
        val entry = uiState.history.firstOrNull { it.link.url == url } ?: return resolveIncoming(url)
        uiState = uiState.copy(handlingIncomingLink = true, handingOff = true, leaveSettings = true)
        openSaved(entry, finishAfterOpen = true)
    }

    private fun openSaved(entry: HistoryEntry, finishAfterOpen: Boolean) {
        cancelContentWork()
        job = viewModelScope.launch {
            val destination = destinationFor(entry)
            val url = urlForHistory(entry, destination) ?: return@launch
            val prepared = uiState.history.firstOrNull { it.link.url == entry.link.url }?.destinationLinks?.get(destination.key)
            val service = destination.matchService
            if (prepared != null && prepared.url.forSharing() == url && !prepared.exact && prepared.matchingEnabled && service != null &&
                canBeMissing(service, entry.metadata)
            ) {
                return@launch openMissing(entry.metadata, entry.link, destination, url, finishAfterOpen)
            }
            effectChannel.send(Effect.Open(url, destination.packageName, finishAfterOpen))
        }
    }

    /** Recent's Open uses the saved default, not whatever result is on screen. */
    private fun destinationFor(entry: HistoryEntry): Destination =
        uiState.ruleFor(entry.link) ?: uiState.defaultDestination

    private suspend fun urlForHistory(entry: HistoryEntry, destination: Destination): String? {
        val service = destination.matchService
        if (service != null && service == entry.link.service) return destination.adapt(entry.link.url)
        // YouTube and YouTube Music play the same videos, so one opens as itself in the other.
        if (entry.metadata.type == ItemType.TRACK) {
            entry.link.youtubeVideoOn(service)?.let { return destination.adapt(it) }
        }
        // A playlist plays as a queue of its first songs, as the Play button in the app does, not as a search.
        // An album has its own page there, which the exact match below finds.
        if (entry.metadata.type == ItemType.PLAYLIST && entry.metadata.tracks.isNotEmpty() && (service == MusicService.YOUTUBE_MUSIC || service == MusicService.YOUTUBE)) {
            entry.link.youtubePlaylistOn(service)?.let { return destination.adapt(it) }
            matcher.youtubeQueue(service, entry.metadata.tracks.take(MAX_QUEUE)) {}?.let { return it }
        }
        entry.destinationLinks[destination.key]?.takeIf { it.matchingEnabled == uiState.exactMatch }?.let { return it.url.forSharing() }
        val match = if (uiState.exactMatch && service != null) matcher.lookFor(service, entry.metadata) else null
        val exactUrl = (match as? ExactMatcher.Match.Found)?.url
        val url = exactUrl?.let(destination::adapt) ?: destination.searchUrl(searchQuery(entry.metadata))
        val history = historyStore.remember(entry.link.url, destination.key, PreparedLink(url, exactUrl != null, matched(match)))
        uiState = uiState.copy(history = history)
        return url.forSharing()
    }

    /** Kept until the undo offer goes away, so clearing by mistake loses nothing. */
    private var clearedHistory: List<HistoryEntry> = emptyList()

    fun clearHistory() {
        clearedHistory = uiState.history
        historyStore.clear()
        uiState = uiState.copy(
            history = emptyList(),
            canUndoClearHistory = true,
            removedOneFromHistory = false,
            historyUndoId = uiState.historyUndoId + 1
        )
    }

    /** Drops one item, e.g. swiped away; undo puts it back where it was. */
    fun removeHistoryEntry(entry: HistoryEntry) {
        clearedHistory = uiState.history
        uiState = uiState.copy(
            history = historyStore.replace(uiState.history.filterNot { it.link.url == entry.link.url }),
            canUndoClearHistory = true,
            removedOneFromHistory = true,
            historyUndoId = uiState.historyUndoId + 1
        )
    }

    fun undoClearHistory() {
        // Anything looked up since stays on top, and the rest goes back in its old order.
        val kept = clearedHistory.map { it.link.url }.toSet()
        val restored = uiState.history.filterNot { it.link.url in kept } + clearedHistory
        uiState = uiState.copy(history = historyStore.replace(restored), canUndoClearHistory = false)
        clearedHistory = emptyList()
    }

    fun forgetClearedHistory() {
        clearedHistory = emptyList()
        uiState = uiState.copy(canUndoClearHistory = false)
    }

    /** Stops a link from another app on its way out and shows Crosstune instead. */
    fun cancelHandoff() {
        job?.cancel()
        pendingOpen = false
        uiState = uiState.copy(handingOff = false, isLoading = false, isMatching = false)
    }

    fun searchQuery(): String? = uiState.result?.let(::searchQuery)

    /** The result's songs as "Artist - Title" lines, which song list transfer sites read. */
    fun songList(): String? = uiState.result?.tracks?.takeIf { it.isNotEmpty() }?.joinToString("\n") { track ->
        if (track.artist.isBlank()) track.title else "${track.artist} - ${track.title}"
    }

    fun destinationUrl(): String? {
        val destination = uiState.resultDestination
        uiState.link?.takeIf { it.service != null && destination.matchService == it.service }?.let { return destination.adapt(it.url) }
        // YouTube and YouTube Music play the same videos, so one is shared as itself in the other.
        uiState.link?.takeIf { uiState.result?.type == ItemType.TRACK }?.youtubeVideoOn(destination.matchService)
            ?.let { return destination.adapt(it) }
        // Not there, the song is shared where it is, rather than as a search that won't find it, unless
        // searching anyway was picked.
        uiState.link?.takeIf { uiState.resultMissing && uiState.notFoundAction != NotFoundAction.SEARCH && it.service != null }?.let { return it.url }
        // Every shown result has its link prepared before Copy and Share are enabled.
        return uiState.destinationUrls[destination]?.url?.forSharing()
    }


    fun clear() {
        cancelContentWork()
        uiState = uiState.copy(
            linkText = "",
            isLoading = false,
            isMatching = false,
            result = null,
            destinationUrls = emptyMap(),
            selectedDestination = null,
            link = null,
            error = null,
            showDestinationPicker = false,
            handlingIncomingLink = false,
            handingOff = false
        )
    }

    /** A new song or an empty screen replaces every operation owned by the previous content. */
    private fun cancelContentWork() {
        job?.cancel()
        job = null
        lastRequest = null
        pendingOpen = false
        retryListens = false
        dismissSongSearch()
        dismissLyrics()
        cancelTrackWork()
        coverJob?.cancel()
        coverJob = null
        uiState = uiState.copy(isLoading = false, isMatching = false, listening = false, canRetry = false)
    }

    private fun cancelTrackWork() {
        trackJob?.cancel()
        trackJob = null
        uiState = uiState.copy(queueProgress = null, isMatching = false)
    }

    private fun rememberDestination(destination: Destination, prepared: PreparedLink) {
        val history = uiState.link?.let { historyStore.remember(it.url, destination.key, prepared) } ?: uiState.history
        uiState = uiState.copy(destinationUrls = uiState.destinationUrls + (destination to prepared), history = history)
    }

    fun selectResultDestination(destination: Destination) {
        rememberLastApp(destination)
        uiState = uiState.copy(selectedDestination = destination)
        prepareResultDestination()
    }

    /**
     * Asking which app, or showing the song first, there's no default to make; the app picked last
     * is offered next time instead. It's stored as the default, which is what the Open button,
     * Recent and the share sheet use, without changing what tapped links do.
     */
    private fun rememberLastApp(destination: Destination) {
        if (uiState.linkMode == LinkMode.OPEN) return
        destinationStore.setDefault(destination)
        uiState = uiState.withDestinations()
    }

    fun selectDefault(destination: Destination) {
        destinationStore.setDefault(destination)
        // Crosstune would only hand the app its own links back, unless a rule sends them elsewhere.
        (destination as? Destination.Service)?.service
            ?.takeIf { interception.isEnabled(it) && destinationStore.rule(it) == null }
            ?.let { interception.setEnabled(it, false) }
        refreshSystemState()
    }

    fun setRule(source: MusicService, destination: Destination?) {
        destinationStore.setRule(source, destination)
        // Without its rule, the app the user listens in would only get its own links back.
        if (destination == null && interception.isEnabled(source) && source == uiState.listeningService()) {
            interception.setEnabled(source, false)
        }
        refreshSystemState()
    }

    /**
     * Opens every source's links the first time setup asks for them, but the one the user listens
     * in, whose links already open there; videos too, since only music videos are opened elsewhere.
     * Changing them later is up to the user, in settings.
     */
    fun preselectSources() {
        if (preferences.getBoolean(KEY_SOURCES_PRESELECTED, false)) return
        preferences.edit { putBoolean(KEY_SOURCES_PRESELECTED, true) }
        if (uiState.interceptsAnything) return
        val listening = uiState.listeningService()
        MusicService.entries.filter { it.canBeSource && it != listening }.forEach { interception.setEnabled(it, true) }
        Frontend.SOURCES.forEach { interception.setEnabled(it, true) }
        uiState = uiState.withDestinations().copy(showLinkSettingsHelper = true)
        loadSystemState()
    }

    fun setFrontendRule(frontend: Frontend, destination: Destination?) {
        destinationStore.setRule(frontend, destination)
        refreshSystemState()
    }

    fun setFrontendIntercepted(frontend: Frontend, enabled: Boolean) {
        interception.setEnabled(frontend, enabled)
        uiState = uiState.withDestinations().copy(showLinkSettingsHelper = enabled || uiState.showLinkSettingsHelper)
        loadSystemState()
    }

    fun setIntercepted(source: MusicService, enabled: Boolean) {
        interception.setEnabled(source, enabled)
        // Android still has to be told to let Crosstune open the newly added domains.
        uiState = uiState.withDestinations().copy(showLinkSettingsHelper = enabled || uiState.showLinkSettingsHelper)
        loadSystemState()
    }

    fun addCustomDestination(name: String, template: String): Boolean {
        if (name.isBlank() || !Destination.isValidTemplate(template)) return false
        destinationStore.addCustom(name, template)
        uiState = uiState.withDestinations()
        return true
    }

    /** Moves a web frontend to another site; false if [address] isn't a web address. */
    fun setFrontendInstance(frontend: Frontend, address: String): Boolean {
        if (!destinationStore.setInstance(frontend, address)) return false
        // Links saved for it point at the old site, which the user may have left because it's down.
        uiState = uiState.copy(history = historyStore.forget(Destination.Alternative(frontend).key))
        refreshSystemState()
        return true
    }

    fun removeCustomDestination(custom: Destination.Custom) {
        destinationStore.removeCustom(custom)
        refreshSystemState()
    }

    fun setExactMatch(enabled: Boolean) {
        uiState = uiState.copy(exactMatch = enabled, destinationUrls = emptyMap())
        preferences.edit { putBoolean(KEY_EXACT_MATCH, enabled) }
        prepareResultDestination()
    }

    /** A frontend's video goes back to its site, in a browser; any other to the YouTube app. */
    private suspend fun openAsIs(video: MusicLink) {
        pendingOpen = false
        val packageName = if (video.frontendUrl == null) MusicService.YOUTUBE.packageName else null
        effectChannel.send(Effect.Open(video.frontendUrl ?: video.url, packageName, finishAfterOpen = true))
    }

    fun setOnlyMusicVideos(enabled: Boolean) {
        uiState = uiState.copy(onlyMusicVideos = enabled)
        preferences.edit { putBoolean(KEY_ONLY_MUSIC_VIDEOS, enabled) }
    }

    /** What a tapped or shared link does, picked with the default app: open in it, ask, or show here first. */
    fun setLinkMode(mode: LinkMode) {
        uiState = uiState.copy(askEachTime = mode == LinkMode.ASK, showSongFirst = mode == LinkMode.SHOW)
        preferences.edit {
            putBoolean(KEY_ASK_EACH_TIME, mode == LinkMode.ASK)
            putBoolean(KEY_SHOW_SONG_FIRST, mode == LinkMode.SHOW)
        }
    }

    fun setShareSheetApps(enabled: Boolean) {
        uiState = uiState.copy(shareSheetApps = enabled)
        preferences.edit { putBoolean(KEY_SHARE_SHEET_APPS, enabled) }
    }

    fun setCleanLinks(enabled: Boolean) {
        uiState = uiState.copy(cleanLinks = enabled)
        preferences.edit { putBoolean(KEY_CLEAN_LINKS, enabled) }
    }

    /** Saved links keep what the service returned, so turning the setting off brings it back. */
    private fun String.forSharing(): String = if (uiState.cleanLinks) TrackingLinks.clean(this) else this

    fun dismissLinkSettingsHelper() {
        uiState = uiState.copy(showLinkSettingsHelper = false)
        preferences.edit { putBoolean(KEY_LINK_SETTINGS_HELPER_DISMISSED, true) }
    }

    /** Closes the main screen's notices about [keys]; one about anything else still shows. */
    fun dismissNotices(keys: Set<String>) {
        val dismissed = uiState.dismissedNotices + keys
        uiState = uiState.copy(dismissedNotices = dismissed)
        preferences.edit { putStringSet(KEY_DISMISSED_NOTICES, dismissed) }
    }

    fun showError(error: AppError) {
        retryListens = false
        uiState = uiState.copy(isLoading = false, isMatching = false, error = error, canRetry = false, handingOff = false)
    }

    companion object {
        const val PREFERENCES_NAME = "crosstune_preferences"
        /** A song without any picture can wait a little longer for its cover than a video with a frame. */
        private const val COVER_TIMEOUT_MS = 5_000L
        private const val VIDEO_COVER_TIMEOUT_MS = 2_000L
        private const val KEY_LINK_SETTINGS_HELPER_DISMISSED = "link_settings_helper_dismissed"
        private const val KEY_DISMISSED_NOTICES = "dismissed_notices"
        private const val KEY_ASK_EACH_TIME = "ask_each_time"
        private const val KEY_EXACT_MATCH = "exact_match"
        private const val KEY_CLEAN_LINKS = "clean_links"
        private const val KEY_SHARE_SHEET_APPS = "share_sheet_apps"
        private const val KEY_SHOW_SONG_FIRST = "show_song_first"
        private const val KEY_THEME = "theme"
        const val KEY_PALETTE = "palette"
        private const val KEY_NOT_FOUND = "not_found"
        private const val KEY_PURE_BLACK = "pure_black"
        private const val KEY_VISUALS = "visuals"
        private const val KEY_VISUALS_SHADE = "visuals_shade"
        private const val KEY_READINGS = "lyrics_readings"
        private const val KEY_CHORDS = "lyrics_chords"
        private const val KEY_ROMANIZED = "lyrics_romanized"
        private const val KEY_TRANSLATION = "lyrics_translation"
        private const val KEY_TRANSLATION_CHATGPT = "translation_chatgpt"
        private const val KEY_WORDS_AS = "lyrics_words_as"
        private const val KEY_CHATGPT_MODEL = "chatgpt_model"

        /** Typed text of this many words or more may describe a song rather than name it. */
        const val DESCRIPTION_WORDS = 3

        /** The fewest songs ChatGPT names for a description that make a playlist. */
        const val MIN_PLAYLIST = 3

        /** Where a song's explanation, which is no line's, says it's from. */
        const val SONG = -1
        private const val KEY_TRANSLATE_INTO = "translate_into"
        private const val KEY_UNTRANSLATED = "untranslated_languages"
        private const val KEY_READING_SCALE = "reading_scale"
        private const val KEY_CHATGPT_WELCOMED = "chatgpt_welcomed"
        private const val KEY_FLOATING_BACKGROUND = "floating_background"
        private const val KEY_FLOATING_SCALE = "floating_scale"
        private const val KEY_FLOATING_PREVIOUS_LINE = "floating_previous_line"
        private const val KEY_FLOATING_LEFT = "floating_left"
        private const val KEY_FLOATING_VISUALS = "floating_visuals"
        private const val KEY_FLOATING_NEXT_LINE = "floating_next_line"
        private const val KEY_FLOATING_LOCKED = "floating_locked"
        private const val KEY_FLOATING_TOP = "floating_top"

        /** How far apart two hearings of a song can be and still be the same place in it. */
        const val STEADY_MS = 2_000L

        /** How close a song heard by another name must be to where the one shown is to be the same recording. */
        const val SAME_RECORDING_MS = 10_000L

        /** How far into the song shown that's believed: earlier, it may just as well be the next song. */
        const val SAME_RECORDING_AFTER_MS = 30_000L
        /** How long ago the song can have been heard and still line up a video with it. */
        const val HEARD_LATELY_MS = 60_000L

        /** Long enough not to keep Shazam busy, short enough to catch a skip or the next song soon. */
        const val LISTEN_ALONG_PAUSE_MS = 8_000L

        /** How many wrong meanings a practised line's right one is picked among. */

        /** How many times the rest after hearing nothing doubles: 8 s, then 16, 32 and at most 64. */
        const val QUIET_PAUSE_DOUBLINGS = 3

        /** How long the last line repeats for, with no next line to end at. */
        const val LAST_LINE_MS = 6_000L
        /**
         * How long a line plays on past where the words say it ends. Words are timed to show a little
         * before they're sung, half a second in songs heard, so the next line's time is about when
         * this one's last sound starts fading, not when it's gone; cut there, its last word is lost.
         */
        const val LINE_TAIL_MS = 1_000L
        private const val REPEAT_CHECK_MS = 100L
        private const val SEEK_SETTLE_MS = 1_000L
        /** How long a song the music app has already takes to be found. */
        private const val FIND_SONG_MS = 400L
        /** How long the music app has to find a song asked for by name, and start it. */
        const val LOAD_SONG_MS = 12_000L
        /** How long the music app has to say it went where it was sent. */
        private const val SEEK_WAIT_MS = 3_000L
        /** How far before a line the app may say it is, having gone there, as apps round where they are. */
        private const val SEEK_SLACK_MS = 1_500L
        private const val SOUND_CHECK_MS = 50L
        /** YouTube makes temporary playlists of up to 50 videos. */
        const val MAX_QUEUE = 50
        /** Songs whose covers are looked up when a playlist shows: Spotify lists 100, the first 30 with covers. */
        private const val MAX_COVER_LOOKUPS = 100
        private const val KEY_ONLY_MUSIC_VIDEOS = "only_music_videos"
        private const val KEY_SOURCES_PRESELECTED = "sources_preselected"

        /** How many times, and how long apart, setup in one tap looks for lyrics being let follow music apps. */
        private const val FOLLOW_WAITS = 10
        private const val FOLLOW_WAIT_MS = 100L
        private const val KEY_SETUP_COMPLETE = "setup_complete"

        /** Preferences only an install from before first-run setup can have. */
        private val LEGACY_KEYS = listOf(
            KEY_LINK_SETTINGS_HELPER_DISMISSED, "default_target", "history", KEY_ASK_EACH_TIME, KEY_EXACT_MATCH
        )
    }
}
