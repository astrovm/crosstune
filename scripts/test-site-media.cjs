// Executes the site's actual script in a browser fixture; no npm dependencies.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const test = require('node:test');
const html = fs.readFileSync(require('node:path').join(__dirname, '../site/index.html'), 'utf8');
const source = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].at(-1)[1];

function page({ dark = false, override, reduced = false, blockedStorage = false, language = 'en' } = {}, script = source) {
  const preference = matches => ({ matches, listeners: [], addEventListener(_, fn) { this.listeners.push(fn); }, change(value) { this.matches = value; this.listeners.forEach(fn => fn()); } });
  const os = preference(dark), motion = preference(reduced);
  const element = () => ({ listeners: {}, attributes: {}, style: {}, classList: { add() {}, remove() {} },
    addEventListener(name, fn) { this.listeners[name] = fn; },
    setAttribute(name, value) { this.attributes[name] = value; },
    getAttribute(name) { return this.attributes[name] ?? null; },
    removeAttribute(name) { delete this.attributes[name]; },
    getBoundingClientRect() { return { top: 0 }; }, click() { this.listeners.click(); } });
  const button = element(), play = element(), steps = element(), container = element(), hero = element();
  steps.hidden = true; hero.querySelector = () => container;
  const videos = ['home', 'lyrics'].map(name => Object.assign(element(), {
    dataset: { clip: `/img/${language}/${name}` }, loads: 0, plays: 0, paused: true,
    load() { this.loads++; }, pause() { this.paused = true; }, play() { this.plays++; this.paused = false; return Promise.resolve(); },
    attributes: { src: `/img/${language}/${name}-light.mp4` }
  }));
  const dataset = override ? { theme: override } : {};
  const storage = {};
  vm.runInNewContext(script, {
    document: { documentElement: { dataset }, fonts: { ready: Promise.resolve() }, getElementById: () => steps,
      querySelector: selector => ({ '.theme': button, '.play-toggle': play, '#google-play': steps, '.hero': hero })[selector],
      querySelectorAll: () => videos },
    matchMedia: query => query.includes('reduced-motion') ? motion : os,
    localStorage: { setItem(key, value) { if (blockedStorage) throw Error('blocked'); storage[key] = value; } },
    addEventListener() {}, location: { hash: '' }
  });
  return { videos, button, dataset, os, motion, storage };
}
function expectMedia(p, theme, reduced = p.motion.matches) {
  for (const video of p.videos) {
    assert.equal(video.poster, `${video.dataset.clip}-${theme}.jpg`);
    assert.equal(video.getAttribute('src'), reduced ? null : `${video.dataset.clip}-${theme}.mp4`);
    assert.equal(video.paused, reduced);
  }
}

test('system and explicit themes choose matching localized media', () => {
  for (const language of ['en', 'es', 'pt-br', 'de', 'fr', 'it', 'nl', 'pl', 'ru', 'tr', 'id', 'hi', 'ja', 'ko', 'zh-cn', 'zh-tw']) {
    for (const dark of [false, true]) {
      expectMedia(page({ dark, language }), dark ? 'dark' : 'light');
      expectMedia(page({ dark, override: 'light', language }), 'light');
      expectMedia(page({ dark, override: 'dark', language }), 'dark');
    }
  }
});
test('theme button saves the choice and follows it despite system changes', () => {
  const p = page();
  p.button.click(); expectMedia(p, 'dark'); assert.equal(p.storage.theme, 'dark');
  const loads = p.videos.map(v => v.loads);
  p.os.change(true); assert.deepEqual(p.videos.map(v => v.loads), loads);
  p.button.click(); expectMedia(p, 'light'); assert.equal(p.storage.theme, 'light');
  p.os.change(true); expectMedia(p, 'light');
});
test('reduced motion uses the matching still and never starts video', () => {
  const p = page({ reduced: true });
  expectMedia(p, 'light'); assert.equal(p.videos.reduce((sum, v) => sum + v.plays, 0), 0);
  p.button.click(); expectMedia(p, 'dark');
  p.motion.change(false); expectMedia(p, 'dark');
  p.motion.change(true); expectMedia(p, 'dark');
});
test('storage failure does not prevent switching themes', () => {
  const p = page({ blockedStorage: true });
  p.button.click(); expectMedia(p, 'dark');
});
test('generated event sequences always keep posters, sources and motion consistent', () => {
  let seed = 202;
  for (let trial = 0; trial < 20; trial++) {
    const p = page({ dark: trial % 2 === 0, reduced: trial % 3 === 0 });
    for (let step = 0; step < 50; step++) {
      seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0;
      if (seed % 3 === 0) p.button.click();
      else if (seed % 3 === 1) p.os.change(!p.os.matches);
      else p.motion.change(!p.motion.matches);
      expectMedia(p, p.dataset.theme || (p.os.matches ? 'dark' : 'light'));
    }
  }
});
module.exports = { page, expectMedia, source };
