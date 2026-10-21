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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.ButtonDefaults
import androidx.annotation.StringRes
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
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
import kotlin.math.roundToLong

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
    var optionsOpen by rememberSaveable { mutableStateOf(false) }
    var preview by remember { mutableStateOf<CardPreview?>(null) }
    LaunchedEffect(optionsOpen) { if (!optionsOpen) preview = null }
    val optionLineKey = practice.line?.let { "${it.text}|${it.title}|${it.artist}" }
    // A new card starts clear. The menu stays open only while this one is on screen.
    LaunchedEffect(optionLineKey) { optionsOpen = false }
    val cover = (practice.line ?: practice.lastSong)?.artworkUrl
    val tint by animateColorAsState(coverColor(cover, actions.loadArtwork) ?: MaterialTheme.colorScheme.primary, tween(durationMillis = 700), label = "practice tint")
    val ground = MaterialTheme.colorScheme.surface
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(0f to tint.copy(alpha = 0.5f), 0.55f to tint.copy(alpha = 0.12f), 1f to ground))) {
            Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().widthIn(max = ContentMaxWidth).align(Alignment.TopCenter)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp, end = 4.dp)) {
                    IconButton(onClick = actions.onStopPractice) { AppIcon(R.drawable.ic_close, contentDescription = stringResource(R.string.floating_close)) }
                    val line = practice.line
                    if (practice.total > 0 && line != null) {
                        Progress(practice, Modifier.weight(1f).padding(start = 8.dp))
                        IconButton(onClick = { optionsOpen = !optionsOpen }) {
                            AppIcon(R.drawable.ic_tune, contentDescription = stringResource(R.string.practice_card_options))
                        }
                    }
                }
                val optionLine = practice.line
                if (optionsOpen && optionLine != null) {
                    CardTypesPanel(optionLine, state.cardTypes, practice.turned, actions, onClose = { optionsOpen = false }, onPreview = { preview = it }, canPlay = practice.sound != LineSound.NONE)
                }
                AnimatedContent(targetState = practice, contentKey = ::shownAs, transitionSpec = { slide(forward = true) }, label = "card", modifier = Modifier.weight(1f)) { shown ->
                    val line = shown.line
                    if (shown.total == 0) CaughtUp()
                    else if (line == null) Done(shown, actions)
                    else Card(line, shown, state.cardTypes, actions, preview.takeIf { optionsOpen })
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
 * A card: the song it's from and its front; once turned, its back comes in below. A side with its sound plays the line. Turned, it's said whether it was known, each button saying when it's back.
 */
@Composable
private fun Card(line: SavedLine, practice: Practice, types: List<CardType>, actions: ScreenActions, preview: CardPreview? = null) {
    val type = preview?.type ?: types.firstOrNull { it.id == line.cardType } ?: READ_IT
    val cards = type.options
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
                    if (practice.turned || preview != null) Modifier
                    else Modifier.clip(MaterialTheme.shapes.extraLarge).clickable(onClickLabel = stringResource(R.string.practice_show), onClick = actions.onTurnPractice)
                )
        ) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(24.dp).animateContentSize(Motion.size), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                SongChip(line, actions, kind = type.takeIf { types.count { it.on } > 1 })
                // Each side plays the line when it has its sound; a front with nothing to show or play shows the words.
                if (preview != null) {
                    val side = if (preview.back) cards.back else cards.front
                    if (side.sound) LineSound(line, practice.sound, actions.onReplayLine)
                    CardSideContent(line, side, preview.back)
                } else {
                val front = cards.front
                val plays = front.sound && practice.sound != LineSound.NONE
                if (plays) LineSound(line, practice.sound, actions.onReplayLine)
                if (!front.bare) CardSideContent(line, front, back = false)
                else if (!plays) CardSideContent(line, CardSide(), back = false)
                // Turned, the other side comes in below the front, which stays.
                if (practice.turned) {
                    HorizontalDivider()
                    if (cards.back.sound) LineSound(line, practice.sound, actions.onReplayLine)
                    CardSideContent(line, cards.back, back = true)
                }
                }
            }
        }
        if (preview == null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
            if (!practice.turned) {
                Button(onClick = actions.onTurnPractice, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.practice_show)) }
            } else {
                val locale = LocalConfiguration.current.locales[0]
                // Four, in Anki's colors, each saying when the line's back.
                val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
                GRADES.forEach { (grade, label) ->
                    val (fill, text) = gradeColors(grade, dark)
                    Button(
                        onClick = { answer(grade) },
                        modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = fill, contentColor = text),
                        contentPadding = GRADE_PADDING
                    ) { GradeLabel(label, waitText(SavedLinesStore.waitFor(line, grade), locale)) }
                }
            }
        }
    }
}

