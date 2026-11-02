// Small readers for the JSON services answer with, which is untrusted: each one gives back
// something of the right kind or an empty one, like Android's optString and optJSONArray.

export type Obj = { readonly [key: string]: unknown };

export function obj(value: unknown): Obj | undefined {
  return typeof value === "object" && value !== null && !Array.isArray(value) ? (value as Obj) : undefined;
}

export function list(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

export function objects(value: unknown): Obj[] {
  return list(value).flatMap((item) => {
    const found = obj(item);
    return found === undefined ? [] : [found];
  });
}

export function str(value: unknown): string {
  return typeof value === "string" ? value : typeof value === "number" ? String(value) : "";
}

export function num(value: unknown): number {
  const parsed = typeof value === "number" ? value : typeof value === "string" ? Number(value) : 0;
  return Number.isFinite(parsed) ? parsed : 0;
}

/** A YouTube text, as it comes in pieces, "runs", put back together. */
export function runsText(value: unknown): string {
  return objects(obj(value)?.["runs"]).map((run) => str(run["text"])).join("");
}

/** The first object anywhere in [value] that [pick] picks. */
export function findObject(value: unknown, pick: (found: Obj) => Obj | undefined): Obj | undefined {
  const found = obj(value);
  if (found !== undefined) {
    const picked = pick(found);
    if (picked !== undefined) return picked;
    for (const key of Object.keys(found)) {
      const inside = findObject(found[key], pick);
      if (inside !== undefined) return inside;
    }
    return undefined;
  }
  for (const item of list(value)) {
    const inside = findObject(item, pick);
    if (inside !== undefined) return inside;
  }
  return undefined;
}

/** What kind of video YouTube Music says [answer] is about: ATV, a song; OMV, an official video; UGC, someone's upload. */
export function musicVideoTypeIn(answer: unknown): string | undefined {
  return /"musicVideoType":"MUSIC_VIDEO_TYPE_([A-Z_]+)"/.exec(JSON.stringify(answer))?.[1];
}

/** The result rows of a YouTube Music search, which sit at varying depths depending on how it is sectioned. */
export function listItems(value: unknown): Obj[] {
  const found = obj(value);
  if (found !== undefined) {
    const own = obj(found["musicResponsiveListItemRenderer"]);
    return [...(own === undefined ? [] : [own]), ...Object.keys(found).flatMap((key) => listItems(found[key]))];
  }
  return list(value).flatMap(listItems);
}
