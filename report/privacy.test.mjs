// Run with: node --test "report/*.test.mjs"
import { test } from "node:test";
import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import worker from "./worker.js";

/**
 * How README's Privacy section and the English privacy page name each column a report fills. A new column needs
 * its words here, and then in both, so the privacy text never leaves out what a report carries.
 */
const SAID = {
  // Each is the words, or a list of ways either text may say it.
  version: "Crosstune and Android versions",
  android: "Crosstune and Android versions",
  title: "title",
  artist: "artist",
  app: "app Crosstune follows",
  timed: "how they're timed",
  synced: "how they're timed",
  sung: "how they're timed",
  length_ms: "length",
  source: "where its lyrics came from",
  timing: "words and times of each line",
  comment: "what you write",
  problem: ["problems Crosstune finds", "when it finds a song with no lyrics"],
  app_package: "package name",
  speed: "playback speed",
  lead_ms: "how far ahead Crosstune shows the words",
  early_ms: "how far ahead Crosstune shows the words",
  timing_log: "timing notes",
  recording: "recording",
  lyric_line: "whole line of lyrics",
  word: "the word",
  reading_shown: "the reading shown",
  reader: "what made it",
  language: "its script",
  right_reading: "the right reading",
};

/** The columns the Worker's insert names, as it sends them to D1. */
async function columns() {
  let sql = "";
  const env = {
    DB: { prepare: (text) => (text.startsWith("INSERT INTO reports (") && (sql = text), { bind: () => ({ run: async () => ({ meta: { changes: 1 } }), first: async () => ({ why: null }) }) }) },
    LIMIT: { limit: async () => ({ success: true }) },
    NETWORK_KEY: "made-up-key",
  };
  const request = new Request("https://report.crosstune.cc/reports", {
    method: "POST",
    headers: { "Content-Type": "application/json; charset=utf-8", "CF-Connecting-IP": "203.0.113.7" },
    body: JSON.stringify({ version: "2.25.0", song: { title: "Demo", artist: "Band" } }),
  });
  await worker.fetch(request, env);
  return sql.match(/INSERT INTO reports \(([^)]+)\)/)[1].split(",").map((it) => it.trim());
}

/** Whether [text] says [words], or one of them. */
const says = (text, words) => [words].flat().some((it) => text.includes(it));

const repo = (path) => readFileSync(new URL(`../${path}`, import.meta.url), "utf8");

test("every column a report fills has its words in the privacy text", async () => {
  assert.deepEqual((await columns()).sort(), Object.keys(SAID).sort());
});

test("README's Privacy says what each report carries", () => {
  const bullets = repo("README.md").split("\n").filter((line) => line.startsWith("- **Report a problem** in the lyrics") || line.startsWith("- Flagging a word's reading")).join("\n");
  assert.ok(bullets.length > 0);
  for (const [column, words] of Object.entries(SAID)) assert.ok(says(bullets, words), `README doesn't say ${column}: "${words}"`);
});

test("the English privacy page says what each report carries", () => {
  const body = JSON.parse(repo("site/strings/en.json"))["privacy_body"].map(([, text]) => text);
  const paragraphs = body.filter((text) => text.startsWith("Report a problem, in the lyrics") || text.startsWith("Marking a word's reading wrong")).join("\n");
  assert.ok(paragraphs.length > 0);
  for (const [column, words] of Object.entries(SAID)) assert.ok(says(paragraphs, words), `privacy_body doesn't say ${column}: "${words}"`);
});

test("the privacy text says a network's reports are counted for the day, and how many lines by hand are kept", () => {
  // The Worker keeps the first MAX_POINTS lines, and counts each network's day under a key made from its prefix (limits.sql).
  const readme = repo("README.md").split("\n").find((line) => line.startsWith("- **Report a problem** in the lyrics"));
  const page = JSON.parse(repo("site/strings/en.json"))["privacy_body"].map(([, text]) => text).find((text) => text.startsWith("Report a problem, in the lyrics"));
  for (const text of [readme, page]) {
    assert.ok(text.includes("the server keeps the first 300"), text);
    assert.ok(text.includes("counts them for the day under a scrambled code made from part of the address, which can't be turned back into it, and deletes that count the next day"), text);
  }
});

