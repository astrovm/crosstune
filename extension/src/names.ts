// How two services' names for one song are compared. Ported from the app's SongNames.kt, so the
// same two names are the same song here and there. Left out: what only the app's fallbacks need
// (loose matching for queues, kanji, names in another script, covers).

const leadingArticle = /^the\s+/i;
const artistSeparator = /\s*(?:,|&|\+|\/|\bx\b|\bfeat\.?|\bft\.?|\bfeaturing\b)\s*/i;
const marks = /\p{M}+/gu;
const lettersAndDigits = /[^\p{L}\p{N}]+/gu;
const wordSplit = /[^a-z0-9]+/;

/** "Zion and Lennox" and "Zion & Lennox" are two names, whichever language says so. */
const conjunction = /\s+(?:and|y|und|et)\s+/i;

/** A guest credited in the title, as "(feat. Emel)", which some services leave out. */
const featuring = /\s*[(\[]\s*(?:feat|ft|featuring)\b[^()\[\]]*[)\]]/gi;

const EDITION_WORDS = String.raw`original|(?:album|single|short|original|radio)\s+(?:version|edit|mix)`;
/** The song a film or show opens, ends or plays it in, or its soundtrack. */
const TIE_IN = String.raw`主[題题][曲歌]|挿入歌|插曲|片[頭头尾]曲|\bOST\b|\btheme\s+song\b`;
/** A letter of a tag: none of a bracket, nor the dash before another part of the name. */
const IN_TAG = String.raw`(?:(?!\s[-–]\s)[^()\[\]])`;

/**
 * A trailing tag that names no other recording: "Remastered 2012" and "Mono" anywhere in it, a
 * whole "Album Version" or "Original", or what film or show it's the song of. "Live",
 * "Acoustic" or "Remix" are other recordings, so they stay.
 */
const editionTag = new RegExp(
  String.raw`(?:\s+[-–]\s+|\s*[(\[])\s*(?:${EDITION_WORDS}|${IN_TAG}*\b(?:remaster(?:ed|iz(?:ed|ado|ada))?|mono|stereo)\b${IN_TAG}*|${IN_TAG}*(?:${TIE_IN})${IN_TAG}*)\s*[)\]]?$`,
  "i",
);

/** Words in a tag that make it another recording, though it also says it's remastered or in mono. */
const OTHER_RECORDING = new Set(["live", "remix", "acoustic", "demo", "instrumental", "unplugged", "karaoke", "cover", "session", "sessions"]);

/** What a name adds at its end, in brackets or after a dash: "(2018 Remix)", "[Live]", " - Remastered". */
const trailingTag = /(?:\s*[(\[][^()\[\]]*[)\]]|\s+[-–]\s+[^-–]*)$/;

/** Letters and digits only, no marks and no case: "Édition" and "Edition" are one word. */
export function normalize(text: string): string {
  return text.normalize("NFD").replace(marks, "").toLowerCase().replace(lettersAndDigits, "");
}

/** The words written in letters and digits: another script's are what the other title spells out. */
export function words(title: string): Set<string> {
  return new Set(title.normalize("NFD").replace(marks, "").toLowerCase().split(wordSplit).filter((word) => word !== ""));
}

/**
 * "Song - Remastered 2012", "Song (2003 Remaster)" and "Song (feat. A)" are the song itself;
 * "Song (Live; 2013 Remaster)" and "Song - Stereo MC's Remix" are other recordings, so they keep
 * their tags.
 */
export function withoutEditionTag(title: string): string {
  let stripped = title;
  // "Song - 2012 Remaster (Mono)" has two, and a title that's only a tag is left as it is.
  for (;;) {
    const unfeatured = stripped.replace(featuring, "");
    const tag = editionTag.exec(unfeatured)?.[0] ?? "";
    if ([...words(tag)].some((word) => OTHER_RECORDING.has(word))) return unfeatured;
    const shorter = unfeatured.replace(editionTag, "");
    if (shorter === stripped || shorter.trim() === "") return stripped;
    stripped = shorter;
  }
}

/**
 * "A & B feat. C" is credited as just "A" on some services, so each name counts on its own, and
 * "The" doesn't count. The whole credit counts too, for a name that holds a separator: "125, Rue
 * Montmartre" is one artist, not a "125" and a "Rue Montmartre".
 */
export function artists(credit: string): Set<string> {
  const names = [...artistNames(credit).flatMap((name) => name.split(conjunction)), credit];
  return new Set(names.map((name) => normalize(name.replace(leadingArticle, ""))).filter((name) => name !== ""));
}

/** Each name in a credit, split on the separators services use between them. */
export function artistNames(credit: string): string[] {
  return credit.split(artistSeparator).map((name) => name.trim()).filter((name) => name !== "");
}

/** The same name, whatever punctuation, spacing, accents or case write it. */
export function same(one: string, other: string): boolean {
  return normalize(withoutEditionTag(one)) === normalize(withoutEditionTag(other));
}

/** The name without what it adds in brackets or after a dash: "Song (2018 Remix) (2024 Remaster)" is "Song". */
export function bareTitle(title: string): string {
  let bare = title.trim();
  for (;;) {
    const shorter = bare.replace(trailingTag, "").trim();
    if (shorter === bare || shorter === "") return bare;
    bare = shorter;
  }
}

/**
 * [candidate] is the recording [title] names, also when it says what version that is other than in
 * its name: "Song (Remix)" is "Song (2022 Remix)", or "Song" on the album "NEVER ENDING SUMMER
 * REMIX". The original "Song", on an album that doesn't say so, is another.
 */
export function sameVersion(title: string, candidate: string, album = ""): boolean {
  if (same(title, candidate)) return true;
  const named = withoutEditionTag(title).trim();
  const bare = bareTitle(named);
  if (bare === named || normalize(bare) !== normalize(bareTitle(withoutEditionTag(candidate)))) return false;
  // What the tags say, "(Remix)", has to be said there too.
  const version = words(named.slice(bare.length));
  if (version.size === 0) return false;
  const said = new Set([...words(candidate), ...words(album)]);
  return [...version].every((word) => said.has(word));
}

