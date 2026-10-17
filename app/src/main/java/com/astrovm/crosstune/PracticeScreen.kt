package com.astrovm.crosstune

import android.icu.util.Measure
import android.icu.util.MeasureUnit
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

internal const val PRACTICE_CARD_TAG = "practice-card"

/**
 * Saved lines practised as cards, over the color of the song they're from. Each song comes in on
 * its own while the music app gets to it; each card plays its line there, when it can. Done, it
 * says how it went, and the music played before is a tap away.
 */
@Composable
internal fun PracticeScreen(state: UiState, actions: ScreenActions) {
    val practice = state.practice ?: return
    BackHandler(onBack = actions.onStopPractice)
    val cover = (practice.line ?: practice.songs.lastOrNull())?.artworkUrl
    val tint by animateColorAsState(coverColor(cover, actions.loadArtwork) ?: MaterialTheme.colorScheme.primary, tween(durationMillis = 700), label = "practice tint")
    val ground = MaterialTheme.colorScheme.surface
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(0f to tint.copy(alpha = 0.5f), 0.55f to tint.copy(alpha = 0.12f), 1f to ground))) {
            Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().widthIn(max = ContentMaxWidth).align(Alignment.TopCenter)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp, end = 24.dp)) {
                    IconButton(onClick = actions.onStopPractice) { AppIcon(R.drawable.ic_close, contentDescription = stringResource(R.string.floating_close)) }
                    if (practice.total > 0 && !practice.finished) Progress(practice, Modifier.weight(1f).padding(start = 8.dp))
                }
                AnimatedContent(targetState = practice, contentKey = ::shownAs, transitionSpec = { slide(forward = true) }, label = "card", modifier = Modifier.weight(1f)) { shown ->
                    val line = shown.line
                    if (shown.total == 0) CaughtUp()
                    else if (line == null) Done(shown, actions)
                    else if (shown.intro) SongIntro(line, shown, actions)
                    else Card(line, shown, actions)
                }
            }
        }
    }
}

/** What the screen shows, by which a new one slides in: the same card missed comes back as a new one. */
private fun shownAs(practice: Practice): String {
    val line = practice.line
    return when {
        practice.total == 0 -> "none"
        line == null -> "done"
        practice.intro -> "intro|${line.title}|${line.artist}"
        else -> "card|${line.text}|${line.title}|${line.artist}|${practice.done}|${practice.missed}"
    }
}

/** How far along the session is: a bar for each card, the ones done lit. */
@Composable
private fun Progress(practice: Practice, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.practice_progress, practice.done, practice.total)
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = modifier.semantics { contentDescription = description }) {
        repeat(practice.total) { index ->
            val color = when {
                index < practice.done -> MaterialTheme.colorScheme.primary
                index == practice.done -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
            }
            Box(modifier = Modifier.weight(1f).height(4.dp).clip(CircleShape).background(color))
        }
    }
}

/** None due: a check, all caught up. */
@Composable
private fun CaughtUp() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.fillMaxSize()) {
        Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
        Text(stringResource(R.string.practice_caught_up), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
    }
}

/** A song new to the session, on its own while the music app gets to it, with how many of its lines are coming. */
@Composable
private fun SongIntro(line: SavedLine, practice: Practice, actions: ScreenActions) {
    val cards = practice.queue.takeWhile { sameSongOf(it, line) }.size
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp)) {
        CoverArt(line.artworkUrl, actions.loadArtwork, size = 200.dp)
        Text(line.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 20.dp))
        Text(line.artist, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        AppLoading(line.app, modifier = Modifier.padding(top = 24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 16.dp)) {
            repeat(cards) { index ->
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (index == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)))
            }
        }
    }
}

