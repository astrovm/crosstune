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

test("the privacy text says a network's reports are counted for the day under a keyed code, that backups keep what's deleted, and how many lines by hand are kept", () => {
  // The Worker keeps the first MAX_POINTS lines, and counts each network's day under an HMAC key (limits.sql), and nothing
  // else about the sender: no wider block, no carrier.
  // D1's Time Travel keeps deleted rows for up to 30 days, and can't be turned off.
  const readme = repo("README.md").split("\n").find((line) => line.startsWith("- **Report a problem** in the lyrics"));
  const page = JSON.parse(repo("site/strings/en.json"))["privacy_body"].map(([, text]) => text).find((text) => text.startsWith("Report a problem, in the lyrics"));
  for (const text of [readme, page]) {
    assert.ok(text.includes("the server keeps the first 300"), text);
    assert.ok(text.includes("how many reports one network can send, it counts them for the day under a code made with a secret key from the network part of the address, and deletes those counts the next day"), text);
    assert.ok(text.includes("Without the key, a code can't be traced back to the network."), text);
    assert.ok(!/provider|carrier/i.test(text.slice(text.indexOf("IP address"), text.indexOf("Without the key"))), text);
    assert.ok(text.includes("backups keep"), text);
  }
});

/**
 * In each language: that the server keeps the first 300 lines put in time by hand, that a network's day is counted under
 * a keyed code, and that the database's backups keep what's deleted up to 30 days more (D1 Time Travel,
 * which can't be turned off).
 */
