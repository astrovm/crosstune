// What the lyrics panel shows, apart from how it draws it: which one state, and which line is lit.

import { fitsLength, type Line, type Lyrics } from "./lyrics.ts";
import type { Position, TabSong } from "./song.ts";

export { fitsLength } from "./lyrics.ts";

export type View = "access" | "noSong" | "offline" | "loading" | "words" | "none" | "instrumental" | "unavailable";

export interface ViewInput {
  /** Whether the browser lets the extension read the music sites: Firefox asks after installing. */
  access: boolean;
  online: boolean;
  song: TabSong | null;
  /** What was found for [song], or null while looking. */
  lyrics: Lyrics | null;
}

export function viewOf({ access, online, song, lyrics }: ViewInput): View {
  if (!access) return "access";
  if (song === null) return "noSong";
  if (lyrics?.kind === "found") return "words";
  if (!online && (lyrics === null || lyrics.kind === "unavailable")) return "offline";
  if (lyrics === null) return "loading";
  if (lyrics.kind === "none") return "none";
  return lyrics.kind;
}

/** A line lights a moment before it's sung, so it's read by then. */
const LEAD_MS = 300;

/** Which of [lines], in order, is being sung at [positionMs]; -1 before the first. */
export function activeLine(lines: readonly Line[], positionMs: number): number {
  const at = positionMs + LEAD_MS;
  let found = -1;
  for (let low = 0, high = lines.length - 1; low <= high; ) {
    const middle = (low + high) >> 1;
    if ((lines[middle]?.timeMs ?? 0) <= at) {
      found = middle;
      low = middle + 1;
    } else high = middle - 1;
  }
  return found;
}

/** Whether lines can follow the music: the site says where it is, and they're timed for this recording. */
export function follows(lyrics: Lyrics | null, position: Position | null): boolean {
  return position !== null && lyrics?.kind === "found" && lyrics.lines.length > 0 && fitsLength(lyrics.lengthMs, position.durationMs);
}