/** The music app getting to the song: its icon, by name when it's known, and that it's on its way. */
@Composable
private fun AppLoading(app: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val known = remember(app) {
        app?.let { name ->
            runCatching {
                val info = context.packageManager.getApplicationInfo(name, 0)
                context.packageManager.getApplicationLabel(info).toString() to context.packageManager.getApplicationIcon(info).toBitmap(72, 72).asImageBitmap()
            }.getOrNull()
        }
    }
    val description = known?.let { stringResource(R.string.practice_loading, it.first) }
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier.semantics { description?.let { contentDescription = it } }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            known?.let { Image(it.second, contentDescription = null, modifier = Modifier.size(24.dp).clip(CircleShape)) }
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * A card: its face, the rest under it once turned, and the song it's from. Turned, it's said how well
 * it was known with the buttons, each saying when it's back, or swiped: left, again; right, good.
 * Not up, which scrolls a long card.
 */
@Composable
private fun Card(line: SavedLine, practice: Practice, actions: ScreenActions) {
    val haptics = LocalHapticFeedback.current
    val grade = { grade: Grade ->
        haptics.performHapticFeedback(if (grade == Grade.AGAIN) HapticFeedbackType.Reject else HapticFeedbackType.Confirm)
        actions.onGradePractice(grade)
    }
    val dragged = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val scope = rememberCoroutineScope()
    val swipe = with(LocalDensity.current) { SWIPE_DISTANCE.toPx() }
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f),
            onClick = actions.onTurnPractice,
            enabled = !practice.turned,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .testTag(PRACTICE_CARD_TAG)
                .offset { IntOffset(dragged.value.x.roundToInt(), dragged.value.y.roundToInt()) }
                .rotate(dragged.value.x / 40f)
                .then(
                    if (!practice.turned) Modifier
                    else Modifier.pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                val swiped = when {
                                    dragged.value.x > swipe -> Grade.GOOD
                                    dragged.value.x < -swipe -> Grade.AGAIN
                                    else -> null
                                }
                                if (swiped != null) grade(swiped) else scope.launch { dragged.animateTo(Offset.Zero) }
                            },
                            onHorizontalDrag = { change, amount ->
                                change.consume()
                                scope.launch { dragged.snapTo(dragged.value + Offset(amount, 0f)) }
                            }
                        )
                    }
                )
        ) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(24.dp).animateContentSize(Motion.size), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                CardFace(line, practice, actions)
                if (practice.turned) {
                    HorizontalDivider()
                    CardBack(line, practice.face)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SongChip(line, actions, Modifier.weight(1f))
                    // Heard on its face, its button's there; otherwise it plays again from here once turned, or while it plays.
                    if (practice.face != Face.LISTEN && practice.sound != LineSound.NONE && (practice.turned || practice.sound == LineSound.PLAYING)) {
                        SoundButton(line, practice.sound, actions.onReplayLine, size = 40.dp)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
            if (!practice.turned) {
                Button(onClick = actions.onTurnPractice, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.practice_show)) }
            } else {
                val locale = LocalConfiguration.current.locales[0]
                GradeButton(R.string.practice_again, waitText(SavedLinesStore.waitFor(line, Grade.AGAIN), locale), Grade.AGAIN, Modifier.weight(1f)) { grade(it) }
                GradeButton(R.string.practice_good, waitText(SavedLinesStore.waitFor(line, Grade.GOOD), locale), Grade.GOOD, Modifier.weight(1f)) { grade(it) }
                GradeButton(R.string.practice_easy, waitText(SavedLinesStore.waitFor(line, Grade.EASY), locale), Grade.EASY, Modifier.weight(1f)) { grade(it) }
            }
        }
    }
}

/** How far a card's swiped to say how well it was known. */
private val SWIPE_DISTANCE = 96.dp

