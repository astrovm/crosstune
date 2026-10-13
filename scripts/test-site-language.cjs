// Runs the navigation script shipped in every generated language page.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');

const codes = ['en', 'es', 'pt-br', 'de', 'fr', 'it', 'nl', 'pl', 'ru', 'tr', 'id', 'hi', 'ja', 'ko', 'zh-cn', 'zh-tw'];
const documents = codes.flatMap(code => ['', 'privacy/'].map(section => {
  const home = code === 'en' ? '/' : `/${code}/`;
  const html = fs.readFileSync(path.join(__dirname, '../docs', home, section, 'index.html'), 'utf8');
  const script = [...html.matchAll(/<script>([\s\S]*?)<\/script>/gi)]
    .map(match => match[1]).find(source => source.includes('function pickLanguage('));
  assert.ok(script, `${home}${section} has its navigation script`);
  return { code, section, html, script };
}));
const route = (code, section) => (code === 'en' ? '/' : `/${code}/`) + section;

function page(document, { saved = null, browser = ['en'], blockedStorage = false } = {}) {
  const assigned = [], replaced = [], writes = [];
  const context = {
    localStorage: {
      getItem() { if (blockedStorage) throw Error('blocked'); return saved; },
      setItem(key, value) { if (blockedStorage) throw Error('blocked'); writes.push([key, value]); }
    },
    navigator: { languages: browser, language: 'en' },
    location: { set href(value) { assigned.push(value); }, replace(value) { replaced.push(value); } }
  };
  vm.runInNewContext(document.script, context, { timeout: 1000 });
  return { assigned, replaced, writes, pick: context.pickLanguage };
}

test('every picker option selects a supported locale and preserves the page section', () => {
  for (const document of documents) {
    const options = [...document.html.matchAll(/<option value="([^"]+)"/g)].map(match => match[1]);
    assert.deepEqual(options, codes);
    for (const code of options) {
      const p = page(document);
      p.pick(code);
      assert.deepEqual(p.assigned, [route(code, document.section)]);
      assert.deepEqual(p.writes, [['language', code]]);
    }
  }
});

test('English pages restore each supported saved locale; localized pages stay put', () => {
  for (const document of documents) {
    for (const saved of codes) {
      const p = page(document, { saved, browser: ['ja'] });
      const expected = document.code === 'en' && saved !== 'en' ? [route(saved, document.section)] : [];
      assert.deepEqual(p.replaced, expected);
    }
  }
});

const invalid = [
  '/attacker.invalid', '\\attacker.invalid', '//attacker.invalid', 'https://attacker.invalid',
  'javascript:alert(1)', 'data:text/html,test', '%2f%2fattacker.invalid', '../privacy',
  'es/../../', 'es?next=//attacker.invalid', 'es#fragment', 'unknown', '__proto__',
  'constructor', 'toString', 'ES', ' es', 'es ', '', null, undefined, {}, ['es'],
  '\nes', 'es\u0000', 'es\r\n'
];

test('invalid stored values and picker inputs cannot navigate or overwrite the preference', () => {
  for (const document of documents) {
    for (const value of invalid) {
      const p = page(document, { saved: value });
      assert.deepEqual(p.replaced, []);
      p.pick(value);
      assert.deepEqual(p.assigned, []);
      assert.deepEqual(p.writes, []);
    }
  }
});

test('invalid saved values fall back to the browser instead of creating broken paths', () => {
  for (const document of documents.filter(document => document.code === 'en')) {
    for (const saved of invalid) {
      assert.deepEqual(page(document, { saved, browser: ['es-AR'] }).replaced, [route('es', document.section)]);
    }
  }
});

test('first visit follows supported browser tags, regional variants and Chinese scripts', () => {
  const cases = [
    [['es-MX'], 'es'], [['PT-br'], 'pt-br'], [['zh-Hant-HK'], 'zh-tw'],
    [['zh-HK'], 'zh-tw'], [['zh-MO'], 'zh-tw'], [['zh-Hans-SG'], 'zh-cn'],
    [['unsupported', 'ja-JP'], 'ja'], [['en-US', 'es'], 'en'], [[], 'en'],
    [['__proto__', 'constructor', 'toString'], 'en']
  ];
  for (const document of documents.filter(document => document.code === 'en')) {
    for (const [browser, code] of cases) {
      assert.deepEqual(page(document, { browser }).replaced, code === 'en' ? [] : [route(code, document.section)]);
    }
    assert.deepEqual(page(document, { browser: undefined }).replaced, []);
  }
});

test('blocked storage still permits browser detection and manual language changes', () => {
  for (const document of documents) {
    const p = page(document, { blockedStorage: true, browser: ['ja'] });
    assert.deepEqual(p.replaced, document.code === 'en' ? [route('ja', document.section)] : []);
    p.pick('es');
    assert.deepEqual(p.assigned, [route('es', document.section)]);
    assert.deepEqual(p.writes, []);
  }
});

test('generated untrusted strings only produce allowlisted destinations', () => {
  let seed = 208;
  const alphabet = 'abcdefghijklmnopqrstuvwxyz0123456789/\\:%?#.\t\n';
  const strings = Array.from({ length: 500 }, () => {
    let value = '';
    for (let i = 0; i < 24; i++) {
      seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0;
      value += alphabet[seed % alphabet.length];
    }
    return value;
  });
  for (const document of documents.filter(document => document.code === 'en')) {
    const allowed = codes.map(code => route(code, document.section));
    for (const value of strings) {
      const p = page(document, { saved: value, browser: [value] });
      p.pick(value);
      for (const url of [...p.assigned, ...p.replaced]) {
        assert.ok(allowed.includes(url), url);
        assert.equal(new URL(url, 'https://example.test').origin, 'https://example.test');
      }
    }
  }
});
