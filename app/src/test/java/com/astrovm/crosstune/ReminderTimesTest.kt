package com.astrovm.crosstune

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Daily occurrences stay ordered in real time, even when a clock gap crosses midnight. */
class ReminderTimesTest {
    private fun check(zone: ZoneId, minute: Int, now: Long) {
        val context = "$zone $minute ${Instant.ofEpochMilli(now)}"
        val latest = latestReminderAt(minute, now, zone)
        val next = nextReminderAt(minute, now, zone)
        assertTrue(context, latest <= now)
        assertTrue(context, next > now)
        assertEquals(context, latest, nextReminderAt(minute, latest - 1, zone))
        assertEquals(context, next, nextReminderAt(minute, latest, zone))
        assertEquals(context, latest, latestReminderAt(minute, next - 1, zone))
        assertEquals(context, next, latestReminderAt(minute, next, zone))
    }

    @Test
    fun everyTransitionKeepsDailyOccurrencesOrdered() {
        val first = Instant.parse("1990-01-01T00:00:00Z")
        val end = Instant.parse("2041-01-01T00:00:00Z")
        for (name in ZoneId.getAvailableZoneIds().sorted()) {
            val zone = ZoneId.of(name)
            var transition = zone.rules.nextTransition(first)
            while (transition != null && transition.instant < end) {
                val before = transition.dateTimeBefore
                val after = transition.dateTimeAfter
                val minutes = setOf(0, 30, 90, 150, 1410, before.hour * 60 + before.minute, after.hour * 60 + after.minute)
                for (delta in listOf(-3_600_000L, -1L, 0L, 1L, 3_600_000L)) {
                    val now = transition.instant.toEpochMilli() + delta
                    for (minute in minutes) check(zone, minute, now)
                }
                transition = zone.rules.nextTransition(transition.instant.plusSeconds(1))
            }
        }
    }
}