/** In each language: that the server keeps the first 300 lines put in time by hand, and that a network's day is counted. */
const NETWORK_DAY = {
  "en": ["(the server keeps the first 300)", "To limit how many reports one network can send, it counts them for the day under a scrambled code made from part of the address, which can't be turned back into it, and deletes that count the next day."],
  "de": ["(der Server behält die ersten 300)", "Damit ein Netz nicht beliebig viele Meldungen schicken kann, zählt er sie für den Tag unter einem unkenntlichen Code aus einem Teil der Adresse, aus dem sich die Adresse nicht zurückgewinnen lässt, und löscht diese Zählung am nächsten Tag."],
  "es": ["(el servidor guarda las primeras 300)", "Para limitar cuántos reportes puede mandar una misma red, los cuenta por día con un código cifrado hecho con parte de la dirección, que no se puede volver a convertir en ella, y borra esa cuenta al día siguiente."],
  "fr": ["(le serveur garde les 300 premières)", "Pour limiter le nombre de signalements qu'un même réseau peut envoyer, il les compte pour la journée sous un code brouillé tiré d'une partie de l'adresse, qui ne permet pas de la retrouver, et efface ce compte le lendemain."],
  "hi": ["(सर्वर पहली 300 रखता है)", "एक नेटवर्क से कितनी रिपोर्ट भेजी जा सकती हैं, इसे सीमित करने के लिए सर्वर उन्हें उस दिन के लिए पते के एक हिस्से से बने एक छिपे हुए कोड के तहत गिनता है, जिससे पता वापस नहीं निकाला जा सकता, और अगले दिन यह गिनती मिटा देता है।"],
  "id": ["(server menyimpan 300 yang pertama)", "Untuk membatasi berapa banyak laporan yang bisa dikirim dari satu jaringan, server menghitungnya per hari dengan kode acak yang dibuat dari sebagian alamat itu, yang tidak bisa diubah kembali menjadi alamatnya, lalu menghapus hitungan itu keesokan harinya."],
  "it": ["(il server conserva le prime 300)", "Per limitare quante segnalazioni può inviare una stessa rete, le conta per la giornata con un codice cifrato ricavato da una parte dell'indirizzo, da cui l'indirizzo non si può risalire, e cancella quel conteggio il giorno dopo."],
  "ja": ["（サーバーに残るのは最初の 300 行）", "1 つのネットワークから送れる報告の数を制限するため、アドレスの一部から作った元に戻せない暗号化コードでその日の報告数を数え、翌日にその記録を消去します。"],
  "ko": ["(서버에는 처음 300줄만 남아요)", "한 네트워크에서 보낼 수 있는 신고 수를 제한하려고, 주소 일부로 만든 되돌릴 수 없는 암호화된 코드로 그날의 신고 수를 세고, 다음 날 그 기록을 지워요."],
  "nl": ["(de server bewaart de eerste 300)", "Om te beperken hoeveel meldingen één netwerk kan sturen, telt hij ze per dag onder een versleutelde code die uit een deel van het adres is gemaakt en niet terug te rekenen is naar het adres, en wist hij die telling de volgende dag."],
  "pl": ["(serwer zachowuje pierwsze 300)", "Aby ograniczyć liczbę zgłoszeń z jednej sieci, serwer liczy je w danym dniu pod zaszyfrowanym kodem utworzonym z części adresu, którego nie da się z powrotem zamienić na adres, i usuwa ten licznik następnego dnia."],
  "pt-br": ["(o servidor guarda as 300 primeiras)", "Para limitar quantos relatos uma mesma rede pode enviar, ele os conta por dia com um código embaralhado feito de parte do endereço, que não pode ser convertido de volta nele, e apaga essa contagem no dia seguinte."],
  "ru": ["(сервер хранит первые 300)", "Чтобы ограничить число сообщений из одной сети, сервер считает их за день под зашифрованным кодом, полученным из части адреса, по которому адрес нельзя восстановить, и на следующий день удаляет этот счётчик."],
  "tr": ["(sunucu ilk 300 satırı saklar)", "Bir ağdan gönderilebilecek bildirim sayısını sınırlamak için bunları, adresin bir kısmından üretilen ve adrese geri çevrilemeyen şifreli bir kodla o gün için sayar ve bu sayımı ertesi gün siler."],
  "zh-cn": ["（服务器只保留前 300 行）", "为了限制同一网络能发送多少报告，服务器会用由地址的一部分生成、无法还原成地址的加密代码记下当天的报告数，并在第二天删除这个计数。"],
  "zh-tw": ["（伺服器只保留前 300 行）", "為了限制同一網路能傳送多少回報，伺服器會用由位址的一部分產生、無法還原成位址的加密代碼記下當天的回報數，並在隔天刪除這個計數。"]
};

test("every language's privacy page says it too", () => {
  const files = readdirSync(new URL("../site/strings/", import.meta.url)).filter((it) => it.endsWith(".json"));
  assert.deepEqual(files.map((it) => it.replace(".json", "")).sort(), Object.keys(NETWORK_DAY).sort());
  for (const file of files) {
    const body = JSON.parse(repo(`site/strings/${file}`))["privacy_body"].map(([, text]) => text);
    for (const words of NETWORK_DAY[file.replace(".json", "")]) assert.equal(body.filter((text) => text.includes(words)).length, 1, `${file}: ${words}`);
  }
});
