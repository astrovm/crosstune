package com.astrovm.crosstune

import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.astrovm.crosstune.ui.theme.Palette
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.math.abs
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex

internal sealed interface Effect {
    /**
     * [packageName] is null for custom destinations, which open in whatever app handles the URL.
     * [atMs] is when it was asked, so one that waited while Crosstune was away isn't opened long after.
     * [asking] is the song's name as a search, for an app that opens no page of its website's, only a search ([MusicService.appSearch]).
     */
    data class Open(
        val url: String,
        val packageName: String?,
        val finishAfterOpen: Boolean,
        val atMs: Long = SystemClock.elapsedRealtime(),
        val asking: String? = null,
        val qobuz: QobuzNumbers.Opening? = null
    ) : Effect
    data class Share(val url: String) : Effect

    /**
     * Brings [packageName] forward as it is, for a song it already plays, which opening it on the song would restart.
     * The player covers where an error would show, so it's left unsaid there.
     */
    data class Show(val packageName: String, val finishAfterOpen: Boolean, val saysWhenItFails: Boolean = true) : Effect

    /** Shazam can't be reached, so another app that names songs, when there's one, listens instead. */
    data object RecognizeElsewhere : Effect
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
    playback: PlaybackSource?,
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
    /** Asks cards due back once a day; nullable so tests need none. */
    private val reminders: PracticeReminders? = null,
    /** Where a song's chords come from; nullable so tests need no chord sites. */
    private val chordFinder: ChordFinder? = null,
    /** Which language a song's words are in; nullable so tests need none. */
    private val lyricsLanguage: LyricsLanguage? = null,
    /** Setup in one tap, for people who run Shizuku; nullable so tests need none. */
    private val oneTap: OneTapSetup? = null,
    /** How a Japanese song's kanji are sung, which a dictionary can't say; nullable so tests need none. */
    private val sungReadings: SungReadings? = null,
    /** UniDic, the Japanese dictionary fetched once to read kanji better; nullable so tests need none. */
    private val uniDic: UniDic? = null,
    /** Where a newer Crosstune is looked for; null for a copy an app store updates. */
    private val updates: UpdateChecker? = null,
    /** Calibration may use the microphone only when its permission has already been granted. */
    private val mayCalibrate: () -> Boolean = { false },
    /** Whether Shizuku or root let Crosstune hear the phone's own sound cleanly, as playback capture. */
    private val mayCapture: () -> Boolean,
    /** Hears the words sung in what the phone plays, to line the timed words up to them; nullable so tests need none. */
    private val singing: SingingHearing? = null,
    /** Where reports on the words go, kept private; nullable so tests need none. */
    private val reports: ReportSender? = null,
    /** How long between reports owed, sent one after another. */
    private val reportPaceMs: Long = REPORT_PACE_MS,
    /** Whether the microphone may be used, which Android says; asked again whenever Crosstune comes back. */
    private val microphoneAccess: () -> MicAccess = { MicAccess.ALLOWED },
    /** The kind of phone, whose settings pages the steps to fix things show. */
    phone: Phone,
    /** Where the words of the songs shown lately are kept for after the app's closed; nullable so tests need none. */
    private val keptOnDisk: LookupCache? = null,
    /** Where what was heard sung of songs whose words have no timing is kept, so they're timed from the start next time; nullable so tests need none. */
    private val heardUntimed: LookupCache? = null,
    /** Whether a song's lyrics are looked up as its card shows, see [lookAhead]. */
    private val lookAhead: Boolean = true,
    /** Plays a playlist longer than one queue through, see [playAll]. */
    private val playQueue: PlayQueue? = null,
    private val queueOrder: QueueOrder? = null,
    /** Pauses the music after a while, see [playerSleep]. */
    private val sleepTimer: SleepTimer? = null,
    /** Tries Qobuz's other number for a song it doesn't play, see [QobuzNumbers]. */
    private val qobuzNumbers: QobuzNumbers? = null,
    /** The songs, albums and playlists kept for offline; nullable so tests need none. */
    private val offline: OfflineSongs? = null,
    /** Makes the small YouTube player a card's line plays in when its music app can't move to it; nullable so tests need none. */
    newLinePlayer: (() -> LinePlayer)? = null,
    practiceClockLog: PracticeClockLog = PracticeClockLog(false)
) : ViewModel() {
    /**
     * An app opened on a song is covered rather than left, once Android lets Crosstune show over other
     * apps: on a TV, where brought in front of it, YouTube stops playing, and on a phone, when the app
     * only plays in front, as YouTube Music without Premium, which stops as soon as it's left.
     */
    private val playback: PlaybackSource? = playback?.let { source ->
        object : PlaybackSource by source {
            // Covered as it's opened, while Crosstune's still in sight, which Android wants for the cover to listen along.
            override fun openSong(app: String, link: String): Boolean {
                if (uiState.canFloatOverApps && (phone == Phone.TV || source.playsOnlyInFront(app))) uiState = uiState.copy(coveringApp = true)
                return source.openSong(app, link).also { if (!it) uncover() }
            }

            override suspend fun playSong(song: MusicMetadata, app: String?, link: String?, mustStart: Boolean, orLastPlayed: Boolean): Boolean {
                qobuzNumbers?.off()
                return source.playSong(song, app, link, mustStart, orLastPlayed)
            }

            // Played on from behind where it can be; one that only plays in front is brought there for it, covered the same way.
            override fun playPause(song: MusicMetadata, play: Boolean, app: String?, session: Int?) {
                qobuzNumbers?.off()
                leftJob?.cancel()
                if (!play) return source.playPause(song, false, app, session)
                viewModelScope.launch {
                    if (source.resume(song, app, session)) return@launch
                    if (uiState.canFloatOverApps) uiState = uiState.copy(coveringApp = true)
                    if (!source.bringToFront(song, app, session)) uncover()
                }
            }

            // Already in front of it, the words of the song just played show there.
            override fun comesBack() = if (!uiState.coveringApp) source.comesBack() else if (uiState.lyricsFor == null) playerLyrics() else Unit
        }
    }


    private val historyStore = HistoryStore(preferences)
    private val savedLinesStore = SavedLinesStore(preferences)
    /** Keep a translation behind a pending move, so it finds the line under its new words. */
    private val savedLineUpdates = Mutex()
    private val syncStore = LyricsSyncStore(preferences)
    private val leadStore = LeadStore(preferences)
    private val gapStore = ShazamGapStore(preferences)
    private val sungStore = SungStore(preferences)
    private val autoReports = AutoReports(preferences, now = wallClock)

    /** The timed words as found, before the lines put in time by hand, [syncPoints], move them. */
    private var foundLines: List<LyricLine> = emptyList()
    private var found: LyricsFinder.Lyrics.Found? = null

    /** The words found in Latin letters, written in Japanese or Korean by ChatGPT, line by line, once they are. */
    private var restored: List<String>? = null

    /** [lines] as they show: in the song's script when found in Latin letters and written so, timed as found. */
    private fun shown(lines: List<LyricLine>): List<LyricLine> =
        // Written otherwise, the line's words are no longer the ones timed.
        restored?.let { written -> lines.mapIndexed { index, line -> written.getOrNull(index)?.let { line.copy(text = it, words = emptyList()) } ?: line } } ?: lines
    private var syncPoints: List<SyncPoint> = emptyList()

    /**
     * How much of the song plays per ms of its words past the lines put in time: a recording sped up
     * or slowed, as uploads can be, plays its words that much sooner or later as it goes on.
     */
    private var wordsPace = 1.0

    /**
     * How the words go on past the lines put in time: at [wordsPace], unless the video's own speed is
     * already taken off its clock ([leadSpeed]), which would count that speed twice.
     */
    private val pace get() = if (leadSpeedTaken) 1.0 else wordsPace

    /** The words of the songs shown lately, by recording, the last one shown last. */
    private val kept = object : LinkedHashMap<String, KeptWords>(KEPT_SONGS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, KeptWords>) = size > KEPT_SONGS
    }
    private val destinationStore = DestinationStore(preferences, { linkState?.apps.orEmpty() }, interception::isInstalled)

    init {
        migrateExistingInstall()
        migrateLinkMode()
        migrateExactMatch()
    }

    /** The last look at what Android says about apps and links, null until the first one finishes. */
    private var linkState: LinkState? = null
    private var systemJob: Job? = null
    /** What [UiState.playingNow] is made of: the song heard playing, and the one an app is followed on. */
    private var heardNow: MusicMetadata? = null
    private var followedNow: Pair<MusicMetadata, Following>? = null

    /** Whether the bar with what's playing is on screen, the only time a song's named by ear on its own. */
    private val barInSight = MutableStateFlow(false)

    /** When the song an app names none for is listened to next, on the [now] clock, and how many times in a row nothing was heard. */
    private var nextByEarAtMs = 0L
    private var quietByEar = 0

    /** A new one each time what plays may have changed, to listen again at once; see [forgetHeardByEar]. */
    private val byEarAgain = MutableStateFlow(Any())

    /** The song last heard by ear, and where the app said it was then, to tell where in it it is now; see [fits]. */
    private var heardFrom: HeardFrom? = null

    /**
     * The song [heard] by ear, and how the app said it played it then, [following]: its session, length, queue and clock.
     * Whatever the app says next is checked against that, never against a follow that went or started over since, so
     * nothing lost or reset meanwhile lets it through. Null when the app said nothing of it then.
     */
    private class HeardFrom(val heard: Heard.Song, val following: Following?, startedAtMs: Long) {
        /** Where the app was on its clock when the sound heard started, going by at [appSpeed]; null when it didn't say. */
        val appAtMs = following?.clock?.positionAt(startedAtMs)
        val appSpeed = following?.clock?.speed ?: 1f
    }

    /**
     * Whether the song shown is what an app on the phone plays with no song named, as heard there, so
     * listening along with its words hears the phone's own sound first, as the microphone hears the room too.
     */
    private var alongByPhone = false

    /** What an app plays now with no song named, as Android says while it plays; null while none does, or it's paused. */
    private var liveUnnamed: Unnamed? = null

    /**
     * The last one seen playing, and how it's followed in its app, paused too, as long as the app has it,
     * as a song is; see [PlayingUnnamed.following]. A song or another one seen playing since takes its place.
     */
    private var followedUnnamed: Pair<Unnamed, Following>? = null

    /** What the last listen by ear could hear with, as [earsNow] has it, when it named no song; null after one that did. */
    private var unheardWith: List<Boolean>? = null

    /** Whether the last listen by ear to what an app plays now, with no song named, named none: talk, a song Shazam doesn't know, or a failure. */
    private var unheardByEar = false

    /** The work naming a song by ear, which a listen asked for waits on, so the microphone isn't opened twice. */
    private var byEar: Job? = null

    /** The one listen by ear going on, which listening along or hearing the singing stops and waits on first. */
    private var byEarListen: Job? = null

    /**
     * The words of the song last named by ear, looked for ahead, apart from listening: a slow lookup
     * holds neither the next listen nor one asked for.
     */
    private var byEarLook: Job? = null

    /** The screen's state, which the parts work is handed to change too. */
    private val screen = mutableStateOf(
        UiState(
            linksInCrosstune = preferences.getBoolean(KEY_LINKS_IN_CROSSTUNE, false),
            playRightAway = preferences.getBoolean(KEY_PLAY_RIGHT_AWAY, true),
            cleanLinks = preferences.getBoolean(KEY_CLEAN_LINKS, true),
            offlineSpaceMb = preferences.getInt(KEY_OFFLINE_SPACE, OFFLINE_SPACE_MB),
            offlineLikes = preferences.getBoolean(KEY_OFFLINE_LIKES, true),
            shareSheetApps = preferences.getBoolean(KEY_SHARE_SHEET_APPS, true),
            wakesForNextSong = preferences.getBoolean(KEY_WAKES_FOR_NEXT_SONG, false),
            onlyMusicVideos = preferences.getBoolean(KEY_ONLY_MUSIC_VIDEOS, true),
            theme = ThemeMode.entries.firstOrNull { it.name == preferences.getString(KEY_THEME, null) } ?: ThemeMode.SYSTEM,
            palette = savedPalette(preferences.getString(KEY_PALETTE, null)),
            pureBlack = preferences.getBoolean(KEY_PURE_BLACK, false),
            playingBar = preferences.getBoolean(KEY_PLAYING_BAR, true),
            linesEarlyMs = preferences.getLong(KEY_LINES_EARLY, LINES_EARLY_MS),
            nextLineAheadMs = preferences.getLong(KEY_NEXT_LINE_AHEAD, nextLineAheadBefore()),
            visuals = preferences.getBoolean(KEY_VISUALS, true),
            visualsShade = preferences.getFloat(KEY_VISUALS_SHADE, DEFAULT_VISUALS_SHADE),
            visualsSettings = VisualsStore(preferences).load(),
            canFollowApps = playback?.hasAccess() == true,
            followRestricted = playback?.restricted() == true,
            notFoundAction = NotFoundAction.entries.firstOrNull { it.name == preferences.getString(KEY_NOT_FOUND, null) } ?: NotFoundAction.ASK,
            chords = ChordsState(on = preferences.getBoolean(KEY_CHORDS, false)),
            autoReports = autoReports.allowed,
            cardTypes = loadCardTypes(preferences),
            learning = Learning(
                readings = preferences.getBoolean(KEY_READINGS, true),
                romanized = preferences.getBoolean(KEY_ROMANIZED, false),
                translation = preferences.getBoolean(KEY_TRANSLATION, false),
                chatGptTranslation = preferences.getBoolean(KEY_TRANSLATION_CHATGPT, true),
                into = preferences.getString(KEY_TRANSLATE_INTO, null)?.let(AppLanguage::translationTag),
                untranslated = preferences.getStringSet(KEY_UNTRANSLATED, null).orEmpty().filter { it in AppLanguage.untranslatedTags }.toSet(),
                readingScale = preferences.getFloat(KEY_READING_SCALE, READING_SCALE).coerceIn(MIN_READING_SCALE, MAX_READING_SCALE),
                wordsScale = preferences.getFloat(KEY_WORDS_SCALE, 1f).coerceIn(MIN_WORDS_SCALE, MAX_WORDS_SCALE),
                // Until it's set, it's as big as the words, as it was when they went together.
                translationScale = preferences.getFloat(KEY_TRANSLATION_SCALE, preferences.getFloat(KEY_WORDS_SCALE, 1f)).coerceIn(MIN_WORDS_SCALE, MAX_WORDS_SCALE),
                centered = if (preferences.contains(KEY_WORDS_CENTERED)) preferences.getBoolean(KEY_WORDS_CENTERED, false) else null,
                wordsAs = WordsAs.entries.firstOrNull { it.name == preferences.getString(KEY_WORDS_AS, null) } ?: WordsAs.WRITTEN,
                romajiTyped = preferences.getBoolean(KEY_ROMAJI_TYPED, false),
                katakanaAsHiragana = preferences.getBoolean(KEY_KATAKANA_AS_HIRAGANA, false)
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
            reminder = reminders?.state ?: Reminder(),
            systemStateKnown = false,
            phone = phone,
            microphone = microphoneAccess()
        ).withOneTap().withDestinations()
    )

    init {
        // The timer is the app's, so one set from another screen, or before Crosstune was closed, shows too.
        sleepTimer?.let { timer -> viewModelScope.launch { timer.set.collect { uiState = uiState.copy(sleep = it) } } }
        // A force stop takes the alarm with it, until now.
        reminders?.arm()
    }

    var uiState by screen
        private set

    /** Kept lines practised as cards. */
    private val cards = PracticeCards(screen, viewModelScope, playback, matcher, savedLinesStore, AppTraitsStore(preferences, wallClock), wallClock, now, reminders, newPlayer = newLinePlayer, readDispatcher = systemDispatcher, clockLog = practiceClockLog)

    /** Shizuku can start or stop at any time, which takes setting up in one tap with it. */
    private val stopWatchingOneTap = oneTap?.watch { uiState = uiState.withOneTap() }

    /** Whether there's one tap now, and through what, or Shizuku stopped, which takes it away. */
    private fun UiState.withOneTap(): UiState =
        copy(canSetUpInOneTap = oneTap?.available() == true, oneTapRoot = oneTap?.root() == true, shizukuStopped = oneTap?.shizukuStopped() == true)

    private val visualsStore = VisualsStore(preferences)

    /** Visuals settings changed, here or by the visuals themselves, e.g. one found too slow, show at once. */
    private val visualsChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in VisualsStore.KEYS) uiState = uiState.copy(visualsSettings = visualsStore.load())
    }.also(preferences::registerOnSharedPreferenceChangeListener)

    override fun onCleared() {
        cards.release()
        stopWatchingOneTap?.invoke()
        preferences.unregisterOnSharedPreferenceChangeListener(visualsChanged)
    }

    /**
     * Does all there is to fix, through Shizuku or root: stops the apps in the way, allows the
     * links, the microphone, lyrics following music apps, and a late reminder's exact minute. What it couldn't do is still there to fix by hand.
     */
    fun setUpInOneTap() {
        val setup = oneTap ?: return
        if (uiState.settingUpInOneTap) return
        val state = uiState
        uiState = uiState.copy(settingUpInOneTap = true, oneTapFailed = false)
        viewModelScope.launch {
            val worked = inOneTap(
                setup, apps = state.blockingApps.orEmpty().map { it.packageName }, links = state.interceptsAnything,
                follow = !state.canFollowApps, microphone = state.microphone != MicAccess.ALLOWED, exact = state.reminder.late
            )
            uiState = uiState.copy(settingUpInOneTap = false, oneTapFailed = !worked)
            refreshSystemState()
            refreshFollowing()
            refreshReminder()
        }
    }

    /**
     * Fixes [fix] in one tap, through Shizuku or root, after setup as during it, rather than by hand,
     * and [then] does what it was for; what it couldn't is left to the steps. Whether there's one tap to try.
     */
    fun fixInOneTap(fix: OneTapFix, then: () -> Unit = {}): Boolean {
        val setup = oneTap ?: return false
        if (!uiState.canSetUpInOneTap) return false
        uiState.fixingInOneTap?.let { return it == fix }
        uiState = uiState.copy(fixingInOneTap = fix)
        viewModelScope.launch {
            val worked = inOneTap(
                setup, links = fix == OneTapFix.LINKS, follow = fix == OneTapFix.FOLLOW, floating = fix == OneTapFix.FLOAT, exact = fix == OneTapFix.EXACT
            )
            val need = when (fix) {
                OneTapFix.LINKS -> Need.Links
                OneTapFix.FOLLOW -> Need.Follow
                OneTapFix.FLOAT -> Need.Float
                OneTapFix.EXACT -> Need.Exact
            }
            uiState = uiState.copy(fixingInOneTap = null, fixes = if (worked) uiState.fixes else Fixes(list = uiState.fixes?.list == true, steps = listOf(need), failed = true))
            refreshSystemState()
            refreshFollowing()
            // Allowed, the alarm is set again for the exact minute at once, and the problem goes.
            refreshReminder()
            if (worked) then()
        }
        return true
    }

    private suspend fun inOneTap(
        setup: OneTapSetup,
        apps: List<String> = emptyList(),
        links: Boolean,
        follow: Boolean,
        microphone: Boolean = false,
        floating: Boolean = false,
        exact: Boolean
    ): Boolean {
        val worked = setup.setUp(apps = apps, links = links, follow = follow, microphone = microphone, floating = floating, exact = exact)
        // Android lets Crosstune follow music apps a moment after it's told to.
        var waits = 0
        while (worked && follow && playback?.hasAccess() != true && waits++ < FOLLOW_WAITS) delay(FOLLOW_WAIT_MS)
        return worked
    }

    /**
     * Versions before first-run setup always intercepted Spotify links. People updating from one
     * keep that, so their links don't silently stop opening in Crosstune.
     */
    private fun migrateExistingInstall() {
        if (preferences.contains(KEY_SETUP_COMPLETE)) return
        // Recorded on a fresh install's first launch too, so data created after it (e.g. history
        // from a link opened right away) is never mistaken for an old install.
        val isExistingInstall = LEGACY_KEYS.any(preferences::contains)
        if (isExistingInstall) interception.setEnabled(MusicService.SPOTIFY, true)
        preferences.edit { putBoolean(KEY_SETUP_COMPLETE, isExistingInstall) }
    }

    /**
     * Links show in Crosstune first for whoever had them ask or show the song first, both of which
     * the song's card does now, and they wait for Play there. Everyone else's go straight to the app.
     */
    private fun migrateLinkMode() {
        if (preferences.contains(KEY_LINKS_IN_CROSSTUNE)) return
        val shownFirst = preferences.getBoolean(KEY_ASK_EACH_TIME, false) || preferences.getBoolean(KEY_SHOW_SONG_FIRST, false)
        preferences.edit {
            putBoolean(KEY_LINKS_IN_CROSSTUNE, shownFirst)
            putBoolean(KEY_PLAY_RIGHT_AWAY, !shownFirst)
        }
    }

    /**
     * Open the exact match was a setting of its own. Turned off, links opening straight in an app
     * opened a search there, which is what not playing right away does now; songs opening in
     * Crosstune are always looked up.
     */
    private fun migrateExactMatch() {
        if (!preferences.contains(KEY_EXACT_MATCH)) return
        val searched = !preferences.getBoolean(KEY_EXACT_MATCH, true) && !preferences.getBoolean(KEY_LINKS_IN_CROSSTUNE, false)
        preferences.edit {
            if (searched) putBoolean(KEY_PLAY_RIGHT_AWAY, false)
            remove(KEY_EXACT_MATCH)
        }
    }

    private val effectChannel = Channel<Effect>(Channel.BUFFERED)
    val effects: Flow<Effect> = effectChannel.receiveAsFlow()

    init {
        // A fresh install opens every source's links from the start, rather than in a setup first.
        if (!preferences.getBoolean(KEY_SETUP_COMPLETE, false)) {
            preferences.edit { putBoolean(KEY_SETUP_COMPLETE, true) }
            preselectSources()
        }
        // What's kept for offline, and what's still to fetch for it.
        if (offline != null) viewModelScope.launch { showKeptOffline() }
        // Read as the app starts, before anyone can open them, so they show at once as they were left.
        keptOnDisk?.let { disk ->
            viewModelScope.launch {
                val read = withContext(systemDispatcher) { disk.entries().mapNotNull { (key, json) -> KeptWords.from(json)?.let { key to it } } }
                // Any shown since are newer.
                read.forEach { (key, words) -> if (key !in kept) kept[key] = words }
            }
        }
        val readingCache = if (lyricsFinder != null && sungReadings != null) CachedSavedReadings(lyricsFinder, sungReadings, tutor) else null
        // Read older lines again without opening their songs. Before dictionary versions were saved, only the caches can tell how they were sung.
        if (uiState.savedLines.any { it.readByOlder || (readingCache != null && it.needsCachedReading) }) {
            viewModelScope.launch {
                // As they were read: with UniDic, when it's on the phone; never fetched for this.
                uniDic?.let { if (Readings.uniDic == null) Readings.uniDic = it.tokenizer(fetch = false) }
                savedLinesStore.change(systemDispatcher) { kept ->
                    val versioned = readAgain(kept) ?: kept
                    readingCache?.refresh(versioned) ?: versioned.takeIf { it != kept }
                }?.let { fresh ->
                    // A session started while this ran copied the old lines. It shows these, and keeps its order and schedule.
                    uiState = uiState.copy(savedLines = fresh, practice = uiState.practice?.withReadings(fresh))
                }
            }
        }
        updates?.let { checker ->
            viewModelScope.launch {
                // Asked first, so what changed on screen meanwhile is kept.
                val update = checker.newer()
                uiState = uiState.copy(update = update)
            }
        }
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
        viewModelScope.launchKept({ chatGptJob = it }) {
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
        explanationJob?.cancel()
        songExplanationJob?.cancel()
        uiState = uiState.copy(chatGpt = uiState.chatGpt.copy(model = slug))
        translateAgain(uiState.learning.copy(explanation = null, songExplanation = null))
    }

    fun signOutOfChatGpt() {
        val account = chatGpt ?: return
        chatGptJob?.cancel()
        uiState = uiState.copy(chatGpt = ChatGptState())
        viewModelScope.launch { account.signOut() }
    }

    private var job: Job? = null
    private var lastRequest: Request? = null
    private var pendingOpen = false
    /** A link opened in Crosstune plays as soon as it's found, once. */
    private var pendingPlay = false

    /** What was last looked up, and how, for Try again. */
    private data class Request(val input: LinkInput, val openWhenReady: Boolean, val destination: Destination?, val here: Boolean)

    /**
     * The user's choices and which sources Crosstune opens, which are quick to read, so anything
     * reading them right after a change sees it. What Android says about other apps and links comes
     * from the last look at it, see [loadSystemState].
     */
    private fun UiState.withDestinations(): UiState {
        val destinations = destinationStore.allDestinations()
        val picked = destinationStore.hasDefault(destinations)
        val offered = if (picked) null else destinationStore.offered(destinations)
        return copy(
            defaultDestination = offered ?: destinationStore.defaultDestination(destinations),
            destinations = destinations,
            selectedDestination = selectedDestination?.takeIf { it in destinations },
            rules = MusicService.entries.mapNotNull { source -> destinationStore.rule(source, destinations)?.let { source to it } }.toMap(),
            frontendRules = Frontend.SOURCES.mapNotNull { frontend -> destinationStore.rule(frontend, destinations)?.let { frontend to it } }.toMap(),
            intercepted = MusicService.entries.filter { it.canBeSource && interception.isEnabled(it) }.toSet(),
            hasDefault = picked
        ).withLinkState()
    }

    /** What Android last said about apps and links, as it concerns the sources Crosstune opens now. */
    private fun UiState.withLinkState(): UiState {
        val state = linkState ?: return this
        val claiming = state.claimingApps(intercepted)
        return copy(
            systemStateKnown = true,
            installed = state.serviceApps.keys,
            musicApps = state.apps,
            unapprovedHosts = state.unapprovedHosts,
            ownLinksOff = state.ownLinksOff,
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
        lastConfirmedQueue?.takeIf { it.confirmed }?.let(::refreshQueueExpiry)
        val previousDestination = uiState.resultDestination
        uiState = uiState.withDestinations().withOneTap().copy(microphone = microphoneAccess(), lockless = playback?.lockless() == true)
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
        viewModelScope.launchKept({ systemJob = it }) {
            val (stored, state) = withContext(systemDispatcher) { historyStore.load() to interception.linkState() }
            linkState = state
            // Anything looked up or cleared meanwhile is newer than what was read.
            val unchanged = uiState.history === history
            val previousDestination = uiState.resultDestination
            // The choices again too, as a build found by the links it opens is one of them: the song on screen, found
            // for the app it would have opened in before, is found again for that build.
            uiState = uiState.withDestinations().let { if (unchanged) it.copy(history = stored) else it }
            if (previousDestination != uiState.resultDestination) prepareResultDestination()
            pickTheOnlyMusicApp()
        }
    }

    /**
     * With one music app on the phone, but YouTube, which comes with most and is mostly videos,
     * that's where songs play, without asking; it can be changed in settings.
     */
    private fun pickTheOnlyMusicApp() {
        if (uiState.hasDefault) return
        // Saved as the service, which a build patched from it stands for while its own app isn't there.
        selectDefault(Destination.Service((uiState.installed - MusicService.YOUTUBE).singleOrNull() ?: return))
    }

    /** Without an app songs play in yet, the first one picked on a song becomes it. */
    private fun adoptDefault(destination: Destination) {
        if (!uiState.hasDefault) selectDefault(destination)
    }

    fun onUrlChange(text: String) {
        // Typed over, the link or song waiting for the internet is no longer wanted.
        linkWaits = false
        forgetHeld()
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
        viewModelScope.launchKept({ searchJob = it }) {
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
            val describes = usesChatGpt() && words.split(phoneRegex("\\s+")).size >= DESCRIPTION_WORDS
            uiState = uiState.copy(
                isSearchingSongs = false,
                isAskingForSongs = describes,
                songSearch = songs,
                songsFromChatGpt = 0,
                songSearchQuery = words,
                error = null
            )
            if (!describes) return@launchKept
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
        viewModelScope.launchKept({ job = it }) {
            val songs = tutor.songsLike(song)?.let { found(searcher, it) }.orEmpty()
            uiState = uiState.copy(isLoading = false)
            if (songs.isEmpty()) return@launchKept showError(AppError.NO_SONGS_LIKE)
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
        (lastRequest?.input as? LinkInput.RecognizedSong)?.takeIf { it.text == typed }?.let { return it }
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
     * With [show], it only shows the result, for the user to pick what to do. Otherwise it goes
     * where links go: straight to the app, or to the song's card here. With [keepWords], the words
     * floating as it came stay, and the player under them, for a link only handed on.
     */
    /** A link only handed on, the words floating again: done with it, so the words show when Crosstune's opened, not the hand-off. */
    fun handedOn() {
        uiState = uiState.copy(handlingIncomingLink = false, handingOff = false)
    }

    fun resolveIncoming(text: String?, destination: Destination? = null, show: Boolean = false, after: AfterLookup = AfterLookup.OPEN, keepWords: Boolean = false) {
        lookUpIncoming(text, destination, show, after, keepWords)
        // Once the lookup's begun, as beginning it forgets where the last song came from.
        uiState = uiState.copy(fromAnotherApp = true)
    }

    private fun lookUpIncoming(text: String?, destination: Destination?, show: Boolean, after: AfterLookup, keepWords: Boolean) {
        val incoming = text?.let { MusicLinks.extractFirstUrl(it) ?: it }?.trim().orEmpty()
        val here = showsHere(destination, show, after)
        // The words stay only for a link handed on: one shown here is what's shown.
        val keepsWords = keepWords && !show && !here
        cancelContentWork(keepsWords = keepsWords)
        uiState = uiState.copy(
            linkText = incoming,
            handlingIncomingLink = true,
            handingOff = !show && !here,
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
        resolve(input, openWhenReady = !show && !here, destination, here = here, keepsWords = keepsWords)
    }

    /** Whether a link that came in shows here: set to, or without an app songs play in yet, where one's picked. */
    private fun showsHere(destination: Destination?, show: Boolean, after: AfterLookup) =
        !show && destination == null && after == AfterLookup.OPEN && (uiState.linksInCrosstune || !uiState.hasDefault)

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

    /** Whether a link just copied may be filled in: nothing else is shown or under way. */
    val readyForCopy get() = uiState.let { it.linkText.isBlank() && it.result == null && !it.isLoading && !it.listening && it.lyricsFor == null }

    /** A music link copied a moment ago, filled in as if pasted; a YouTube video only once it's known to be music. */
    fun pasteCopy(text: String) {
        val input = MusicLinks.parse(text)
        if (input !is LinkInput.Link || !input.link.mayNotBeMusic) return pasteLink(text)
        resolve(input, openWhenReady = false, copied = true)
    }

    /** A YouTube video, which Crosstune only takes once YouTube Music says it's music, unless any video may be. */
    private val MusicLink.mayNotBeMusic get() = uiState.onlyMusicVideos && service == MusicService.YOUTUBE && type == ItemType.TRACK

    @VisibleForTesting
    internal val lookingUp get() = job?.isActive == true

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
        val (input, openWhenReady, destination, here) = lastRequest ?: return
        val fromAnotherApp = uiState.fromAnotherApp
        resolve(input, openWhenReady, destination, here = here)
        // Tried again, a link from another app still goes back there with Back.
        uiState = uiState.copy(fromAnotherApp = fromAnotherApp)
    }

    /** Whether the link last looked up couldn't be for being offline, so it's looked up once back online. */
    private var linkWaits = false

    /**
     * The phone reaching the internet, or not. Back online, what couldn't be done without it is done
     * now: the link looked up, the song heard named, the words, their translation, their chords and the songs of a
     * name. The song heard is named from the sound heard then, not listened for again, as what plays
     * now may be another one.
     */
    fun connectionChanged(online: Boolean) {
        val wasOffline = uiState.offline
        // Said to have failed a moment before Android said it's offline, a link or song waits all the same.
        if (!online && uiState.error == AppError.NETWORK) {
            if (retryListens) uiState = uiState.copy(heardWaits = held != null) else linkWaits = true
            uiState = uiState.copy(error = null)
        }
        uiState = uiState.copy(offline = !online)
        if (!online || !wasOffline) return
        if (linkWaits) {
            linkWaits = false
            retry()
        }
        if (uiState.heardWaits) nameHeld()
        if (uiState.lyricsFailed) showLyrics()
        if (uiState.learning.translationFailed) retryTranslation()
        if (uiState.chords.failed) retryChords()
        uiState.songSearchQuery.takeIf { it.isNotEmpty() && uiState.songSearch.isEmpty() && !uiState.isSearchingSongs }?.let(::searchSongs)
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
        // The words of what's playing are the song's heard in what an app plays, while it's still on, without listening again.
        if (lyrics) {
            confirmedRadioQueue()?.song?.let { song ->
                showSong(song)
                return showQueueLyrics(song)
            }
            // Shown as any song is, which stops whatever else was under way, and keeps the player on this song under its words.
            // Only when a tap would open it too; else it's listened to again.
            uiState.playingUnnamed?.heard?.takeIf(::opens)?.let { heard -> return showHeard(heard, lyrics = true, byPhone = true) }
        }
        // The bar goes while it listens, and its own listening with it, so the microphone isn't opened twice.
        barInSight.value = false
        cancelContentWork()
        heardSong = null
        hear(null)
        retryListens = true
        uiState = uiState.copy(
            listening = true, isLoading = false, isMatching = false, error = null, linkText = "", result = null,
            destinationUrls = emptyMap(), selectedDestination = null, link = null, showDestinationPicker = false,
            handlingIncomingLink = false, handingOff = false
        )
        val byEar = byEar
        // Its words are of what an app plays with no song named, heard from the phone's own sound first, as the
        // microphone hears the room too; with no app playing, one on a speaker it's cast to, or to name a song, the
        // microphone hears what's nearby.
        val unnamed = lyrics && playback?.playsHereWithoutSong() == true
        viewModelScope.launchKept({ job = it }) {
            byEar?.cancelAndJoin()
            var checked: CheckedPlayerSong? = null
            val heard = try {
                if (unnamed) listener.listenToPlaying() else listener.listen { playing ->
                    val found = checkPlayerSong(playing)
                    checked = found
                    found?.lookup?.match != ExactMatcher.Match.Missing
                }
            } finally {
                if (isActive) uiState = uiState.copy(listening = false)
            }
            answer(heard, lyrics, byPhone = unnamed, checked = checked?.takeIf { it.song == heard })
        }
    }

    private data class CheckedPlayerSong(val song: Heard.Song, val destination: Destination, val lookup: ExactMatcher.Lookup)

    /** Checks the catalog before showing the app's identification. A search that fails tells us nothing about it. */
    private suspend fun checkPlayerSong(playing: Heard.Song): CheckedPlayerSong? {
        if (!uiState.exactMatch) return null
        val destination = uiState.defaultDestination
        val service = destination.matchService ?: return null
        if (!canMatchExactly(service, playing.metadata.type)) return null
        return CheckedPlayerSong(playing, destination, matcher.lookup(service, playing.metadata))
    }

    /** Shows what listening found, its words too when asked for [lyrics]; [byPhone] when it's what an app on the phone plays. */
    private fun answer(heard: Heard, lyrics: Boolean, byPhone: Boolean = false, checked: CheckedPlayerSong? = null) {
        when (heard) {
            is Heard.Song -> showHeard(heard, lyrics, byPhone, checked)
            Heard.Nothing -> showListenError(AppError.NO_MATCH)
            is Heard.Failed -> showListenError(heard.error)
            is Heard.Unsent -> {
                held = heard to lyrics
                // Offline, it waits, as a link does; online, the network failed some other way, and says so.
                if (uiState.offline) uiState = uiState.copy(heardWaits = true) else showListenError(AppError.NETWORK)
            }
        }
    }

    /** A song heard while Shazam couldn't be reached, and whether its words were asked for. */
    private var held: Pair<Heard.Unsent, Boolean>? = null

    private fun forgetHeld() {
        held = null
        uiState = uiState.copy(heardWaits = false)
    }

    /** Asks Shazam about the song heard offline, now the phone is back online. */
    private fun nameHeld() {
        val (heard, lyrics) = held ?: return
        forgetHeld()
        uiState = uiState.copy(isLoading = true, error = null)
        viewModelScope.launchKept({ job = it }) {
            val named = heard.send()
            uiState = uiState.copy(isLoading = false)
            answer(named, lyrics)
        }
    }

    /**
     * Offers what music apps play while Crosstune is in sight, fetching each song's words ahead. A
     * song an app paused stays offered, with its app's buttons, as long as the app has it.
     */
    suspend fun watchPlayingNow() {
        val previous = uiState.playingNow?.song
        val shownUnnamed = uiState.playingUnnamed
        val previousUnnamed = shownUnnamed?.playing
        heardNow = null
        followedNow = null
        liveUnnamed = null
        followedUnnamed = null
        // The station may have paused out of sight. Keep expired text tied to its old deadline.
        if (lastConfirmedQueue?.isConfirmedAt(now()) == true) forgetRadioPlayback()
        if (shownUnnamed != null && shownUnnamed.playing.app == RadioGarden.PACKAGE) {
            uiState = uiState.copy(playingUnnamed = shownUnnamed.copy(heard = null, confirmedQueueSong = null))
        }
        val source = playback?.takeIf { it.hasAccess() }
        if (source == null) {
            uiState = uiState.copy(playingNow = null, playingUnnamed = null)
            return
        }
        // Registration, callbacks and cancellation share the main thread, so none can reattach
        // a controller while the lifecycle is releasing its listeners.
        coroutineScope {
            launch {
                source.nowPlayingUpdates()
                    .onEach { heard ->
                        heardNow = heard?.metadata
                        showPlayingNow()
                    }
                    // Clock updates keep the offer current without restarting its lyrics lookup.
                    .distinctUntilChangedBy { it?.metadata }
                    .collectLatest { heard -> heard?.let { lyricsFinder?.firstLook(it.metadata) } }
            }
            launch {
                source.nowPlayingUpdates()
                    .mapNotNull { it?.metadata }
                    // Paused apps don't announce a playing song; ask the last one's app whether it still has it.
                    .onStart { previous?.let { emit(it) } }
                    .distinctUntilChanged()
                    .collectLatest { song ->
                        source.follow(song).collect { following ->
                            followedNow = following?.let { song to it }
                            showPlayingNow()
                            likeKeptOffline(song, following)
                        }
                    }
            }
            launch { nameUnnamed(source) }
            launch { followUnnamed(source, previousUnnamed) }
        }
    }

    private fun followResultSession(source: PlaybackSource, song: MusicMetadata, app: String): Flow<Following?> {
        val installed = interception::isInstalled
        val packages = MusicService.ofOwnApp(app)?.appPackages.orEmpty()
            .filter { installed(it) && sameApp(app, it, installed) }.ifEmpty { listOf(app) }
        return combine(packages.map { source.follow(song, it) }) { sessions ->
            sessions.filterNotNull().filter { it.canContinueIn(app) }.maxByOrNull { it.clock.playing }
        }
    }

    /** A song paused in its final second can be opened again from its link. */
    private fun Following.canContinueIn(app: String?): Boolean =
        sameApp(app, appPackage, interception::isInstalled) &&
            (clock.playing || durationMs?.let { it <= 0 || clock.positionMs < it - RESUME_END_MARGIN_MS } != false)

    /**
     * Follows the last thing an app played with no song named in that app, for the bar's buttons and where it is,
     * and keeps it on the bar once it's paused, as long as the app has it, as a song is. A song seen playing since,
     * or another one with no song named, takes its place.
     */
    private suspend fun followUnnamed(source: PlaybackSource, previous: Unnamed?) {
        merge<Unnamed?>(source.unnamedUpdates().filterNotNull(), source.nowPlayingUpdates().filterNotNull().map { null })
            // Paused, it isn't said to play; ask the last one's app whether it still has it.
            .onStart { previous?.let { emit(it) } }
            // The session too: the same app can start another for the same station, and the bar's buttons follow that one.
            .distinctUntilChangedBy { station -> station?.let { listOf(it.app, it.played, it.reportsState, it.session) } }
            .collectLatest { unnamed ->
                followedUnnamed = null
                showUnnamed()
                // The notification identifies the station, but there is no clock or transport to follow by name.
                if (unnamed?.reportsState != true) return@collectLatest
                source.follow(unnamed.played, unnamed.app, unnamed.session).collect { following ->
                    followedUnnamed = following?.let { unnamed to it }
                    if (unnamed.app != RadioGarden.PACKAGE) checkHeard(following)
                    showUnnamed()
                }
            }
    }

    /**
     * Keeps the song heard by ear on what the app with no song named plays only while what it says of it, [now], is what
     * it said when the song was heard, as [HeardFrom] has it. When it says nothing it can be told by, as no position, or
     * said nothing then, what plays can't be told from the song heard: it goes, as no song is better than the wrong one.
     */
    private fun checkHeard(now: Following?) {
        val from = heardFrom?.takeIf { it.heard === uiState.playingUnnamed?.heard } ?: return
        val was = from.following
        val another = if (was == null || now == null) was != now else mayPlayAnother(was, now)
        if (another) forgetHeardByEar()
    }

    /**
     * Whether what an app with no song named played on may be another now, as it was followed [was] and is [now]: it
     * isn't where it should be by now, as after a skip or a seek, its queue or its length changed, or it plays in a new
     * session. Each may be the next file, with the same blank name.
     */
    private fun mayPlayAnother(was: Following, now: Following): Boolean {
        val at = now()
        return abs(was.clock.positionAt(at) - now.clock.positionAt(at)) > BY_EAR_JUMP_MS ||
            was.upNext != now.upNext || otherLength(was.durationMs, now.durationMs) || was.session != now.session
    }

    /**
     * Whether an app's length, [was] then [now], says it plays another: any change but one that only grows, as a
     * station's does that says how long it's played, VRadio's by a minute each minute (Issue #998).
     */
    private fun otherLength(was: Long?, now: Long?) = if (was == null || now == null) was != now else now < was

    /**
     * What plays may have changed, as by a skip, a seek, or a song up next played: the song heard in it isn't known to be
     * on, so it's shown no more, and what plays is listened to again at once.
     */
    private fun forgetHeardByEar() {
        uiState.playingUnnamed?.let { uiState = uiState.copy(playingUnnamed = it.copy(heard = null)) }
        heardFrom = null
        nextByEarAtMs = 0L
        quietByEar = 0
        unheardByEar = false
        unheardWith = null
        byEarAgain.value = Any()
    }

    /** A button Crosstune sent to [app] may have moved what it plays on: see [forgetHeardByEar]. Only for the app that names no song. */
    private fun movedOn(app: String?) {
        if (app != null && app == uiState.playingUnnamed?.playing?.app) forgetHeardByEar()
    }

    /**
     * Shows what an app plays with no song named: what plays now, else the last one, paused, while its app has it,
     * unless a song plays. The song heard in it stays only while it plays on, as it isn't known once it's paused.
     */
    private fun showUnnamed() {
        val shown = liveUnnamed ?: followedUnnamed?.first?.takeIf { heardNow == null }
        val following = followedUnnamed?.takeIf { (followed) -> shown?.reportsState == true && followed.app == shown.app && followed.played == shown.played }?.second
        val song = confirmedRadioQueue(shown)?.song
        val heard = uiState.playingUnnamed?.takeIf {
            liveUnnamed != null && (it.playing == shown || it.playing.sameRadioPlayback(shown))
        }?.heard?.takeIf { song == null || RadioGarden.sameSong(song, it.metadata) }
        uiState = uiState.copy(playingUnnamed = shown?.let { PlayingUnnamed(it, heard, following, song) })
        showPlayingNow()
    }

    /** The queue and logo can change while the same Radio Garden station plays on. */
    private fun Unnamed.sameRadioPlayback(other: Unnamed?): Boolean {
        if (app != RadioGarden.PACKAGE || other?.isRadioSession(name, session) != true) return false
        return where == other.where
    }

    /** Radio Garden's clock measures the stream. A song heard in it has its own clock. */
    private val PlayingUnnamed?.followingForHearing: Following?
        get() = this?.takeUnless { it.playing.app == RadioGarden.PACKAGE }?.following

    /** Tells whether the bar with what's playing is on screen, as it comes and goes. */
    fun playingBarInSight(shown: Boolean) {
        barInSight.value = shown
    }

    /** Stops a listen by ear, and waits till it lets go of the microphone and the phone's sound, so neither is opened twice. */
    private suspend fun byEarLetsGo() {
        byEarListen?.cancelAndJoin()
    }

    /**
     * Shows the song [heard] by ear on the bar till it should be over, or a while when that isn't known,
     * then what the app plays alone again, though nothing new was heard yet, and in or out of sight. Only
     * while the app plays what it played as it was heard, [startedWith]: see [checkHeard]. False when it went on
     * while it was listened to, as to its next file, so what was heard is of what it played before.
     */
    private fun showHeardByEar(heard: Heard.Song, startedWith: Following?): Boolean {
        heardFrom = HeardFrom(heard, startedWith, heard.startedAtMs ?: now())
        uiState.playingUnnamed?.let { uiState = uiState.copy(playingUnnamed = it.copy(heard = heard)) }
        checkHeard(uiState.playingUnnamed.followingForHearing)
        if (heardFrom?.heard !== heard) return false
        viewModelScope.launch {
            delay(endOf(heard)?.minus(now()) ?: BY_EAR_UNKNOWN_MS)
            // Only this hearing: a song heard since stays till its own end.
            uiState.playingUnnamed?.takeIf { it.heard === heard }?.let { uiState = uiState.copy(playingUnnamed = it.copy(heard = null)) }
        }
        return true
    }

    /**
     * Names the song while the station's bar is on screen. Checks any title and artist from its queue
     * first, then listens by ear. The confirmed title stays while this station's next claim is checked.
     */
    private suspend fun nameUnnamed(source: PlaybackSource) {
        source.unnamedUpdates().distinctUntilChanged().collectLatest { unnamed ->
            // Started, paused or played again, it's listened to at once once it plays.
            if (unnamed?.sameRadioPlayback(liveUnnamed) != true) {
                nextByEarAtMs = 0L
                quietByEar = 0
                unheardByEar = false
            }
            liveUnnamed = unnamed
            val check = updateRadioQueue(unnamed)
            showUnnamed()
            if (unnamed == null) return@collectLatest
            coroutineScope {
                if (check != null) {
                    if (confirmedRadioQueue() == null) confirmQueueSong(check) else launch { confirmQueueSong(check) }
                }
                // The phone has nothing to hear when the station plays away. A skip restarts the listen.
                if (!unnamed.away) {
                    combine(barInSight, byEarAgain) { inSight, again -> again.takeIf { inSight } }
                        .distinctUntilChanged().collectLatest { if (it != null) nameByEar() }
                }
            }
        }
    }

    internal class QueueCheck(
        val station: String?,
        val session: Int?,
        val song: MusicMetadata,
        private val firstSeen: Long
    ) {
        var confirmed = false
            private set
        private var lengthMs = QUEUE_SONG_UNKNOWN_MS
        private var lastEvidence = firstSeen
        var until = firstSeen + QUEUE_SONG_UNKNOWN_MS
            private set

        val expiresAt get() = lastEvidence + lengthMs * 2

        fun isConfirmedAt(at: Long) = confirmed && at < expiresAt

        fun isDueAt(at: Long) = at >= until

        fun waitForListen(at: Long, microphone: Boolean): Long {
            if (!isConfirmedAt(at)) return 0L
            val pause = when {
                !isDueAt(at) -> until - at
                microphone -> return 0L
                else -> EARS_CHECK_MS
            }
            return minOf(pause, expiresAt - at)
        }

        fun confirm(durationMs: Long?) {
            confirmed = true
            lengthMs = durationMs ?: QUEUE_SONG_UNKNOWN_MS
            if (durationMs != null) until = firstSeen + durationMs + QUEUE_SONG_MARGIN_MS
        }

        fun heardAt(at: Long) {
            lastEvidence = at
            until = at + lengthMs
        }

        fun forget() {
            confirmed = false
            until = 0L
        }

        fun isForStation(unnamed: Unnamed): Boolean = unnamed.isRadioSession(station, session)

        fun isForSong(unnamed: Unnamed): Boolean =
            isForStation(unnamed) && unnamed.queueSong?.let { RadioGarden.sameSong(song, it) } == true
    }

    private var queueCheck: QueueCheck? = null
    private var lastConfirmedQueue: QueueCheck? = null
    private var queueExpiry: Job? = null

    private fun confirmedRadioQueue(unnamed: Unnamed? = liveUnnamed): QueueCheck? =
        unnamed?.let { live -> lastConfirmedQueue?.takeIf { it.isForStation(live) && it.isConfirmedAt(now()) } }

    private fun updateRadioQueue(unnamed: Unnamed?): QueueCheck? {
        if (unnamed == null) {
            // The live broadcast can move on while this session is paused or stopped.
            forgetRadioPlayback()
            return null
        }
        val previous = queueCheck
        val last = lastConfirmedQueue
        if (last != null && !last.isForStation(unnamed)) clearRadioQueue(last)
        val song = unnamed.queueSong
        val check = when {
            song == null -> previous?.takeIf { it.isForStation(unnamed) }
            last?.isForSong(unnamed) == true -> last
            previous?.isForSong(unnamed) == true -> previous
            else -> QueueCheck(unnamed.name, unnamed.session, song, now())
        }
        queueCheck = check
        logRadioQueueUpdate(unnamed, check, kept = check === previous || check === last)
        return check?.takeIf { song != null && !it.confirmed }
    }

    private fun forgetRadioPlayback() {
        clearRadioQueue(lastConfirmedQueue ?: queueCheck)
        queueCheck = null
    }

    private fun clearRadioQueue(check: QueueCheck?) {
        queueExpiry?.cancel()
        queueExpiry = null
        lastConfirmedQueue = null
        check?.let { logRadioQueue("forgotten", it.station, it.song, it.session) }
    }

    private fun logRadioQueueUpdate(unnamed: Unnamed, check: QueueCheck?, kept: Boolean) {
        if (unnamed.app != RadioGarden.PACKAGE) return
        val action = when {
            check == null -> "none"
            kept -> "kept"
            else -> "new"
        }
        logRadioQueue(action, unnamed.name, unnamed.queueSong, unnamed.session)
    }

    private fun logRadioQueue(action: String, station: String?, song: MusicMetadata?, session: Int?) {
        if (Log.isLoggable(TIMING_LOG, Log.DEBUG)) {
            Log.d(TIMING_LOG, "radio queue check=$action station=$station session=$session title=${song?.title} artist=${song?.artist}")
        }
    }

    /** The catalog checks a fresh name once. Further evidence comes from the broadcast. */
    private suspend fun confirmQueueSong(check: QueueCheck) {
        combine(barInSight, snapshotFlow { followingRadioLyrics }) { bar, lyrics -> bar || lyrics }.first { it }
        val remaining = check.until - now()
        val found = withTimeoutOrNull(remaining) { matcher.findRadioSong(check.song) } ?: return
        check.confirm(found.lengthMs)
        lastConfirmedQueue = check
        logRadioQueue("confirmed", check.station, check.song, check.session)
        showUnnamed()
        refreshQueueExpiry(check)
        byEarAgain.value = Any()
    }

    /** Recheck on resume because the title ages during sleep and the dispatcher delay does not. */
    private fun refreshQueueExpiry(check: QueueCheck) {
        queueExpiry?.cancel()
        if (!check.isConfirmedAt(now())) {
            forgetQueueSong(check)
            return
        }
        viewModelScope.launchKept({ queueExpiry = it }) {
            while (check.isConfirmedAt(now())) delay(check.expiresAt - now())
            forgetQueueSong(check)
        }
    }

    private fun mayUseMicrophone() = uiState.phone != Phone.TV && mayCalibrate()

    /** Once the listen is due, check for newly granted access while the title is still valid. */
    private suspend fun waitForQueueCheck(check: QueueCheck) {
        while (true) {
            val pause = check.waitForListen(now(), mayUseMicrophone())
            if (pause == 0L) return
            delay(pause)
        }
    }

    private fun forgetQueueSong(check: QueueCheck) {
        check.forget()
        logRadioQueue("forgotten", check.station, check.song, check.session)
        showUnnamed()
    }

    /** Keeps the title when it was heard again. Other answers go through ordinary naming by ear. */
    private fun checkQueueHearing(check: QueueCheck, heard: Heard): Boolean {
        if (check !== confirmedRadioQueue()) return false
        val matched = heard is Heard.Song && RadioGarden.sameSong(check.song, heard.metadata)
        if (matched) {
            check.heardAt(now())
        } else {
            queueExpiry?.cancel()
            forgetQueueSong(check)
        }
        return matched
    }

    /**
     * Listens to what plays, again once the song heard should be over, as the next one has started by
     * then. Shared sound first; then the microphone, if it was allowed before and this isn't a TV, whatever
     * the phone plays through, as it can't tell a speaker or a car from headphones. Any failure is quiet:
     * what plays stays named as the app has it.
     */
    private suspend fun nameByEar() {
        byEar = currentCoroutineContext()[Job]
        while (true) {
            val waiting = confirmedRadioQueue()
            if (waiting != null) waitForQueueCheck(waiting) else untilByEar()
            // Listening along with the words, or hearing them sung, has the microphone and the phone's sound till it's done.
            listOfNotNull(alongJob, singingJob, voiceJob).forEach { it.join() }
            val ears = earsNow()
            val byMicrophone = mayUseMicrophone()
            val check = confirmedRadioQueue()
            if (check != null && (!byMicrophone || !check.isDueAt(now()))) continue
            // How the app said it played as the sound to hear started, which what it says after is checked against.
            val startedWith = uiState.playingUnnamed.followingForHearing
            // Stopped for one of those, it's tried again once that's done.
            val heard = coroutineScope {
                val listening = async { listener.listenToPlaying(byMicrophone) }
                byEarListen = listening
                listening.join()
                listening.takeUnless { it.isCancelled }?.await()
            } ?: continue
            if (check != null && checkQueueHearing(check, heard)) continue
            unheardByEar = heard !is Heard.Song
            val wait = showByEarResult(heard, startedWith) ?: return
            nextByEarAtMs = now() + wait
            unheardWith = ears.takeUnless { heard is Heard.Song }
        }
    }

    /** Shows the result and gives the wait before listening again. Null when playback moved while listening. */
    private fun showByEarResult(heard: Heard, startedWith: Following?): Long? {
        return when (heard) {
            is Heard.Song -> {
                quietByEar = 0
                // Shown till it should be over, which may be well before the next listen. Heard in what the app
                // played before it went on, it's never shown, and dropping it listens again at once, in place of this.
                if (!showHeardByEar(heard, startedWith)) return null
                byEarLook?.cancel()
                lyricsFinder?.let { finder -> viewModelScope.launchKept({ byEarLook = it }) { finder.firstLook(heard.metadata) } }
                untilOver(heard)
            }
            // Talk, ads or a pause: no song plays, so none is shown, and the next try waits longer each time.
            Heard.Nothing -> {
                uiState.playingUnnamed?.let { uiState = uiState.copy(playingUnnamed = it.copy(heard = null)) }
                BY_EAR_RETRY_MS shl minOf(quietByEar++, QUIET_PAUSE_DOUBLINGS)
            }
            else -> BY_EAR_RETRY_MS
        }
    }

    /**
     * Waits till the next listen by ear. After one that named no song, a new way to hear starts it at
     * once, the rests from the shortest: the microphone or the phone's own sound allowed, or no longer.
     */
    private suspend fun untilByEar() {
        while (now() < nextByEarAtMs) {
            val with = unheardWith ?: return delay(nextByEarAtMs - now())
            delay(minOf(EARS_CHECK_MS, nextByEarAtMs - now()))
            if (with != earsNow()) {
                quietByEar = 0
                nextByEarAtMs = now()
            }
        }
    }

    /** What naming by ear can hear with now: the microphone allowed, the phone's own sound shared. */
    private fun earsNow() = listOf(mayCalibrate(), mayCapture())

    /**
     * When the song [heard] should be over, on the [now] clock, from its length and where in it it was heard, going by
     * at the speed it was heard at; null when either isn't known.
     */
    private fun endOf(heard: Heard.Song): Long? {
        val length = heard.metadata.lengthMs ?: return null
        val at = heard.offsetMs ?: return null
        return endOf(heard, length, at)
    }

    /** When the song [heard], [length] long, should be over, heard [at] that far into it. */
    private fun endOf(heard: Heard.Song, length: Long, at: Long) = (heard.startedAtMs ?: now()) + ((length - at) / heard.speed).toLong()

    /**
     * How long until the next listen: a little after the song [heard] should be over, as the next one has
     * started by then, or a while when its length isn't known.
     */
    private fun untilOver(heard: Heard.Song): Long {
        val end = endOf(heard) ?: return BY_EAR_UNKNOWN_MS
        return (end - now() + BY_EAR_AFTER_MS).coerceIn(BY_EAR_SOONEST_MS, BY_EAR_LATEST_MS)
    }

    /**
     * Shows the song heard in what an app plays, as a pasted link would, when it's safe to. With none heard
     * when it can't be named by ear, what Crosstune once took it for, a song by its title alone, opens as
     * that did, card and player, as the user asks for it, never by itself. Else, the player on what the app
     * plays, as it names it.
     */
    fun openPlayingUnnamed() {
        val playing = uiState.playingUnnamed ?: return
        confirmedRadioQueue(playing.playing)?.song?.let { return showSong(it) }
        playing.heard?.let { heard ->
            if (opens(heard)) return showHeard(heard, lyrics = false, byPhone = true)
            // No longer where it could be, the bar no longer shows it either.
            if (!fits(heard)) forgetHeardByEar()
        }
        if (!playing.playing.reportsState) return peekPlayingUnnamed()
        val song = playing.playing.asSong?.takeIf { cantName(playing.playing) } ?: return peekPlayingUnnamed()
        if (uiState.result?.let { sameSong(it, song) } != true) showSong(song)
        if (uiState.player?.song != song) openPlayer(song, playing.playing.app, played = true)
    }

    /**
     * Whether what [unnamed] plays can't be named by ear now: it plays away from the phone, nothing can hear it, or the
     * last listen to it named no song. The phone's own sound is heard when it's shared, or, with the microphone
     * allowed, through Android's own, the way the visuals hear it, on a TV and through headphones too.
     */
    private fun cantName(unnamed: Unnamed): Boolean {
        val ears = mayCapture() || mayCalibrate()
        return unnamed.away || !ears || unheardByEar
    }

    /**
     * Whether a tap opens the song [heard], as opening the wrong song is worse than opening none: only
     * while it's known to be still on ([fits]), and not when only the microphone heard it, as that may be
     * another song in the room.
     */
    private fun opens(heard: Heard.Song) = !heard.inRoom && fits(heard)

    /**
     * Whether where the song [heard] should be now is inside it, which takes its length: from where in it it was
     * heard, on by as much as the app's clock went since, when it said where it was then and says now. When it said
     * neither, by the time gone by, as the bar's own timer goes ([endOf]). When it said only one, as while its follow
     * starts over, what plays can't be told from the song heard, so it isn't.
     */
    private fun fits(heard: Heard.Song): Boolean {
        val length = heard.metadata.lengthMs ?: return false
        val at = heard.offsetMs ?: return false
        val from = heardFrom?.takeIf { it.heard === heard }
        val clock = uiState.playingUnnamed.followingForHearing?.clock
        val appAt = from?.appAtMs
        if (from == null || appAt == null || clock == null) return appAt == null && clock == null && now() < endOf(heard, length, at)
        return (at + (clock.positionAt(now()) - appAt) / from.appSpeed * heard.speed).toLong() in 0 until length
    }

    /** The player on what an app plays with no song named, as the app names it, with its buttons, as a swipe up from the bar opens it. */
    fun peekPlayingUnnamed() {
        val unnamed = uiState.playingUnnamed?.playing ?: return
        openPlayer(unnamed.played, unnamed.app, played = true, unnamed = unnamed)
    }

    private fun showPlayingNow() {
        // A song another app paused isn't offered over what an app plays now, even with no song named, as a
        // station; over what says nothing a song could be read from, as a vlog, it is, as Crosstune always had it.
        val song = heardNow ?: followedNow?.first?.takeIf { uiState.playingUnnamed?.playing?.said != true }
        uiState = uiState.copy(playingNow = song?.let { NowPlaying(it, followedNow?.takeIf { (followed) -> followed == song }?.second) })
    }

    /**
     * Opens the player on the song playing now, which then stays in sight. Its card is shown under
     * the player, so its link, Copy and Share are a swipe down away.
     */
    fun openPlayingNow() {
        val now = uiState.playingNow ?: return
        val song = withCollectionTrackAlbum(now.song)
        if (uiState.result?.let { sameSong(it, song) } != true) showSong(song)
        if (uiState.player?.song != song) openPlayer(song, now.following?.appPackage, played = true)
    }

    /** The player on the song playing now, alone, as a swipe up from the bar opens it before it's let go. */
    fun peekPlayingNow() {
        val now = uiState.playingNow ?: return
        openPlayer(withCollectionTrackAlbum(now.song), now.following?.appPackage, played = true)
    }

    /** A player may name the track opened from its collection and leave the album out. */
    private fun withCollectionTrackAlbum(song: MusicMetadata): MusicMetadata {
        if (song.album != null) return song
        val id = RecordingKey.trackIdentity(song.url) ?: return song
        val album = uiState.result?.tracks?.firstNotNullOfOrNull { track ->
            track.album?.takeIf { RecordingKey.trackIdentity(track.url) == id }
        } ?: return song
        return song.copy(album = album)
    }

    /** Pauses the song playing now, or plays it again: as its button shows, playing when its app doesn't say where it is. */
    fun playingNowPlayPause() {
        val bar = onTheBar() ?: return
        playback?.playPause(bar.song, bar.following?.clock?.playing == false, bar.app, bar.session)
    }

    fun playingNowSkip() {
        val bar = onTheBar() ?: return
        playback?.skip(bar.song, true, bar.app, bar.session)
        movedOn(bar.app)
    }

    /**
     * What the bar's buttons are for: the song playing now, or what an app plays with no song named, as the app
     * names it, asked of that app by package, so they reach it whatever it calls it, even nothing.
     */
    private data class BarTarget(val song: MusicMetadata, val following: Following?, val app: String?, val session: Int?)

    private fun onTheBar(): BarTarget? =
        uiState.playingNow?.let { BarTarget(it.song, it.following, null, it.following?.session) }
            ?: uiState.playingUnnamed?.takeIf { it.canControl }?.let { BarTarget(it.playing.played, it.following, it.playing.app, it.playing.session) }

    /**
     * Shows [song] as a result, like a pasted link, by the Shazam page [appleMusicId] names when it's
     * known. The player's own song's card goes under it; any other song takes the player's place.
     */
    private fun showSong(song: MusicMetadata, appleMusicId: String? = null, checked: CheckedPlayerSong? = null) {
        val input = appleMusicId?.let { MusicLinks.parse("https://www.shazam.com/song/$it") } ?: MusicLinks.recognizedSong(song)
        uiState = uiState.copy(linkText = (input as? LinkInput.RecognizedSong)?.text ?: (input as LinkInput.Link).link.url)
        resolve(input, openWhenReady = false, underPlayer = uiState.player?.let { sameSong(it.song, song) } == true, checked = checked)
    }

    private fun showHeard(heard: Heard.Song, lyrics: Boolean, byPhone: Boolean, checked: CheckedPlayerSong? = null) {
        TimingLog.note("recognized ${heard.offsetMs} at ${heard.startedAtMs} match ${heard.matchId} received ${now()} speed ${heard.speed} length ${heard.metadata.lengthMs}")
        retryListens = false
        showSong(heard.metadata, heard.appleMusicId, checked)
        alongByPhone = byPhone
        // Once it's shown, as showing it forgets what was heard for whatever was shown before.
        heardSong = heard.metadata
        // Its words aren't found yet, so the gap for them moves it once they are.
        hear(heard.clock, heard.matchId)
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
        if (error == AppError.RECOGNITION_UNAVAILABLE) effectChannel.trySend(Effect.RecognizeElsewhere)
    }

    /**
     * Looks [input] up, and opens it when [openWhenReady]. A video [copied] a moment ago shows
     * nothing until YouTube Music says it's music, and then only if nothing else has been shown since.
     * A link that came [here], as links do, plays as soon as it's found when set to.
     */
    private fun resolve(
        input: LinkInput, openWhenReady: Boolean, destination: Destination? = null, copied: Boolean = false,
        here: Boolean = false, underPlayer: Boolean = false, keepsWords: Boolean = false, checked: CheckedPlayerSong? = null
    ) {
        cancelContentWork(closesPlayer = !underPlayer, keepsWords = keepsWords)
        lastRequest = Request(input, openWhenReady, destination, here)
        pendingOpen = openWhenReady
        pendingPlay = here && uiState.playsRightAway
        // A newer request always wins; the older call is cancelled rather than left to overwrite it.
        fun showLoading() {
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
        }
        if (!copied) showLoading()
        // A link already in Recent uses what was saved, after checking for a missing album on older entries.
        // A short link's target isn't known until it's followed, so it's always looked up.
        val link = (input as? LinkInput.Link)?.link
        val saved = link?.let(::savedAs)
        viewModelScope.launchKept({ job = it }) {
            // A video from another app that isn't music, like a tutorial, opens as it would without
            // Crosstune; one copied, or that YouTube Music can't tell about, e.g. offline, is let be.
            val video = link?.takeIf { (copied || here || openWhenReady && destination == null) && it.mayNotBeMusic }
            // Looked up while asking whether it's music, so a music video costs one wait, not two.
            val lookup = async {
                val entry = saved?.let { refreshSavedAlbum(it) }
                val resolution = entry?.let { Resolution.Resolved(it.link, it.metadata) } ?: withCover(resolver.resolve(input))
                resolution to entry
            }
            if (!lookupGoesOn(video, copied, lookup, ::showLoading)) return@launchKept
            val (resolution, entry) = lookup.await()
            lookedUp(resolution, entry, checked)
        }
    }

    /**
     * Whether [lookup] goes on once YouTube Music says if [video] is music: one [copied] is only filled
     * in if it is, and one that isn't opens as it is. Otherwise [lookup] is cancelled.
     */
    private suspend fun lookupGoesOn(video: MusicLink?, copied: Boolean, lookup: Job, showLoading: () -> Unit): Boolean {
        val music = video?.let { matcher.isMusicVideo(it.id) }
        if (copied) {
            if (video == null || music != true || !readyForCopy) {
                lookup.cancel()
                return false
            }
            uiState = uiState.copy(linkText = video.frontendUrl ?: video.url, handlingIncomingLink = false, handingOff = false)
            showLoading()
        } else if (video != null && music == false) {
            lookup.cancel()
            openAsIs(video)
            return false
        }
        return true
    }

    /** [wanted] as Recent has it, as parsed, since Recent doesn't save which frontend's site it came from. */
    private fun savedAs(wanted: MusicLink): HistoryEntry? = uiState.history.firstOrNull { it.link.url == wanted.url }?.copy(link = wanted)
        // An album or playlist kept without its songs, e.g. by a version that couldn't read them, is looked up again for them.
        ?.takeUnless { (it.link.type == ItemType.ALBUM || it.link.type == ItemType.PLAYLIST) && it.metadata.tracks.isEmpty() }

    /** Checks the catalogue once after an upgrade. If it can't answer, keep the old entry for another try. */
    private suspend fun refreshSavedAlbum(entry: HistoryEntry): HistoryEntry {
        if (entry.albumChecked || uiState.offline) return entry
        val refreshed = resolver.resolve(LinkInput.Link(entry.link)) as? Resolution.Resolved ?: return entry
        return entry.afterAlbumLookup(refreshed.metadata)
    }

    /** What a link's lookup found, or why it failed; [saved] is how Recent had it. */
    private suspend fun lookedUp(resolution: Resolution, saved: HistoryEntry?, checked: CheckedPlayerSong?) {
        when (resolution) {
            is Resolution.Failed -> {
                // Offline, the link waits for the internet, as the field's icon says, and is looked up once it's back.
                linkWaits = resolution.error == AppError.NETWORK && uiState.offline
                uiState = uiState.copy(
                    isLoading = false,
                    error = resolution.error.takeUnless { linkWaits },
                    canRetry = resolution.error.canRetry,
                    link = resolution.link,
                    handingOff = false
                )
            }
            is Resolution.Resolved -> onResolved(resolution, saved, checked)
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

    /** [saved] is how Recent has it, including the links already found for it. */
    private suspend fun onResolved(resolution: Resolution.Resolved, saved: HistoryEntry?, checked: CheckedPlayerSong?) {
        // Keep saved retries. A search saved after exact matching failed needs a fresh lookup.
        val prepared = saved?.destinationLinks.orEmpty().filterValues { !it.lasting || it.exact || !it.matchingEnabled }
        val namedLink = checked?.takeIf { uiState.exactMatch }?.let {
            it.destination to preparedLink(it.destination, resolution.metadata, it.lookup)
        }
        val links = if (namedLink == null) prepared else prepared + (namedLink.first.key to namedLink.second)
        val entry = HistoryEntry(resolution.link, resolution.metadata, links, albumChecked = saved?.albumChecked ?: true)
        val history = historyStore.add(entry)
        // The box shows the clean link Crosstune works with, e.g. without "?si=" or a short link's redirect.
        uiState = uiState.copy(
            isLoading = false,
            result = resolution.metadata,
            songsLike = false,
            link = resolution.link,
            linkText = fieldText(resolution.link),
            destinationUrls = savedDestinations(entry) + listOfNotNull(namedLink),
            history = history
        )
        lookAhead()
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
        viewModelScope.launchKept({ coverJob = it }) {
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
            val saved = entry.destinationLinks[destination.key] ?: return@mapNotNull null
            if (saved.canUseFor(uiState.exactMatch)) destination to saved else null
        }.toMap()

    /** A destination or exact-match preference change prepares the current result again. */
    private fun prepareResultDestination() {
        cancelTrackWork()
        if (uiState.result == null || uiState.showDestinationPicker) return
        job?.cancel()
        uiState = uiState.copy(isMatching = false)
        viewModelScope.launchKept({ job = it }) { prepareResult() }
    }

    private suspend fun prepareResult() {
        // Where a link goes on its own may be a build only found by the links it opens, which Android says once asked.
        if (pendingOpen) snapshotFlow { uiState.systemStateKnown }.first { it }
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
        // Incoming links without an app to go to must not search one before asking.
        val ask = uiState.selectedDestination == null && (!uiState.hasDefault || ownService != null)
        if (pendingOpen && ask) {
            pendingOpen = false
            uiState = uiState.copy(showDestinationPicker = true, pickerHides = ownService, handingOff = false)
            return
        }
        // Opened in Crosstune, it plays from here, when its app can be asked to; otherwise the card has the choices.
        if (pendingPlay) {
            pendingPlay = false
            // Whether its app is on the phone is only known once Android says, which a song from Recent can beat.
            snapshotFlow { uiState.systemStateKnown }.first { it }
            if (uiState.canPlayResult) return playResult()
        }
        val destination = uiState.resultDestination
        val preparedUrl = prepareDestination(destination)
        if (!pendingOpen) return
        pendingOpen = false
        val target = uiState.resultDestination
        open(target, finishAfterOpen = true, preparedUrl = preparedUrl.takeIf { target == destination })
    }

    private var trackJob: Job? = null

    /**
     * Plays the result's songs in YouTube Music or YouTube, as one queue of the first [MAX_QUEUE],
     * which is all YouTube takes; [playQueue] plays a longer list on from there. In an app that takes
     * no list, they play song by song. [shuffled], the whole list is shuffled first, as the app would
     * only shuffle the part it was given.
     */
    fun playAll(shuffled: Boolean = false) {
        val destination = uiState.resultDestination
        val service = destination.matchService ?: return
        val all = uiState.result?.tracks.orEmpty().let { if (shuffled) it.shuffled() else it }
        val name = uiState.result?.title
        if (service.playsSongBySong) return playSongBySong(all, service, destination, name)
        val tracks = all.take(MAX_QUEUE)
        cancelTrackWork()
        viewModelScope.launchKept({ trackJob = it }) {
            uiState = uiState.copy(queueProgress = 0 to tracks.size)
            try {
                val queue = matcher.youtubeQueue(service, tracks) { looked -> uiState = uiState.copy(queueProgress = looked to tracks.size) }
                if (queue == null) return@launchKept showError(AppError.NOT_FOUND)
                // In Crosstune's player, as one song plays, from the list's first song found.
                val app = destination.packageName
                val first = queue.songs.first()
                openPlayer(first, app, queue.url)
                // Before the app's asked, so what it played until then isn't taken for the list's start.
                playOn(all, service, destination, queue, name)
                val asked = app != null && playback?.playList(app, queue.url, mustStart = true) == true
                if (!playThere(first, app, queue.url, asked, exact = true, list = true)) effectChannel.send(opening(queue.url, app, finishAfterOpen = false, song = first))
            } finally {
                if (isActive) uiState = uiState.copy(queueProgress = null)
            }
        }
    }

    /**
     * Plays [tracks] in the app [destination] is, which takes no list, one after another: the first
     * asked for here, with the player on it, and [playQueue] asking for each next one as the one before
     * ends. With no app to ask, the first opens there, as it would on its own. An app that plays none
     * asked for is opened on each song's own link instead, coming to the front for it.
     */
    private fun playSongBySong(tracks: List<MusicMetadata>, service: MusicService, destination: Destination, name: String?) {
        val app = destination.packageName ?: return
        val first = tracks.first()
        cancelTrackWork()
        viewModelScope.launchKept({ trackJob = it }) {
            if (!service.playsAskedSongs) {
                uiState = uiState.copy(isMatching = true)
                val link = try {
                    matcher.find(service, first)
                } finally {
                    if (isActive) uiState = uiState.copy(isMatching = false)
                }
                link ?: return@launchKept openTrack(first)
                openPlayer(first, app, link)
                // Before the app's opened, so what it played until then isn't taken for the list's start.
                playQueue?.play(tracks, service, app, 1, listOf(first), name)
                if (!playThere(first, app, link, asked = false, exact = true, list = true)) {
                    playQueue?.stop()
                    effectChannel.send(opening(link, app, finishAfterOpen = false, song = first))
                }
                return@launchKept
            }
            openPlayer(first, app)
            if (playback?.playSong(first, app, mustStart = true) == true) {
                playQueue?.play(tracks, service, app, 1, listOf(first), name)
            } else {
                closePlayer()
                openTrack(first)
            }
        }
    }

    /** Plays the rest of [tracks], the list called [name], on in the app [destination] is, after [queue], their first [MAX_QUEUE]. */
    private suspend fun playOn(tracks: List<MusicMetadata>, service: MusicService, destination: Destination, queue: YouTubeQueue, name: String?) {
        playQueue?.play(tracks, service, destination.packageName ?: return, MAX_QUEUE, queue.songs, name)
    }

    /** Opens one of a playlist's songs where the result goes, matched like a song of its own. */
    fun openTrack(track: MusicMetadata) {
        val destination = uiState.resultDestination
        cancelTrackWork()
        viewModelScope.launchKept({ trackJob = it }) {
            // A song from a playlist on the same service is opened by its own link.
            track.url?.takeIf { destination.matchService != null && MusicLinks.serviceFor(it) == destination.matchService }?.let { url ->
                effectChannel.send(opening(destination.adapt(url).forSharing(), destination.packageName, finishAfterOpen = false, song = track))
                return@launchKept
            }
            // YouTube and YouTube Music play the same videos, so one opens as itself in the other.
            track.url?.let { MusicLinks.linkFor(it) }?.youtubeVideoOn(destination.matchService)?.let { url ->
                effectChannel.send(opening(destination.adapt(url).forSharing(), destination.packageName, finishAfterOpen = false, song = track))
                return@launchKept
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
                return@launchKept openMissing(track, track.url?.let(MusicLinks::linkFor), destination, url, finishAfterOpen = false)
            }
            effectChannel.send(opening(url.forSharing(), destination.packageName, finishAfterOpen = false, song = track))
        }
    }

    /** Opens the result from a button tap; the picker passes the destination chosen for this link. */
    fun openResult(destination: Destination? = null, finishAfterOpen: Boolean = false) {
        qobuzNumbers?.off()
        val chosen = destination ?: uiState.resultDestination
        adoptDefault(chosen)
        // Opened, as asked, rather than played right away.
        pendingOpen = false
        pendingPlay = false
        uiState = uiState.copy(
            showDestinationPicker = false, isMatching = false,
            selectedDestination = destination ?: uiState.selectedDestination
        )
        job?.cancel()
        viewModelScope.launchKept({ job = it }) { open(chosen, finishAfterOpen, tapped = true) }
    }

    /**
     * Has the song's app play it in the background, in Crosstune's player; opened on the song and left again
     * when it can't be asked, as Spotify. With only a search for it, an app that can be asked is opened there,
     * and Spotify, which would play nothing from one, isn't.
     */
    fun playResult() {
        qobuzNumbers?.off()
        val destination = uiState.resultDestination as? Destination.Service ?: return
        adoptDefault(destination)
        val song = uiState.result ?: return
        // On a TV the song's words cover the app it plays in, which Android has to let them first.
        if (uiState.phone == Phone.TV && !uiState.canFloatOverApps) {
            // Allowed now, which only the activity looks up again when it resumes, so play goes on knowing it.
            if (!fixInOneTap(OneTapFix.FLOAT) { setCanFloatOverApps(true); playResult() }) fixSteps(listOf(Need.Float))
            return
        }
        job?.cancel()
        viewModelScope.launchKept({ job = it }) {
            uiState = uiState.copy(playingResult = true)
            try {
                val link = prepareDestination(destination) ?: return@launchKept
                playPreparedSong(song, destination, link)
            } finally {
                uiState = uiState.copy(playingResult = false)
            }
        }
    }

    private suspend fun playPreparedSong(song: MusicMetadata, destination: Destination.Service, link: String) {
        val source = playback
        val app = destination.packageName
        // Already in that app, playing or paused, it carries on from where it is.
        val there = source?.takeIf { it.hasAccess() }?.let { followResultSession(it, song, app).first() }
        if (there != null) {
            openPlayer(song, there.appPackage, link, session = there.session)
            if (!there.clock.playing) source.playPause(song, true, there.appPackage, there.session)
            return
        }
        val exact = uiState.destinationUrls[destination]?.exact != false
        val service = destination.service
        // Spotify, which plays none asked for, has only a search for it, which it would play nothing from: the card says Search.
        if (!service.playsAskedSongs && !exact) return
        openPlayer(song, app, link)
        // Other apps can be asked by name. YouTube Music needs its own exact link, and Spotify is opened on it.
        val asked = service.playsAskedSongs && (exact || service != MusicService.YOUTUBE_MUSIC) &&
            source?.playSong(song, app, link, mustStart = true) == true
        if (!playThere(song, app, link, asked, exact)) open(destination, finishAfterOpen = false, tapped = true, preparedUrl = link)
    }

    /**
     * [song] playing in [app], with the player on it, once [app] was [asked] to play it in the background: seen to play it
     * there, or with no way to see, that's that, as an app may say yes and not play it. An app that couldn't be asked is
     * opened on its [exact] [link], and, when Crosstune can see it play there, left again once it does, as practice does,
     * unless it wouldn't play in the background, where it would stop. Seen not to play it there, it's left in front, with
     * the player closed, and the rest of a [list] isn't played on. False, with the player closed, when it could be neither.
     */
    private suspend fun playThere(song: MusicMetadata, app: String?, link: String, asked: Boolean, exact: Boolean, list: Boolean = false): Boolean {
        leftJob?.cancel()
        adJob?.cancel()
        val source = playback?.takeIf { it.hasAccess() }
        if (asked && (source == null || playsAsAsked(source, song, app, link, exact))) return true
        val back = source?.takeIf { exact && app != null && openWatched(it, song, app, link) } ?: return false.also { closePlayer() }
        val (seen, busy) = seenPlayingOrBusy(back, song, app, link)
        if (!busy) return true.also { leftOrNot(back, seen, song, list) }
        // Still on something else, as the ads YouTube without Premium plays first, it's left there, or covered, until the
        // song's on: left during an ad, it might stop, and that's no song to see stop and bring it back for.
        viewModelScope.launchKept({ adJob = it }) { leftOrNot(back, seenPlaying(back, song, app, link, mustPlay = true, withinMs = ADS_MS), song, list) }
        return true
    }

    /** Waiting for the song an app plays ads before, to come back to it then. */
    private var adJob: Job? = null

    /**
     * Left once it plays [song], not once it shows it: YouTube Music, left before it starts, never does. Never [seen]
     * playing, as Apple Music signed out, it stays in front, where it says why, and the player and a [list] to play on go.
     */
    private fun leftOrNot(back: PlaybackSource, seen: Seen?, song: MusicMetadata, list: Boolean) {
        if (seen == null) {
            // Out from under the cover, which would hide why.
            uncover()
            closePlayer()
            if (list) playQueue?.stop()
            return
        }
        back.comesBack()
        watchLeft(song)
    }

    /** Whether [app], asked for [song], was seen to play it. Someone else's song it stopped when there's no [exact] link to take over. */
    private suspend fun playsAsAsked(source: PlaybackSource, song: MusicMetadata, app: String?, link: String, exact: Boolean): Boolean {
        // Played in that app, not another that has it too.
        val seen = seenPlaying(source, song, app, link, mustPlay = false) ?: return false
        val cover = coverIn(seen, song, app) ?: return true
        if (!exact) source.playPause(cover, false)
        return false
    }

    /** [app] opened on [song]'s [link], watched from before that if Qobuz's, as it can stay on a loading page there. */
    private fun openWatched(source: PlaybackSource, song: MusicMetadata, app: String, link: String): Boolean {
        val pending = if (MusicService.ofOwnApp(app) == MusicService.QOBUZ) qobuzNumbers?.prepare(song, app, link) else null
        return source.openSong(app, link).also { pending?.started(it) }
    }

    /**
     * What [app], asked by name, was [seen] to play when it's someone else's song. It plays what comes first in its search,
     * which can be a cover: Qobuz played tribute albums for Despacito and Gangnam Style. It may also play on from what it
     * was left on as it wakes. An app's other name for the artist only has it opened on the song's link, which plays the same song.
     */
    private fun coverIn(seen: Seen, song: MusicMetadata, app: String?): MusicMetadata? {
        // YouTube Music is asked by its own link, not by name, and its artists are often the channel's.
        if (seen.following != null || MusicService.ofPackage(app) == MusicService.YOUTUBE_MUSIC) return null
        return seen.playing?.metadata?.takeUnless { SongNames.sameArtist(it.artist, song.artist) }
    }

    /**
     * What [app], opened on [song], is seen to do with it, see [seenPlaying], and, not seen to play it, whether it played
     * anything else meanwhile. Playing an ad first, YouTube without Premium names it the ad's way, or nothing, and gets
     * to the song once it's over, even with nothing to say for a moment in between. Paused, or still on the song it was
     * on before, it isn't getting anywhere.
     */
    private suspend fun seenPlayingOrBusy(source: PlaybackSource, song: MusicMetadata, app: String?, link: String): Pair<Seen?, Boolean> = coroutineScope {
        val over = uiState.player?.over
        val anything = async {
            app != null && withTimeoutOrNull(LOAD_SONG_MS) {
                combine(source.plays(app), source.unnamedUpdates()) { plays, unnamed ->
                    plays?.takeIf { it.playing }?.song?.let { over == null || !sameSong(it, over) } == true ||
                        unnamed != null && sameApp(app, unnamed.app, interception::isInstalled)
                }.first { it }
            } != null
        }
        val seen = seenPlaying(source, song, app, link, mustPlay = true)
        (seen to (seen == null && anything.await())).also { anything.cancel() }
    }

    /** What an app was seen doing with a song it was asked for: playing it as that song, or something it calls otherwise. */
    private data class Seen(val following: Following?, val playing: Heard.Song?)

    /**
     * What [app] is seen, [withinMs], to do: to have [song], [mustPlay] it or not, or, whatever the app names it,
     * to have gone on to play something other than what it played when asked. Null if neither.
     */
    private suspend fun seenPlaying(source: PlaybackSource, song: MusicMetadata, app: String?, link: String, mustPlay: Boolean, withinMs: Long = LOAD_SONG_MS): Seen? {
        val over = uiState.player?.over
        val (following, playing) = withTimeoutOrNull(withinMs) {
            combine(source.follow(song), source.nowPlayingUpdates(), ::Pair).first { (following, playing) ->
                following != null && (!mustPlay || following.clock.playing) && sameApp(app, following.appPackage, interception::isInstalled) ||
                    following == null && playing != null && startedAsked(playing, app, over, link)
            }
        } ?: return null
        // Seen to go on to it from another song, that's what the app calls the song at that link. Not from nothing, as an
        // app may show what it played last a moment first.
        val wentOn = playing?.takeIf { following == null && over != null && !sameSong(it.metadata, over) }
        if (app != null && wentOn != null) playedAs[app to link] = wentOn.metadata
        return Seen(following, playing)
    }

    /**
     * What each app was seen to go on to play for a link, by the name it gives it. Asked for that link again while still
     * on it, an app may do nothing, as YouTube Music does, so there's no change to see.
     */
    private val playedAs = mutableMapOf<Pair<String, String>, MusicMetadata>()

    private var leftJob: Job? = null

    /**
     * An app that stops, or lets go of the song, as soon as Crosstune comes back over it only plays in front, as YouTube and YouTube Music without Premium,
     * which Crosstune can't learn by asking it from behind when it isn't running: asked to play on, it's brought back
     * there, covered. Paused from Crosstune meanwhile, it's left paused.
     */
    private fun watchLeft(song: MusicMetadata) {
        leftJob?.cancel()
        val source = playback ?: return
        if (uiState.coveringApp) return
        viewModelScope.launchKept({ leftJob = it }) {
            // Stopped, or let go of altogether, as YouTube does, and not waiting under an ad, which YouTube Music plays on as if it were the song.
            val stopped = withTimeoutOrNull(START_MS) { source.follow(song).dropWhile { it == null }.first { it == null || !it.clock.playing && !it.ad }; true }
            if (stopped == true) source.playPause(song, true)
        }
    }

    private var playerJob: Job? = null

    /** Opens the player on [song], asked of [app] at [link], and follows it there as it plays; [unnamed] when it names no song. */
    private fun openPlayer(song: MusicMetadata, app: String?, link: String? = null, played: Boolean = false, unnamed: Unnamed? = null, session: Int? = unnamed?.session) {
        // The app may be on another song as it's asked, which isn't the one it goes on to play.
        val over = playback?.takeIf { !played && it.hasAccess() }?.nowPlaying()?.takeIf { sameApp(app, it.app, interception::isInstalled) }?.metadata
        val stationApp = unnamed?.takeUnless { it.reportsState }?.let { it.label ?: it.app }
        uiState = uiState.copy(
            player = PlayerState(song, app, link, played = played, over = over, songless = unnamed != null, stationAppLabel = stationApp, session = session),
            canFollowApps = playback?.hasAccess() == true
        )
        followPlayer()
        peekWords()
    }

    private var peekJob: Job? = null

    /**
     * The player's song's words, for the line being sung under its buttons: as they were shown, or
     * looked up as the song's card looks them up ahead, which opening them then shares.
     */
    private fun peekWords() {
        peekJob?.cancel()
        // What names no song has no words to look up.
        val song = uiState.player?.takeIf { !it.songless }?.song ?: return
        val asked = asPlayed(song)
        val key = RecordingKey.of(asked)
        kept[key]?.takeIf { it.madeWith == madeWith(asked) }?.let { words ->
            if (words.canPreview == true) return showPeek(song, words.lines, words.lyrics, words.timedByEar)
            if (words.canPreview == false) return
        }
        val finder = lyricsFinder?.takeIf { lookAhead && asked.type == ItemType.TRACK && asked.artist.isNotBlank() } ?: return
        val look = lookingAhead[key] ?: lookUpAhead(finder, key, asked)
        viewModelScope.launchKept({ peekJob = it }) {
            val result = look.await()
            val words = result.lyrics as? LyricsFinder.Lyrics.Found ?: return@launchKept
            if (!result.fallback && !words.markedInstrumental) showPeek(song, words.lines, words.words, byEar = false)
        }
    }

    /** [lines] under [song]'s player, while it shows it, timed [byEar] or not; words that aren't timed show their first line. */
    private fun showPeek(song: MusicMetadata, lines: List<LyricLine>, words: String, byEar: Boolean) {
        // The cover can arrive while these words load; it does not change which song they belong to.
        val player = uiState.player?.takeIf { it.song.copy(artworkUrl = null) == song.copy(artworkUrl = null) } ?: return
        val shown = lines.ifEmpty { words.lines().filter { it.isNotBlank() }.take(1).map { LyricLine(0, it) } }
        uiState = uiState.copy(player = player.copy(lines = shown, linesByEar = byEar))
    }

    /**
     * Follows the player's song in its app. Once it played, the next song the app plays takes over,
     * as the words do; not in practice, which moves the app from song to song.
     */
    private fun followPlayer() {
        playerJob?.cancel()
        playerJob = null
        val opened = uiState.player ?: return
        val source = playback?.takeIf { it.hasAccess() } ?: return
        if (opened.stationOnly) {
            viewModelScope.launchKept({ playerJob = it }) { followStationDisplay(source) }
            return
        }
        val song = opened.song
        viewModelScope.launchKept({ playerJob = it }) {
            combine(source.follow(song, opened.followApp, opened.session), source.nowPlayingUpdates(), source.unnamedUpdates(), ::Triple).collect { (following, playing, unnamed) ->
                val player = uiState.player ?: return@collect
                val played = player.played || following?.clock?.playing == true
                // Paused, the app still has the song, maybe by another name, e.g. in its own language; only once it's on another is it the next.
                // Not yet played, the asked app going on to a song is the one asked for, whatever its name there, e.g.
                // in another script or with the artist's name written otherwise, which the app's session can't be matched by.
                val next = playing?.takeIf { (played || startedAsked(it, player.app, player.over, player.link)) && following == null && uiState.practice == null && !sameSong(it.metadata, song) }
                // In the app that plays it, which may not be the one the song before played in.
                if (next != null) return@collect openPlayer(next.metadata, next.app ?: player.following?.appPackage ?: player.app, played = true)
                if (tookUpUnnamed(unnamed, playing, following, played, song)) return@collect
                uiState = uiState.copy(player = player.withCurrentCover(playing, unnamed, following).copy(following = following, played = played))
            }
        }
    }

    /** A stateless station has no transport to follow. Keep its display only while it is current. */
    private suspend fun followStationDisplay(source: PlaybackSource) {
        source.unnamedUpdates().collect { station ->
            val player = uiState.player?.takeIf { it.stationOnly } ?: return@collect
            if (station == null || station.app != player.app || station.reportsState) {
                closePlayer()
            } else {
                uiState = uiState.copy(player = player.copy(song = station.played, stationAppLabel = station.label ?: station.app, session = station.session))
            }
        }
    }

    /** A bitmap cover can be saved after the song has already reached the player. */
    private fun PlayerState.withCurrentCover(playing: Heard.Song?, unnamed: Unnamed?, following: Following?): PlayerState {
        val current = if (songless) unnamed?.let { it.played to it.app } else playing?.let { it.metadata to it.app }
        val (metadata, packageName) = current ?: return this
        val matches = if (songless) metadata.copy(artworkUrl = song.artworkUrl) == song else sameSong(metadata, song)
        val cover = metadata.artworkUrl?.takeIf {
            matches && sameApp(following?.appPackage ?: app, packageName, interception::isInstalled)
        } ?: return this
        return copy(song = song.copy(artworkUrl = cover))
    }

    /**
     * Opens the player on [unnamed], what an app plays with no song named, e.g. the next station or a file
     * with no tags, when the app went on to it from [song] once it [played]. What reports no state has
     * no transport to follow, even if it has the same title or another session can still be [following].
     * Only in the player's own app, and only when no named song is [playing].
     */
    private fun tookUpUnnamed(unnamed: Unnamed?, playing: Heard.Song?, following: Following?, played: Boolean, song: MusicMetadata): Boolean {
        val app = uiState.player?.let { it.following?.appPackage ?: it.app }
        val next = unnamed?.takeIf { playing == null && played && uiState.practice == null && it.app == app } ?: return false
        val sameSong = next.session == uiState.player?.session && next.played.copy(artworkUrl = song.artworkUrl) == song
        if (next.reportsState && (following != null || sameSong)) return false
        openPlayer(next.played, next.app, played = true, unnamed = next)
        return true
    }

    /**
     * Whether [playing] is what the [app] asked for a song has gone on to play, rather than what it played [over] before; or,
     * still on that, what it was seen to play for the same [link] before.
     */
    private fun startedAsked(playing: Heard.Song, app: String?, over: MusicMetadata?, link: String?): Boolean =
        // An ad under the song's name is no song started: free YouTube Music plays them so.
        app != null && !playing.ad && sameApp(app, playing.app, interception::isInstalled) &&
            (over == null || !sameSong(playing.metadata, over) || link?.let { playedAs[app to it] }?.let { sameSong(playing.metadata, it) } == true)

    fun closePlayer() {
        // Nor brought back to it, or Crosstune to the app, once an ad's over.
        leftJob?.cancel()
        adJob?.cancel()
        playerJob?.cancel()
        playerJob = null
        peekJob?.cancel()
        uiState = uiState.copy(player = null)
    }

    /** Brings the app the player's song plays in forward as it is, which opening the song there would restart. */
    fun openPlayerApp() {
        val player = uiState.player ?: return
        val app = player.following?.appPackage ?: player.app ?: return
        qobuzNumbers?.off()
        effectChannel.trySend(Effect.Show(app, finishAfterOpen = false, saysWhenItFails = false))
    }

    /**
     * The player's song's card under it, for its menu: looked up once that's opened on a song the
     * card isn't of, e.g. the next one its app went on to, rather than each song as it plays.
     */
    fun playerMenu() {
        val song = uiState.player?.takeIf { !it.songless }?.song ?: return
        if (uiState.result?.let { sameSong(it, song) } != true) showSong(song)
    }

    /** Closes the player on the song's card, with the apps to open it in over it. */
    fun playerOpenWith() {
        closePlayer()
        uiState = uiState.copy(showDestinationPicker = true, pickerHides = null)
    }

    /** The app the player's buttons ask, by package: for what names no song, its own, whatever it calls it, even nothing; else whichever plays the song. */
    private val PlayerState.askedOf: String? get() = app.takeIf { songless }

    /** The app to follow while its selected session is still available. */
    private val PlayerState.followApp: String? get() = askedOf ?: app.takeIf { session != null }

    /**
     * Plays or pauses the player's song; asked of its app again when it isn't playing it. What names no song has no
     * name to be asked for by, so once its app is on to another, the app plays whatever it has.
     */
    fun playerPlayPause() {
        val player = uiState.player ?: return
        if (player.stationOnly) return
        val following = player.following ?: return run {
            if (player.songless) {
                player.app?.let { playback?.playApp(it, player.session) }
                return
            }
            // Still being asked for, a second ask would start it over.
            if (!uiState.playingResult) viewModelScope.launch {
                // On a TV, YouTube lets go of a song once Crosstune is in front, so it's opened on it again, covered.
                if (playback?.playSong(player.song, player.app, player.link) == false && uiState.phone == Phone.TV) player.app?.let { app -> player.link?.let { playback.openSong(app, it) } }
            }
        }
        playback?.playPause(player.song, !following.clock.playing, player.askedOf, following.session)
    }

    /** Has the app go on to its [next] song, or back to the one before, which the player then shows. */
    fun playerSkip(next: Boolean) {
        val player = uiState.player ?: return
        val following = player.following ?: return
        if (!(if (next) following.canSkipNext else following.canSkipPrevious)) return
        uiState = uiState.copy(player = player.copy(played = true))
        playback?.skip(player.song, next, player.askedOf, following.session)
        movedOn(player.app)
    }

    /** Has the app shuffle, or play in order again. */
    fun playerShuffle() {
        val player = uiState.player ?: return
        val shuffles = player.following?.shuffle ?: return
        playback?.shuffle(player.song, !shuffles, player.askedOf, player.following.session)
    }

    /** Has the app repeat the next way, as music apps go round them: not at all, all of its list, then the song. */
    fun playerRepeat() {
        val player = uiState.player ?: return
        val repeats = player.following?.repeat ?: return
        playback?.repeat(player.song, Repeat.entries[(repeats.ordinal + 1) % Repeat.entries.size], player.askedOf, player.following.session)
    }

    /** Presses the app's own like for the player's song. */
    fun playerLike() {
        val player = uiState.player ?: return
        if (player.following?.like == null) return
        playback?.like(player.song, player.askedOf, player.following.session)
    }

    /** Plays the song up next from the session that supplied the row. */
    fun playerPlayQueued(id: Long, session: Int) {
        val player = uiState.player?.takeUnless { it.stationOnly } ?: return
        uiState = uiState.copy(player = player.copy(played = true))
        playback?.playQueued(player.song, id, player.askedOf, session)
        movedOn(player.app)
    }

    /** Plays the list on from the song up next at [index], one it still has to ask the app for. */
    fun playerPlayLater(index: Int) {
        val player = uiState.player?.takeUnless { it.stationOnly } ?: return
        val apps = player.following?.upNext.orEmpty().count { it.id != null }
        uiState = uiState.copy(player = player.copy(played = true))
        viewModelScope.launch { playQueue?.playFrom(index - apps) }
        movedOn(player.app)
    }

    /**
     * Moves the song up next at [from] to [to]: the app's songs among themselves, by the order
     * Crosstune keeps over the app's, and a list's still to ask for among theirs.
     */
    fun playerMoveQueued(from: Int, to: Int) {
        val following = uiState.player?.following ?: return
        val upNext = following.upNext
        val moved = upNext.getOrNull(from) ?: return
        val onto = upNext.getOrNull(to) ?: return
        val apps = upNext.count { it.id != null }
        when {
            moved.id != null && onto.id != null -> queueOrder?.move(following, from, to)
            moved.id == null && onto.id == null -> viewModelScope.launch { playQueue?.move(from - apps, to - apps) }
        }
    }

    /** The player's song's card, under it, with its links. */
    fun playerCard() {
        val song = uiState.player?.takeIf { !it.songless }?.song ?: return
        closePlayer()
        if (uiState.result?.let { sameSong(it, song) } != true) showSong(song)
    }

    /** Pauses the music in [minutes], or once the player's song ends, without them. */
    fun playerSleep(minutes: Int?) {
        val timer = sleepTimer ?: return
        if (minutes != null) return timer.after(minutes)
        uiState.player?.let { timer.afterSong(it.song, it.askedOf, it.following?.session ?: it.session) }
    }

    fun playerSleepOff() {
        sleepTimer?.off()
    }

    fun playerSeek(positionMs: Long) {
        val player = uiState.player ?: return
        if (player.following?.canSeek != true) return
        playback?.seekTo(player.song, positionMs, player.askedOf, player.following.session)
        movedOn(player.app)
    }

    /** The player's song's words, over it. */
    fun playerLyrics() {
        val player = uiState.player?.takeIf { !it.songless } ?: return
        showLyricsFor(player.song, playing = player.played)
    }

    /** Opens [url] in [packageName], with [song] named for an app that takes a search, not its page. */
    private fun opening(url: String, packageName: String?, finishAfterOpen: Boolean, song: MusicMetadata? = uiState.result): Effect.Open {
        qobuzNumbers?.off()
        val pending = if (song?.type == ItemType.TRACK && packageName == MusicService.QOBUZ.packageName && url.toHttpUrlOrNull()?.pathSegments?.firstOrNull() == "track") {
            qobuzNumbers?.prepare(song, packageName, url)
        } else null
        return Effect.Open(url, packageName, finishAfterOpen, asking = song?.let(::searchQuery), qobuz = pending)
    }

    /** [tapped] is a tap on its button or in the picker, rather than a link that came in opening on its own. */
    private suspend fun open(destination: Destination, finishAfterOpen: Boolean, tapped: Boolean = false, preparedUrl: String? = null) {
        val url = preparedUrl ?: prepareDestination(destination) ?: return
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
                    effectChannel.send(opening(original.url, original.service?.packageName, finishAfterOpen))
                    return
                }
                NotFoundAction.SEARCH -> Unit
            }
        }
        // Already in that app, playing or paused, it's brought forward, not asked for again, which would restart it.
        // Only a song plays: an album or a playlist named as the song playing opens as itself.
        val there = uiState.result?.takeIf { tapped && it.type == ItemType.TRACK }?.let { song ->
            playback?.takeIf { it.hasAccess() }?.follow(song)?.first()?.takeIf { it.canContinueIn(destination.packageName) }
        }
        if (there?.appPackage != null) return effectChannel.send(Effect.Show(there.appPackage, finishAfterOpen))
        effectChannel.send(opening(url, destination.packageName, finishAfterOpen))
    }

    /**
     * Searches for the result by its name where it goes, whether or not it was found there: for another version of it,
     * under another name, or to pick it there without it starting.
     */
    fun searchResult() {
        val destination = uiState.resultDestination
        val result = uiState.result ?: return
        // Whatever was on its way to open or play, the search is what was asked for now.
        pendingOpen = false
        pendingPlay = false
        job?.cancel()
        // The link Copy and Share give is still made, where it waited for Android to say whether the song can play.
        if (destination !in uiState.destinationUrls) viewModelScope.launchKept({ job = it }) { prepareDestination(destination) }
        effectChannel.trySend(opening(destination.searchUrl(searchQuery(result)).forSharing(), destination.packageName, finishAfterOpen = false))
    }

    /**
     * A song from Recent or a playlist that [destination] doesn't have: searched, opened where it's
     * from, or offered both, as set.
     */
    private suspend fun openMissing(song: MusicMetadata, original: MusicLink?, destination: Destination, searchUrl: String, finishAfterOpen: Boolean) {
        val opensWhere = original?.takeIf { it.service != null }
        val action = uiState.notFoundAction
        when {
            action == NotFoundAction.ORIGINAL && opensWhere != null ->
                effectChannel.send(opening(opensWhere.url, opensWhere.service?.packageName, finishAfterOpen, song))
            // Searched, also when it's set to open where it's from and that's unknown.
            action == NotFoundAction.SEARCH || action == NotFoundAction.ORIGINAL ->
                effectChannel.send(opening(searchUrl.forSharing(), destination.packageName, finishAfterOpen, song))
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
            if (original && link != null) opening(link.url, link.service?.packageName, finishAfterOpen = false, song = offer.song)
            else opening(offer.searchUrl.forSharing(), offer.destination.packageName, finishAfterOpen = false, song = offer.song)
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

    @VisibleForTesting
    internal val findingWords get() = lyricsJob?.isActive == true

    private var followJob: Job? = null

    /** The last song Crosstune heard, so its words can follow on from where it was heard. */
    private var heardSong: MusicMetadata? = null

    /** Where [heardSong] is, as far as hearing it can tell, kept steady against what one hearing gets wrong. */
    private var heardClock: PlaybackClock? = null

    /** The recording Shazam matched [heardClock] to, and the gap moved it by, so a gap learnt later moves it too. */
    private var heardMatch: String? = null
    private var heardGapMs = 0L

    /** A place heard far from where the words are, held until a second hearing agrees with it. */
    private var unconfirmed: PlaybackClock? = null

    /** When the song was last heard, so a hearing long ago isn't held against an app's say. */
    private var heardAtMs = 0L

    /** Where the app playing the song says it is, before taking off how far its video is ahead. */
    private var appFollowing: Following? = null

    /** The name of the app last seen playing the song shown, kept once it stops or goes on, which is often when a report goes. */
    private var playedIn: String? = null

    /** The package and the speed of the app [playedIn] names, kept the same way: a report about timing says which player and how fast. */
    private var playedPackage: String? = null
    private var playedSpeed: Float? = null

    /** How far the video an app plays is ahead of its song, as last heard; null until it's been heard. */
    private var appLeadMs: Long? = null
    /**
     * How far the video is ahead by the last hearing alone, while hearings don't agree on it yet: the
     * words follow from there meanwhile, rather than stand still, but it's never kept.
     */
    private var guessedLeadMs: Long? = null

    /** Where in what the app plays how far it's ahead was taken: a video may go a little slower or faster than the song, as an old transfer does, so it's only that far ahead there. */
    private var leadFromMs = 0L

    /** How fast each hearing of the video said it goes, wherever it said it is: a part played again is heard elsewhere, but at this speed. */
    private val leadSpeeds = mutableListOf<Float>()
    /** The recording Shazam matched the hearings [appLeadMs] was learnt from to, this time. */
    private var leadMatch: String? = null

    /**
     * Shazam's gap for the recording it [match]ed, as it was before the words were put in time by hand
     * by moving it, and the music app's lead then, so Reset puts both back; and the line that moved it.
     */
    private data class HandGap(val match: String, val gapMs: Long?, val leadMs: Long?, val lineMs: Long)
    private var handGap: HandGap? = null
    private var leadTiming = LeadTiming()
    private var gapTiming = LeadTiming()
    /** The recording Shazam matched the hearings [gapTiming] holds to, as each has a gap of its own. */
    private var gapMatch: String? = null
    private var autoCalibrating = false
    private var calibrationAttempted = false

    private var nextJob: Job? = null

    /**
     * A song with no words shown, still to be heard for a voice: whether it's taken for an instrumental, and the whiles heard
     * so far. Words found for it that LRCLIB's marks or its title say no one sings are [held], [final] or still to be timed better.
     */
    private data class Unsung(
        val song: MusicMetadata,
        val likelyInstrumental: Boolean,
        val tries: Int = 0,
        val held: LyricsFinder.Lyrics.Found? = null,
        val final: Boolean = true,
        val heldUntilMs: Long? = null
    )

    private var unsung: Unsung? = null
    private var voiceJob: Job? = null

    /** Shows held words if no listen settles them in the time one takes: see [HELD_MS]. */
    private var heldJob: Job? = null

    /** Hearing the words sung, to line them up, or to time them; [singingTries] since the app's clock last started or jumped. */
    private var singingJob: Job? = null

    /** What was heard sung of the song shown, its words found with no timing, to time them by: this time, and the times before. */
    private var heardStretches: List<HeardStretch> = emptyList()

    /** The [heardStretches] the lines shown were timed by: the timing of the last while heard is stopped when Crosstune goes out of sight. */
    private var timedBy: List<HeardStretch> = emptyList()
    private var singingTries = 0

    /**
     * Whether the app's clock is known to be where the song is: Crosstune saw it say so as the song
     * started. Picked up partway, or after a pause or a move, it may be most of a second behind, as
     * YouTube Music's often is, so the singing heard then puts this time right, and only this time,
     * unless the words are heard far further off than that.
     */
    private var clockSure = false
    /** The app's clock as last said, to tell when it jumps rather than plays on. */
    private var lastAppClock: PlaybackClock? = null
    /** Where lines were heard sung by the app's clock, unsure, so for this time only; and whether they were. */
    private var heardPoints: List<SyncPoint> = emptyList()
    /** Whether no singing was heard where the words are timed, and none found yet: no line is shown sung till it is. */
    private var unheard = false
    private var heardThisTime = false
    /** How far off the words as found a whole while heard last said they are, far enough off to say they're off themselves, with the app's clock unsure: they're kept so once another while agrees. */
    private var farOffMs: Long? = null
    /** A hearing kept before this listening session, not another hearing in this session. */
    private var pendingBeforePlay: PendingShift? = null
    /** Whether the lines shown were moved this time by a while found further off than near lines are looked at, and nothing agreed yet. */
    private var movedFar = false
    /** A whole while moved the words far this time; report only once later whiles can no longer put them back. */
    private var farReportPending = false
    /** Whether the words are being lined up to the singing, through the song, with the app's clock sure; lines heard so far stay till it's done. */
    private var liningUp = false
    /** Whether the speech model, offered to be fetched by hand, was turned down; it isn't offered again till Crosstune opens again. */
    private var modelClosed = false

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
        showLyricsFor(uiState.lyricsFor ?: uiState.result ?: return, playing = followingRadioLyrics)
    }

    /** [requested] as its words are looked up: as the app playing it has it, when one does. */
    private fun asPlayed(requested: MusicMetadata): MusicMetadata {
        val current = playback?.recording(requested)?.takeIf { sameSong(it, requested) && it.recordingId != null } ?: return requested
        val sourceUrl = requested.url ?: uiState.link?.url?.takeIf { uiState.result?.copy(artworkUrl = requested.artworkUrl) == requested }
        return RecordingKey.asPlayed(requested, current, sourceUrl)
    }

    /** The words of songs shown, by recording, while they're being looked up ahead. */
    private val lookingAhead = HashMap<String, Deferred<LyricsFinder.FirstLook>>()

    /**
     * Looks up the words of the song shown as it shows, so they're there, or on their way, when
     * opened; once found they're kept by the finder, so only one still on its way is held here.
     */
    private fun lookAhead() {
        // A link going straight to the user's app shows nothing here to open the words of.
        val finder = lyricsFinder?.takeIf { lookAhead && !uiState.handingOff } ?: return
        val song = uiState.result?.takeIf { it.type == ItemType.TRACK }?.let(::asPlayed) ?: return
        val key = RecordingKey.of(song)
        if (key in lookingAhead || key in kept) return
        lookUpAhead(finder, key, song)
    }

    /**
     * Looks up [song]'s words, held under [key] till they're found. Held before the lookup runs: answered at once from
     * what's kept, it'd be dropped before it was held, then held for good, and the song never looked up ahead again.
     */
    private fun lookUpAhead(finder: LyricsFinder, key: String, song: MusicMetadata): Deferred<LyricsFinder.FirstLook> =
        viewModelScope.asyncKept({ look -> lookingAhead[key] = look; look.invokeOnCompletion { lookingAhead.remove(key) } }) { finder.firstLook(song) }

    /** Whether the open lyrics follow the songs Radio Garden puts in its queue. */
    var followingRadioLyrics by mutableStateOf(false)
        private set

    /** A radio queue has a song name but no song clock. Listen to the phone to find the position. */
    private fun showQueueLyrics(song: MusicMetadata) {
        val firstSong = !followingRadioLyrics
        followingRadioLyrics = true
        alongByPhone = true
        showLyricsFor(song, playing = true)
        if (firstSong) listenAlong()
    }

    private var lyricsFromFallback = false
    private var lyricsCanPreview: Boolean? = null

    private fun showLyricsFor(requested: MusicMetadata, playing: Boolean = false) {
        val song = asPlayed(requested)
        // Only a song has words, and one whose artist is unknown can't be looked up safely.
        val finder = lyricsFinder ?: return
        if (song.type != ItemType.TRACK || song.artist.isBlank()) return
        // Already showing these words, or on the way, so a second tap doesn't fetch them again. A lookup
        // that failed is worth asking again, so Retry is the one case that does fetch.
        if (uiState.lyricsFor == song && (uiState.lyrics.isNotEmpty() || uiState.isLoadingLyrics)) return
        // A song just heard, or playing as it's shown, is followed on to the next one; one picked to read stays.
        shownPlayed = playing || heardSong?.let { sameSong(it, song) } == true
        val sendAfterLeaving = leaveWords()
        keepShown()
        lyricsJob?.cancel()
        stopLearning()
        stopFollowing()
        foundLines = emptyList()
        found = null
        lyricsFromFallback = false
        lyricsCanPreview = null
        heardStretches = emptyList()
        timedBy = emptyList()
        unsung = null
        voiceJob?.cancel()
        heldJob?.cancel()
        restored = null
        syncPoints = emptyList()
        wordsPace = 1.0
        keptByHand = false
        handGap = null
        if (showKept(song)) {
            follow(song)
        } else {
            viewModelScope.launchKept({ lyricsJob = it }) {
                var first: LyricsFinder.FirstLook? = null
                // No words found, and whether it's taken for an instrumental: worked out once that shows, as it may wait on the app.
                var noWords: Boolean? = null
                uiState = uiState.copy(
                    lyricsFor = song, lyrics = "", lyricLines = emptyList(), lyricsSynced = false, lyricsTimedByEar = false, lyricsOffTake = false, lyricsFailed = false, lyricsInstrumental = false, isLoadingLyrics = true, learning = uiState.learning.fresh(), chords = uiState.chords.fresh(),
                    canFollowApps = playback?.hasAccess() == true
                )
                try {
                    // A lookup that failed says so, rather than claiming the song has no words. The words
                    // show as soon as they're found, and their timing may get better after.
                    val look = lookingAhead.remove(RecordingKey.of(song))?.await() ?: finder.firstLook(song)
                    first = look
                    lyricsFromFallback = look.fallback
                    when (val answer = look.lyrics) {
                        is LyricsFinder.Lyrics.Found -> if (answer.markedInstrumental) held(song, answer, look.final) else showWords(song, answer)
                        // No words were shown while looking, so there's none to clear.
                        LyricsFinder.Lyrics.None -> noWords = false
                        // Having no words is what an instrumental is, not a problem.
                        LyricsFinder.Lyrics.Instrumental -> uiState = uiState.copy(lyricsInstrumental = true)
                        // Taken for an instrumental by its genre, which can be wrong: heard for a voice first.
                        LyricsFinder.Lyrics.LikelyInstrumental -> {
                            uiState = uiState.copy(lyricsInstrumental = true)
                            noWords = true
                        }
                        LyricsFinder.Lyrics.Unavailable -> uiState = uiState.copy(lyrics = "", lyricsFailed = true)
                    }
                } finally {
                    if (isActive) uiState = uiState.copy(isLoadingLyrics = false)
                }
                noWords?.let { noWordsFound(song, likelyInstrumental = it) }
                follow(song)
                first.takeIf { !it.final }?.let { retime(song, it.lyrics) }
                reportUntimed()
            }
        }
        if (autoReports.allowed == null && autoReports.owes(song, SongProblem.SUNG_OFF)) offerToReport(SongProblem.SUNG_OFF)
        watchNext(song)
        if (sendAfterLeaving) sendOwed()
        // A song Crosstune just heard is still playing nearby, so it keeps listening, to stay in time with it.
        if (heardSong?.let { sameSong(it, song) } == true) listenAlong()
    }

    /** [answer], the words found for [song], shown. */
    private suspend fun showWords(song: MusicMetadata, answer: LyricsFinder.Lyrics.Found, heardSinging: Boolean = false) {
        lyricsCanPreview = !answer.markedInstrumental || heardSinging
        useFoundTiming(song, answer)
        uiState = uiState.copy(
            lyrics = answer.words, lyricLines = adjustedLines(syncPoints), lyricsSynced = syncPoints.isNotEmpty(), lyricsFailed = false,
            lyricsInstrumental = false, lyricsOffTake = timedForAnother(song)
        )
        uiState = uiState.copy(learning = uiState.learning.fresh(Readings.displayScriptOf(wordLines())))
        // Words with no timing, heard sung before, are timed as they were heard.
        if (answer.lines.isEmpty()) {
            heardStretches = heardUntimed?.let { HeardSongs(it).load(song, answer.words) }.orEmpty()
            timeByEar(song)
        }
        followSavedLines(song)
        timeSavedLines(song)
        learn()
        chords()
        // Japanese in romaji or Korean in Latin letters is written back in its own script, so it
        // reads like any song in it.
        if (usesChatGpt()) romanizedScript(answer.words)?.let { writeInOwnScript(song, it) }
    }

    /** Restore saved points for these timestamps, including when retiming returns the sheet they were set on. */
    private fun useFoundTiming(song: MusicMetadata, answer: LyricsFinder.Lyrics.Found) {
        foundLines = answer.lines
        found = answer
        syncStore.pending(song, foundLines)
        syncPoints = syncStore.load(song, foundLines)
        wordsPace = syncStore.pace(song, foundLines)
        keptByHand = syncStore.byHand(song, foundLines)
    }

    /**
     * The words shown not timed even after looking further. A cover's words taken from the original are untimed as
     * they should be, as it's sung in its own time.
     */
    private fun reportUntimed() {
        if (found?.lines?.isEmpty() == true && found?.ofTheOriginal != true) problemFound(SongProblem.NO_TIMING)
    }

    /**
     * Keeps the words shown as the app's left, for when they're opened again: once all that's on for
     * them is worked out, as it still is while Android lets the app run, e.g. timing them better.
     */
    fun keep() {
        val busy = listOfNotNull(lyricsJob, readingsJob, restoreJob, translationJob, chordsJob).filter { it.isActive }
        if (busy.isEmpty()) keepShown() else viewModelScope.launch { busy.joinAll(); keep() }
    }

    /**
     * Keeps the words shown for when they're opened again, once all that's on for them is worked out;
     * left before that, they're looked up again.
     */
    private fun keepShown() {
        val song = uiState.lyricsFor ?: return
        if (!wordsReadyToKeep()) return
        val learning = uiState.learning
        val key = RecordingKey.of(song)
        // Where lines were heard this time only isn't kept: next time the app's clock may be sure.
        val points = withGuess(syncPoints)
        val lines = if (heardPoints.isEmpty() && points == syncPoints) uiState.lyricLines else linesFrom(points)
        val words = KeptWords(
            uiState.lyrics, lines, uiState.lyricsSynced, found, foundLines, restored, points,
            // A translation that failed is asked for again.
            learning.copy(translations = if (learning.translationFailed) emptyList() else learning.translations),
            // Chords none were found for are looked for again.
            madeWith(song), uiState.chords.takeIf { it.sheet != null } ?: uiState.chords.fresh(), wordsPace, canPreview = lyricsCanPreview
        )
        kept[key] = words
        keptOnDisk?.let { disk ->
            viewModelScope.launch { disk.keep(key, words.json()) }
        }
    }

    /** Words borrowed offline still need a lookup, and work in progress can be kept when it finishes. */
    private fun wordsReadyToKeep(): Boolean =
        !lyricsFromFallback && uiState.lyrics.isNotEmpty() && !uiState.isLoadingLyrics && lyricsJob?.isActive != true &&
            readingsJob?.isActive != true && restoreJob?.isActive != true && !uiState.learning.translating && !uiState.chords.loading

    /**
     * Shows [song]'s words as they were left, when they're kept and were worked out as they would
     * be now, and says whether they were. Words with more in them than would be shown now, e.g. a
     * credit since left out, are worked out again.
     */
    private fun showKept(song: MusicMetadata): Boolean {
        val kept = kept[RecordingKey.of(song)]?.takeIf {
            it.madeWith == madeWith(song) && it.canPreview != false && it.found?.let { found -> LyricsFinder.cleaned(found, song) == found } != false
        } ?: return false
        found = kept.found
        foundLines = kept.foundLines
        syncStore.pending(song, foundLines)
        lyricsCanPreview = kept.canPreview
        restored = kept.japanese
        syncPoints = kept.syncPoints
        wordsPace = kept.pace
        keptByHand = syncStore.byHand(song, foundLines)
        uiState = uiState.copy(
            lyricsFor = song, lyrics = kept.lyrics, lyricLines = kept.lines, lyricsSynced = kept.synced, lyricsTimedByEar = kept.timedByEar, lyricsFailed = false, lyricsInstrumental = false, isLoadingLyrics = false,
            learning = uiState.learning.fresh(kept.learning.script).copy(
                lines = kept.learning.lines, names = kept.learning.names, readingsUnfinished = kept.learning.readingsUnfinished, translations = kept.learning.translations,
                titleMeaning = kept.learning.titleMeaning, fromRomaji = kept.learning.fromRomaji
            ),
            chords = kept.chords.copy(on = uiState.chords.on), canFollowApps = playback?.hasAccess() == true
        )
        uiState = uiState.copy(lyricsOffTake = timedForAnother(song))
        followSavedLines(song)
        timeSavedLines(song)
        // Whatever's switched on since, and wasn't worked out then.
        learn()
        // Readings that lacked something then, shown as they were until they're read again.
        if (kept.learning.readingsUnfinished) readWords(wordLines())
        chords()
        return true
    }

    /**
     * What a song's words are worked out with: how they're looked up and read, as words looked up or read
     * before a fix are looked up again, the language they're translated into, those left untranslated, and,
     * signed in, ChatGPT, how it's asked to translate and its model, which translate them, put their
     * readings right and write romaji in Japanese.
     */
    private fun madeWith(song: MusicMetadata) =
        listOf(LyricsFinder.cacheVersion(song), Readings.VERSION, translateInto(), uiState.learning.untranslated.sorted(), usesChatGpt(), uiState.learning.chatGptTranslation, TRANSLATION_VERSION, uiState.chatGpt.model).toString()

    /**
     * [found], [song]'s words as shown, timed better once that's worked out, e.g. from another copy
     * of the song; kept as they are when they're put in time by hand meanwhile.
     */
    private suspend fun retime(song: MusicMetadata, found: LyricsFinder.Lyrics) {
        // Words held while a voice is listened for aren't shown yet: they're timed once they are.
        if (found !is LyricsFinder.Lyrics.Found || (found.markedInstrumental && found !== this.found)) return
        val finder = lyricsFinder ?: return
        // Put in time by hand, they stay as they are, next time too: that timing is kept for these lines (Issue #1164).
        // No copy is looked for then, and they're kept for good, so none is next time either.
        if (byHand(song)) return finder.keep(song, found)
        val better = finder.retimed(song, found) as? LyricsFinder.Lyrics.Found ?: return
        if (better.lines == found.lines) return
        // Or put in time by hand while the copies were looked for, which kept the better times meanwhile.
        if (byHand(song)) return finder.keep(song, found)
        if (!mayRetime(song)) return
        useFoundTiming(song, better)
        uiState = uiState.copy(lyricLines = adjustedLines(syncPoints), lyricsSynced = syncPoints.isNotEmpty(), lyricsTimedByEar = false, lyricsOffTake = timedForAnother(song))
        followSavedLines(song)
    }

    /** Whether [song]'s words may still be timed anew: they're still shown, and no one has put them in time, by hand or by the singing. */
    private fun mayRetime(song: MusicMetadata) = shown(song) && syncPoints.isEmpty() && handGap == null

    /** Whether [song]'s words are the ones shown, and put in time by hand. */
    private fun byHand(song: MusicMetadata) = shown(song) && keptByHand

    /** Whether [song]'s words are the ones shown. */
    private fun shown(song: MusicMetadata) = uiState.lyricsFor?.let { sameSong(it, song) } == true

    private fun sameSong(one: MusicMetadata, other: MusicMetadata) =
        SongNames.titleNames(one.title).any { title -> SongNames.titleNames(other.title).any { SongNames.same(title, it) } } && SongNames.sameArtist(one.artist, other.artist) &&
            (one.recordingId == null || other.recordingId == null || one.recordingId == other.recordingId)

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
        listeningAlong()
        // Being lined up by ear, the words aren't offered to put in time by hand meanwhile.
        uiState.lyricsFor?.let { uiState = uiState.copy(lyricsOffTake = timedForAnother(it)) }
    }

    /**
     * Whether Shazam hears what a music app plays, though it says where it is: to tell how far off the
     * words are ([leading]), or, timed for it, to learn Shazam's own gap to them.
     */
    private val lining get() = leading || appFollowing?.let { !it.video && it.clock.playing && learnsGap(it) } == true

    /**
     * Whether Shazam hears how far off the words are now, which the singing waits for; while it only
     * learns its own gap, which says nothing of where they're sung, both hear the same sound at once.
     */
    private val shazamLeads get() = leading && alongJob?.isActive == true

    /** Whether a music app plays a release the words aren't timed for, and how far off they are isn't known yet. */
    private val leading get() = appFollowing?.let { !it.video && it.clock.playing && needsLead(it) && appLeadMs == null } == true

    private val playerSpeed get() = appFollowing?.clock?.speed ?: 1f

    /**
     * Hears what plays, to keep the words in time: what a music app plays through its own sound first, as
     * does what an app with no song named plays, else the microphone; [quietly], never the microphone.
     */
    private suspend fun hearAlong(quietly: Boolean) =
        if (lining || alongByPhone) listener.listenToPlaying(byMicrophone = !quietly) else listener.listen()

    private fun listeningAlong() = viewModelScope.launchKept({ alongJob = it }) {
        byEarLetsGo()
        var quiet = 0
        var calibrationTries = 0
        while (isActive) {
            // A video's long intro isn't the song: what's heard there, before the song may have started, is no try.
            if (autoCalibrating && !songMayNotHaveStarted() && calibrationTries++ >= 5) {
                stopListeningAlong()
                return@launchKept
            }
            // Only to learn Shazam's gap, nobody asked to listen, so never with the microphone.
            val quietly = autoCalibrating && appFollowing?.let(::learnsGap) == true
            // Shazam measures against wall time, so its speed already includes the player's rate.
            // Keep that rate before capture: the player can change it while Shazam answers.
            val capturedSpeed = playerSpeed
            // Radio lyrics already listen. Use that answer if it is still for the current queue claim.
            val radio = confirmedRadioQueue()?.takeIf { followingRadioLyrics && mayUseMicrophone() }
            val heard = hearAlong(quietly)
            checkRadioQueueFromLyrics(radio, heard)
            when (heard) {
                is Heard.Song -> {
                    quiet = 0
                    heardAlong(heard, capturedSpeed)
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
                // What played a while ago is no use to keep in time with now, so it isn't sent later.
                is Heard.Unsent -> delay(listenAlongPauseMs * 2)
                is Heard.Failed -> {
                    // Without the microphone there's nothing to listen with; anything else, e.g. the
                    // network or Shazam being busy, is tried again in a while.
                    if (heard.error == AppError.MICROPHONE) {
                        stopListeningAlong()
                        if (!quietly) uiState = uiState.copy(error = AppError.MICROPHONE, canRetry = false)
                        return@launchKept
                    }
                    delay(listenAlongPauseMs * 2)
                }
            }
        }
    }

    private fun checkRadioQueueFromLyrics(radio: QueueCheck?, heard: Heard) {
        if (radio?.isDueAt(now()) == true) checkQueueHearing(radio, heard)
    }

    /**
     * The song heard: the one shown keeps in time with it; another one takes over the words, once
     * the one shown was heard playing, as the next song. A song picked to read stays.
     */
    private fun heardAlong(hearing: Heard.Song, playerSpeed: Float) {
        TimingLog.note("fingerprint ${hearing.offsetMs} at ${hearing.startedAtMs} match ${hearing.matchId} received ${now()} speed ${hearing.speed} length ${hearing.metadata.lengthMs} app ${appFollowing?.clock} video ${appFollowing?.video} duration ${appFollowing?.durationMs} lead $appLeadMs guessed $guessedLeadMs")
        val song = hearing.metadata
        val shown = uiState.lyricsFor ?: return
        if (!hearingFits(song, shown)) return
        noteHeardSpeed(hearing, playerSpeed)
        appFollowing?.takeIf(::learnsGap)?.let { learnGap(it, shown, hearing) }
        appFollowing?.let { learnPace(it, shown, hearing, playerSpeed) }
        val gap = if (sameSong(song, shown)) gapOf(hearing) else 0L
        val heard = hearing.copy(offsetMs = hearing.offsetMs?.plus(gap))
        learnLead(shown, hearing, heard)
        if (keepsHeardRecording(heard, shown)) return
        if (followHeardSong(heard, hearing, song, shown, gap)) return
        guessHeardLead(song, shown, heard)
        keepInTime()
    }

    /** Another name can take over only after this song was heard and its app no longer says it plays. */
    private fun hearingFits(song: MusicMetadata, shown: MusicMetadata): Boolean {
        if (sameSong(song, shown)) return true
        if (followingRadioLyrics && uiState.playingUnnamed?.confirmedQueueSong != null) return false
        return heardSong?.let { sameSong(it, shown) } == true && appFollowing?.clock?.playing != true
    }

    private fun noteHeardSpeed(hearing: Heard.Song, playerSpeed: Float) {
        if (hearing.clock != null) {
            heardAtMs = now()
            val pacedBefore = pace
            leadSpeeds += hearing.speed / playerSpeed
            if (pace != pacedBefore) showPoints()
        }
    }

    private fun learnLead(shown: MusicMetadata, hearing: Heard.Song, heard: Heard.Song) {
        appFollowing?.takeIf { needsLead(it) && shownPoints.isEmpty() }?.let { app ->
            heard.clock?.let { clock -> leadTiming.heard(app.clock, clock, extraIn(app)) }?.let { lead ->
                appLeadMs = lead
                leadFromMs = app.clock.positionAt(now())
                leadMatch = hearing.matchId
                TimingLog.note("lead $lead from hearing at ${hearing.offsetMs}")
                leadStore.save(shown, lead)
                if (app.video) leadSavedLines(shown, app.appPackage, lead)
                if (autoCalibrating) stopListeningAlong()
            }
        }
    }

    /**
     * Heard by another name of the song right where the song shown is, well into it, it's the same
     * recording on another release, as Shazam sometimes names it, not the next song, which starts over.
     * Another song heard there is still another song, e.g. one the phone plays, the room's named first.
     */
    private fun keepsHeardRecording(heard: Heard.Song, shown: MusicMetadata): Boolean {
        val clock = heardClock
        val at = heard.clock
        if (at == null || clock == null || !sameRecordingThere(heard.metadata, shown, at, clock)) return false
        heardClock = steadied(clock, at)
        keepInTime()
        return true
    }

    private fun sameRecordingThere(song: MusicMetadata, shown: MusicMetadata, at: PlaybackClock, clock: PlaybackClock): Boolean {
        if (!clock.playing || heardSong?.let { sameSong(it, shown) } != true || !SongNames.releasesOfOneSong(song, shown)) return false
        return sameRecordingPlace(clock.positionAt(now()), at.positionAt(now()))
    }

    /** Whether this hearing needs no further clock update. */
    private fun followHeardSong(heard: Heard.Song, hearing: Heard.Song, song: MusicMetadata, shown: MusicMetadata, gap: Long): Boolean {
        if (heardSong?.let { sameSong(it, song) } != true || !sameSong(song, shown)) {
            heardSong = song
            hear(heard.clock, hearing.matchId, gap)
            unconfirmed = null
            if (!sameSong(song, shown)) {
                remember(heard)
                showLyricsFor(song)
                return true
            }
        } else {
            // Heard with no place in it, it stays where it was, and goes on as a first hearing with no place does.
            heard.clock?.let { hear(steadied(heardClock, it), hearing.matchId, gap) }
        }
        return false
    }

    private fun guessHeardLead(song: MusicMetadata, shown: MusicMetadata, heard: Heard.Song) {
        // A part played again is heard as its other time, again and again, so where the song is steadies on
        // that: with nothing to go by yet, the first answer the video could have is taken as soon as it's heard.
        appFollowing?.takeIf { mayGuessLead(it) && guessedLeadMs == null && sameSong(song, shown) }?.let { guessLead(it, heard.clock) }
    }

    /** Whether the video playing may still be before its song: it isn't yet as far in as it's longer than the song. */
    private fun songMayNotHaveStarted(): Boolean {
        val app = appFollowing?.takeIf { it.video } ?: return false
        val extra = extraIn(app) ?: return false
        return app.clock.positionAt(now()) < extra
    }

    /** How much longer what [app] plays is than what the words are timed for, when both are known. */
    private fun extraIn(app: Following) = app.durationMs?.let { playing -> found?.lengthMs?.let { playing - it } }

    /**
     * Whether [app] plays the very release the words are timed for, as long, so where it is is where
     * they are: what's heard then tells how far Shazam's place is from theirs, once for the song.
     */
    private fun learnsGap(app: Following): Boolean {
        val words = wordsMs ?: return false
        val shown = uiState.lyricsFor ?: return false
        return !app.video && app.durationMs != null && found?.timedFor(app.durationMs) == true && syncPoints.isEmpty() && !gapStore.knows(shown, words)
    }

    /** How long the recording the words are timed for is, when they're timed and say. */
    private val wordsMs get() = found?.lengthMs?.takeIf { foundLines.isNotEmpty() }

    private fun learnGap(app: Following, shown: MusicMetadata, heard: Heard.Song) {
        val match = heard.matchId ?: return
        val words = wordsMs ?: return
        if (match != gapMatch) {
            gapMatch = match
            gapTiming = LeadTiming()
        }
        val gap = heard.clock?.let { gapTiming.heard(app.clock, it, -MAX_GAP_MS..MAX_GAP_MS) } ?: return
        gapStore.save(shown, words, match, gap)
        TimingLog.note("gap $gap to match $match")
        if (autoCalibrating) stopListeningAlong()
    }

    /**
     * How fast [app] plays [shown] against how its words are timed, by how much faster or slower than
     * the recording it matched Shazam [heard] it: words timed for a release play that much sooner or
     * later as it goes on. Words that say they're timed for one as long as this keep its pace.
     */
    private fun learnPace(app: Following, shown: MusicMetadata, heard: Heard.Song, playerSpeed: Float) {
        // Words found with no timing are timed by ear, by the clock of what plays: there's no release they're timed for to pace.
        if (heard.clock == null || !sameSong(heard.metadata, shown) || foundLines.isEmpty()) return
        if (found?.lengthMs != null && app.durationMs != null && found?.timedFor(app.durationMs) == true) return
        val speed = heard.speed / playerSpeed
        val heardPace = if (abs(speed - 1) >= MIN_SKEW) 1.0 / speed else 1.0
        if (heardPace == wordsPace) return
        wordsPace = heardPace
        TimingLog.note("pace $heardPace from speed ${heard.speed} player speed $playerSpeed")
        syncStore.save(shown, foundLines, withGuess(syncPoints), heardPace, keptByHand)
        showPoints()
    }

    /** How far the words are from Shazam's place in [heard], as learnt for the recording it matched. */
    private fun gapOf(heard: Heard.Song): Long = heard.matchId?.let { match -> wordsMs?.let { gapStore.load(it, match) } } ?: 0L

    /** Where the song is heard to be, by Shazam's place in the recording it matched, [match], moved by [gapMs]. */
    private fun hear(clock: PlaybackClock?, match: String? = null, gapMs: Long = 0L) {
        heardClock = clock
        heardMatch = match
        heardGapMs = gapMs
    }

    /**
     * Where the song was heard, moved by the gap now known for the recording Shazam matched, e.g.
     * once the words are found, or after they're put in time by hand.
     */
    private fun placeHeard() {
        val match = heardMatch ?: return
        val words = wordsMs ?: return
        val gap = gapStore.load(words, match) ?: 0L
        if (gap == heardGapMs) return
        heardClock = heardClock?.let { it.copy(positionMs = it.positionMs + gap - heardGapMs) }
        heardGapMs = gap
        unconfirmed = null
    }

    /**
     * A song heard along the way joins Recent, as one Crosstune listened for does: by its Apple Music
     * link when Shazam knows it, or else by its name, its cover found by its name when it came with none,
     * or only with the one its app shows, which is kept for a while only.
     */
    private fun remember(heard: Heard.Song) {
        val link = heard.appleMusicId?.let { (MusicLinks.parse("https://www.shazam.com/song/$it") as? LinkInput.Link)?.link }
            ?: MusicLinks.recognizedSong(heard.metadata).let { MusicLink(null, ItemType.TRACK, it.url, it.url) }
        uiState = uiState.copy(history = historyStore.add(HistoryEntry(link, heard.metadata)))
        // Named by an app that gave no cover on the web, it's found by its name, so Recent and its words show one.
        if (heard.metadata.artworkUrl?.startsWith("https://") == true) return
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
        uiState = uiState.copy(following = inSong(appFollowing) ?: heardFollowing(shown), lyricsOffTake = timedForAnother(shown))
        hearSinging()
    }

    /**
     * Lines the timed words up to the singing, once for the song and its words: what the phone plays
     * is heard a while at a time, through the song, and each line heard moves to where it's sung, as
     * putting it in time by hand would, which Reset undoes. Only while a music app on the phone plays
     * it, lined up otherwise, with nothing put in time by hand, and Crosstune in sight with the words.
     */
    private fun hearSinging() {
        hearVoice()
        val hearing = singing ?: return
        val song = uiState.lyricsFor ?: return
        val lines = foundLines
        if (lines.isEmpty()) return hearUntimed(hearing, song)
        if (singingJob?.isActive == true || singingTries >= SINGING_TRIES) return
        if (shazamLeads || nextPaused) return
        if (!playingLinedUp() || !mayCalibrate()) return
        // With the app's clock sure, the words are heard once for the song; unsure, once each time it's picked up.
        if (if (clockSure) (uiState.lyricsSynced && !liningUp) || timedByHand || handGap != null || sungStore.heard(song, lines) else heardThisTime) return
        viewModelScope.launchKept({ singingJob = it }) {
            byEarLetsGo()
            try {
                hearOn(hearing, song, lines)
            } finally {
                // Given up on, or stopped, nothing says the words are wrong, so they're shown as timed.
                doubtTiming(false)
            }
        }
    }

    /**
     * Hears [song], its words found with no timing, a while at a time as a music app on the phone plays
     * it, and times each line heard sung where it's sung; lines not heard stay untimed, never lit. What's
     * heard is kept, so the words are timed from the start next time, and heard on till the song's been
     * heard through [UNTIMED_HEARINGS] times. Only while Crosstune is in sight with the words.
     */
    private fun hearUntimed(hearing: SingingHearing, song: MusicMetadata) {
        val words = found?.words ?: return
        if (!mayHearUntimed()) return
        val store = heardUntimed?.let(::HeardSongs)
        viewModelScope.launchKept({ singingJob = it }) {
            byEarLetsGo()
            // Shown from what was kept, what was heard before isn't loaded yet.
            if (heardStretches.isEmpty()) heardStretches = store?.load(song, words).orEmpty()
            // What was heard last time, kept as its timing was stopped, is timed now: heard enough, it's never heard again.
            if (heardStretches != timedBy) timeByEar(song)
            // A few whiles in a row with nothing sung heard, it's let be till the app's clock starts or jumps.
            while (!heardEnough(song) && singingTries < SINGING_TRIES) {
                val heard = hearStretch(hearing, song, words) ?: return@launchKept
                heardStretches = heardStretches + heard
                store?.save(song, words, heardStretches)
                timeByEar(song)
                // Where the lines in this while were put, by their number, so the phone check and a report can tell; never the words.
                val timed = uiState.lyricLines.withIndex().filter { it.value.heard }
                val here = timed.filter { it.value.timeMs in heard.fromMs..heard.toMs }.joinToString(" ") { "${it.index}>${it.value.timeMs}" }
                TimingLog.note("by ear ${heard.fromMs} heard ${heard.words.size} words, ${timed.size} lines timed, here $here")
                timeSavedLines(song, again = true)
            }
        }
    }

    /** Whether the words found with no timing may be heard now: nothing else hears, and a music app on the phone plays them. */
    private fun mayHearUntimed(): Boolean {
        // A few whiles in a row with nothing sung heard, the hearing itself lets be.
        if (singingJob?.isActive == true || shazamLeads || nextPaused) return false
        return mayCalibrate() && appFollowing?.let { it.app != null && it.clock.playing } == true
    }

    /** The next while of [song] heard, in song time by the app's clock; null when nothing could be heard, or it can't be placed. */
    private suspend fun hearStretch(hearing: SingingHearing, song: MusicMetadata, words: String): HeardStretch? {
        val clock = appFollowing?.clock?.takeIf { it.playing } ?: return null
        singingTries++
        val sung = hearOnce(hearing, uiState.lyrics.lines(), null) ?: return null
        // Moved, paused or gone on meanwhile, what was heard can't be placed; timed words found meanwhile go by their own timing.
        val playing = appFollowing?.clock?.takeIf { it.playing } ?: return null
        val same = uiState.lyricsFor == song && found?.words == words && foundLines.isEmpty()
        if (!same || abs(playing.positionAt(now()) - clock.positionAt(now())) > STEADY_CLOCK_MS) return null
        if (sung.words.isNotEmpty()) singingTries = 0
        val from = clock.positionAt(sung.startedAtMs)
        val to = clock.positionAt(sung.startedAtMs + SingingListener.SECONDS * 1000L)
        val heard = sung.words.filter { it.atMs >= 0 }.map { it.text to clock.positionAt(sung.startedAtMs + it.atMs) }
        return HeardStretch(from, to, heard)
    }

    /**
     * Whether [song] has been heard through [UNTIMED_HEARINGS] times, about: hearing more would hardly
     * time more lines. A song too long for that to be kept, or with no length said anywhere, once as
     * much is heard as is kept: what's heard past it would be gone next time, and heard again.
     */
    private fun heardEnough(song: MusicMetadata): Boolean {
        val kept = HeardSongs.MAX_STRETCHES * SingingListener.SECONDS * 1000L
        val wanted = untimedLengthMs(song)?.let { it * UNTIMED_HEARINGS } ?: kept
        return heardStretches.sumOf { it.toMs - it.fromMs } >= minOf(wanted, kept)
    }

    /** How long [song] is, where the app plays it, as the singing heard is timed by its clock: as the app says, or else the song or its words. */
    private fun untimedLengthMs(song: MusicMetadata) = appFollowing?.durationMs?.takeIf { it > 0 } ?: song.lengthMs ?: found?.lengthMs

    /** [song]'s words, found with no timing, timed by the singing heard in [heardStretches], once any line's been heard. */
    private suspend fun timeByEar(song: MusicMetadata) {
        val heard = heardStretches
        val words = uiState.lyrics
        val knownLength = untimedLengthMs(song)
        val length = knownLength ?: heard.map { it.toMs }.maxOrNull() ?: return
        // A lyric source or a different recording can give the wrong length; only the player says this one ended.
        val playerLengthKnown = appFollowing?.durationMs?.let { it > 0 } == true
        // A long song heard through twice takes a while to line up, so it's done away from the screen.
        val lines = withContext(systemDispatcher) { SungTiming.timed(words.lines(), heard, length, playerLengthKnown) }
        // Timed words found meanwhile, or these written otherwise, as in their own script: this is out of date.
        // Left, or heard again, the job doing this is stopped, so it never gets here.
        if (foundLines.isNotEmpty() || uiState.lyrics != words) return
        timedBy = heard
        // Nothing heard is sure of any line now, so the lines lit before go dark too.
        if (lines.isEmpty() && !uiState.lyricsTimedByEar) return
        uiState = uiState.copy(lyricLines = lines, lyricsTimedByEar = lines.isNotEmpty())
        uiState = uiState.copy(following = inSong(appFollowing) ?: uiState.following)
    }

    /**
     * No words found for [song]. Many songs in one, a medley or a whole concert, have no one song's words
     * to miss. Only a song someone sings has words to miss, so one the phone plays is heard first, while
     * Crosstune can hear it: rain, or a piece no store lists, has none. Taken for an instrumental
     * [likelyInstrumental] by its genre alone, a voice heard says it's sung after all. Not heard, as when
     * it plays elsewhere, it's reported as it always was.
     */
    private suspend fun noWordsFound(song: MusicMetadata, likelyInstrumental: Boolean) {
        if (SongNames.manySongs(song)) return
        // With reports turned off, a voice heard changes nothing, but for a song taken for an instrumental.
        if (!likelyInstrumental && autoReports.allowed == false) return
        // The app is asked, as no words may come back before following it has said it plays the song.
        val canHear = singing != null && mayCalibrate() && (shownPlayed || appPlays(song))
        // Noted, as a report of no words then says how it came to be one.
        TimingLog.note("no words, likely instrumental $likelyInstrumental, a voice ${if (canHear) "is listened for" else "can't be heard"}")
        if (canHear) {
            unsung = Unsung(song, likelyInstrumental)
            hearVoice()
        } else if (!likelyInstrumental) {
            problemFound(SongProblem.NO_LYRICS)
        }
    }

    /**
     * Whether a listen for a voice in [song] can start: the microphone's allowed, Crosstune is in sight and not naming a
     * song, and a music app on this phone plays it now. Asked of the apps, as the words come before the song's followed,
     * while what's followed is still the song before. A song heard from another device, or in an app Crosstune can't
     * see, can't be listened to.
     */
    private suspend fun listenCanStart(song: MusicMetadata): Boolean {
        if (singing == null || !mayCalibrate()) return false
        // Naming a song has the microphone, and out of sight, nothing's heard.
        if (shazamLeads || nextPaused) return false
        return appPlays(song)
    }

    /**
     * Whether a music app on this phone plays [song] now, as the apps say when asked: one that doesn't say in
     * [APP_ASKED_MS], or a song heard from another device, isn't.
     */
    private suspend fun appPlays(song: MusicMetadata): Boolean {
        val source = playback?.takeIf { it.hasAccess() } ?: return false
        val following = withTimeoutOrNull(APP_ASKED_MS) { source.follow(song).first() }
        return following?.let { it.app != null && it.clock.playing } == true
    }

    /**
     * [words] found for [song], [final] or still to be timed better, that LRCLIB's marks or its title say no one sings
     * (Issue #861): hidden while a voice is listened for, and kept hidden only as [hidesHeldWords] says. Where no listen can
     * start they show at once, and with nothing settled in the time a listen takes, they show then.
     */
    private suspend fun held(song: MusicMetadata, words: LyricsFinder.Lyrics.Found, final: Boolean) {
        val waiting = Unsung(song, likelyInstrumental = true, held = words, final = final)
        if (!listenCanStart(song)) {
            settle(waiting, Listen.NOT_STARTED)
            return
        }
        TimingLog.note("LRCLIB or the title says no one sings in it: a voice is listened for before its words show")
        uiState = uiState.copy(lyricsInstrumental = true)
        val listening = waiting.copy(heldUntilMs = SystemClock.elapsedRealtime() + HELD_MS)
        unsung = listening
        holdUntil(listening)
        hearVoice()
    }

    private fun holdUntil(waiting: Unsung) {
        val deadline = waiting.heldUntilMs ?: return
        heldJob?.cancel()
        viewModelScope.launchKept({ heldJob = it }) {
            delay((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0))
            unsung?.takeIf { it.held === waiting.held }?.let {
                voiceJob?.cancel()
                settleLate(it, Listen.TIMED_OUT)
            }
        }
    }

    /** [heard]'s held words, by what the listen came to, [listen]: shown unless [hidesHeldWords] says. Whether they show. */
    private suspend fun settle(heard: Unsung, listen: Listen): Boolean {
        val words = heard.held ?: return false
        TimingLog.note("held words, the listen: $listen")
        if (hidesHeldWords(listen)) {
            unsung = heard
            uiState = uiState.copy(lyricsInstrumental = true)
            return false
        }
        unsung = null
        showWords(heard.song, words, heardSinging = listen == Listen.VOICE)
        return true
    }

    /** [settle], once the lookup's done: the words shown are timed better too, or said to be untimed. */
    private suspend fun settleLate(heard: Unsung, listen: Listen) {
        val words = heard.held ?: return
        if (!settle(heard, listen)) return
        if (!heard.final) retime(heard.song, words)
        reportUntimed()
    }

    /**
     * Hears the song with no words found for a voice, a while at a time, [VOICE_TRIES] whiles apart
     * through it, as the speech model misses one in four. Heard sung, it has words no one has, which is
     * a problem found; heard nothing sung all along, it has none to find. Only while the music app on the
     * phone plays it and Crosstune is in sight; a while heard as it stops is heard again once it plays.
     */
    private fun hearVoice() {
        val hearing = singing ?: return
        val waiting = unsung ?: return
        if (!mayHearVoice(waiting.song)) {
            // An active recheck for hidden words stops as soon as it cannot hear their song.
            if (waiting.tries == VOICE_TRIES) voiceJob?.cancel()
            return
        }
        if (voiceJob?.isActive == true) return
        viewModelScope.launchKept({ voiceJob = it }) {
            byEarLetsGo()
            var heard = waiting
            while (heard.tries < VOICE_TRIES) {
                if (heard.tries > 0) delay(VOICE_PAUSE_MS)
                val started = SystemClock.elapsedRealtime()
                val sung = hearing.hear(null, null)
                TimingLog.note("voice while ${heard.tries + 1}: ${if (sung == null) "nothing heard, model missing ${hearing.missesModel}, no sound ${hearing.heardNoSound}" else "sings ${sung.sings}"}")
                // Stopped: heard again once it plays, or, for held words, shown if it isn't in time.
                if (unsung !== heard || uiState.following?.clock?.playing != true) return@launchKept
                voiceIn(hearing, sung, held = heard.held != null)?.let { return@launchKept heardOut(heard, it) }
                heard = voiceWindowFinished(heard, started)
            }
            if (waiting.tries < VOICE_TRIES) heardOut(heard, Listen.NO_VOICE)
            if (heard.held != null) hearLater(hearing, heard)
        }
    }

    /** Count a completed window and give the next window the time the phone spent decoding this one. */
    private fun voiceWindowFinished(heard: Unsung, startedAtMs: Long): Unsung {
        val extra = (SystemClock.elapsedRealtime() - startedAtMs - VOICE_CAPTURE_MS).coerceAtLeast(0)
        val next = heard.copy(tries = heard.tries + 1, heldUntilMs = heard.heldUntilMs?.plus(extra))
        unsung = next
        if (next.heldUntilMs != heard.heldUntilMs && next.tries < VOICE_TRIES) holdUntil(next)
        return next
    }

    /** Hidden words can still belong to a song that starts singing later, e.g. after a long intro. */
    private suspend fun hearLater(hearing: SingingHearing, heard: Unsung) {
        while (true) {
            delay(VOICE_PAUSE_MS)
            if (unsung !== heard || !mayHearVoice(heard.song)) return
            val sung = withTimeoutOrNull(HELD_MS) { hearing.hear(null, null) }
            if (unsung !== heard || !mayHearVoice(heard.song)) return
            if (sung?.sings == true) return heardOut(heard, Listen.VOICE)
        }
    }

    /**
     * What a while heard, [sung], says of a voice in the song: null while it may still be music alone. Without the speech
     * model, or with no sound to hear, as from a phone turned all the way down, nothing can be heard, so it's as if it
     * couldn't be; for [held] words, so is any while heard with nothing in it, as only sound heard can say no one sings.
     */
    private fun voiceIn(hearing: SingingHearing, sung: Sung?, held: Boolean): Listen? = when {
        sung?.sings == true -> Listen.VOICE
        sung != null -> null
        held || hearing.missesModel || hearing.heardNoSound -> Listen.FAILED
        else -> null
    }

    /** The listen for a voice in [heard] came to [listen]: its held words settled, or its missing words a problem, as they always were. */
    private suspend fun heardOut(heard: Unsung, listen: Listen) {
        if (heard.held != null) {
            heldJob?.cancel()
            return settleLate(heard, listen)
        }
        unsung = null
        if (listen != Listen.NO_VOICE) voiceHeard(heard, sung = listen == Listen.VOICE)
    }

    /** Whether [song], with no words found, may be heard for a voice now: shown, played by a music app on the phone, Crosstune in sight. */
    private fun mayHearVoice(song: MusicMetadata): Boolean {
        if (shazamLeads || nextPaused) return false
        // An artwork lookup can finish while the same song's words stay open.
        if (!mayCalibrate() || uiState.lyricsFor?.copy(artworkUrl = song.artworkUrl) != song) return false
        return uiState.following?.let { it.app != null && it.clock.playing } == true
    }

    /** [heard] was heard sung, or [sung] false, couldn't be heard at all: its missing words are a problem, as they always were. */
    private fun voiceHeard(heard: Unsung, sung: Boolean) {
        if (sung && heard.likelyInstrumental) uiState = uiState.copy(lyricsInstrumental = false)
        if (sung || !heard.likelyInstrumental) problemFound(SongProblem.NO_LYRICS)
    }

    /** Hears the singing a while at a time, through the song, and puts the words where it's heard. */
    private suspend fun hearOn(hearing: SingingHearing, song: MusicMetadata, lines: List<LyricLine>) {
        // Where in the song the singing was heard up to, so the next while heard is further on.
        var heardTo: Long? = null
        pendingBeforePlay = syncStore.pending(song, lines)
        // Heard nothing to match, the words may be timed far off, so where they're timed says nothing.
        var straightOn = false
        // Whether any singing was found where the words are, while hearing on.
        var found = false
        while (true) {
            // Whether a voice was heard in this while's first seconds, before the rest of it is in.
            var voiced = false
            // Heard against the lines as they're shown, so already where they're sung, a little off at most.
            val shown = LyricsSync.adjust(lines, shownPoints, pace)
            // Heard where lines are sung, so there are words to hear.
            delay(untilSung(shown, uiState.following?.clock ?: return, heardTo, straightOn) ?: return)
            val clock = uiState.following?.clock?.takeIf { playingLinedUp() } ?: return
            singingTries++
            val sung = hearOnce(hearing, lines.map { it.text }, heardFirst(song, lines, clock, shown, heardTo == null) { voiced = true }) ?: return
            if (!stillPlays(song, lines, clock)) return
            // Nothing heard sung where the words are timed, as when they're timed into an intro, the next
            // while is heard straight on, wherever the words are timed: the singing may be well after them.
            val shift = SungAlignment.shiftMs(sung, clock::positionAt, shown)
            straightOn = shift == null
            found = foundSinging(found, sung, clock, shown, shift, voiced)
            val first = heardTo == null
            heardTo = clock.positionAt(sung.startedAtMs + SingingListener.SECONDS * 1000L)
            if (shift == null) {
                TimingLog.note("sung nothing to place, clock sure $clockSure")
                if (singingTries >= SINGING_TRIES) return
                continue
            }
            singingTries = 0
            TimingLog.note("sung $shift off, clock sure $clockSure, first $first")
            if (!clockSure) placeThisTime(song, sung, clock, shown, shift, whole = true)
            else if (placeForGood(song, lines, sung, clock, shown, shift, first)) return
        }
    }

    /**
     * The first few seconds of a while, told as soon as they're heard: lines sung in them are put where
     * they're heard at once, not once the whole while is, as words timed far off are wrong all the while.
     * On the song's [first] while, no voice where the [shown] lines are timed doubts them instead: lines
     * timed into an intro aren't shown sung all the while it's heard. But the speech model sometimes
     * hears singing as music alone, depending on just where what it hears starts, so it's only doubted
     * when the seconds heard again a little later, as asked for, have none either. On any while, a voice
     * there lifts that doubt, as soon as it's heard, and is told [voiced].
     */
    private fun heardFirst(song: MusicMetadata, lines: List<LyricLine>, clock: PlaybackClock, shown: List<LyricLine>, first: Boolean, voiced: () -> Unit): suspend (Sung) -> Boolean {
        var quiet = 0
        return heard@{ heard ->
            if (!stillPlays(song, lines, clock)) return@heard false
            val shift = SungAlignment.shiftMs(heard, clock::positionAt, shown)
            when {
                shift != null -> {
                    voiced()
                    placeHeard(song, heard, clock, shown, shift)
                    false
                }
                !noVoice(heard, clock, shown) -> {
                    voiced()
                    doubtTiming(false)
                    false
                }
                !first -> false
                // Music alone, heard once: heard again a little later before it's believed.
                ++quiet < QUIET_HEARINGS -> true
                else -> {
                    doubtTiming(true)
                    false
                }
            }
        }
    }

    /** The lines heard in [sung] put where they're sung, [shift] off as a whole, before the rest of the while is in. */
    private fun placeHeard(song: MusicMetadata, sung: Sung, clock: PlaybackClock, shown: List<LyricLine>, shift: Long) {
        doubtTiming(false)
        if (!clockSure) placeThisTime(song, sung, clock, shown, shift, whole = false)
        else placeSung(song, SungAlignment.linesMs(sung, clock::positionAt, shown, shift.takeIf { abs(it) >= SINGING_NUDGE_MS } ?: 0L))
    }

    /** What's heard sung, in the words' language, its [first] seconds told as they're heard; null when it can't be heard, as for want of the speech model, which is offered then. */
    private suspend fun hearOnce(hearing: SingingHearing, lines: List<String>, first: (suspend (Sung) -> Boolean)?): Sung? {
        val sung = hearing.hear(lyricsLanguage?.of(lines)?.substringBefore('-'), first)
        // The model isn't fetched on mobile data by itself: it's offered by hand.
        if (sung == null && hearing.missesModel && !modelClosed) uiState = uiState.copy(singingModel = ModelOffer.MISSING)
        return sung
    }

    /** Whether [song] still plays as it did by [clock], with the same words: moved, paused or gone on, what was heard can't be placed. */
    private fun stillPlays(song: MusicMetadata, lines: List<LyricLine>, clock: PlaybackClock): Boolean {
        val playing = uiState.following?.clock ?: return false
        return uiState.lyricsFor == song && foundLines == lines && playing.playing && abs(playing.positionAt(now()) - clock.positionAt(now())) <= STEADY_CLOCK_MS
    }

    /**
     * Whether singing's been found where the words are, [found] before or now, [shift] off. Till it is,
     * no voice heard where they're timed doubts them, so no line's shown sung; a voice heard there, even
     * with too few of its words made out to place them, says they're not timed into an intro after all,
     * as the speech model sometimes hears a while of singing as music alone. So does one [voiced] in
     * the while's first seconds, though the whole while is heard as music alone. A while it made out
     * nothing in at all, not even music, says nothing either way, so it leaves them as they are.
     */
    private fun foundSinging(found: Boolean, sung: Sung, clock: PlaybackClock, shown: List<LyricLine>, shift: Long?, voiced: Boolean): Boolean {
        if (found) return true
        if (sung.words.isNotEmpty()) doubtTiming(shift == null && !voiced && noVoice(sung, clock, shown))
        return shift != null
    }

    /** Whether no voice was heard in [sung] by when the first lines [shown] in it should have been sung. */
    private fun noVoice(sung: Sung, clock: PlaybackClock, shown: List<LyricLine>): Boolean {
        val voiceAt = sung.voiceAtMs?.let { clock.positionAt(sung.startedAtMs + it) } ?: return true
        val sungBy = shown.filter { it.text.isNotBlank() && it.timeMs >= clock.positionAt(sung.startedAtMs) }.getOrNull(SINGING_LINES - 1)?.timeMs
        return sungBy != null && voiceAt > sungBy
    }

    /**
     * The lines heard in [sung] put where they're sung for this time only, the app's [clock] unsure:
     * it may be off as much as the words. Words timed for another take drift further off as it goes,
     * so it's heard on through the song. Lines heard but none told where, the first is [shift] off.
     * Heard [WORDS_OFF_MS] off or more, it's the words that are off, not the clock, so what's heard
     * this time is kept, and they start where they're sung next time; still unsure, it isn't done.
     * That's a problem found too: a song is mostly picked up playing, the words opened after it started.
     * But in a music app, one while alone can be matched to the wrong lines, as a chorus heard seconds
     * from where it's sung, or a line the speech model hears over and over: words timed for another take
     * are off all through the song, so they're kept that far off only once a later while, [whole] or its
     * first seconds, is heard about as far off: the further off, the more they drift, as the take's pace
     * differs too. Till then they stay where they are, even after lines heard near. One found further off
     * than near lines are looked at moves them this time only: that's found only where by far the most
     * words heard agree, across the whole song, so it's likely right, but a chorus sung again may still be
     * timed differently there. A whole while found that far off is a problem only if the words stay
     * moved till they're left or Crosstune goes out of sight. Till another agrees, each is heard where
     * they're kept first: heard there, it puts them back, even if it fits where they're shown now too, as the next chorus may.
     */
    private fun placeThisTime(song: MusicMetadata, sung: Sung, clock: PlaybackClock, shown: List<LyricLine>, shift: Long, whole: Boolean) {
        heardThisTime = true
        val kept = LyricsSync.adjust(foundLines, syncPoints, pace)
        // Moved far this time: heard where the words are kept, that move was wrong. The whole while's undone
        // again, as it's placed against the lines shown before its first seconds put them back.
        val keptShift = if (movedFar) SungAlignment.shiftMs(sung, clock::positionAt, kept) else null
        if (keptShift != null && abs(keptShift) < WORDS_OFF_MS) {
            if (whole) {
                movedFar = false
                clearPending(song)
            }
            farReportPending = false
            farOffMs = null
            heardPoints = heardAt(sung, clock, kept, keptShift, syncPoints).second
            showPoints()
            return
        }
        val (line, heard) = heardAt(sung, clock, shown, shift, shownPoints)
        // How far off the words as found are, by this while: the lines shown may be moved already.
        val offMs = shift + shown[line].timeMs - foundLines[line].timeMs
        val fromMs = clock.positionAt(sung.startedAtMs)
        val toMs = clock.positionAt(sung.startedAtMs + SingingListener.SECONDS * 1000L)
        val current = PendingShift(offMs, minOf(fromMs, toMs), maxOf(fromMs, toMs))
        val agrees = farOffMs?.let { abs(it - offMs) < agreeingMs(offMs) } == true ||
            pendingAgrees(current, whole)
        when {
            keeps(shift, agrees) -> return keepFarOff(song, heard)
            abs(shift) >= WORDS_OFF_MS -> {
                // Remembered for the next while to agree with. Found by the far matcher alone, where by far the
                // most words heard agree, they're shown there this time; otherwise they stay where they are.
                if (whole) {
                    farOffMs = offMs
                    rememberPending(song, current)
                }
                if (abs(shift) <= SungAlignment.MAX_SHIFT_MS) return
                movedFar = true
                if (whole) reportFarIfLeftMoved()
            }
            // Near where they're shown, moved there this time by a while found far off: this one says so too,
            // even after its first seconds put them back for now, so the problem waits to be found again.
            whole -> heardNear(song, offMs)
        }
        heardPoints = heard
        showPoints()
    }

    private fun mayKeepPending() = syncPoints.isEmpty() && !keptByHand && !timedByHand

    private fun heardNear(song: MusicMetadata, offMs: Long) {
        if (!movedFar) clearPending(song)
        farOffMs = offMs.takeIf { movedFar }
        if (movedFar) reportFarIfLeftMoved()
    }

    private fun pendingAgrees(current: PendingShift, whole: Boolean): Boolean {
        if (!whole || !mayKeepPending()) return false
        val previous = pendingBeforePlay ?: return false
        return current.toMs > current.fromMs && previous.separateFrom(current) &&
            abs(previous.offsetMs - current.offsetMs) < agreeingMs(current.offsetMs)
    }

    private fun rememberPending(song: MusicMetadata, pending: PendingShift) {
        if (mayKeepPending()) {
            pendingBeforePlay = null
            syncStore.rememberPending(song, foundLines, pending)
        }
    }

    private fun clearPending(song: MusicMetadata) {
        pendingBeforePlay = null
        syncStore.clearPending(song)
    }

    /** The first line heard in [sung], [shift] off from the [lines] shown, and the points it puts them at, over [points]. Lines heard but none told where, the first is [shift] off. */
    private fun heardAt(sung: Sung, clock: PlaybackClock, lines: List<LyricLine>, shift: Long, points: List<SyncPoint>): Pair<Int, List<SyncPoint>> {
        val placed = SungAlignment.linesMs(sung, clock::positionAt, lines, shift).ifEmpty { mapOf(lines.indexOfFirst { it.timeMs + shift >= clock.positionAt(sung.startedAtMs) }.coerceAtLeast(0) to null) }
        return placed.keys.first() to placed.entries.fold(points) { kept, (at, ms) -> LyricsSync.with(kept, SyncPoint(foundLines[at].timeMs, ms ?: (lines[at].timeMs + shift))) }
    }

    /**
     * Whether a while heard [shift] from the lines shown, and [agrees] with the last one heard far off, keeps
     * them there for good. Near them, only once they were moved there this time by a while found far off.
     * Far from them, in a video, whose words are often seconds off, timed for the release without its intro,
     * one while says so, unless they were put in time by hand, which one while alone never undoes.
     */
    private fun keeps(shift: Long, agrees: Boolean) =
        if (abs(shift) < WORDS_OFF_MS) movedFar && agrees else (appFollowing?.video == true && !keptByHand) || agrees

    /** The lines [heard] far off kept so, as two whiles agree they are: the words are off, not the clock. The first time, that's a problem found, as those heard off with the clock sure are. */
    private fun keepFarOff(song: MusicMetadata, heard: List<SyncPoint>) {
        val first = syncPoints.isEmpty()
        farOffMs = null
        setSync(song, heard)
        if (first) problemFound(SongProblem.SUNG_OFF)
    }

    /**
     * How far apart two whiles may say words [offMs] off are and still agree: words timed for another take
     * drift further off through the song, the further off they are, as its pace differs too.
     */
    private fun agreeingMs(offMs: Long) = maxOf(SUNG_OFF_MS, abs(offMs) / WORDS_OFF_DRIFT)

    /**
     * The lines heard in [sung] put where they're sung for good, the app's [clock] sure; the [first]
     * heard far off is a problem found. Whether it's done: heard to the end, or as near as there's
     * enough singing left to hear.
     */
    private fun placeForGood(song: MusicMetadata, lines: List<LyricLine>, sung: Sung, clock: PlaybackClock, shown: List<LyricLine>, shift: Long, first: Boolean): Boolean {
        val wholeMs = shift.takeIf { abs(it) >= SINGING_NUDGE_MS } ?: 0L
        placeSung(song, SungAlignment.linesMs(sung, clock::positionAt, shown, wholeMs))
        if (first && abs(shift) >= SUNG_OFF_MS) problemFound(SongProblem.SUNG_OFF)
        val done = untilSung(LyricsSync.adjust(lines, shownPoints, pace), clock, clock.positionAt(sung.startedAtMs) + SingingListener.SECONDS * 1000L) == null
        liningUp = !done
        if (done) sungStore.save(song, lines)
        return done
    }

    /**
     * Each line of the words [placed] where it was heard sung, by its index, as putting it in time by
     * hand would: the lines between stretch to fit, and those past the last go on at the pace they set.
     */
    private fun placeSung(song: MusicMetadata, placed: Map<Int, Long>) {
        val shown = LyricsSync.adjust(foundLines, syncPoints, pace)
        if (placed.none { (line, ms) -> ms != shown[line].timeMs }) return
        setSync(song, placed.entries.fold(syncPoints) { kept, (line, ms) -> LyricsSync.with(kept, SyncPoint(foundLines[line].timeMs, ms)) })
    }

    /** The lines put in time: where they were heard this time, with the app's clock unsure, or else as kept. */
    private val shownPoints get() = heardPoints.ifEmpty { syncPoints }

    private fun showPoints() {
        uiState = uiState.copy(lyricLines = linesFrom(shownPoints))
    }

    /**
     * The lines found, put in time by [points] and at the words' pace, as they show. Words found with no
     * timing have no lines found: those shown were timed by ear, by the clock of what plays at the speed
     * it plays, so no point or pace moves them, and they stay as they are.
     */
    private fun linesFrom(points: List<SyncPoint>) = if (foundLines.isEmpty()) uiState.lyricLines else shown(adjustedLines(points))

    /** Only lines heard, or put in time by hand, can bring a longer take's ending into this recording. */
    private fun adjustedLines(points: List<SyncPoint>) =
        LyricsSync.adjust(foundLines, points, pace, if (keptByHand) null else appFollowing?.durationMs ?: uiState.lyricsFor?.lengthMs, found?.lengthMs)

    /** Forgets where lines were heard this time, as the app's clock is sure now, or it stopped. */
    private fun forgetHeard() {
        if (heardPoints.isEmpty()) return
        heardPoints = emptyList()
        movedFar = false
        farReportPending = false
        showPoints()
    }

    /**
     * Whether the app's [clock] plays on from where it was, or jumped: picked up, moved, or played
     * again after a pause from somewhere else. Said fresh as the song starts, it's sure; jumped
     * otherwise, it may be behind, so the singing is heard again.
     */
    private fun noteClock(clock: PlaybackClock) {
        val at = now()
        val last = lastAppClock
        lastAppClock = clock
        val starts = clock.playing && clock.positionMs <= SURE_START_MS && at - clock.atMs <= SURE_START_MS
        if (starts) {
            // Each time it's played again, the singing may be heard a few more times.
            if (!clockSure || heardPoints.isNotEmpty()) {
                heardThisTime = false
                farOffMs = null
                singingTries = 0
            }
            clockSure = true
            // The play that ended may have left the words moved far by a while alone.
            reportFarIfStillMoved()
            forgetHeard()
        } else if (last == null || abs(clock.positionAt(at) - last.positionAt(at)) > SAME_CLOCK_MS) {
            clockSure = false
            heardThisTime = false
            farOffMs = null
            singingTries = 0
        }
    }

    /**
     * Whether a music app on the phone plays the song, lined up with its words, or a video well into
     * its song with no hearing it could have, as every answer may be a part played again: the singing
     * is what's left to line it up by.
     */
    private fun playingLinedUp() = uiState.following?.let { it.app != null && it.clock.playing && (it.timingKnown || unheard || !songMayNotHaveStarted()) } == true

    /** Whether to [doubt] where the words are timed, with no singing heard there, so no line's shown sung till it's found. */
    private fun doubtTiming(doubt: Boolean) {
        if (unheard == doubt) return
        unheard = doubt
        uiState = uiState.copy(following = inSong(appFollowing) ?: uiState.following)
    }

    /** [untilSung] from where [clock] has the song now. */
    private fun untilSung(lines: List<LyricLine>, clock: PlaybackClock, after: Long?, straightOn: Boolean = false): Long? =
        untilSung(lines, clock.positionAt(now()), clock.speed, after, straightOn)

    /**
     * Whether what [app] plays may be ahead of, or behind, where the words are timed: a video, which
     * may open with more than the song, or a release not as long as the one they're timed for.
     */
    private fun needsLead(app: Following) = app.video || found?.timedFor(app.durationMs) == false

    /** Whether [song]'s lines are timed for a recording not as long as the one playing (the app's own, for a video), so likely off, and not put right yet. */
    private fun timedForAnother(song: MusicMetadata) =
        foundLines.isNotEmpty() && syncPoints.isEmpty() && handGap == null && appLeadMs == null && found?.timedFor(song.lengthMs ?: appFollowing?.durationMs) == false &&
            !(lining && alongJob?.isActive == true)

    /** Whether how far [app]'s video is ahead may still be guessed: not lined up yet, by hearings or the singing, and well into its song. */
    private fun mayGuessLead(app: Following) = app.video && appLeadMs == null && shownPoints.isEmpty() && !songMayNotHaveStarted()

    /** Takes how far [app]'s video is ahead of [song] from its last hearing, when that's a lead a video could have. */
    private fun guessLead(app: Following, song: MusicMetadata) = guessLead(app, heardClock?.takeIf { heardSong?.let { sameSong(it, song) } == true && now() - heardAtMs <= HEARD_LATELY_MS })

    private fun guessLead(app: Following, heard: PlaybackClock?) {
        val at = heard?.takeIf { it.playing && app.clock.playing } ?: return
        val from = app.clock.positionAt(at.atMs)
        (from - at.positionMs).takeIf { it in LeadTiming.possible(extraIn(app)) }?.let { lead ->
            guessedLeadMs = lead
            leadFromMs = from
            TimingLog.note("lead guessed $lead")
        }
    }

    /**
     * Where the song is in what [app] plays: its place less how far it was heard to be ahead of the
     * words. A video not yet heard, so not lined up, gives way to where the song was heard, if it was;
     * while it's being lined up, it goes by the last hearing. With no singing heard where the words
     * are timed, and none found yet, no line's shown sung.
     */
    private fun inSong(app: Following?): Following? =
        // Timed by the singing heard where the app has the song, the words go by its clock as it is.
        if (uiState.lyricsTimedByEar) app else app?.let(::leadTaken)?.let { if (unheard) it.copy(timingKnown = false) else it }

    /** [app]'s place less how far it was heard to be ahead of the words, as [inSong] has it. */
    private fun leadTaken(app: Following): Following {
        if (!app.video && appLeadMs == null) return app
        val lead = leadNow ?: return if (calibrationAttempted || shownPoints.isNotEmpty()) app.copy(timingKnown = shownPoints.isNotEmpty())
            else uiState.lyricsFor?.let(::heardFollowing) ?: app.copy(timingKnown = false)
        val speed = leadSpeed
        return app.copy(clock = app.clock.copy(positionMs = leadFromMs - lead + ((app.clock.positionMs - leadFromMs) * speed).toLong(), speed = app.clock.speed * speed))
    }

    /** [positionMs] in the song, where the app playing it has it: later, in a video ahead of it. */
    private fun inApp(positionMs: Long): Long {
        val lead = leadNow?.takeIf { appFollowing != null } ?: return positionMs
        return leadFromMs + ((positionMs - leadFromMs + lead) / leadSpeed).toLong()
    }

    /** How fast the video goes by the song, as most hearings of it say, once enough have: one's speed is well off at times, so it's their middle one. */
    private val leadSpeed: Float
        get() {
            if (leadSpeeds.size < LEAD_SPEED_HEARINGS) return 1f
            return leadSpeeds.sorted()[leadSpeeds.size / 2].takeIf { it in LEAD_SPEEDS } ?: 1f
        }

    /** Whether [leadTaken] takes the speed off [appFollowing]'s clock, which it does once it has a lead and enough hearings to tell the speed. */
    private val leadSpeedTaken: Boolean
        get() = leadNow != null && leadSpeed != 1f && appFollowing?.let { it.video || appLeadMs != null } == true

    /** How far the video is ahead: as lined up, or else as last heard. */
    private val leadNow get() = appLeadMs ?: guessedLeadMs

    fun stopListeningAlong() {
        autoCalibrating = false
        pauseListeningAlong()
        // Not lined up by ear after all, the words may be put in time by hand.
        uiState = uiState.copy(listeningAlong = false, lyricsOffTake = uiState.lyricsFor?.let(::timedForAnother) == true)
        // Shazam done with the phone's sound, the singing in it is heard.
        hearSinging()
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
        leadTiming = LeadTiming()
        gapTiming = LeadTiming()
        gapMatch = null
        calibrationAttempted = false
        leadSpeeds.clear()
        placeHeard()
        uiState = uiState.copy(following = heardFollowing(song))
        val source = playback?.takeIf { it.hasAccess() } ?: return
        viewModelScope.launchKept({ followJob = it }) {
            source.follow(song).collect { playing ->
                if (playing?.clock?.playing == true && appFollowing?.clock?.playing != true) appPlaysAgain.tryEmit(Unit)
                // The app stopped it, was closed or went on to another song: the words stop where it left
                // them, rather than run on from where the song was heard before, until it's heard again.
                val left = inSong(appFollowing)?.clock?.takeIf { playing == null }?.let { PlaybackClock(it.positionAt(now()), now(), playing = false) }
                if (left != null && heardSong?.let { sameSong(it, song) } == true) hear(left)
                appFollowing = playing
                playing?.app?.let { playedIn = it }
                playing?.let {
                    playedPackage = it.appPackage
                    playedSpeed = it.clock.speed
                }
                playing?.clock?.let(::noteClock)
                // Listening by itself only lines up what the app plays: once it plays this no more, there's
                // nothing for it, and the microphone would hear the room, as between two songs.
                if (playing == null && autoCalibrating) stopListeningAlong()
                if (playing?.let(::needsLead) == true && appLeadMs == null) {
                    // Kept for words timed otherwise, or kept wrongly, as a false answer once was, it can't be how far this one's ahead.
                    leadStore.load(song)?.takeIf { it in LeadTiming.possible(extraIn(playing)) }?.let { lead ->
                        appLeadMs = lead
                        leadFromMs = playing.clock.positionAt(now())
                        if (playing.video) leadSavedLines(song, playing.appPackage, lead)
                    }
                }
                // Heard before the app said where it is, or before the song could have started, the answer goes by now.
                if (playing?.let(::mayGuessLead) == true && guessedLeadMs == null) guessLead(playing, song)
                // Playing the release the words are timed for, it's heard too, once, to put them right on any other.
                val lines = playing?.let(::needsLead) == true && appLeadMs == null && syncPoints.isEmpty()
                if (playing != null && playing.clock.playing && (lines || learnsGap(playing)) && !calibrationAttempted && mayCalibrate()) {
                    calibrationAttempted = true
                    if (!uiState.listeningAlong) autoCalibrating = true
                    listenAlong()
                }
                // What the app plays tells whether the words are timed for it.
                uiState = uiState.copy(following = inSong(playing) ?: heardFollowing(song) ?: left?.let { Following(it, app = null, canSeek = false) }, lyricsOffTake = timedForAnother(song), lyricLines = if (shownPoints.isEmpty()) uiState.lyricLines else linesFrom(shownPoints))
                hearSinging()
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
        val queue = if (followingRadioLyrics) snapshotFlow { uiState.playingUnnamed?.confirmedQueueSong } else flowOf(null)
        viewModelScope.launchKept({ nextJob = it }) {
            combine(source.follow(song), source.nowPlayingUpdates(), queue, ::Triple).collect { (app, playing, queued) ->
                if (app?.clock?.playing == true) shownPlayed = true
                // Practice moves the music app from song to song, which the words stay out of.
                if (uiState.practice != null) return@collect
                val skips = (app?.canSkipNext == true) to (app?.canSkipPrevious == true)
                if (skips != (uiState.canSkipNext to uiState.canSkipPrevious)) uiState = uiState.copy(canSkipNext = skips.first, canSkipPrevious = skips.second)
                // An app still on the song shown, by another name, e.g. "Song - Remastered", or paused there, plays no other.
                if (!shownPlayed || app != null) return@collect
                showNextLyrics(song, playing, queued)
            }
        }
    }

    private fun showNextLyrics(song: MusicMetadata, playing: Heard.Song?, queued: MusicMetadata?) {
        if (playing != null) {
            if (sameSong(playing.metadata, song)) return
            remember(playing)
            showLyricsFor(playing.metadata, playing = true)
        } else if (queued != null) {
            if (RadioGarden.sameSong(queued, song)) return
            heardSong = null
            hear(null)
            remember(Heard.Song(queued, appleMusicId = null))
            showQueueLyrics(queued)
        }
    }

    /** Crosstune went out of sight with no words floating: the words stay on the song they show until it's back. */
    fun pauseFollowingNext() {
        reportFarIfStillMoved()
        nextPaused = true
        nextJob?.cancel()
        nextJob = null
        // Out of sight, the singing isn't heard either, until it's back.
        singingJob?.cancel()
        voiceJob?.cancel()
    }

    fun resumeFollowingNext() {
        if (!nextPaused) return
        nextPaused = false
        uiState.lyricsFor?.let(::watchNext)
        hearSinging()
    }

    /** Has the music app the words follow go on to its [next] song, or back to the one before, whose words then show. */
    fun skipSong(next: Boolean) {
        val song = uiState.lyricsFor ?: return
        if (!(if (next) uiState.canSkipNext else uiState.canSkipPrevious)) return
        shownPlayed = true
        playback?.skip(song, next)
        movedOn(uiState.following?.appPackage)
    }

    private fun stopFollowing() {
        followJob?.cancel()
        followJob = null
        singingJob?.cancel()
        singingJob = null
        voiceJob?.cancel()
        singingTries = 0
        clockSure = false
        lastAppClock = null
        forgetHeard()
        heardThisTime = false
        farOffMs = null
        liningUp = false
        appFollowing = null
        playedIn = null
        playedPackage = null
        playedSpeed = null
        appLeadMs = null
        guessedLeadMs = null
        leadMatch = null
        uiState = uiState.copy(following = null)
    }

    /** Back from Android's settings, where following music apps may have just been allowed. */
    fun refreshFollowing() {
        val access = playback?.hasAccess() == true
        if (access == uiState.canFollowApps) return
        uiState = uiState.copy(canFollowApps = access)
        uiState.lyricsFor?.takeIf { !uiState.isLoadingLyrics }?.let(::follow)
        followPlayer()
    }

    /** Whether the microphone may be used now, just asked about. */
    fun refreshMicrophone() {
        uiState = uiState.copy(microphone = microphoneAccess())
    }

    /** The list of things to fix, from the bar; one tap starts afresh there. */
    fun openFixes() {
        uiState = uiState.copy(fixes = Fixes(list = true), oneTapFailed = false)
    }

    /** The steps of [needs], one at a time, from the list or wherever they're needed. */
    fun fixSteps(needs: List<Need>) {
        uiState = uiState.copy(fixes = Fixes(list = uiState.fixes?.list == true, steps = needs))
    }

    /** On to the next thing to fix, done or skipped; after the last, back to the list, or out. */
    fun nextFix() {
        val fixes = uiState.fixes ?: return
        uiState = uiState.copy(fixes = if (fixes.at + 1 < fixes.steps.size) fixes.copy(at = fixes.at + 1) else fixes.listed())
    }

    /** Out of the steps, back to the list they came from, if they did. */
    fun fixBack() {
        uiState = uiState.copy(fixes = uiState.fixes?.listed())
    }

    private fun Fixes.listed(): Fixes? = Fixes(list = true).takeIf { list }

    fun closeFixes() {
        uiState = uiState.copy(fixes = null)
    }

    /**
     * Before Android 12, which can't tell, a test link to [host] reached Crosstune: its links open
     * here, so the app of the service the link is of doesn't take them.
     */
    fun linkTested(host: String?) {
        val service = LinkInterception.HOSTS.keys.firstOrNull { host == testHost(it) }
        val apps = uiState.installedSourceApps.filter { service != null && MusicService.ofPackage(it.packageName) == service }
        uiState = uiState.copy(linksTested = uiState.linksTested + Need.Links.key + apps.map { Need.StopApp(it).key })
    }

    fun selectPalette(palette: Palette) {
        preferences.edit { putString(KEY_PALETTE, palette.name) }
        uiState = uiState.copy(palette = palette)
    }

    fun setPureBlack(on: Boolean) {
        preferences.edit { putBoolean(KEY_PURE_BLACK, on) }
        uiState = uiState.copy(pureBlack = on)
    }

    fun setPlayingBar(on: Boolean) {
        preferences.edit { putBoolean(KEY_PLAYING_BAR, on) }
        uiState = uiState.copy(playingBar = on)
    }

    fun setLinesEarly(ms: Long) {
        preferences.edit { putLong(KEY_LINES_EARLY, ms) }
        uiState = uiState.copy(linesEarlyMs = ms)
    }

    /**
     * How far ahead the next line brightens for one who hasn't set it since it could be set. Before, it
     * was only on or off: on is the second it was then, off stays off, and never touched gets the default.
     */
    private fun nextLineAheadBefore(): Long = when {
        !preferences.contains(KEY_NEXT_LINE_FIRST) -> NEXT_LINE_AHEAD_DEFAULT_MS
        preferences.getBoolean(KEY_NEXT_LINE_FIRST, false) -> NEXT_LINE_AHEAD_MS
        else -> 0
    }

    fun setNextLineAhead(ms: Long) {
        preferences.edit { putLong(KEY_NEXT_LINE_AHEAD, ms) }
        uiState = uiState.copy(nextLineAheadMs = ms)
    }

    /** Kept only once let go, [save], rather than at every step of the slider. */
    fun setVisualsShade(shade: Float, save: Boolean) {
        if (save) preferences.edit { putFloat(KEY_VISUALS_SHADE, shade) }
        uiState = uiState.copy(visualsShade = shade)
    }

    fun nextVisual() {
        uiState = uiState.copy(visualsSkips = uiState.visualsSkips + 1)
    }

    /** Never shows the visual showing again, and moves on. */
    fun dropVisual() {
        uiState = uiState.copy(visualsDrops = uiState.visualsDrops + 1)
    }

    /** Visual [name] on or off, as Settings lists them. */
    fun setVisualOn(name: String, on: Boolean) = visualsStore.turn(name, on)

    fun setSkipHeavyVisuals(on: Boolean) = visualsStore.skipHeavy(on)

    fun setVisualsChangeEvery(ms: Long) = visualsStore.changeEvery(ms)

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
        if (uiState.following?.let { it.canSeek && it.timingKnown } != true) return
        playback?.seekTo(song, inApp(positionMs))
        movedOn(uiState.following?.appPackage)
    }

    /**
     * Line [index] is being sung right now, the user says: it's put there, and the lines around it
     * move with it. Only while something says where the song is.
     */
    fun syncLine(index: Int) {
        val song = uiState.lyricsFor ?: return
        val line = foundLines.getOrNull(index) ?: return
        val position = uiState.following?.clock?.positionAt(now()) ?: return
        handTimed(song)
        val point = SyncPoint(line.timeMs, position.coerceAtLeast(0L))
        val hand = handGap
        if (syncPoints.isEmpty() && (hand == null || hand.lineMs == line.timeMs) && moveGap(song, point.songMs - point.lineMs, line.timeMs)) return
        // Another line put in time after the gap moved stretches the words from the one that moved it, now in place.
        setSync(song, LyricsSync.with(if (syncPoints.isEmpty() && hand != null) listOf(SyncPoint(hand.lineMs, hand.lineMs)) else syncPoints, point))
    }

    /** Plays or pauses the song in the music app the words follow. */
    fun playPause() {
        val song = uiState.lyricsFor ?: return
        val app = uiState.following?.takeIf { it.app != null } ?: return
        playback?.playPause(song, !app.clock.playing)
    }

    /** All the words [deltaMs] later in the song (earlier, when it's negative), for a song heard a little off. */
    fun nudgeSync(deltaMs: Long) {
        uiState.lyricsFor?.takeIf { foundLines.isNotEmpty() }?.let(::handTimed)
        nudge(deltaMs)
    }

    /** The words being put in time by hand: offered to be reported by itself now, while they're seen, but sent once they're left. */
    private fun handTimed(song: MusicMetadata) {
        timedByHand = true
        // Kept so with the words, as the points or Shazam's gap moved are, past leaving them.
        keptByHand = true
        syncStore.save(song, foundLines, withGuess(syncPoints), wordsPace, byHand = true)
        // Put in time by hand, the singing isn't heard to put them in time too.
        liningUp = false
        singingJob?.cancel()
        if (autoReports.allowed == null) problemFound(SongProblem.TIMED_BY_HAND)
    }

    private fun nudge(deltaMs: Long) {
        val song = uiState.lyricsFor ?: return
        val first = foundLines.firstOrNull() ?: return
        if (syncPoints.isEmpty() && moveGap(song, deltaMs, handGap?.lineMs ?: first.timeMs)) return
        setSync(song, if (syncPoints.isEmpty()) listOf(SyncPoint(first.timeMs, first.timeMs + deltaMs)) else syncPoints.map { it.copy(songMs = it.songMs + deltaMs) })
    }

    /** A report on the words shown, [song]'s: how they're timed, and each line put in time by hand. */
    private fun reportOn(sender: ReportSender, song: MusicMetadata?, comment: String = "", problem: SongProblem? = null): Report {
        // Corrections heard by ear belong in the timing log, without the text of the lines.
        val byHand = if (problem == SongProblem.SUNG_OFF) emptyList() else handTiming()
        return Report(
            sender.version, song, timed = uiState.lyricLines.isNotEmpty() && !uiState.lyricsTimedByEar, synced = uiState.lyricsSynced, app = timingAtCorrection?.takeIf { timedByHand }?.first ?: playedIn, comment = comment,
            sung = song != null && sungStore.heard(song, foundLines), lengthMs = found?.lengthMs, timing = byHand, problem = problem, source = found?.source,
            player = if (byHand.isNotEmpty() || problem?.aboutTiming == true) playerTiming() else null
        )
    }

    /**
     * Why the words may be off, for a report about their timing: the app that was followed, how fast it
     * played, how far ahead the words were shown, and the timing log. Put in time by hand, the log is
     * as it was at the last line moved, since the words are often left, and reported, a song later.
     */
    private fun playerTiming(): PlayerTiming = timingAtCorrection?.takeIf { timedByHand }?.second ?: currentPlayerTiming()

    /**
     * The player's timing now, with the lead [leadTaken] goes by: none (zero) for an app followed on its
     * own clock, as an audio player is until a lead is heard. With no lead to say, the log starts by
     * saying why, so a report never leaves it to be guessed.
     */
    private fun currentPlayerTiming(): PlayerTiming {
        val app = appFollowing
        val lead = if (app != null && !app.video && appLeadMs == null) 0L else leadNow.takeIf { app != null }
        val reason = when {
            app == null -> "no player followed"
            lead == null -> "video not calibrated"
            else -> null
        }
        val log = TimingLog.lastMinute()
        return PlayerTiming(playedPackage, playedSpeed, lead, uiState.linesEarlyMs, if (reason == null) log else "lead unavailable: $reason\n$log")
    }

    /** The timing log as it was when the words were last put in time by hand. */
    private var timingAtCorrection: Pair<String?, PlayerTiming>? = null

    private sealed interface ManualReportDraft {
        data class Song(val target: String?, val comment: String, val report: Report) : ManualReportDraft
        data class Reading(val input: ReadingReport, val report: ReadingReport) : ManualReportDraft
    }

    private var manualReport: ManualReportDraft? = null
    private var manualReportJob: Job? = null

    /** Sends a report on the words shown, with what the user says is wrong. [UiState.report] says how it goes. */
    fun sendReport(comment: String) {
        val sender = reports ?: return
        if (uiState.report == ReportStatus.SENDING) return
        val target = uiState.lyricsFor?.let(RecordingKey::of)
        val draft = (manualReport as? ManualReportDraft.Song)?.takeIf { it.target == target && it.comment == comment }
            ?: ManualReportDraft.Song(target, comment, reportOn(sender, uiState.lyricsFor, comment).copy(id = java.util.UUID.randomUUID().toString()))
        manualReport = draft
        uiState = uiState.copy(report = ReportStatus.SENDING)
        val previous = manualReportJob
        viewModelScope.launchKept({ manualReportJob = it }) {
            previous?.join()
            val delivery = sender.send(draft.report)
            if (manualReport === draft) uiState = uiState.copy(report = delivery.manualStatus)
        }
    }

    /**
     * Sends that the reading shown for [word], in line [index], is wrong, with the [right] one when the
     * user says it: only that, the line and the song. [UiState.report] says how it goes.
     */
    fun reportReading(word: Word, index: Int, right: String) {
        val sender = reports ?: return
        if (uiState.report == ReportStatus.SENDING) return
        val song = uiState.lyricsFor ?: return
        val script = uiState.learning.script ?: return
        val line = wordLines().getOrNull(index) ?: return
        val input = ReadingReport(sender.version, song, found?.source, line, word, word.shown(uiState.learning.romajiTyped), script, right)
        val draft = (manualReport as? ManualReportDraft.Reading)?.takeIf { it.input == input }
            ?: ManualReportDraft.Reading(input, input.copy(id = java.util.UUID.randomUUID().toString()))
        manualReport = draft
        uiState = uiState.copy(report = ReportStatus.SENDING)
        val previous = manualReportJob
        viewModelScope.launchKept({ manualReportJob = it }) {
            previous?.join()
            val delivery = sender.send(draft.report)
            if (manualReport === draft) uiState = uiState.copy(report = delivery.manualStatus)
        }
    }

    fun closeReport() {
        manualReport = null
        uiState = uiState.copy(report = null)
    }

    /** Whether the words shown were put in time by hand, rather than moved to the singing heard. */
    private var timedByHand = false

    /** Whether the timing kept for the words shown was put in time by hand, this time or before; one while alone never undoes it. */
    private var keptByHand = false

    /**
     * [problem] found with the words shown: reported by itself, once a song, when the user lets it;
     * offered to be from now on, while they haven't said.
     */
    private fun problemFound(problem: SongProblem) {
        val song = uiState.lyricsFor ?: return
        when (autoReports.allowed) {
            true -> reportProblem(song, problem)
            null -> {
                if (problem == SongProblem.SUNG_OFF && !autoReports.reported(song, problem)) keepFarReport(song)
                offerToReport(problem)
            }
            false -> Unit
        }
    }

    /**
     * The user is asked once an app session, so a problem found when it was asked already waits for the
     * next. Another found while the offer is on screen goes with it, and Yes sends every one it's for.
     */
    private fun offerToReport(problem: SongProblem) {
        if (uiState.reportOffer) {
            uiState = uiState.copy(reportOfferFor = uiState.reportOfferFor + problem)
            return
        }
        if (!autoReports.mayAsk()) return
        autoReports.asked()
        uiState = uiState.copy(reportOfferFor = setOf(problem))
    }

    private fun reportProblem(song: MusicMetadata, problem: SongProblem) {
        val sender = reports ?: return
        if (autoReports.reported(song, problem)) return
        val report = reportOn(sender, song, problem = problem).copy(id = autoReports.idFor(song, problem))
        // On the phone at once, as it is so far, so nothing that ends the work below loses it: it goes at the next start.
        autoReports.owe(song, problem, report.json())
        viewModelScope.launch {
            // Found before it's followed, or before following it said anything, as a song with no words always is.
            val playing = if (report.app == null) playback?.takeIf { it.hasAccess() }?.follow(song)?.first() else null
            val player = report.player?.let { it.copy(appPackage = it.appPackage ?: playing?.appPackage, speed = it.speed ?: playing?.clock?.speed) }
            val json = withContext(systemDispatcher) { report.copy(app = report.app ?: playing?.app, player = player).json() }
            // In place of the one kept above, with its id, then sent from there.
            deliver(sender, autoReports.owe(song, problem, json))
        }
    }

    /**
     * [owed], kept on the phone, goes now, and how it went is null where it didn't go: past the day's share, it waits
     * for a later day, and one gone another way, or on its way already, isn't sent twice. Once on its way, it's seen
     * through, whatever cancels the rest: a report the box got is never kept to go again, and one that didn't go
     * takes no place of the day.
     */
    private suspend fun deliver(sender: ReportSender, owed: AutoReports.Owed): Delivery? {
        val going = autoReports.take(owed) ?: return null
        return withContext(NonCancellable) {
            var delivery: Delivery? = null
            try {
                sender.send(owed.json).also { delivery = it }
            } finally {
                autoReports.went(going, delivery)
            }
        }
    }

    /**
     * The words shown are left: put in time by hand, they're reported, so with the timing settled;
     * an offer made for them goes. Returns whether to check owed reports after the screen changes.
     */
    private fun leaveWords(): Boolean {
        val sendAfterLeaving = !timedByHand && autoReports.allowed == true && uiState.lyricsFor != null
        // An offer for words being closed would vanish unseen, using up this session's one offer.
        reportFarIfStillMoved()
        if (autoReports.allowed == null && SongProblem.SUNG_OFF in uiState.reportOfferFor &&
            uiState.lyricsFor?.let { autoReports.owes(it, SongProblem.SUNG_OFF) } == true
        ) autoReports.offerInterrupted()
        closeReport()
        if (timedByHand && (syncPoints.isNotEmpty() || handGap != null) && autoReports.allowed == true) {
            uiState.lyricsFor?.let { reportProblem(it, SongProblem.TIMED_BY_HAND) }
        }
        timedByHand = false
        uiState = uiState.copy(reportOfferFor = emptySet())
        return sendAfterLeaving
    }

    /** A whole while found the words far off: with no correction saved, it's a problem if they're left moved. */
    private fun reportFarIfLeftMoved() {
        if (syncPoints.isEmpty()) farReportPending = true
    }

    /** No more of this play will be heard: a lone far match is reported only if nothing put the words back. */
    private fun reportFarIfStillMoved() {
        if (farReportIsDue()) problemFound(SongProblem.SUNG_OFF)
        farReportPending = false
    }

    /** Keep the original report until consent is decided, even if the screen later closes. */
    private fun keepFarReport(song: MusicMetadata) {
        val sender = reports ?: return
        val report = reportOn(sender, song, problem = SongProblem.SUNG_OFF).copy(id = autoReports.idFor(song, SongProblem.SUNG_OFF))
        autoReports.owe(song, SongProblem.SUNG_OFF, report.json())
    }

    private fun farReportIsDue() = farReportPending && movedFar && syncPoints.isEmpty()

    /**
     * Words put in time by hand are reported once they're left, so a report on them is kept owed, whatever the
     * user said about reports: it stays on the phone. Android may end the app before the words are left, and
     * the yes may come after.
     */
    private fun keepOwed() {
        val song = uiState.lyricsFor ?: return
        val sender = reports ?: return
        if (!timedByHand) return
        val report = reportOn(sender, song, problem = SongProblem.TIMED_BY_HAND).copy(id = autoReports.idFor(song, SongProblem.TIMED_BY_HAND))
        autoReports.owe(song, SongProblem.TIMED_BY_HAND, report.json())
    }

    /**
     * Each report owed goes, once a song, and is dropped when it got there or the box turned it away; one that
     * didn't get there waits for the next time, and a problem found past the day's share for a later day.
     * Words still in front of the user are left out: they may be put in time some more, and go once they're left.
     */
    private fun sendOwed() {
        val sender = reports ?: return
        if (autoReports.allowed != true) return
        viewModelScope.launch {
            autoReports.sending.withLock {
                for (owed in autoReports.owed()) {
                    // Lyrics can open or reopen while another report is sent or paced.
                    val inFront = uiState.lyricsFor?.let(autoReports::owedKeys).orEmpty()
                    if (owed.key in inFront) continue
                    // Past the day's share, or gone another way meanwhile, as the words it's for were left: it's skipped.
                    val delivery = deliver(sender, owed) ?: continue
                    // One that doesn't get there stops the rest, which wait for the next time, as they would fail too.
                    if (delivery == Delivery.FAILED || delivery == Delivery.RATE_LIMITED) break
                    // Paced under the box's 5 a minute from one phone, with room left for what's found meanwhile.
                    delay(reportPaceMs)
                }
            }
        }
    }

    /** Fetches the speech model offered, on any network, then hears the singing again with it. */
    fun fetchSingingModel() {
        val hearing = singing ?: return
        uiState = uiState.copy(singingModel = ModelOffer.FETCHING)
        viewModelScope.launch {
            val fetched = hearing.fetchModel()
            uiState = uiState.copy(singingModel = if (fetched) null else ModelOffer.FAILED)
            if (fetched) {
                singingTries = 0
                hearSinging()
            }
        }
    }

    /** The speech model isn't wanted for now. */
    fun closeSingingModel() {
        modelClosed = true
        uiState = uiState.copy(singingModel = null)
    }

    /** Problems found from now on are reported by itself, or never, and those offered for go too. */
    fun allowAutoReports(allowed: Boolean) {
        autoReports.allowed = allowed
        val offered = uiState.reportOfferFor
        uiState = uiState.copy(autoReports = allowed, reportOfferFor = emptySet())
        if (!allowed) return
        // The words put in time by hand go once they're left; those of other songs, put in time before, go now.
        (offered - SongProblem.TIMED_BY_HAND).filterNot { problem ->
            uiState.lyricsFor?.let { autoReports.owes(it, problem) } == true
        }.forEach(::problemFound)
        sendOwed()
    }

    /**
     * The offer closed for now, not as a no: it isn't made again this app session, and the third close
     * is a no for good. Settings still has the switch.
     */
    fun closeAutoReportOffer() {
        autoReports.closed()
        uiState = uiState.copy(autoReports = autoReports.allowed, reportOfferFor = emptySet())
    }

    /**
     * Each line put in time by hand, and where it's heard: those the words moved from, and the one
     * that moved Shazam's gap, by as much as it moved.
     */
    private fun handTiming(): List<HandTiming> {
        fun line(ms: Long) = foundLines.firstOrNull { it.timeMs == ms }?.text.orEmpty()
        val gap = handGap?.let { hand ->
            val now = wordsMs?.let { gapStore.load(it, hand.match) } ?: return@let null
            HandTiming(line(hand.lineMs), hand.lineMs, hand.lineMs + (hand.gapMs ?: 0L) - now)
        }
        return syncPoints.map { HandTiming(line(it.lineMs), it.lineMs, it.songMs) } + listOfNotNull(gap)
    }

    /** The words back in time as they were found, and Shazam's gap as it was before they were moved by hand. */
    fun resetSync() {
        val song = uiState.lyricsFor ?: return
        timedByHand = false
        autoReports.owedNothing(song)
        // Undone while the singing was being heard through the song, it isn't heard on.
        if (liningUp) {
            liningUp = false
            singingJob?.cancel()
            sungStore.save(song, foundLines)
        }
        handGap?.let { hand ->
            handGap = null
            wordsMs?.let { words -> hand.gapMs?.let { gapStore.save(song, words, hand.match, it) } ?: gapStore.forget(words, hand.match) }
            hand.leadMs?.let { moveLead(song, it) }
            placeHeard()
        }
        setSync(song, emptyList())
    }

    /**
     * The words heard by Shazam put [shiftMs] later, by hand: it's how far Shazam's place in the
     * recording it matched is from them, so it's learnt as that recording's gap, for every release
     * Shazam matches to it, rather than kept for this one alone. False when they're not lined up by
     * Shazam, or the gap would be too far to believe.
     */
    private fun moveGap(song: MusicMetadata, shiftMs: Long, lineMs: Long): Boolean {
        val words = wordsMs ?: return false
        val app = appFollowing
        // Words following a music app playing the release they're timed for are off themselves, not Shazam.
        val match = (if (app == null) heardMatch?.takeIf { heardClock != null } else leadMatch?.takeIf { needsLead(app) && appLeadMs != null }) ?: return false
        val before = gapStore.load(words, match)
        val gap = (before ?: 0L) - shiftMs
        if (abs(gap) > MAX_GAP_MS) return false
        if (handGap == null) handGap = HandGap(match, before, appLeadMs.takeIf { app != null }, lineMs)
        gapStore.save(song, words, match, gap)
        TimingLog.note("gap moved by hand to $gap")
        timingAtCorrection = playedIn to currentPlayerTiming()
        appLeadMs?.takeIf { app != null }?.let { moveLead(song, it + shiftMs) }
        placeHeard()
        uiState = uiState.copy(lyricsSynced = true, lyricsOffTake = false)
        keepInTime()
        keepOwed()
        return true
    }

    /** The music app's video put [leadMs] ahead of the words, and kept so, for it and the lines saved from it. */
    private fun moveLead(song: MusicMetadata, leadMs: Long) {
        // Moved by as much as the words were, from where the video's speed is already taken from.
        appLeadMs = leadMs
        leadStore.save(song, leadMs)
        appFollowing?.takeIf { it.video }?.let { leadSavedLines(song, it.appPackage, leadMs) }
        // Hearings from before were taken with the gap as it was.
        leadTiming = LeadTiming()
    }

    private fun setSync(song: MusicMetadata, points: List<SyncPoint>) {
        clearPending(song)
        syncPoints = points
        TimingLog.note("lines in time ${points.takeLast(MAX_NOTED_POINTS).joinToString(" ") { "${it.lineMs}>${it.songMs}" }}")
        if (timedByHand) timingAtCorrection = playedIn to currentPlayerTiming()
        // Put in time by hand or for good, what was heard this time only gives way.
        heardPoints = emptyList()
        movedFar = false
        farReportPending = false
        // Kept for the words as found, which is how they're found next time. Put in time by the singing, they no longer are by hand.
        keptByHand = timedByHand
        syncStore.save(song, foundLines, withGuess(points), wordsPace, keptByHand)
        uiState = uiState.copy(lyricLines = linesFrom(points), lyricsSynced = points.isNotEmpty(), lyricsOffTake = false)
        keepInTime()
        // Lines kept to practise play from where they're heard now.
        timeSavedLines(song, again = true)
        keepOwed()
    }

    /** [points] put in time from a video's guessed lead, as they are without it: where they're kept, as next time nothing's heard yet. */
    private fun withGuess(points: List<SyncPoint>): List<SyncPoint> {
        if (guessedLeadMs == null || appLeadMs != null) return points
        return points.map { it.copy(songMs = inApp(it.songMs)) }
    }

    private var restoreJob: Job? = null

    /**
     * Has ChatGPT write [song]'s words, found in Latin letters, in [script], Japanese or Korean, each
     * line timed as found; then they're worked out again in it, with readings of their own.
     */
    private fun writeInOwnScript(song: MusicMetadata, script: Script) {
        val lines = wordLines()
        viewModelScope.launchKept({ restoreJob = it }) {
            val written = tutor?.fromRomaji(song, lines, script) ?: return@launchKept
            if (uiState.lyricsFor != song || wordLines() != lines) return@launchKept
            restored = written
            stopLearning()
            uiState = uiState.copy(
                lyrics = written.joinToString("\n"),
                lyricLines = shown(uiState.lyricLines),
                learning = uiState.learning.fresh(Readings.displayScriptOf(written)).copy(fromRomaji = true),
                chords = uiState.chords.fresh()
            )
            followSavedLines(song)
            learn()
            chords()
        }
    }

    /** Crosstune no longer covers the app playing, closed or back in sight itself. */
    fun uncover() {
        uiState = uiState.copy(coveringApp = false)
    }

    /**
     * Closes the words. The next song starts without them, rather than showing the last one's, and
     * what was heard is forgotten, so words opened later don't listen along to what plays by then.
     */
    /**
     * From the words down to their song's player: the one under them, or one opened on their song when
     * they were opened on their own, over that song's card, as the words may have gone on to another.
     */
    fun lyricsToPlayer() {
        val song = uiState.lyricsFor ?: return
        if (uiState.player == null) {
            openPlayer(song, uiState.following?.appPackage, played = shownPlayed)
            if (uiState.result?.let { sameSong(it, song) } != true) showSong(song)
        }
        dismissLyrics()
    }

    fun dismissLyrics() {
        followingRadioLyrics = false
        heardSong = null
        // Words held for a listen are closed with the rest: nothing shows them, or looks them up, once they're gone.
        heldJob?.cancel()
        unsung = null
        val sendAfterLeaving = leaveWords()
        keepShown()
        previewHeardLyrics()
        lyricsJob?.cancel()
        lyricsJob = null
        nextJob?.cancel()
        nextJob = null
        shownPlayed = false
        uiState = uiState.copy(canSkipNext = false, canSkipPrevious = false)
        stopLearning()
        stopFollowing()
        stopListeningAlong()
        uiState = uiState.copy(
            lyricsFor = null, lyrics = "", lyricLines = emptyList(), lyricsSynced = false, lyricsTimedByEar = false, lyricsOffTake = false, lyricsFailed = false, lyricsInstrumental = false, isLoadingLyrics = false,
            learning = uiState.learning.fresh(), chords = uiState.chords.fresh()
        )
        if (sendAfterLeaving) sendOwed()
    }

    /** Give the player its preview once the user returns from lyrics and a listen confirmed the singing. */
    private fun previewHeardLyrics() {
        val shown = uiState.lyricsFor ?: return
        if (found?.markedInstrumental != true || lyricsCanPreview != true) return
        val song = uiState.player?.song ?: return
        if (RecordingKey.of(asPlayed(song)) == RecordingKey.of(shown)) {
            showPeek(song, uiState.lyricLines, uiState.lyrics, uiState.lyricsTimedByEar)
        }
    }

    private var readingsJob: Job? = null
    private var translationJob: Job? = null

    /** What was being worked out is for words no longer shown. */
    private fun stopLearning() {
        chordsJob?.cancel()
        readingsJob?.cancel()
        translationJob?.cancel()
        restoreJob?.cancel()
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
     * Line [index] of the words shown, as it's kept: with what helps read it, where it's sung,
     * where the next line starts, and the app playing it, to play it again.
     */
    private fun savedLineAt(index: Int): SavedLine? {
        val song = uiState.lyricsFor ?: return null
        val text = wordLines().getOrNull(index) ?: return null
        val learning = uiState.learning
        return SavedLine(
            text,
            translation = learning.translations.getOrNull(index),
            title = song.title,
            artist = song.artist,
            explanation = learning.explanation?.takeIf { it.index == index && it.done }?.text
        ).readBy(learning.lines.getOrNull(index)).timedAt(index)
    }

    /** This line, timed as line [index] of the timed words shown, when they are, with the app playing them. */
    private fun SavedLine.timedAt(index: Int): SavedLine {
        val lines = uiState.lyricLines
        val start = lines.getOrNull(index)?.takeIf { it.heard }?.timeMs ?: return copy(artworkUrl = uiState.lyricsFor?.artworkUrl)
        // Timed again, it stays with the app it was kept from, when none follows the words now.
        val app = appFollowing
        return copy(
            startMs = start, endMs = endOf(lines, index), nextMs = nextStart(lines, index), lengthMs = recordingLength(),
            leadMs = appLeadMs?.takeIf { app?.video == true } ?: leadMs.takeIf { app == null } ?: 0L,
            app = app?.appPackage ?: this.app, artworkUrl = uiState.lyricsFor?.artworkUrl ?: artworkUrl
        )
    }

    /**
     * Where line [index] of [lines] ends: where the words say, when they're timed that closely, or
     * else where the next line starts; the last line, with no next one, is given a few seconds.
     */
    private fun endOf(lines: List<LyricLine>, index: Int): Long {
        val line = lines[index]
        return line.endMs ?: nextStart(lines, index) ?: (line.timeMs + LAST_LINE_MS)
    }

    /** Where the line after [index] starts, when one starts later than this one. */
    private fun nextStart(lines: List<LyricLine>, index: Int): Long? {
        val line = lines[index]
        return lines.drop(index + 1).firstOrNull { it.heard && it.timeMs > line.timeMs }?.timeMs
    }

    /**
     * How long the recording these words were timed on lasts. The words say, when they do. Otherwise
     * the app that was playing them, when it says a real length. 0 is not one.
     */
    private fun recordingLength(): Long? = found?.lengthMs?.takeIf { it > 0 } ?: appFollowing?.durationMs?.takeIf { it > 0 }

    /**
     * Lines of [song] kept are read as its lines are now, put right, but for what the user put right in
     * them, and those that can't tell that apart; only those with no reading yet when [unread].
     */
    private suspend fun rereadSavedLines(song: MusicMetadata, unread: Boolean = false) {
        val lines = wordLines()
        val again = uiState.savedLines.filter { it.isOf(song) && it.line == null && it.rereadable && (!unread || it.ruby == null) }
            .mapNotNull { saved -> lines.indexOf(saved.text).takeIf { it >= 0 }?.let(::savedLineAt) }
        val script = uiState.learning.script
        if (again.isEmpty() || script == null) return
        savedLinesStore.change(systemDispatcher) { kept -> rereadAs(kept, again, script) }?.let { uiState = uiState.copy(savedLines = it) }
    }

    /**
     * Lines of [song] kept from its words as they were, before they changed, e.g. found again on another
     * site, are kept as the lines they are now, practised as far as they had been: read and translated as
     * these are, and where these are sung, with what was put right in them. One kept already as it is now is left as it was.
     */
    private fun followSavedLines(song: MusicMetadata) {
        val lines = wordLines()
        val times = uiState.lyricLines.takeIf { it.isNotEmpty() }?.map { it.timeMs }
        val kept = uiState.savedLines.filter { it.isOf(song) }
        val taken = kept.mapTo(mutableSetOf()) { it.text }
        val changes = kept.mapNotNull { saved ->
            val was = saved.line ?: saved.text
            if (was in lines) return@mapNotNull null
            val index = lineNow(was, saved.startMs, lines, times).takeIf { it >= 0 } ?: return@mapNotNull null
            val now = lines[index]
            // A word kept stays with its line, while it's still sung in it.
            if (saved.line != null) return@mapNotNull (saved to saved.copy(line = now)).takeIf { saved.text in now }
            if (!taken.add(now)) return@mapNotNull null
            val read = savedLineAt(index) ?: return@mapNotNull null
            val moved = saved.copy(text = now, reading = read.reading, romanized = read.romanized, ruby = read.ruby, readByHand = false, typed = read.typed, readWith = read.readWith, translation = read.translation ?: saved.translation)
            saved to if (times != null) moved.timedAt(index) else moved
        }
        if (changes.isEmpty()) return
        // Move the words and their corrections together. Reading them may need the dictionary, away from the screen.
        val script = uiState.learning.script
        viewModelScope.launch {
            savedLineUpdates.withLock {
                savedLinesStore.change(systemDispatcher) { current ->
                    followedSavedLines(current, changes, song, script)
                }?.let { uiState = uiState.copy(savedLines = it) }
            }
        }
    }

    private fun followedSavedLines(current: List<SavedLine>, changes: List<Pair<SavedLine, SavedLine>>, song: MusicMetadata, script: Script?): List<SavedLine> {
        // Another save can finish while the readings are worked out. Keep its destination and edits.
        val occupied = current.filter { it.isOf(song) }.mapTo(mutableSetOf()) { it.text }
        return current.map { saved ->
            val change = changes.firstOrNull { it.first.isSame(saved) }
            if (change == null) saved else {
                val (was, moved) = change
                if (saved.line != null) saved.copy(line = if (saved.line == was.line) moved.line else saved.line)
                else if (!occupied.add(moved.text)) saved
                else movedSavedLine(saved, was, moved, script)
            }
        }
    }

    private fun movedSavedLine(saved: SavedLine, was: SavedLine, moved: SavedLine, script: Script?): SavedLine {
        // Bounds are one edit: mixing an old end with a newer start can reverse the window.
        val timing = if (saved.startMs == was.startMs && saved.endMs == was.endMs && saved.nextMs == was.nextMs) moved else saved
        val replacement = saved.copy(
            text = moved.text, reading = moved.reading, romanized = moved.romanized, ruby = moved.ruby,
            readByHand = false, typed = moved.typed, readWith = moved.readWith,
            translation = if (saved.translation == was.translation) moved.translation else saved.translation,
            startMs = timing.startMs, endMs = timing.endMs, nextMs = timing.nextMs,
            lengthMs = if (saved.lengthMs == was.lengthMs) moved.lengthMs else saved.lengthMs,
            leadMs = if (saved.leadMs == was.leadMs) moved.leadMs else saved.leadMs,
            app = if (saved.app == was.app) moved.app else saved.app,
            artworkUrl = if (saved.artworkUrl == was.artworkUrl) moved.artworkUrl else saved.artworkUrl
        )
        return if (script == null) replacement else replacement.carrying(saved, script)
    }

    /** Lines of [song] kept are translated as its lines are now, e.g. into another language. */
    private fun retranslateSavedLines(song: MusicMetadata) {
        val lines = wordLines()
        val translations = uiState.learning.translations
        // Keep this result even if the user closes the words while a move finishes.
        viewModelScope.launch {
            savedLineUpdates.withLock {
                val changes = savedLinesStore.load().filter { it.isOf(song) && it.line == null }.mapNotNull { saved ->
                    val now = translations.getOrNull(lines.indexOf(saved.text))?.takeIf { it != saved.translation } ?: return@mapNotNull null
                    saved to saved.copy(translation = now)
                }
                if (changes.isNotEmpty()) uiState = uiState.copy(savedLines = savedLinesStore.replaceAll(changes))
            }
        }
    }

    /**
     * Lines of [song] kept from the video [app] plays, before its intro was known, start where the song
     * does in it now that it is, so practice plays them, not the intro.
     */
    private fun leadSavedLines(song: MusicMetadata, app: String?, lead: Long) {
        val off = uiState.savedLines.filter { it.isOf(song) && it.timed && it.app == app && it.leadMs != lead }
        if (off.isNotEmpty()) uiState = uiState.copy(savedLines = savedLinesStore.replaceAll(off.map { it to it.copy(leadMs = lead) }))
    }

    /**
     * Lines of [song] kept before they could be timed are timed now, by the timed words found for it;
     * [again], all its lines are, as when the words were put in time by hand.
     */
    private fun timeSavedLines(song: MusicMetadata, again: Boolean = false) {
        val lines = uiState.lyricLines.takeIf { it.isNotEmpty() } ?: return
        val untimed = uiState.savedLines.filter { it.isOf(song) && (again || !it.timed) }
        untimed.forEach { saved ->
            val index = lines.indexOfFirst { it.text == (saved.line ?: saved.text) }.takeIf { it >= 0 && lines[it].heard } ?: return@forEach
            uiState = uiState.copy(savedLines = savedLinesStore.timed(saved.timedAt(index), again))
        }
    }

    /**
     * Practises the lines due, as cards, each song's together; or only [song]'s, all of them when none
     * is due, to learn it. What the music app plays now is kept, to go back to once done.
     */
    fun startPractice(song: MusicMetadata? = null) {
        stopRepeating()
        // The song the words follow, in its app; or else whatever plays, which the last app played has.
        val playing = uiState.following?.takeIf { it.clock.playing && it.appPackage != null }
        val before = uiState.lyricsFor?.let { shown -> playing?.let { Before(shown, inApp(it.clock.positionAt(now())), it.appPackage) } }
            ?: when (val first = playback?.whatPlays()) {
                is WhatPlays.Named -> first.song.clock?.let { Before(first.song.metadata, it.positionAt(now()), app = first.song.app) }
                is WhatPlays.Nameless -> first.playing.takeIf { it.reportsState }?.let { Before(it.played, 0, it.app, songless = true) }
                null -> null
            }
        cards.start(song, before)
    }

    fun replayLine() = cards.replay()

    fun turnPractice() = cards.turn()

    fun readCardAs(readings: List<String>) = cards.readAs(readings)

    fun gradePractice(grade: Grade) = cards.grade(grade)

    fun stopPractice() = cards.stop()

    /** The small player's picture, shown on the card its line plays in. */
    fun linePlayerView() = cards.playerView()

    /** Crosstune is out of sight: a line playing in the small player stops. */
    fun pausePracticeSound() = cards.background()

    /** Due cards asked back once a day, or not. */
    fun setReminder(on: Boolean) {
        reminders?.turn(on)
        refreshReminder()
    }

    /** The time of day they're asked back, from now on, in minutes from midnight. */
    fun setReminderTime(minute: Int) {
        reminders?.at(minute)
        refreshReminder()
    }

    /** What may have changed in Android's settings: whether it lets them show, and whether the exact minute is still allowed. */
    fun refreshReminder() {
        // Opened after a revoke, or after the exact minute was allowed again, the alarm is set the best way still allowed.
        reminders?.arm()
        uiState = uiState.copy(reminder = reminders?.state ?: uiState.reminder)
    }

    /** With no card left due, a reminder showing is out of date. */
    fun clearReminderIfNoneDue() {
        if (uiState.savedLines.cardsDueCount(wallClock(), uiState.cardTypes) == 0) reminders?.clear()
    }

    /** A reminder tapped: what's due, unless a session's going, over whatever's open, settings too. */
    fun practiseDue() {
        reminders?.practised()
        uiState = uiState.copy(leaveSettings = true)
        if (uiState.practice?.line == null) startPractice()
    }

    fun backToBefore() = cards.backToBefore()

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
        viewModelScope.launchKept({ wordJob = it }) {
            val language = translateInto()
            val inLine = if (usesChatGpt() && line != null) tutor?.meaning(word.lookup, line, language) else null
            if (inLine != null) {
                uiState = uiState.copy(word = WordMeaning(word, inLine.meaning, type = inLine.type))
                return@launchKept
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
        viewModelScope.launchKept({ explanationJob = it }) {
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
        viewModelScope.launchKept({ questionJob = it }) {
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
        viewModelScope.launchKept({ songExplanationJob = it }) {
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
        viewModelScope.launchKept({ songFactsJob = it }) {
            val facts = tutor.songFacts(song) ?: return@launchKept
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
        viewModelScope.launchKept({ songQuestionJob = it }) {
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
        viewModelScope.launchKept({ wordsToKnowJob = it }) {
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
        viewModelScope.launchKept({ artistJob = it }) {
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
        viewModelScope.launchKept({ examplesJob = it }) {
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
        val start = lines.getOrNull(index)?.takeIf { it.heard }?.timeMs ?: return
        if (uiState.following?.let { it.canSeek && it.timingKnown } != true) return
        val end = LineWindow.playedUntil(start, endOf(lines, index), nextStart(lines, index))
        repeatJob?.cancel()
        uiState = uiState.copy(repeating = index)
        playback?.seekTo(song, inApp(start))
        movedOn(uiState.following?.appPackage)
        var sought = now()
        viewModelScope.launchKept({ repeatJob = it }) {
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

    /**
     * The readings shown, to be read again when the words show again, as something they need couldn't be had. Only
     * while they're read: whatever replaces them stops that first.
     */
    private fun unfinished() {
        uiState = uiState.copy(learning = uiState.learning.copy(readingsUnfinished = true))
    }

    /** The words shown, line by line: the timed ones, or else the plain ones. */
    private fun wordLines(): List<String> = uiState.lyricLines.map { it.text }.ifEmpty { uiState.lyrics.lines() }

    private var uniDicJob: Job? = null

    /**
     * Japanese is read with UniDic too, once it's on the phone. Until then it's fetched, whatever
     * song shows, and the words shown are read again with it once it's here.
     */
    private suspend fun readWithUniDic() {
        val dictionary = uniDic ?: return
        if (Readings.uniDic != null || uniDicJob?.isActive == true) return
        Readings.uniDic = dictionary.tokenizer(fetch = false)
        if (Readings.uniDic != null) return
        viewModelScope.launchKept({ uniDicJob = it }) {
            Readings.uniDic = dictionary.tokenizer(fetch = true) ?: return@launchKept
            if (uiState.learning.script == Script.JAPANESE && uiState.learning.lines.isNotEmpty()) {
                readingsJob?.cancel()
                uiState = uiState.copy(learning = uiState.learning.copy(lines = emptyList()))
                learn()
            }
        }
    }

    /** Works out what's switched on for the words shown, and isn't worked out yet. */
    private fun learn() {
        val learning = uiState.learning
        val lines = wordLines()
        if (!learning.reads && readingsJob?.isActive == true) {
            // Turned off while they're worked out: nothing more is asked for them, and they're worked out again once on.
            readingsJob?.cancel()
            uiState = uiState.copy(learning = uiState.learning.copy(lines = emptyList()))
        }
        if (learning.lines.isEmpty()) readWords(lines)
        val translator = translator ?: return
        if (learning.translation && uiState.lyrics.isNotEmpty() && learning.translations.isEmpty() && !learning.translating) {
            viewModelScope.launchKept({ translationJob = it }) {
                uiState = uiState.copy(learning = uiState.learning.copy(translating = true, translationFailed = false))
                val language = translateInto()
                // A song in a language the user reads needs none, line by line, and nor does one already in
                // the language it'd be translated into, in whatever variety: a Spanish song into Argentina's.
                val sung = lyricsLanguage?.of(lines)
                if (sung != null && (sung in learning.untranslated || sung == AppLanguage.untranslatedTag(language))) {
                    uiState = uiState.copy(learning = uiState.learning.copy(translations = lines.map { null }, translating = false))
                    return@launchKept
                }
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
                uiState.lyricsFor?.let(::retranslateSavedLines)
                // The song's name too, to know what it means, the same way.
                val song = uiState.lyricsFor ?: return@launchKept
                val title = listOf(song.title)
                val meaning = ((if (usesChatGpt() && learning.chatGptTranslation) tutor?.translate(title, language, song) else null) ?: translator.translate(title, language))
                    ?.firstOrNull()?.takeUnless { it.equals(song.title.trim(), ignoreCase = true) }
                if (uiState.lyricsFor == song) uiState = uiState.copy(learning = uiState.learning.copy(titleMeaning = meaning))
            }
        }
    }

    /**
     * Works out how [lines], the words shown, read, while readings are on and they aren't being worked out already. Read
     * again, what UtaTen or ChatGPT put right in the readings shown stays until it answers again.
     */
    private fun readWords(lines: List<String>) {
        val script = uiState.learning.script
        if (!uiState.learning.reads || script == null || readingsJob?.isActive == true) return
        val kept = uiState.learning.lines
        viewModelScope.launchKept({ readingsJob = it }) {
            if (script == Script.JAPANESE) readWithUniDic()
            // Japanese loads its dictionary the first time, a moment better spent away from the screen.
            val song = uiState.lyricsFor
            val read = withContext(systemDispatcher) { Readings.of(lines, script) }
            // The song's name and its artist's read the same way, to show how to say them.
            val names = song?.let { withContext(systemDispatcher) { Readings.of(listOf(it.title, it.artist), script) } }.orEmpty()
            // Read without UniDic while it may still come: read again with it, once it's here, when the words show again.
            val withoutUniDic = script == Script.JAPANESE && uniDic != null && Readings.uniDic == null
            val coming = puttingRight(script, song)
            val shown = keeping(kept, read, coming)
            uiState = uiState.copy(learning = uiState.learning.copy(lines = shown, names = names, readingsUnfinished = withoutUniDic))
            // Lines kept with no reading, as ones once written otherwise, are read as they are now; all of them, but
            // for what was put right in them, once the dictionary's readings are all there are, as for Japanese once
            // UtaTen's are known, since Japanese ones kept before UtaTen's were marked can't be told from the dictionary's.
            song?.let { rereadSavedLines(it, unread = script == Script.JAPANESE) }
            if (song != null && coming.isNotEmpty()) putRight(song, lines, read, kept, coming)
        }
    }

    /** What may put right how [song]'s words in [script] read: UtaTen, for Japanese, and ChatGPT, signed in. */
    private fun puttingRight(script: Script, song: MusicMetadata?): Set<Reader> =
        if (script == Script.JAPANESE && song != null) setOfNotNull(Reader.SUNG, Reader.CHATGPT.takeIf { usesChatGpt() }) else emptySet()

    /**
     * [dictionary], how [song]'s [lines] read by the dictionary, put right where they're sung otherwise, from a site that
     * writes the kana over the words, and by ChatGPT, signed in. What either put right in [kept], the readings shown
     * before, stays shown until it answers, as it's [coming]. One that can't be had is asked for again when the words
     * show again; a song UtaTen has no page for is kept as having none, and isn't.
     */
    private suspend fun putRight(song: MusicMetadata, lines: List<String>, dictionary: List<LineReading>, kept: List<LineReading>, coming: Set<Reader>) {
        var read = dictionary
        val waiting = coming.toMutableSet()
        // Sung readings, 都会 as まち.
        val sung = sungReadingsOf(song, lines, read)
        if (sung == null && sungReadings != null) unfinished()
        sung?.let { fixes ->
            // Words timed better meanwhile from another copy, which may write them otherwise.
            if (wordLines() != lines) return
            waiting -= Reader.SUNG
            read = Readings.corrected(read, fixes)
            uiState = uiState.copy(learning = uiState.learning.copy(lines = keeping(kept, read, waiting)))
            // With none from UtaTen, and no ChatGPT to put them right, the dictionary's are all there are.
            if (fixes.isNotEmpty() || Reader.CHATGPT !in waiting) rereadSavedLines(song)
        }
        // Signed in, ChatGPT puts right the readings the dictionary gets wrong in this song.
        if (Reader.CHATGPT !in waiting) return
        val checked = tutor?.checkReadings(song, lines, read) ?: return unfinished()
        if (wordLines() == lines) {
            uiState = uiState.copy(learning = uiState.learning.copy(lines = keeping(kept, checked, waiting - Reader.CHATGPT)))
            rereadSavedLines(song)
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
        viewModelScope.launchKept({ chordsJob = it }) {
            uiState = uiState.copy(chords = uiState.chords.copy(loading = true))
            val answer = finder.chordsOf(song, lines)
            val failure = when (answer) {
                ChordFinder.Chords.WikiTimedOut -> ChordFailure.WIKI_TIMEOUT
                ChordFinder.Chords.WikiCheck -> ChordFailure.WIKI_CHECK
                else -> null
            }
            uiState = uiState.copy(
                chords = uiState.chords.copy(
                    loading = false,
                    tried = true,
                    sheet = (answer as? ChordFinder.Chords.Found)?.sheet,
                    failed = failure != null || answer == ChordFinder.Chords.Unavailable,
                    failure = failure
                )
            )
            // Many songs in one have no one sheet to miss.
            if (answer == ChordFinder.Chords.None && !SongNames.manySongs(song)) problemFound(SongProblem.NO_CHORDS)
        }
    }

    /** Looks the chords up again, after a site wouldn't answer. */
    fun retryChords() {
        uiState = uiState.copy(chords = uiState.chords.copy(tried = false, failed = false, failure = null))
        chords()
    }

    /**
     * Shows the song's chords over its words, from now on, or stops, and stops looking them up.
     * Switched on again, a song with no sheet, none found or a site that wouldn't answer, is looked up again.
     * A sheet is only ever there once looked up, so it alone says there's nothing to ask again.
     */
    fun setChords(on: Boolean) {
        preferences.edit { putBoolean(KEY_CHORDS, on) }
        if (!on) chordsJob?.cancel()
        val chords = uiState.chords
        uiState = uiState.copy(chords = chords.copy(on = on, loading = on && chords.loading, tried = chords.sheet != null, failed = false, failure = null))
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

    /** Kept only once let go, [save], rather than at every step of the slider. */
    fun setWordsScale(scale: Float, save: Boolean) {
        if (save) preferences.edit { putFloat(KEY_WORDS_SCALE, scale) }
        uiState = uiState.copy(learning = uiState.learning.copy(wordsScale = scale))
    }

    fun setWordsCentered(centered: Boolean) {
        preferences.edit { putBoolean(KEY_WORDS_CENTERED, centered) }
        uiState = uiState.copy(learning = uiState.learning.copy(centered = centered))
    }

    /** Kept only once let go, [save], rather than at every step of the slider. */
    fun setTranslationScale(scale: Float, save: Boolean) {
        if (save) preferences.edit { putFloat(KEY_TRANSLATION_SCALE, scale) }
        uiState = uiState.copy(learning = uiState.learning.copy(translationScale = scale))
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

    /** Spells romaji as typed, sensei, or as said, sensee, from now on. */
    fun setRomajiTyped(on: Boolean) {
        preferences.edit { putBoolean(KEY_ROMAJI_TYPED, on) }
        uiState = uiState.copy(learning = uiState.learning.copy(romajiTyped = on))
    }

    /** Writes katakana in hiragana too where the words show in kana, どあ for ドア, from now on. */
    fun setKatakanaAsHiragana(on: Boolean) {
        preferences.edit { putBoolean(KEY_KATAKANA_AS_HIRAGANA, on) }
        uiState = uiState.copy(learning = uiState.learning.copy(katakanaAsHiragana = on))
    }

    /** The kinds of practice card, from now on; one is always on. */
    fun setCardTypes(types: List<CardType>, save: Boolean) {
        val kinds = if (types.none { it.on }) types.mapIndexed { index, type -> type.copy(on = type.on || index == 0) } else types
        if (save) saveCardTypes(preferences, kinds)
        uiState = uiState.copy(cardTypes = kinds)
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
        translateAgain(uiState.learning.copy(chatGptTranslation = on))
    }

    /** The words shown translated again, as [learning] says to now, rather than as they were. */
    private fun translateAgain(learning: Learning) {
        translationJob?.cancel()
        uiState = uiState.copy(learning = learning.copy(translations = emptyList(), translating = false, translationFailed = false))
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
        translateAgain(uiState.learning.copy(untranslated = untranslated))
    }

    /** Translates into [tag], one of [AppLanguage.translationTags], from now on, or into the app's own with null. */
    fun setTranslateInto(tag: String?) {
        val into = tag?.takeIf { it in AppLanguage.translationTags }
        preferences.edit { if (into == null) remove(KEY_TRANSLATE_INTO) else putString(KEY_TRANSLATE_INTO, into) }
        // Lines translated into the last language are no use in this one.
        uiState = uiState.copy(word = null)
        translateAgain(uiState.learning.copy(into = into))
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
        val saved = uiState.destinationUrls[destination]
        saved?.takeIf(PreparedLink::lasting)?.let { return it.url.forSharing() }
        val lookup = if (uiState.exactMatch && service != null) {
            uiState = uiState.copy(isMatching = true)
            lookupDestination(service, metadata, saved).also { uiState = uiState.copy(isMatching = false) }
        } else {
            null
        }
        val prepared = preparedLink(destination, metadata, lookup)
        rememberDestination(destination, prepared)
        return prepared.url.forSharing()
    }

    private suspend fun lookupDestination(service: MusicService, metadata: MusicMetadata, saved: PreparedLink?): ExactMatcher.Lookup {
        val provisional = saved?.takeIf { it.exact && !it.lasting }?.url?.let(MusicLinks::linkFor)?.youtubeVideoOn(service)
        return matcher.lookup(service, metadata, saved?.retry, provisional)
    }

    private fun preparedLink(destination: Destination, metadata: MusicMetadata, lookup: ExactMatcher.Lookup?): PreparedLink {
        val found = lookup?.match as? ExactMatcher.Match.Found
        val url = found?.url?.let(destination::adapt) ?: destination.searchUrl(searchQuery(metadata))
        return PreparedLink(url, found != null, matched(lookup?.match), lookup?.retry)
    }

    /**
     * Whether a link was prepared with exact matching: not when the lookup got no answer, e.g.
     * offline, so it isn't taken as not being there, and is looked for again next time.
     */
    private fun matched(match: ExactMatcher.Match?) =
        uiState.exactMatch && match != ExactMatcher.Match.Failed && match != ExactMatcher.Match.WaitingForNames

    /** Opens the link in the app it came from, e.g. when it can't be resolved or the user prefers it. */
    fun openOriginal() {
        val link = uiState.link ?: return
        // An exact match may still be running; it must not open a second app when it finishes.
        job?.cancel()
        cancelTrackWork()
        pendingOpen = false
        uiState = uiState.copy(showDestinationPicker = false, isMatching = false)
        val finishAfterOpen = lastRequest?.openWhenReady == true
        effectChannel.trySend(opening(link.url, link.service?.packageName, finishAfterOpen))
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

    /** Shows [entry]'s card; [play], it plays from there, as a link shown here first does. */
    fun showHistoryEntry(entry: HistoryEntry, play: Boolean = false) {
        selectHistoryEntry(entry)
        pendingPlay = play
        val refresh = !entry.albumChecked && !uiState.offline
        if (refresh) uiState = uiState.copy(result = null, isLoading = true)
        viewModelScope.launchKept({ job = it }) {
            if (refresh) {
                val updated = refreshSavedAlbum(entry)
                val history = if (updated.albumChecked) historyStore.update(entry.link.url, updated.metadata, albumChecked = true) else uiState.history
                uiState = uiState.copy(result = updated.metadata, history = history, isLoading = false)
            }
            lookAhead()
            loadTrackCovers()
            prepareResult()
        }
    }

    /**
     * Recent's ▶ goes where a tapped link does: to the song's card here first, playing from there
     * when it plays right away, or else straight to the user's app, from what was saved.
     */
    fun openHistoryEntry(entry: HistoryEntry) =
        if (uiState.linksInCrosstune) showHistoryEntry(entry, play = uiState.playsRightAway) else openSaved(entry, finishAfterOpen = false)

    /**
     * A Recent song from the widget's ▶: as Recent's own ▶, with Crosstune going away after when it
     * goes straight to the user's app. Gone from Recent meanwhile, it's looked up again.
     */
    fun openRecent(url: String?) {
        val entry = uiState.history.firstOrNull { it.link.url == url } ?: return resolveIncoming(url)
        uiState = uiState.copy(leaveSettings = true)
        if (uiState.linksInCrosstune) {
            openHistoryEntry(entry)
            // Shown from the home screen, Back goes back there, as for a link from another app.
            uiState = uiState.copy(fromAnotherApp = true)
            return
        }
        uiState = uiState.copy(handlingIncomingLink = true, handingOff = true)
        openSaved(entry, finishAfterOpen = true)
    }

    private fun openSaved(entry: HistoryEntry, finishAfterOpen: Boolean) {
        cancelContentWork()
        viewModelScope.launchKept({ job = it }) {
            val destination = destinationFor(entry)
            val url = urlForHistory(entry, destination) ?: return@launchKept
            val prepared = uiState.history.firstOrNull { it.link.url == entry.link.url }?.destinationLinks?.get(destination.key)
            val service = destination.matchService
            if (prepared != null && prepared.url.forSharing() == url && !prepared.exact && prepared.matchingEnabled && service != null &&
                canBeMissing(service, entry.metadata)
            ) {
                return@launchKept openMissing(entry.metadata, entry.link, destination, url, finishAfterOpen)
            }
            effectChannel.send(opening(url, destination.packageName, finishAfterOpen, entry.metadata))
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
            matcher.youtubeQueue(service, entry.metadata.tracks.take(MAX_QUEUE)) {}?.let { queue ->
                playOn(entry.metadata.tracks, service, destination, queue, entry.metadata.title)
                return queue.url
            }
        }
        val saved = entry.destinationLinks[destination.key]
        saved?.takeIf { it.lasting && it.canUseFor(uiState.exactMatch) }?.let { return it.url.forSharing() }
        val lookup = if (uiState.exactMatch && service != null) lookupDestination(service, entry.metadata, saved) else null
        val prepared = preparedLink(destination, entry.metadata, lookup)
        val history = historyStore.remember(entry.link.url, destination.key, prepared)
        uiState = uiState.copy(history = history)
        return prepared.url.forSharing()
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
    private fun cancelContentWork(closesPlayer: Boolean = true, keepsWords: Boolean = false) {
        job?.cancel()
        job = null
        forgetHeld()
        lastRequest = null
        pendingOpen = false
        pendingPlay = false
        retryListens = false
        dismissSongSearch()
        if (!keepsWords) dismissLyrics()
        if (!keepsWords) alongByPhone = false
        if (closesPlayer && !keepsWords) closePlayer()
        cancelTrackWork()
        coverJob?.cancel()
        coverJob = null
        uiState = uiState.copy(isLoading = false, isMatching = false, listening = false, canRetry = false, fromAnotherApp = false)
    }

    private fun cancelTrackWork() {
        qobuzNumbers?.off()
        trackJob?.cancel()
        trackJob = null
        uiState = uiState.copy(queueProgress = null, isMatching = false)
    }

    private fun rememberDestination(destination: Destination, prepared: PreparedLink) {
        val history = uiState.link?.let { historyStore.remember(it.url, destination.key, prepared) } ?: uiState.history
        uiState = uiState.copy(destinationUrls = uiState.destinationUrls + (destination to prepared), history = history)
    }

    fun selectResultDestination(destination: Destination) {
        adoptDefault(destination)
        uiState = uiState.copy(selectedDestination = destination)
        prepareResultDestination()
    }

    fun selectDefault(destination: Destination) {
        destinationStore.setDefault(destination)
        // Opening links in the app, Crosstune would only hand it its own links back, unless a rule sends them elsewhere.
        (destination as? Destination.Service)?.service
            ?.takeIf { !uiState.linksInCrosstune && interception.isEnabled(it) && destinationStore.rule(it) == null }
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
     * Opens the big services' links on a fresh install, but the one the user listens in, whose
     * links already open there. Each site is a box to tick in Android's settings, so the rest wait
     * in settings: YouTube's links are mostly videos, SoundCloud's, Bandcamp's and Audiomack's songs
     * are rarely anywhere else, and Invidious and Piped are a dozen sites of their own.
     */
    private fun preselectSources() {
        // A TV has nowhere to allow links in, and links rarely reach one anyway.
        if (preferences.getBoolean(KEY_SOURCES_PRESELECTED, false) || uiState.phone == Phone.TV) return
        preferences.edit { putBoolean(KEY_SOURCES_PRESELECTED, true) }
        if (uiState.interceptsAnything) return
        val listening = uiState.listeningService()
        STARTING_SOURCES.filter { it != listening }.forEach { interception.setEnabled(it, true) }
        uiState = uiState.withDestinations().copy(showLinkSettingsHelper = true)
        loadSystemState()
    }

    fun setFrontendRule(frontend: Frontend, destination: Destination?) {
        destinationStore.setRule(frontend, destination)
        refreshSystemState()
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

    /** Whether tapped and shared links show in Crosstune first, or go straight to the music app. */
    fun setShowInCrosstuneFirst(enabled: Boolean) {
        lookUpAsNow(uiState.copy(linksInCrosstune = enabled))
        preferences.edit { putBoolean(KEY_LINKS_IN_CROSSTUNE, enabled) }
        // Straight to the app, Crosstune would only hand it its own links back; shown here first, they come here too.
        val app = (uiState.defaultDestination as? Destination.Service)?.service
        if (!enabled) selectDefault(uiState.defaultDestination)
        else if (app?.opensTappedLinks == true) setIntercepted(app, true)
    }

    fun setPlayRightAway(enabled: Boolean) {
        lookUpAsNow(uiState.copy(playRightAway = enabled))
        preferences.edit { putBoolean(KEY_PLAY_RIGHT_AWAY, enabled) }
    }

    /** Takes [state], and prepares the song shown again when that changes whether it opens itself or a search. */
    private fun lookUpAsNow(state: UiState) {
        val exact = uiState.exactMatch
        uiState = state
        if (state.exactMatch != exact) {
            uiState = uiState.copy(destinationUrls = emptyMap())
            prepareResultDestination()
        }
    }

    fun setWakesForNextSong(enabled: Boolean) {
        uiState = uiState.copy(wakesForNextSong = enabled)
        preferences.edit { putBoolean(KEY_WAKES_FOR_NEXT_SONG, enabled) }
    }

    fun setShareSheetApps(enabled: Boolean) {
        uiState = uiState.copy(shareSheetApps = enabled)
        preferences.edit { putBoolean(KEY_SHARE_SHEET_APPS, enabled) }
    }

    fun setCleanLinks(enabled: Boolean) {
        uiState = uiState.copy(cleanLinks = enabled)
        preferences.edit { putBoolean(KEY_CLEAN_LINKS, enabled) }
    }

    /** Whether the phone's on a network that costs no data, the only one songs kept offline are fetched on. */
    private var unmetered = false

    fun unmeteredChanged(unmetered: Boolean) {
        this.unmetered = unmetered
        if (unmetered) fetchKeptOffline()
    }

    /** Keeps the song, album or playlist shown for offline, or lets it go when it's kept already. */
    fun keepOffline() {
        val offline = offline ?: return
        val item = uiState.result ?: return
        viewModelScope.launch {
            offline.has(item)?.let { offline.letGo(it.id) } ?: offline.keep(item)
            showKeptOffline()
            // Kept while it plays, it's liked there now, not the next time it plays.
            followedNow?.let { (song, following) -> likeKeptOffline(song, following) }
        }
    }

    /** Whether songs kept for offline are liked in the music app that plays them, so it downloads them too. */
    fun setOfflineLikes(on: Boolean) {
        uiState = uiState.copy(offlineLikes = on)
        preferences.edit { putBoolean(KEY_OFFLINE_LIKES, on) }
    }

    /**
     * Likes [song] in the app [following] it, when it's kept for offline and not liked there yet, so the
     * app downloads it too, as it does what's liked once that's switched on there. Only where the app shows
     * whether it's liked: its like goes on and off, so pressed blind it could unlike it. Once per song.
     */
    private suspend fun likeKeptOffline(song: MusicMetadata, following: Following?) {
        if (!uiState.offlineLikes) return
        val offline = offline ?: return
        val liked = following?.like?.liked ?: return
        if (offline.likeOnce { sameSong(it, song) } && !liked) playback?.like(song)
    }

    /** How many megabytes what's kept for offline may take: none more is fetched past it. */
    fun setOfflineSpace(megabytes: Int) {
        uiState = uiState.copy(offlineSpaceMb = megabytes)
        preferences.edit { putInt(KEY_OFFLINE_SPACE, megabytes) }
        fetchKeptOffline()
    }

    private suspend fun showKeptOffline() {
        val offline = offline ?: return
        showKeptOffline(offline)
        fetchKeptOffline()
    }

    /** Read first, as reading waits on storage and the state may change meanwhile, e.g. going offline. */
    private suspend fun showKeptOffline(offline: OfflineSongs) {
        val kept = offline.all()
        val used = offline.size()
        uiState = uiState.copy(keptOffline = kept, offlineUsedBytes = used)
    }

    private var keepingOffline: Job? = null

    /** Asked to fetch while fetching, as when a network comes back while the last one fails, it goes round again. */
    private var fetchAgain = false

    /**
     * How many times what's kept for offline was gone round, so a test can tell a request of one time
     * round from one of the next: their requests are the same, and the song's card asks its own too.
     */
    @VisibleForTesting
    @Volatile
    internal var keptOfflineRounds = 0
        private set

    /**
     * Fetches what each song kept for offline needs and isn't saved yet, one at a time, on a network
     * that costs no data, while there's room.
     */
    private fun fetchKeptOffline() {
        val offline = offline ?: return
        if (!unmetered) return
        if (keepingOffline?.isActive == true) {
            fetchAgain = true
            return
        }
        viewModelScope.launchKept({ keepingOffline = it }) {
            do {
                fetchAgain = false
                keptOfflineRounds++
                fetchEachKept(offline)
            } while (fetchAgain)
        }
    }

    private suspend fun fetchEachKept(offline: OfflineSongs) {
        for (kept in offline.all()) {
            for (song in kept.songs.filter { OfflineSongs.keyOf(it).let { key -> key !in kept.saved || key in kept.unread } }) {
                if (!unmetered || uiState.offlineUsedBytes >= uiState.offlineSpaceMb * BYTES_PER_MB) return
                // Its words or chords unreachable for a moment, it's tried again on the next network. Only its sung
                // readings, it's kept without them for now and the next songs are fetched, and they're asked for on the next.
                when (withContext(Pinning(kept.id)) { fetchForOffline(song) }) {
                    Fetched.NOT -> return
                    Fetched.UNREAD -> offline.saved(kept.id, song, unread = true)
                    Fetched.ALL -> offline.saved(kept.id, song)
                }
                showKeptOffline(offline)
            }
        }
    }

    /** What a song kept for offline could fetch: all it needs, all but its sung readings, or not its words or chords. */
    private enum class Fetched { ALL, UNREAD, NOT }

    /**
     * Looks up all the song's words screen may need, as it's set to show them: the words and their
     * timing, their translation, chords, how Japanese ones are sung, and its cover. How much it could.
     */
    private suspend fun fetchForOffline(song: MusicMetadata): Fetched {
        val lyrics = lyricsFinder?.lyricsOf(song) ?: return Fetched.ALL
        song.artworkUrl?.let { artwork.keep(it) }
        if (lyrics == LyricsFinder.Lyrics.Unavailable) return Fetched.NOT
        val found = lyrics as? LyricsFinder.Lyrics.Found ?: return Fetched.ALL
        val lines = found.lines.map { it.text }.ifEmpty { found.words.lines() }
        if (uiState.learning.translation) translator?.translate(lines, translateInto())
        if (uiState.chords.on) {
            when (chordFinder?.chordsOf(song, lines)) {
                ChordFinder.Chords.Unavailable, ChordFinder.Chords.WikiTimedOut, ChordFinder.Chords.WikiCheck -> return Fetched.NOT
                else -> Unit
            }
        }
        val script = Readings.scriptOf(lines)
        // Asked as when the words show: romaji and kana are made from the readings too.
        val sung = if (uiState.learning.reads && script == Script.JAPANESE) sungReadingsOf(song, lines, withContext(systemDispatcher) { Readings.of(lines, script) }) else emptyList()
        // Readings turned off while UtaTen was asked: none are wanted any more, so the song has all it needs.
        return if (sung != null || sungReadings == null || !uiState.learning.reads) Fetched.ALL else Fetched.UNREAD
    }

    /**
     * How [song]'s kanji are sung in its [lines], [read] as the dictionary reads them, from UtaTen: asked only while the
     * words are read, in readings, Latin letters or kana. Checked again before each request, so one already sent
     * doesn't lead to another once that's been turned off, on screen or while a song is kept offline.
     */
    private suspend fun sungReadingsOf(song: MusicMetadata, lines: List<String>, read: List<LineReading>): List<ReadingFix>? =
        sungReadings?.takeIf { uiState.learning.reads }?.of(song, lines, read) { uiState.learning.reads }

    /** Saved links keep what the service returned, so turning the setting off brings it back. */
    private fun String.forSharing(): String = if (uiState.cleanLinks) TrackingLinks.clean(this) else this

    fun dismissLinkSettingsHelper() {
        uiState = uiState.copy(showLinkSettingsHelper = false)
        preferences.edit { putBoolean(KEY_LINK_SETTINGS_HELPER_DISMISSED, true) }
    }

    /** Downloads the newer Crosstune, which Android then offers to install over this one. */
    fun openUpdate() {
        val update = uiState.update ?: return
        viewModelScope.launch { effectChannel.send(Effect.Open(update.apkUrl, packageName = null, finishAfterOpen = false)) }
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

    init {
        // Last, once everything it reads is there: a report owed from before the app was ended goes at each start the user lets reports go.
        reports?.let { autoReports.keepEarlier(it.version, Report(it.version).android) }
        sendOwed()
    }

    companion object {
        const val PREFERENCES_NAME = "crosstune_preferences"

        /** The box for reports takes 5 a minute from one address; owed ones go 4 a minute. */
        const val REPORT_PACE_MS = 15_000L
        /** A song without any picture can wait a little longer for its cover than a video with a frame. */
        private const val COVER_TIMEOUT_MS = 5_000L
        private const val VIDEO_COVER_TIMEOUT_MS = 2_000L
        private const val KEY_LINK_SETTINGS_HELPER_DISMISSED = "link_settings_helper_dismissed"
        private const val KEY_DISMISSED_NOTICES = "dismissed_notices"
        private const val KEY_ASK_EACH_TIME = "ask_each_time"
        private const val KEY_EXACT_MATCH = "exact_match"
        private const val KEY_CLEAN_LINKS = "clean_links"
        private const val KEY_OFFLINE_SPACE = "offline_space_mb"
        private const val KEY_OFFLINE_LIKES = "offline_likes"

        /** What's kept for offline may take this much by default, a few hundred songs' worth. */
        const val OFFLINE_SPACE_MB = 200
        /** As Android counts them when it shows a size. */
        const val BYTES_PER_MB = 1000L * 1000
        private const val KEY_SHARE_SHEET_APPS = "share_sheet_apps"
        /** Read by the play queue too, which plays on with no screen open. */
        const val KEY_WAKES_FOR_NEXT_SONG = "wakes_for_next_song"
        private const val KEY_SHOW_SONG_FIRST = "show_song_first"
        private const val KEY_LINKS_IN_CROSSTUNE = "links_in_crosstune"
        private const val KEY_PLAY_RIGHT_AWAY = "play_right_away"
        private const val KEY_THEME = "theme"
        const val KEY_PALETTE = "palette"
        private const val KEY_NOT_FOUND = "not_found"
        private const val KEY_PURE_BLACK = "pure_black"
        private const val KEY_PLAYING_BAR = "playing_bar"
        private const val KEY_LINES_EARLY = "lines_early_ms"
        private const val KEY_NEXT_LINE_FIRST = "next_line_first"
        private const val KEY_NEXT_LINE_AHEAD = "next_line_ahead_ms"
        private const val KEY_VISUALS = "visuals"
        private const val KEY_VISUALS_SHADE = "visuals_shade"
        private const val KEY_READINGS = "lyrics_readings"
        private const val KEY_CHORDS = "lyrics_chords"
        private const val KEY_ROMANIZED = "lyrics_romanized"
        private const val KEY_ROMAJI_TYPED = "lyrics_romaji_typed"
        private const val KEY_KATAKANA_AS_HIRAGANA = "lyrics_katakana_as_hiragana"
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

        /** How many songs' words are kept to show again at once. */
        const val KEPT_SONGS = 20
        private const val KEY_UNTRANSLATED = "untranslated_languages"
        private const val KEY_READING_SCALE = "reading_scale"
        private const val KEY_WORDS_SCALE = "words_scale"
        private const val KEY_WORDS_CENTERED = "words_centered"
        private const val KEY_TRANSLATION_SCALE = "translation_scale"
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

        /** Whether another name heard [heardMs] into its song is where the song shown is, [shownMs] into it: well into it, and close. */
        internal fun sameRecordingPlace(shownMs: Long, heardMs: Long): Boolean =
            shownMs >= SAME_RECORDING_AFTER_MS && abs(heardMs - shownMs) <= SAME_RECORDING_MS

        /**
         * How long until what's heard from [here] in the song on, going by at [speed], has lines sung in it;
         * null when no stretch of the song left has enough. Only from [after] on, when the song was heard
         * up to there, and [straightOn] from there, wherever the lines are timed.
         */
        internal fun untilSung(lines: List<LyricLine>, here: Long, speed: Float, after: Long?, straightOn: Boolean = false): Long? {
            val at = maxOf(here, after ?: here)
            val heardMs = SingingListener.SECONDS * 1000L - SINGING_TAIL_MS
            val starts = lines.filter { it.text.isNotBlank() && it.timeMs + SINGING_LEAD_MS >= at }
            // Heard from just before a line starts, or now, as one just has: a while opening on music alone, the model may hear only that.
            // Less just before, when that's what lets enough lines after it be heard too, so the line isn't left to the next while.
            // A line alone only when it's heard from its start: its words are all that tell it apart, so one already begun may not be placed.
            val from = if (straightOn) at else starts.withIndex().firstNotNullOfOrNull { (index, line) ->
                val last = if (line.timeMs >= at && SungAlignment.canPlaceAlone(line, lines)) line.timeMs else starts.getOrNull(index + SINGING_LINES - 1)?.timeMs
                last?.let { maxOf(at, line.timeMs - SINGING_LEAD_MS, it - heardMs + 1).takeIf { from -> from <= maxOf(at, line.timeMs) } }
            } ?: return null
            return ((from - here) / speed).toLong()
        }

        /** How long ago the song can have been heard and still line up a video with it. */
        const val HEARD_LATELY_MS = 60_000L
        /** How far Shazam's place in a song may be from where its words are timed. */
        const val MAX_GAP_MS = 60_000L

        /** The lines put in time a note of them names: the latest, as a song has many. */
        const val MAX_NOTED_POINTS = 6

        /** How much faster or slower than the recording it matched Shazam must hear one for it to go on at its own pace: less is as near as Shazam tells. */
        const val MIN_SKEW = 1f / 256

        /** Whiles a song with no words found is heard for a voice, at most: one in four is heard as music alone, and four rarely all are. */
        const val VOICE_TRIES = 4

        /** The pause between them, so they're heard across the song, a verse apart, rather than all in its intro. */
        const val VOICE_PAUSE_MS = 20_000L
        private const val VOICE_CAPTURE_MS = SingingListener.SECONDS * 1_000L

        /**
         * How long words held for a listen wait for it to settle them before they show: the time one listen takes,
         * [VOICE_TRIES] whiles heard [VOICE_PAUSE_MS] apart, and a while more for the speech model to hear the last.
         * A finished window adds any time the model took beyond its recording.
         */
        const val HELD_MS = (VOICE_TRIES + 1) * VOICE_CAPTURE_MS + (VOICE_TRIES - 1) * VOICE_PAUSE_MS

        /** How long the music apps are asked whether one plays a song before a listen for a voice in it starts: they say at once. */
        const val APP_ASKED_MS = 1_000L

        /** Times the singing is heard for a song, at most, while too few words heard match its words to tell. */
        const val SINGING_TRIES = 3

        /** How many times through a song with untimed words is heard, all told, to time them: more would hardly time more lines. */
        const val UNTIMED_HEARINGS = 2

        /** Hearings of a video it takes to say how fast it goes, and the speeds it could go at. */
        const val LEAD_SPEED_HEARINGS = 3
        private val LEAD_SPEEDS = 0.9f..1.1f
        /** Lines sung in what's heard, at least, so enough words to match are heard. */
        const val SINGING_LINES = 3
        /** How many of a while's first seconds heard as music alone doubt the words' timing: the speech model often misses a voice once. */
        const val QUIET_HEARINGS = 2
        /** The last moment of what's heard, where a line starting hasn't been sung yet. */
        const val SINGING_TAIL_MS = 3_000L
        /** How long before a line's timed to start it's heard from, as the words may be timed a little late. */
        const val SINGING_LEAD_MS = 1_500L
        /** Words heard sung less off than this stay as they are: hearing them is about as exact. */
        const val SINGING_NUDGE_MS = 150L
        /** An app saying it plays from no further than this into the song, as it says it, starts it: where it says is sure. */
        const val SURE_START_MS = 1_500L
        /** An app's clock as far as this from where it was playing on to has jumped. */
        const val SAME_CLOCK_MS = 250L

        /** How far off the singing heard has to be from the words for them to be reported as off. */
        const val SUNG_OFF_MS = 1_000L
        /** Heard this far off, twice what an app's clock picked up partway can be behind, the words are off themselves. */
        const val WORDS_OFF_MS = 2_000L
        /** Words heard off by more than this many times a while's drift from another are off by another amount: Bizarre Love Triangle, 22.8 s then 20.8 s. */
        const val WORDS_OFF_DRIFT = 8
        /** Where the song is may drift this much while it's heard, and still be where it was. */
        const val STEADY_CLOCK_MS = 300L

        /** Long enough not to keep Shazam busy, short enough to catch a skip or the next song soon. */
        const val LISTEN_ALONG_PAUSE_MS = 8_000L

        /** How many wrong meanings a practised line's right one is picked among. */

        /** How many times the rest after hearing nothing doubles: 8 s, then 16, 32 and at most 64. */
        const val QUIET_PAUSE_DOUBLINGS = 3

        /**
         * How long naming by ear waits after a failure, or after hearing nothing, doubled as for
         * [QUIET_PAUSE_DOUBLINGS]: talk between songs often runs a minute or two.
         */
        const val BY_EAR_RETRY_MS = 30_000L

        /** How long after a song heard should be over it's listened to again: past a fade and a cross-fade into the next. */
        const val BY_EAR_AFTER_MS = 10_000L

        /** How long naming by ear waits after a song whose length isn't known: about half a song. */
        const val BY_EAR_UNKNOWN_MS = 90_000L
        private const val QUEUE_SONG_UNKNOWN_MS = 90_000L
        private const val QUEUE_SONG_MARGIN_MS = 10_000L

        /** The soonest and the latest naming by ear listens again after a song: the end of a song may be misheard, and a long one may be skipped. */
        const val BY_EAR_SOONEST_MS = 20_000L
        const val BY_EAR_LATEST_MS = 360_000L

        /** How often, while naming by ear rests after hearing no song, it checks for a new way to hear. */
        const val EARS_CHECK_MS = 1_000L

        /**
         * How far off from where it should be by now an app may say it is, and still play on from where it was:
         * an app saying where it is now and then is a moment off, at most; further, it was moved, or plays another.
         */
        const val BY_EAR_JUMP_MS = 2_000L

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
        /** How long the music app has to find a song asked for by name, and start it. */
        const val LOAD_SONG_MS = 12_000L

        /** How long an app opened on a song may play something else first: YouTube Music was seen to play two ads in a row. */
        const val ADS_MS = 2 * LONGEST_AD_MS
        /** YouTube makes temporary playlists of up to 50 videos. */
        const val MAX_QUEUE = 50
        /** Songs whose covers are looked up when a playlist shows: Spotify lists 100, the first 30 with covers. */
        private const val MAX_COVER_LOOKUPS = 100
        private const val KEY_ONLY_MUSIC_VIDEOS = "only_music_videos"
        private const val KEY_SOURCES_PRESELECTED = "sources_preselected"

        /** The sources whose links open in Crosstune from the start. */
        private val STARTING_SOURCES = listOf(MusicService.SPOTIFY, MusicService.YOUTUBE_MUSIC, MusicService.APPLE_MUSIC, MusicService.DEEZER, MusicService.TIDAL)

        /** How many times, and how long apart, setup in one tap looks for lyrics being let follow music apps. */
        private const val FOLLOW_WAITS = 10
        private const val FOLLOW_WAIT_MS = 100L
        private const val RESUME_END_MARGIN_MS = 1_000L
        private const val KEY_SETUP_COMPLETE = "setup_complete"

        /** Preferences only an install from before first-run setup can have. */
        private val LEGACY_KEYS = listOf(
            KEY_LINK_SETTINGS_HELPER_DISMISSED, "default_target", "history", KEY_ASK_EACH_TIME, KEY_EXACT_MATCH
        )
    }
}
