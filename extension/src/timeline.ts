// SoundCloud's player keeps no <audio> or <video> in the page, so there is nothing to read the position
// from. Its progress bar says it, in whole seconds, which is what the words follow when there is no element.

/** The progress bar of SoundCloud's player, which holds where the song is and how long it lasts. */
export const PROGRESS_BAR = ".playbackTimeline__progressWrapper";

/** Where a player says it is, in seconds. */
export interface Reading {
  seconds: number;
  lengthSeconds: number;
}

/** What a progress bar says, from its aria-valuenow and aria-valuemax; null while it's empty or says nothing sensible. */
export function readingOf(now: string | null, max: string | null): Reading | null {
  if (now === null || max === null || now.trim() === "" || max.trim() === "") return null;
  const seconds = Number(now);
  const lengthSeconds = Number(max);
  if (!Number.isFinite(seconds) || !Number.isFinite(lengthSeconds) || seconds < 0 || lengthSeconds <= 0) return null;
  return { seconds, lengthSeconds };
}

/**
 * The progress bar moves a whole second at a time, but the words change within one. From the moment it
 * last moved, the part of a second gone since is known, which puts the position within a few milliseconds.
 */
export class SecondsClock {
  #seconds = -1;
  #movedAt = 0;

  /** The position in milliseconds, when the bar says [seconds] at [at] (a clock in milliseconds). */
  positionMs(seconds: number, playing: boolean, at: number): number {
    // Paused, the part of a second isn't running; once it goes again, it starts over from the bar's next move.
    if (seconds !== this.#seconds || !playing) {
      this.#seconds = seconds;
      this.#movedAt = at;
    }
    return seconds * 1_000 + Math.min(Math.max(at - this.#movedAt, 0), 999);
  }
}
