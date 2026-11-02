import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import { test } from "node:test";

const root = new URL("../_locales/", import.meta.url);
const messages = (locale: string): Record<string, { message: string }> =>
  JSON.parse(readFileSync(new URL(`${locale}/messages.json`, root), "utf8"));
const english = Object.keys(messages("en")).sort();
const locales = readdirSync(root);

test("the extension speaks the app's 16 languages", () => {
  assert.deepEqual(locales.sort(), ["de", "en", "es", "fr", "hi", "id", "it", "ja", "ko", "nl", "pl", "pt_BR", "ru", "tr", "zh_CN", "zh_TW"]);
});

for (const locale of locales) {
  test(`${locale} says everything English does, within the stores' limits`, () => {
    const own = messages(locale);
    assert.deepEqual(Object.keys(own).sort(), english);
    for (const [key, { message }] of Object.entries(own)) assert.ok(message.trim().length > 0, key);
    // The Chrome Web Store turns away a description longer than this.
    assert.ok((own["extDescription"]?.message ?? "").length <= 132);
  });
}