const NETWORK_DAY = {
  "en": ["(the server keeps the first 300)", "To limit how many reports one network can send, it counts them for the day under a code made with a secret key from the network part of the address, and deletes those counts the next day; the database's backups keep them up to 30 days more. Without the key, a code can't be traced back to the network.", "Each is deleted after 180 days, and the database's backups keep it up to 30 days more."],
  "de": ["(der Server behält die ersten 300)", "Damit ein Netz nicht beliebig viele Meldungen schicken kann, zählt er sie für den Tag unter einem Code, der mit einem geheimen Schlüssel aus dem Teil der Adresse gebildet wird, der das Netz angibt, und löscht diese Zählungen am nächsten Tag; die Sicherungen der Datenbank behalten sie bis zu 30 Tage länger. Ohne den Schlüssel lässt sich ein Code nicht auf das Netz zurückführen.", "Jede wird nach 180 Tagen gelöscht, und die Sicherungen der Datenbank behalten sie bis zu 30 Tage länger."],
  "es": ["(el servidor guarda las primeras 300)", "Para limitar cuántos reportes puede mandar una misma red, los cuenta por día con un código hecho con una clave secreta a partir de la parte de la dirección que indica la red, y borra esas cuentas al día siguiente; las copias de seguridad de la base de datos las guardan hasta 30 días más. Sin la clave, un código no se puede asociar a la red.", "Cada uno se borra a los 180 días, y las copias de seguridad de la base de datos lo guardan hasta 30 días más."],
  "fr": ["(le serveur garde les 300 premières)", "Pour limiter le nombre de signalements qu'un même réseau peut envoyer, il les compte pour la journée sous un code créé avec une clé secrète à partir de la partie de l'adresse qui désigne le réseau, et efface ces comptes le lendemain ; les sauvegardes de la base de données les gardent jusqu'à 30 jours de plus. Sans la clé, un code ne permet pas de retrouver le réseau.", "Chacun est supprimé au bout de 180 jours, et les sauvegardes de la base de données le gardent jusqu'à 30 jours de plus."],
  "hi": ["(सर्वर पहली 300 रखता है)", "एक नेटवर्क से कितनी रिपोर्ट भेजी जा सकती हैं, इसे सीमित करने के लिए सर्वर उन्हें उस दिन के लिए ऐसे कोड के तहत गिनता है जो एक गुप्त कुंजी से, पते के नेटवर्क बताने वाले हिस्से से बनाया जाता है, और अगले दिन ये गिनतियाँ मिटा देता है; डेटाबेस के बैकअप इन्हें 30 दिन तक और रखते हैं। कुंजी के बिना किसी कोड से नेटवर्क का पता नहीं लगाया जा सकता।", "हर रिपोर्ट 180 दिन बाद मिटा दी जाती है, और डेटाबेस के बैकअप उसे 30 दिन तक और रखते हैं।"],
  "id": ["(server menyimpan 300 yang pertama)", "Untuk membatasi berapa banyak laporan yang bisa dikirim dari satu jaringan, server menghitungnya per hari dengan kode yang dibuat memakai kunci rahasia dari bagian alamat yang menunjukkan jaringan, lalu menghapus hitungan itu keesokan harinya; cadangan basis data menyimpannya hingga 30 hari lagi. Tanpa kunci itu, kode tidak bisa dilacak kembali ke jaringannya.", "Setiap laporan dihapus setelah 180 hari, dan cadangan basis data menyimpannya hingga 30 hari lagi."],
  "it": ["(il server conserva le prime 300)", "Per limitare quante segnalazioni può inviare una stessa rete, le conta per la giornata con un codice creato con una chiave segreta dalla parte dell'indirizzo che indica la rete, e cancella quei conteggi il giorno dopo; i backup del database li conservano fino a 30 giorni in più. Senza la chiave, da un codice non si può risalire alla rete.", "Ognuna viene cancellata dopo 180 giorni, e i backup del database la conservano fino a 30 giorni in più."],
  "ja": ["（サーバーに残るのは最初の 300 行）", "1 つのネットワークから送れる報告の数を制限するため、アドレスのうちネットワークを示す部分から秘密の鍵で作ったコードでその日の報告数を数え、翌日にその記録を消去します。データベースのバックアップには、さらに最大 30 日間残ります。鍵がなければ、コードからネットワークをたどることはできません。", "報告はそれぞれ 180 日後に削除され、データベースのバックアップには、さらに最大 30 日間残ります。"],
  "ko": ["(서버에는 처음 300줄만 남아요)", "한 네트워크에서 보낼 수 있는 신고 수를 제한하려고, 주소 중 네트워크를 나타내는 부분으로 비밀 키를 써서 만든 코드로 그날의 신고 수를 세고, 다음 날 그 기록을 지워요. 데이터베이스 백업에는 최대 30일 더 남아요. 키가 없으면 코드로 네트워크를 알아낼 수 없어요.", "신고는 각각 180일 뒤에 삭제되고, 데이터베이스 백업에는 최대 30일 더 남아요."],
  "nl": ["(de server bewaart de eerste 300)", "Om te beperken hoeveel meldingen één netwerk kan sturen, telt hij ze per dag onder een code die met een geheime sleutel is gemaakt uit het deel van het adres dat het netwerk aangeeft, en wist hij die tellingen de volgende dag; de back-ups van de database bewaren ze tot 30 dagen langer. Zonder de sleutel is een code niet te herleiden tot het netwerk.", "Elke melding wordt na 180 dagen verwijderd, en de back-ups van de database bewaren haar tot 30 dagen langer."],
  "pl": ["(serwer zachowuje pierwsze 300)", "Aby ograniczyć liczbę zgłoszeń z jednej sieci, serwer liczy je w danym dniu pod kodem utworzonym tajnym kluczem z części adresu wskazującej sieć i usuwa te liczniki następnego dnia; kopie zapasowe bazy danych przechowują je do 30 dni dłużej. Bez klucza nie da się powiązać kodu z siecią.", "Każde jest usuwane po 180 dniach, a kopie zapasowe bazy danych przechowują je do 30 dni dłużej."],
  "pt-br": ["(o servidor guarda as 300 primeiras)", "Para limitar quantos relatos uma mesma rede pode enviar, ele os conta por dia com um código feito com uma chave secreta a partir da parte do endereço que indica a rede, e apaga essas contagens no dia seguinte; os backups do banco de dados as guardam por até 30 dias a mais. Sem a chave, um código não pode ser associado à rede.", "Cada um é apagado após 180 dias, e os backups do banco de dados o guardam por até 30 dias a mais."],
  "ru": ["(сервер хранит первые 300)", "Чтобы ограничить число сообщений из одной сети, сервер считает их за день под кодом, созданным с секретным ключом из части адреса, обозначающей сеть, и на следующий день удаляет эти счётчики; резервные копии базы данных хранят их до 30 дней дольше. Без ключа по коду нельзя определить сеть.", "Каждое удаляется через 180 дней, а резервные копии базы данных хранят его до 30 дней дольше."],
  "tr": ["(sunucu ilk 300 satırı saklar)", "Bir ağdan gönderilebilecek bildirim sayısını sınırlamak için bunları, adresin ağı gösteren kısmından gizli bir anahtarla üretilen bir kodla o gün için sayar ve bu sayımları ertesi gün siler; veritabanının yedekleri bunları 30 güne kadar daha tutar. Anahtar olmadan bir koddan ağa ulaşılamaz.", "Her biri 180 gün sonra silinir ve veritabanının yedekleri onu 30 güne kadar daha tutar."],
  "zh-cn": ["（服务器只保留前 300 行）", "为了限制同一网络能发送多少报告，服务器会用密钥，由地址中表示网络的部分生成代码，记下当天的报告数，并在第二天删除这些计数；数据库的备份最多还会再保留 30 天。没有密钥，就无法从代码追溯到网络。", "每份报告会在 180 天后删除，数据库的备份最多还会再保留 30 天。"],
  "zh-tw": ["（伺服器只保留前 300 行）", "為了限制同一網路能傳送多少回報，伺服器會用密鑰，由位址中表示網路的部分產生代碼，記下當天的回報數，並在隔天刪除這些計數；資料庫的備份最多還會再保留 30 天。沒有密鑰，就無法從代碼追溯到網路。", "每份回報會在 180 天後刪除，資料庫的備份最多還會再保留 30 天。"]
};

test("every language's privacy page says it too", () => {
  const files = readdirSync(new URL("../site/strings/", import.meta.url)).filter((it) => it.endsWith(".json"));
  assert.deepEqual(files.map((it) => it.replace(".json", "")).sort(), Object.keys(NETWORK_DAY).sort());
  for (const file of files) {
    const body = JSON.parse(repo(`site/strings/${file}`))["privacy_body"].map(([, text]) => text);
    for (const words of NETWORK_DAY[file.replace(".json", "")]) assert.equal(body.filter((text) => text.includes(words)).length, 1, `${file}: ${words}`);
  }
});