/** What a card shows first, to think of the rest. One to hear that can't be played is read instead. */
@Composable
private fun CardFace(line: SavedLine, practice: Practice, actions: ScreenActions) {
    val face = practice.face
    if (face == Face.LISTEN && practice.sound == LineSound.NONE) {
        CardLine(line)
    } else if (face == Face.LISTEN) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
            SoundButton(line, practice.sound, actions.onReplayLine, size = 96.dp)
            // The meaning, to help, once peeked at; a word to hear has none of its own.
            if (!practice.turned && line.translation != null) {
                if (practice.hint) {
                    Text(line.translation, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 20.dp))
                } else {
                    IconButton(onClick = actions.onHintPractice, modifier = Modifier.padding(top = 12.dp)) {
                        AppIcon(R.drawable.ic_visibility, contentDescription = stringResource(R.string.practice_hint), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    } else if (face == Face.LINE) {
        CardLine(line)
    } else if (face == Face.MEANING) {
        CardMeaning(line)
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(line.line?.let { withGap(it, line.text) } ?: GAP, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(line.translation.orEmpty(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** What shows once a card's turned: the side it didn't show. */
@Composable
private fun CardBack(line: SavedLine, face: Face) {
    if (face == Face.LISTEN) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CardLine(line)
            if (line.translation != null) CardMeaning(line)
        }
    } else if (face == Face.LINE) {
        CardMeaning(line)
    } else if (face == Face.MEANING) {
        CardLine(line)
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CardLine(line)
            line.line?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** A card's side with the line, as kept: how it reads over it, and in Latin letters under it. */
@Composable
private fun CardLine(line: SavedLine) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        line.reading?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(line.text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        line.romanized?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** A card's side with what the line means. */
@Composable
private fun CardMeaning(line: SavedLine) {
    Text(line.translation.orEmpty(), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
}

/**
 * The line's sound: a ring that fills as the line plays, with bars that move while it does; one
 * going round while the app gets to it; and a play button once it's played, to play it again.
 */
@Composable
private fun SoundButton(line: SavedLine, sound: LineSound, onPlay: () -> Unit, size: Dp) {
    val played = remember(line, sound) { Animatable(0f) }
    LaunchedEffect(line, sound) {
        if (sound == LineSound.PLAYING) played.animateTo(1f, tween(((line.endMs ?: 0L) - (line.startMs ?: 0L)).toInt().coerceAtLeast(0), easing = LinearEasing))
    }
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size + 12.dp)) {
        if (sound == LineSound.LOADING) {
            CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(size + 12.dp))
        } else if (sound == LineSound.PLAYING) {
            CircularProgressIndicator(progress = { played.value }, strokeWidth = 3.dp, modifier = Modifier.size(size + 12.dp))
        }
        Surface(onClick = onPlay, enabled = sound == LineSound.PLAYED, shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(size)) {
            Box(contentAlignment = Alignment.Center) {
                if (sound == LineSound.PLAYING) Bars(size / 3)
                else Icon(painterResource(R.drawable.ic_play), contentDescription = stringResource(R.string.practice_play), modifier = Modifier.size(size / 2))
            }
        }
    }
}

/** Bars rising and falling, out of step, while a line plays. */
@Composable
private fun Bars(height: Dp) {
    val beat = rememberInfiniteTransition(label = "bars")
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(height)) {
        repeat(BARS) { index ->
            val level by beat.animateFloat(0.25f, 1f, infiniteRepeatable(tween(450, delayMillis = index * 90), RepeatMode.Reverse), label = "bar $index")
            Box(Modifier.width(4.dp).height(height * level).clip(CircleShape).background(MaterialTheme.colorScheme.onPrimaryContainer))
        }
    }
}

private const val BARS = 4

/** The song a card's from: its cover and title. */
@Composable
private fun SongChip(line: SavedLine, actions: ScreenActions, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier) {
        CoverArt(line.artworkUrl, actions.loadArtwork, size = 24.dp)
        Text(line.title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** How well a card was known, with when it's back if so. */
@Composable
private fun GradeButton(label: Int, wait: String, grade: Grade, modifier: Modifier, onGrade: (Grade) -> Unit) {
    val content: @Composable () -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(label))
            Text(wait, style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp))
        }
    }
    if (grade == Grade.AGAIN) {
        OutlinedButton(onClick = { onGrade(grade) }, modifier = modifier) { content() }
    } else if (grade == Grade.GOOD) {
        FilledTonalButton(onClick = { onGrade(grade) }, modifier = modifier) { content() }
    } else {
        Button(onClick = { onGrade(grade) }, modifier = modifier) { content() }
    }
}

/** How long [ms] is, short, in minutes, hours or days, as [locale] writes them, e.g. "10m" or "3d". */
internal fun waitText(ms: Long, locale: Locale): String {
    val format = android.icu.text.MeasureFormat.getInstance(locale, android.icu.text.MeasureFormat.FormatWidth.NARROW)
    val minutes = ms / 60_000
    return when {
        minutes < 60 -> format.format(Measure(minutes, MeasureUnit.MINUTE))
        minutes < 24 * 60 -> format.format(Measure(minutes / 60, MeasureUnit.HOUR))
        else -> format.format(Measure(minutes / (24 * 60), MeasureUnit.DAY))
    }
}

/** All done: the songs' covers, how many were practised, days in a row, how they went, and the music from before. */
@Composable
private fun Done(practice: Practice, actions: ScreenActions) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Spacer(Modifier.height(32.dp))
        Covers(practice.songs, actions)
        val done = stringResource(R.string.practice_done, practice.done)
        Text(
            practice.done.toString(),
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 24.dp).semantics { contentDescription = done }
        )
        // Practice just now, so today's in a row.
        val streak = stringResource(R.string.practice_streak, practice.streak)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp).semantics(mergeDescendants = true) { contentDescription = streak }) {
            AppIcon(R.drawable.ic_local_fire_department, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(20.dp))
            Text(practice.streak.toString(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 4.dp))
        }
        val tally = stringResource(R.string.practice_tally, practice.missed, practice.known, practice.easy)
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(top = 24.dp).semantics(mergeDescendants = true) { contentDescription = tally }) {
            Tally(R.drawable.ic_close, practice.missed, MaterialTheme.colorScheme.error)
            Tally(R.drawable.ic_check, practice.known, MaterialTheme.colorScheme.onSurface)
            Tally(R.drawable.ic_star, practice.easy, MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.weight(1f))
        // What played before, unless that's where practice left off.
        practice.before?.takeIf { before -> practice.songs.lastOrNull()?.let { it.title == before.song.title && it.artist == before.song.artist } != true }?.let { before ->
            Surface(onClick = actions.onBackToBefore, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)) {
                    CoverArt(before.song.artworkUrl, actions.loadArtwork, size = 32.dp)
                    Text(stringResource(R.string.practice_back_to, before.song.title), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    AppIcon(R.drawable.ic_play, contentDescription = null, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/** Up to three covers of the songs practised, fanned out. */
@Composable
private fun Covers(songs: List<SavedLine>, actions: ScreenActions) {
    val shown = songs.take(3)
    Row(horizontalArrangement = Arrangement.spacedBy((-16).dp)) {
        shown.forEachIndexed { index, song ->
            val turn = (index - (shown.size - 1) / 2f) * 8f
            CoverArt(song.artworkUrl, actions.loadArtwork, size = 72.dp, modifier = Modifier.rotate(turn).clip(RoundedCornerShape(12.dp)))
        }
    }
}

/** How many were answered one way, by its icon. */
@Composable
private fun Tally(icon: Int, count: Int, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppIcon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Text(count.toString(), style = MaterialTheme.typography.titleMedium, color = color, modifier = Modifier.padding(start = 4.dp))
    }
}
