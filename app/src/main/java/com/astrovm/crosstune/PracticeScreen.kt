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
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import java.util.Locale

internal const val PRACTICE_CARD_TAG = "practice-card"

/**
 * Saved lines practised as cards, over the color of the song they're from. Every card asks the same:
 * the line as written; turned, it plays in the music app, and shows how it reads and what it means.
 * Done, it says how many, and the music played before is a tap away.
 */
@Composable
internal fun PracticeScreen(state: UiState, actions: ScreenActions) {
    val practice = state.practice ?: return
    BackHandler(onBack = actions.onStopPractice)
    val cover = (practice.line ?: practice.lastSong)?.artworkUrl
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
                    else Card(line, shown, state.learning.romajiTyped, actions)
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

/**
 * A card: the song it's from and the line as written; once turned, its sound, how it reads and what
 * it means. Turned, it's said whether it was known, each button saying when it's back.
 */
@Composable
private fun Card(line: SavedLine, practice: Practice, romajiTyped: Boolean, actions: ScreenActions) {
    val haptics = LocalHapticFeedback.current
    val answer = { grade: Grade ->
        haptics.performHapticFeedback(if (grade == Grade.AGAIN) HapticFeedbackType.Reject else HapticFeedbackType.Confirm)
        actions.onGradePractice(grade)
    }
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .testTag(PRACTICE_CARD_TAG)
                // Tapped, it turns: a button with a name for TalkBack, as Show answer is.
                .then(
                    if (practice.turned) Modifier
                    else Modifier.clip(MaterialTheme.shapes.extraLarge).clickable(onClickLabel = stringResource(R.string.practice_show), onClick = actions.onTurnPractice)
                )
        ) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(24.dp).animateContentSize(Motion.size), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                SongChip(line, actions)
                CardFront(line)
                // Heard, the line says how it reads, so it's part of the answer.
                if (practice.turned) {
                    HorizontalDivider()
                    LineSound(line, practice.sound, actions.onReplayLine)
                    CardBack(line, romajiTyped, actions.onReadCardAs)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
            if (!practice.turned) {
                Button(onClick = actions.onTurnPractice, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(stringResource(R.string.practice_show)) }
            } else {
                val locale = LocalConfiguration.current.locales[0]
                // Four, as Anki has them, each saying when the line's back; Good, the usual one, stands out.
                GRADES.forEach { (grade, label) ->
                    val modifier = Modifier.weight(1f).height(56.dp)
                    val content: @Composable RowScope.() -> Unit = { GradeLabel(label, waitText(SavedLinesStore.waitFor(line, grade), locale)) }
                    if (grade == Grade.GOOD) Button(onClick = { answer(grade) }, modifier = modifier, contentPadding = GRADE_PADDING, content = content)
                    else OutlinedButton(onClick = { answer(grade) }, modifier = modifier, contentPadding = GRADE_PADDING, content = content)
                }
            }
        }
    }
}

/** What's asked: the line as written, nothing over it; a word big, with the line it's sung in under it. */
@Composable
private fun CardFront(line: SavedLine) {
    val sungIn = line.line
    if (sungIn == null) {
        Text(line.text, style = MaterialTheme.typography.headlineMedium.writing(line.text), fontWeight = FontWeight.Bold)
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(line.text, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Text(sungIn, style = MaterialTheme.typography.bodyLarge.writing(sungIn), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The answer: the words again with how they read over them, in Latin letters, as said and as
 * typed, and what they mean.
 * A reading that isn't how it's sung can be put right, kanji by kanji.
 */
@Composable
private fun CardBack(line: SavedLine, romajiTyped: Boolean, onReadAs: (List<String>) -> Unit) {
    var fixing by rememberSaveable(line.text, line.title, line.artist) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold).writing(line.text)
        val ruby = line.ruby ?: line.reading?.takeIf { line.line != null }?.let { listOf(Ruby(line.text, it)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f)) {
                if (ruby != null) RubyLine(ruby, style, scale = CARD_READING_SCALE)
                else line.reading?.let { Text(it, style = MaterialTheme.typography.titleLarge) }
            }
            if (line.readable.isNotEmpty() && !fixing) {
                IconButton(onClick = { fixing = true }) { AppIcon(R.drawable.ic_edit, contentDescription = stringResource(R.string.practice_fix_reading)) }
            }
        }
        if (fixing) ReadingFields(line, onSave = { readings -> fixing = false; onReadAs(readings) }, onCancel = { fixing = false })
        // As said and as typed, where they're spelled otherwise.
        bothRomaji(line.romanized, line.typed, romajiTyped).forEach { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        line.translation?.let { Text(it, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary) }
    }
}

/** Each kanji of [line] with its reading, to put right as it's sung; saved only when each is kana. */
@Composable
private fun ReadingFields(line: SavedLine, onSave: (List<String>) -> Unit, onCancel: () -> Unit) {
    val parts = line.readable
    val readings = rememberSaveable(line.text, line.title, line.artist, saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() })) {
        parts.map { it.reading.orEmpty() }.toMutableStateList()
    }
    val kana = readings.all { reading -> reading.isNotBlank() && reading.trim().all(Readings::isKana) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parts.forEachIndexed { index, part ->
            OutlinedTextField(
                value = readings[index],
                onValueChange = { readings[index] = it },
                label = { Text(part.text) },
                isError = readings[index].isNotBlank() && !readings[index].trim().all(Readings::isKana),
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Row(modifier = Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel_button)) }
            TextButton(onClick = { onSave(readings.toList()) }, enabled = kana) { Text(stringResource(R.string.save_button)) }
        }
    }
}

