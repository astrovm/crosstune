package com.astrovm.crosstune

import com.astrovm.crosstune.ui.theme.Palette
import com.astrovm.crosstune.ui.theme.wallpaperColors

internal data class UiState(
    val linkText: String = "",
    val isLoading: Boolean = false,
    val isMatching: Boolean = false,
    /** While the result's app is asked to play it in the background. */
    val playingResult: Boolean = false,
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
    /** Whether tapped and shared links open in Crosstune, on the song's card, rather than straight in an app. */
    val linksInCrosstune: Boolean = false,
    /**
     * Opening in Crosstune, whether the song plays as soon as it's found, in the default app, staying
     * in Crosstune's player; opening straight in an app, whether the song itself opens there, or a search.
     */
    val playRightAway: Boolean = true,
    /** Drops tracking parameters from links Crosstune opens, copies or shares. */
    val cleanLinks: Boolean = true,
    /** Whether the share sheet offers "Open in" an app; Android shows those for any shared text. */
    val shareSheetApps: Boolean = true,
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
    /** How sending a report on the words is going, while one is being written. */
    val report: ReportStatus? = null,
    /** Whether Crosstune reports problems it finds with songs by itself; null while the user hasn't said. */
    val autoReports: Boolean? = null,
    /** Whether the words shown have a problem Crosstune offers to report by itself, from now on. */
    val reportOffer: Boolean = false,
    /** The speech model that lines the words up to the singing, while it's missing and offered to be fetched by hand. */
    val singingModel: ModelOffer? = null,
    /** Whether [lyricLines] are timed for a recording not as long as the one playing, so likely off, and not yet put right. */
    val lyricsOffTake: Boolean = false,
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
    /** Light, dark, or as the phone is. */
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** The app's color: the wallpaper's, or one of the app's own. */
    val palette: Palette = defaultPalette(),
    /** True black grounds in the dark. */
    val pureBlack: Boolean = false,
    /** Whether what's playing shows as a bar at the bottom of the main screen. */
    val playingBar: Boolean = true,
    /** Whether MilkDrop visuals move behind the words, to what the phone plays. */
    val visuals: Boolean = false,
    /** How dark the ground over the visuals is, from 0, none, to 1, black. */
    val visualsShade: Float = DEFAULT_VISUALS_SHADE,
    /** How many times the next visual was asked for, so each ask moves on once. */
    val visualsSkips: Int = 0,
    /** How many times the visual showing was asked not to show again, so each ask drops one. */
    val visualsDrops: Int = 0,
    /** Which visuals show, and for how long. */
    val visualsSettings: VisualsSettings = VisualsSettings(),
    /** What helps read the words in another language, and what's been worked out for them. */
    val learning: Learning = Learning(),
    /** A song's chords over its words, while switched on. */
    val chords: ChordsState = ChordsState(),
    /** Lines kept to study later, newest first. */
    val savedLines: List<SavedLine> = emptyList(),
    /** Saved lines being practised, while they are. */
    val practice: Practice? = null,
    /** The song the player shows, playing in its music app; null while it's closed. */
    val player: PlayerState? = null,
    /** The kinds of practice card, each with what it shows on either side. */
    val cardTypes: List<CardType> = DEFAULT_CARD_TYPES,
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
    /**
     * On a TV, Crosstune covers the app it opened a song in, in a window over it: brought in front of
     * it instead, YouTube stops playing.
     */
    val coveringApp: Boolean = false,
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
    /** Set when a source was turned on before Android 12, which can't tell whether its links are allowed, until they're opened. */
    val showLinkSettingsHelper: Boolean = false,
    /** The update notices the user closed, by version. */
    val dismissedNotices: Set<String> = emptySet(),
    /** A newer Crosstune to update to, for a copy installed from a file. */
    val update: Update? = null,
    /** The song a music app on the phone plays, or has paused, while Crosstune is in sight: a bar to open its player from. */
    val playingNow: NowPlaying? = null,
    val history: List<HistoryEntry> = emptyList(),
    /** False until there's an app songs play in: the only music app, or the first one picked on a song. */
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
    /** Whether setup can be done in one tap, through Shizuku or root, which the user runs. */
    val canSetUpInOneTap: Boolean = false,
    /** Whether that one tap goes through root rather than Shizuku. */
    val oneTapRoot: Boolean = false,
    /** Set when Shizuku is installed but stopped, as it is after the phone restarts, so there's no one tap until it's started. */
    val shizukuStopped: Boolean = false,
    /** Set while setup is being done in one tap. */
    val settingUpInOneTap: Boolean = false,
    /** Set when setup in one tap didn't do it all, left to the user to finish. */
    val oneTapFailed: Boolean = false,
    /** What's being fixed in one tap after setup, through Shizuku or root, as during it. */
    val fixingInOneTap: OneTapFix? = null,
    /** The list of things to fix, or the steps of one, while they show. */
    val fixes: Fixes? = null,
    /** The kind of phone, whose settings pages the steps show. */
    val phone: Phone = Phone.OTHER,
    /** Whether the microphone may be used, which lyrics listen along with. */
    val microphone: MicAccess = MicAccess.ALLOWED,
    /** Before Android 12, what a test link showed works, by [Need.key]: Crosstune's links, and other apps' that no longer take them. */
    val linksTested: Set<String> = emptySet(),
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

    /**
     * Whether songs are looked up in the app they open in, to open the song itself: unless links open
     * straight in an app and don't play right away, when a search opens instead, so nothing starts playing.
     */
    val exactMatch: Boolean
        get() = linksInCrosstune || playRightAway

    /** Whether a tapped or shared link plays as soon as it's found: once there's an app picked to play it in. */
    val playsRightAway: Boolean
        get() = linksInCrosstune && playRightAway && hasDefault

    /** Whether the song's app, installed and with the song, or maybe with it, can be asked to play it while Crosstune stays open. */
    val canPlayResult: Boolean
        get() = result?.type == ItemType.TRACK && resultDestination.let { it is Destination.Service && it.service in installed } && !resultMissing

    /** Whether the words open are the result's, found and not followed in an app yet, so they can start it there. */
    val canPlayFromLyrics: Boolean
        get() = canPlayResult && following?.app == null && !isLoadingLyrics &&
            lyricsFor?.let { it.title == result?.title && it.artist == result?.artist } == true

    /** Whether the share sheet's top row offers Crosstune's entries; Android shows them for any shared text. */
    val shareSheetEntries: Boolean
        get() = hasDefault && shareSheetApps

    /** Nothing shown, looked up or gone wrong: the main screen with just its field. */
    val idle: Boolean
        get() = result == null && error == null && !isLoading

    /**
     * Whether what Android has to allow is asked under the field: before anything's been opened,
     * so Recent's empty. Once something has, what's left folds into the bar of things to fix.
     */
    val firstAsks: Boolean
        get() = idle && history.isEmpty() && toFix > 0

    /** The app the top row offers to open a shared link in, when the default is one. */
    val shareSheetApp: MusicService?
        get() = (defaultDestination as? Destination.Service)?.service

    /** Whether Crosstune opens any links at all. */
    val interceptsAnything: Boolean get() = intercepted.isNotEmpty()

    /** Whether some link Crosstune is set to open isn't allowed yet; false before Android 12, which can't tell. */
    val someLinksNotAllowed: Boolean
        get() = (ownLinksOff && interceptsAnything) || intercepted.any { unapprovedHosts?.get(it).orEmpty().isNotEmpty() }

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
/**
 * The player's [song], asked of the music app [app], by package, with its [link] there for YouTube
 * Music; [following] it once the app plays it, and [played] since.
 */
/** A song a music app plays on the phone, with where it is and what the app lets be done, when it says. */
internal data class NowPlaying(val song: MusicMetadata, val following: Following? = null)

internal data class PlayerState(
    val song: MusicMetadata,
    val app: String?,
    val link: String? = null,
    val following: Following? = null,
    val played: Boolean = false
)

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
    /** Signed in, whether ChatGPT translates, rather than Google Translate. */
    val chatGptTranslation: Boolean = true,
    /** The language picked to translate into, as Settings lists it; null for the app's own. */
    val into: String? = null,
    /** The languages, by code, e.g. "en", the user reads, whose songs aren't translated. */
    val untranslated: Set<String> = emptySet(),
    /** How big readings are beside the words they're over. */
    val readingScale: Float = READING_SCALE,
    /** How big the words are, beside how they start. */
    val wordsScale: Float = 1f,
    /** How big the translation is, beside how it starts. */
    val translationScale: Float = 1f,
    /** Whether the words are centered rather than at the start of the line; null until picked, when a TV centers them. */
    val centered: Boolean? = null,
    /** The script the words are in, when it isn't Latin. */
    val script: Script? = null,
    /** How each line reads, once worked out. */
    val lines: List<LineReading> = emptyList(),
    /** How the song's title and its artist read, in that order, once worked out with its lines. */
    val names: List<LineReading> = emptyList(),
    /** Each line translated, or null where it needs none, once done. */
    val translations: List<String?> = emptyList(),
    /** The song's title translated, once its lines are, when it reads otherwise translated. */
    val titleMeaning: String? = null,
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
    /** Whether Japanese in Latin letters is spelled as typed, sensei, rather than as said, sensee. */
    val romajiTyped: Boolean = false,
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
    fun fresh(script: Script? = null) =
        Learning(readings, romanized, translation, chatGptTranslation, into, untranslated, readingScale, wordsScale, translationScale, centered = centered, script = script, wordsAs = wordsAs, romajiTyped = romajiTyped)
}