/** Only the text layers picked for this side, previewed while its settings are open. */
@Composable
private fun CardSideContent(line: SavedLine, side: CardSide, back: Boolean) {
    val base = if (line.line != null) MaterialTheme.typography.displaySmall else if (back) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium
    val style = base.copy(fontSize = base.fontSize * side.wordsScale, lineHeight = base.lineHeight * side.wordsScale, fontWeight = FontWeight.Bold).writing(line.text)
    val shown = line.shownAs(side.wordsAs, side.romajiTyped)
    val ruby = line.ruby ?: line.reading?.let { listOf(Ruby(line.text, it)) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (side.words) {
            if (side.wordsAs == WordsAs.WRITTEN && side.readings && ruby != null) RubyLine(ruby, style, scale = side.readingScale)
            else Text(shown, style = style)
            line.line?.let { Text(it, style = MaterialTheme.typography.bodyLarge.writing(it), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (side.latin && (!side.words || side.wordsAs != WordsAs.LATIN)) {
            line.latin(side.romajiTyped)?.let { Text(it, style = MaterialTheme.typography.bodyLarge.run { copy(fontSize = fontSize * side.wordsScale, lineHeight = lineHeight * side.wordsScale) }, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (side.translation) line.translation?.let {
            Text(it, style = MaterialTheme.typography.titleLarge.run { copy(fontSize = fontSize * side.translationScale, lineHeight = lineHeight * side.translationScale) }, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** Each kanji of [line] with its reading, to put right as it's sung; saved only when each is kana. */
@Composable
internal fun ReadingFields(line: SavedLine, onSave: (List<String>) -> Unit, onCancel: () -> Unit) {
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
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.heightIn(min = 56.dp)) {
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
private fun SongChip(line: SavedLine, actions: ScreenActions, kind: CardType? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        CoverArt(line.artworkUrl, actions.loadArtwork, size = 24.dp)
        Text(line.title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        // With more than one kind of card, which this is.
        kind?.let { Text(it.title(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1) }
    }
}

private val GRADES = listOf(Grade.AGAIN to R.string.practice_again, Grade.HARD to R.string.practice_hard, Grade.GOOD to R.string.practice_good, Grade.EASY to R.string.practice_easy)

/** Little room around a grade's words, so four fit side by side. */
private val GRADE_PADDING = PaddingValues(horizontal = 4.dp)

/** A grade button's fill and words, the same red, orange, green and blue as AnkiDroid's, in light and dark. */
private fun gradeColors(grade: Grade, dark: Boolean): Pair<Color, Color> = when (grade) {
    Grade.AGAIN -> if (dark) Color(0xFF462828) to Color(0xFFEF9A9A) else Color(0xFFFFCDD2) to Color(0xFFB71C1C)
    Grade.HARD -> if (dark) Color(0xFF4E3122) to Color(0xFFFFCC80) else Color(0xFFFFE0B2) to Color(0xFFB33800)
    Grade.GOOD -> if (dark) Color(0xFF2C3B2C) to Color(0xFFC5E1A5) else Color(0xFFC8E6C9) to Color(0xFF1B5E20)
    Grade.EASY -> if (dark) Color(0xFF223946) to Color(0xFF80D8FF) else Color(0xFFB3E5FC) to Color(0xFF01579B)
}

/** A grade button's words: when the line's back, small, over how well it was known, as in Anki. */
@Composable
private fun GradeLabel(label: Int, wait: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(wait, style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp))
        Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** How long [ms] is, short, in minutes, hours, days, months or years, as [locale] writes them, e.g. "10m", "3d" or "1 yr". */
internal fun waitText(ms: Long, locale: Locale): String {
    val format = android.icu.text.MeasureFormat.getInstance(locale, android.icu.text.MeasureFormat.FormatWidth.NARROW)
    // Months and years aren't narrow, as that writes a month as "1m", which is a minute.
    val long = android.icu.text.MeasureFormat.getInstance(locale, android.icu.text.MeasureFormat.FormatWidth.SHORT)
    val minutes = (ms / 60_000.0).roundToLong()
    val days = minutes / (24 * 60)
    return when {
        minutes < 60 -> format.format(Measure(minutes, MeasureUnit.MINUTE))
        minutes < 24 * 60 -> format.format(Measure(minutes / 60, MeasureUnit.HOUR))
        days < 30 -> format.format(Measure(days, MeasureUnit.DAY))
        days < 365 -> long.format(Measure(days / 30, MeasureUnit.MONTH))
        else -> long.format(Measure(days / 365, MeasureUnit.YEAR))
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
