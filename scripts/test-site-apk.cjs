// Runs the APK link code shipped in every generated home page.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');

const codes = ['en', 'es', 'pt-br', 'de', 'fr', 'it', 'nl', 'pl', 'ru', 'tr', 'id', 'hi', 'ja', 'ko', 'zh-cn', 'zh-tw'];
const fallback = 'https://github.com/astrovm/crosstune/releases/latest';
const regular = 'Crosstune-v2.28.0.apk';
const foss = 'Crosstune-v2.28.0-foss.apk';

async function link(code, release) {
  const html = fs.readFileSync(path.join(__dirname, '../docs', code === 'en' ? '' : code, 'index.html'), 'utf8');
  const script = html.match(/  const apk = document\.querySelector\("\.apk"\);[\s\S]*?    \.catch\(\(\) => \{\}\);/)[0];
  const apk = { href: fallback };
  vm.runInNewContext(script, { document: { querySelector: () => apk }, fetch: async () => ({ ok: true, json: async () => release }) });
  await new Promise(resolve => setImmediate(resolve));
  return apk.href;
}

function asset(name) { return { name, browser_download_url: `https://example.test/${name}` }; }

test('every home page selects the regular APK for its tag regardless of asset order', async () => {
  for (const code of codes) {
    for (const names of [[foss, regular], [regular, foss], [regular]]) {
      assert.equal(await link(code, { tag_name: 'v2.28.0', assets: names.map(asset) }), `https://example.test/${regular}`);
    }
  }
});

test('missing or wrong assets leave the release page link in place', async () => {
  for (const code of codes) {
    for (const release of [
      { tag_name: 'v2.28.0', assets: [asset(foss), asset('Crosstune-v2.27.0.apk')] },
      { tag_name: 'v2.28.0', assets: [] },
      { tag_name: 'v2.28.0', assets: [{ name: regular }] },
      { assets: [asset(regular)] }, null
    ]) assert.equal(await link(code, release), fallback);
  }
});