/** What the visuals are asked to do, as the screen has it. */
internal val UiState.visualsPlay get() = VisualsPlay(visualsSkips, visualsDrops, visualsSettings, hears = microphone == MicAccess.ALLOWED)

/** How the words show: as written, only in kana, for Japanese, or only in Latin letters. */
internal enum class WordsAs { WRITTEN, KANA, LATIN }

/**
 * What shows instead of line [index] as the words are set to show: its kana, or its Latin letters,
 * once its reading's worked out; null as written.
 */
internal fun Learning.insteadOf(index: Int): String? {
    val reading = lines.getOrNull(index) ?: return null
    if (showsAs == WordsAs.KANA) return reading.reading
    if (showsAs == WordsAs.LATIN) return reading.latin(romajiTyped).takeIf { it.isNotBlank() }
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

/** What's fixed in one tap after setup, from where it's needed: the links, following music apps, or floating lyrics. */
internal enum class OneTapFix { LINKS, FOLLOW, FLOAT }

/** A report on the words: on its way, there, or not sent. */
internal enum class ReportStatus { SENDING, SENT, FAILED }

/** The speech model, offered to be fetched by hand: [MISSING], [FETCHING] it, or the fetch [FAILED]. */
internal enum class ModelOffer { MISSING, FETCHING, FAILED }