/** Readings over a card's words, bigger than over lyrics, as they're the answer. */
private const val CARD_READING_SCALE = 0.55f

/**
 * How the line sounds: the music app's name while it gets there; then a button that fills as the
 * line plays, to play it again once it has. Nothing when it can't be played.
 */
@Composable
private fun LineSound(line: SavedLine, sound: LineSound, onPlay: () -> Unit) {
    if (sound == LineSound.LOADING) {
        AppLoading(line.app)
    } else if (sound != LineSound.NONE) {
        SoundButton(line, sound, onPlay)
    }
}

/** The music app getting to the song: its icon and "Opening in" its name, when it's known, or only that it's on its way. */
@Composable
private fun AppLoading(app: String?) {
    val context = LocalContext.current
    val known = remember(app) {
        app?.let { name ->
            runCatching {
                val info = context.packageManager.getApplicationInfo(name, 0)
                context.packageManager.getApplicationLabel(info).toString() to context.packageManager.getApplicationIcon(info).toBitmap(72, 72).asImageBitmap()
            }.getOrNull()
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.height(56.dp)) {
        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        known?.let { (label, icon) ->
            Image(icon, contentDescription = null, modifier = Modifier.size(20.dp).clip(CircleShape))
            Text(stringResource(R.string.practice_loading, label), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The line's sound: a ring that fills as it plays, with bars that move while it does, and a play button once it's played. */
@Composable
private fun SoundButton(line: SavedLine, sound: LineSound, onPlay: () -> Unit) {
    val played = remember(line, sound) { Animatable(0f) }
    LaunchedEffect(line, sound) {
        if (sound == LineSound.PLAYING) played.animateTo(1f, tween(((line.endMs ?: 0L) - (line.startMs ?: 0L)).toInt().coerceAtLeast(0), easing = LinearEasing))
    }
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(SOUND_SIZE + 8.dp)) {
        if (sound == LineSound.PLAYING) CircularProgressIndicator(progress = { played.value }, strokeWidth = 3.dp, modifier = Modifier.size(SOUND_SIZE + 8.dp))
        Surface(onClick = onPlay, enabled = sound == LineSound.PLAYED, shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(SOUND_SIZE)) {
            Box(contentAlignment = Alignment.Center) {
                if (sound == LineSound.PLAYING) Bars(SOUND_SIZE / 3)
                else Icon(painterResource(R.drawable.ic_play), contentDescription = stringResource(R.string.practice_play), modifier = Modifier.size(SOUND_SIZE / 2))
            }
        }
    }
}

private val SOUND_SIZE = 48.dp

/** Bars rising and falling, out of step, while a line plays. */
@Composable
private fun Bars(height: Dp) {
    val beat = rememberInfiniteTransition(label = "bars")
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(height)) {
        repeat(BARS) { index ->
            val level by beat.animateFloat(0.25f, 1f, infiniteRepeatable(tween(450, delayMillis = index * 90), RepeatMode.Reverse), label = "bar $index")
            Box(Modifier.width(3.dp).height(height * level).clip(CircleShape).background(MaterialTheme.colorScheme.onPrimaryContainer))
        }
    }
}

private const val BARS = 4

/** The song a card's from: its cover and title. */
@Composable
private fun SongChip(line: SavedLine, actions: ScreenActions) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CoverArt(line.artworkUrl, actions.loadArtwork, size = 24.dp)
        Text(line.title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private val GRADES = listOf(Grade.AGAIN to R.string.practice_again, Grade.HARD to R.string.practice_hard, Grade.GOOD to R.string.practice_good, Grade.EASY to R.string.practice_easy)

/** Little room around a grade's words, so four fit side by side. */
private val GRADE_PADDING = PaddingValues(horizontal = 4.dp)

/** A grade button's words: how well it was known, and when it's back if so. */
@Composable
private fun GradeLabel(label: Int, wait: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(wait, style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp))
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

/** All done: how many were practised, and the music played before, a tap away. */
@Composable
private fun Done(practice: Practice, actions: ScreenActions) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Spacer(Modifier.weight(1f))
        Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
        Text(
            stringResource(R.string.practice_done, practice.done),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp)
        )
        Spacer(Modifier.weight(1f))
        // What played before, unless that's where practice left off.
        practice.before?.takeIf { before -> practice.lastSong?.let { it.title == before.song.title && it.artist == before.song.artist } != true }?.let { before ->
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
