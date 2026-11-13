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
  report_id: "random id",
};

/**
 * The columns the Worker's insert names, as it sends them to D1, but `at`: when the server got the report, which the
 * phone doesn't send. The privacy text says how long reports are kept, counted from it.
 */
async function columns() {
  let sql = "";
  // A statement as D1 gives it: it runs as it is, or bound to values, alone or in a batch.
  const result = { results: [{ why: null }], meta: { changes: 1 } };
  const runs = { run: async () => result, first: async () => result.results[0], all: async () => result };
  const statement = { ...runs, bind: () => runs };
  const env = {
    DB: { prepare: (text) => (text.startsWith("INSERT INTO reports (") && (sql = text), statement), batch: async (statements) => statements.map(() => result) },
    LIMIT: { limit: async () => ({ success: true }) },
    NETWORK_KEY: "made-up-key",
  };
  const request = new Request("https://report.crosstune.cc/reports", {
    method: "POST",
    headers: { "Content-Type": "application/json; charset=utf-8", "CF-Connecting-IP": "203.0.113.7" },
    body: JSON.stringify({ version: "2.25.0", song: { title: "Demo", artist: "Band" } }),
  });
  await worker.fetch(request, env);
  return sql.match(/INSERT INTO reports \(([^)]+)\)/)[1].split(",").map((it) => it.trim()).filter((it) => it !== "at");
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

test("the privacy text says a network's reports are counted for the day under a keyed code, that backups keep what's deleted, and how many lines by hand go", () => {
  // The app sends at most ReportBox.MAX_POINTS lines, those that tell most, and fewer to stay under MAX_BYTES. The Worker
  // counts each network's day under an HMAC key (limits.sql), and nothing else about the sender: no wider block, no carrier.
  // D1's Time Travel keeps deleted rows for up to 30 days, and can't be turned off.
  const box = repo("app/src/main/java/com/astrovm/crosstune/Feedback.kt");
  const points = box.match(/const val MAX_POINTS = (\d+)/)[1];
  const kb = box.match(/const val MAX_BYTES = (\d+) \* 1024/)[1];
  const readme = repo("README.md").split("\n").find((line) => line.startsWith("- **Report a problem** in the lyrics"));
  const page = JSON.parse(repo("site/strings/en.json"))["privacy_body"].map(([, text]) => text).find((text) => text.startsWith("Report a problem, in the lyrics"));
  for (const text of [readme, page]) {
    assert.ok(text.includes(`(at most the ${points} that tell the most, fewer to stay under ${kb} KB)`), text);
    assert.ok(text.includes("how many reports one network can send, it counts them for the day under a code made with a secret key from the network part of the address, and deletes those counts the next day"), text);
    assert.ok(text.includes("Without the key, a code can't be traced back to the network."), text);
    assert.ok(!/provider|carrier/i.test(text.slice(text.indexOf("IP address"), text.indexOf("Without the key"))), text);
    assert.ok(text.includes("backups keep"), text);
  }
});

test("README and the privacy page say alike, with the app's numbers, what waits on the phone and what is deleted", () => {
  const app = repo("app/src/main/java/com/astrovm/crosstune/AutoReports.kt");
  const aDay = app.match(/const val FOUND_A_DAY = (\d+)/)[1];
  const kept = app.match(/const val MAX_OWED = (\d+)/)[1];
  const said = `A report Crosstune sends by itself that can't get there waits on your phone for the next start: the latest ${kept} put in time by hand, the latest ${kept} problems found, and every timing an earlier version kept. One the server never takes is deleted and never sent again. Each of those carries a random id, made for that report alone and saying nothing about you or your phone, so the server keeps it once if it gets there twice. A report you send yourself with Send isn't kept: if it can't get there, Crosstune says so.`;
  const page = JSON.parse(repo("site/strings/en.json"))["privacy_body"].map(([, text]) => text);
  for (const text of [repo("README.md"), page.join("\n")]) {
    // README names the button in bold, as it does every name in the app.
    assert.ok(text.replaceAll("**Send**", "Send").includes(said), said);
    assert.ok(text.includes(`with words you put in time by hand, at most ${aDay} in a day; the rest wait on your phone for a later day.`) ||
      text.includes(`Problems Crosstune finds and words you put in time by hand go together, at most ${aDay} in a day; the rest wait on your phone for a later day.`), text);
  }
});

/**
 * In each language: that at most the 300 lines put in time by hand that tell the most go, fewer to stay under 48 KB;
 * that a network's day is counted under a keyed code; that the database's backups keep what's deleted up to 30 days
 * more (D1 Time Travel, which can't be turned off); and how the app keeps what doesn't get there: AutoReports.FOUND_A_DAY
 * a day of problems found and words put in time by hand together, the latest MAX_OWED of each kind for "not now",
 * and a report the Worker never takes deleted (Issue #964).
 */
const NETWORK_DAY = {
  "en": ["(at most the 300 that tell the most, fewer to stay under 48 KB)", "To limit how many reports one network can send, it counts them for the day under a code made with a secret key from the network part of the address, and deletes those counts the next day; the database's backups keep them up to 30 days more. Without the key, a code can't be traced back to the network.", "Each is deleted after 180 days, and the database's backups keep it up to 30 days more.", "Problems Crosstune finds and words you put in time by hand go together, at most 23 in a day; the rest wait on your phone for a later day. A report Crosstune sends by itself that can't get there waits on your phone for the next start: the latest 20 put in time by hand, the latest 20 problems found, and every timing an earlier version kept. One the server never takes is deleted and never sent again. Each of those carries a random id, made for that report alone and saying nothing about you or your phone, so the server keeps it once if it gets there twice. A report you send yourself with Send isn't kept: if it can't get there, Crosstune says so."],
  "de": ["(höchstens die 300, die am meisten sagen, weniger, um unter 48 KB zu bleiben)", "Damit ein Netz nicht beliebig viele Meldungen schicken kann, zählt er sie für den Tag unter einem Code, der mit einem geheimen Schlüssel aus dem Teil der Adresse gebildet wird, der das Netz angibt, und löscht diese Zählungen am nächsten Tag; die Sicherungen der Datenbank behalten sie bis zu 30 Tage länger. Ohne den Schlüssel lässt sich ein Code nicht auf das Netz zurückführen.", "Jede wird nach 180 Tagen gelöscht, und die Sicherungen der Datenbank behalten sie bis zu 30 Tage länger.", "Probleme, die Crosstune findet, und Songtexte, die du von Hand gesetzt hast, gehen zusammen höchstens 23 am Tag; der Rest wartet auf deinem Handy auf einen späteren Tag. Eine Meldung, die Crosstune von selbst sendet und die nicht ankommt, wartet auf deinem Handy bis zum nächsten Start: die letzten 20 von Hand gesetzten, die letzten 20 gefundenen Probleme und alle Zeiten, die eine frühere Version gespeichert hat. Eine, die der Server nie annimmt, wird gelöscht und nie wieder gesendet. Jede davon trägt eine zufällige ID, nur für diese Meldung gemacht, die nichts über dich oder dein Handy sagt, damit der Server sie nur einmal speichert, wenn sie zweimal ankommt. Eine Meldung, die du selbst mit Senden schickst, wird nicht gespeichert: Kommt sie nicht an, sagt Crosstune es dir."],
  "es": ["(como mucho las 300 que más dicen, menos para no pasar de 48 KB)", "Para limitar cuántos reportes puede mandar una misma red, los cuenta por día con un código hecho con una clave secreta a partir de la parte de la dirección que indica la red, y borra esas cuentas al día siguiente; las copias de seguridad de la base de datos las guardan hasta 30 días más. Sin la clave, un código no se puede asociar a la red.", "Cada uno se borra a los 180 días, y las copias de seguridad de la base de datos lo guardan hasta 30 días más.", "Los problemas que encuentra Crosstune y las letras que pusiste en tiempo a mano se mandan, entre todos, hasta 23 por día; el resto espera en tu teléfono a otro día. Un reporte que Crosstune manda solo y no llega espera en tu teléfono al próximo inicio: los últimos 20 puestos en tiempo a mano, los últimos 20 problemas encontrados y todos los tiempos que guardó una versión anterior. Uno que el servidor nunca acepta se borra y no se vuelve a mandar. Cada uno lleva un id al azar, hecho solo para ese reporte, que no dice nada de vos ni de tu teléfono, así el servidor lo guarda una vez aunque le llegue dos veces. Un reporte que mandás vos con Enviar no se guarda: si no llega, Crosstune te lo dice."],
  "fr": ["(au plus les 300 qui en disent le plus, moins pour rester sous 48 Ko)", "Pour limiter le nombre de signalements qu'un même réseau peut envoyer, il les compte pour la journée sous un code créé avec une clé secrète à partir de la partie de l'adresse qui désigne le réseau, et efface ces comptes le lendemain ; les sauvegardes de la base de données les gardent jusqu'à 30 jours de plus. Sans la clé, un code ne permet pas de retrouver le réseau.", "Chacun est supprimé au bout de 180 jours, et les sauvegardes de la base de données le gardent jusqu'à 30 jours de plus.", "Les problèmes que Crosstune trouve et les paroles que vous calez à la main partent ensemble, au plus 23 par jour ; le reste attend sur votre téléphone un autre jour. Un signalement que Crosstune envoie de lui-même et qui n'arrive pas attend sur votre téléphone le prochain démarrage : les 20 derniers calés à la main, les 20 derniers problèmes trouvés, et tous les temps gardés par une version précédente. Celui que le serveur ne prend jamais est supprimé et n'est plus jamais envoyé. Chacun porte un identifiant aléatoire, créé pour ce seul signalement, qui ne dit rien de vous ni de votre téléphone, pour que le serveur ne le garde qu'une fois s'il arrive deux fois. Un signalement que vous envoyez vous-même avec Envoyer n'est pas gardé : s'il n'arrive pas, Crosstune vous le dit."],
  "hi": ["(ज़्यादा से ज़्यादा वे 300 जो सबसे ज़्यादा बताती हैं, 48 KB से कम रहने के लिए इससे कम भी)", "एक नेटवर्क से कितनी रिपोर्ट भेजी जा सकती हैं, इसे सीमित करने के लिए सर्वर उन्हें उस दिन के लिए ऐसे कोड के तहत गिनता है जो एक गुप्त कुंजी से, पते के नेटवर्क बताने वाले हिस्से से बनाया जाता है, और अगले दिन ये गिनतियाँ मिटा देता है; डेटाबेस के बैकअप इन्हें 30 दिन तक और रखते हैं। कुंजी के बिना किसी कोड से नेटवर्क का पता नहीं लगाया जा सकता।", "हर रिपोर्ट 180 दिन बाद मिटा दी जाती है, और डेटाबेस के बैकअप उसे 30 दिन तक और रखते हैं।", "Crosstune को मिली समस्याएँ और हाथ से समय पर लाए गए बोल मिलाकर एक दिन में ज़्यादा से ज़्यादा 23 जाते हैं; बाकी आपके फ़ोन पर अगले किसी दिन तक रुकती हैं। Crosstune जो रिपोर्ट अपने आप भेजता है, अगर वह न पहुँच सके, तो वह आपके फ़ोन पर अगली बार ऐप शुरू होने तक रुकती है: हाथ से समय पर लाई गई आख़िरी 20, मिली हुई आख़िरी 20 समस्याएँ, और पिछले संस्करण के रखे सभी समय। जिसे सर्वर कभी नहीं लेता, वह मिटा दी जाती है और फिर कभी नहीं भेजी जाती। इनमें से हर एक में एक रैंडम आईडी होती है, जो सिर्फ़ उसी रिपोर्ट के लिए बनती है और आपके या आपके फ़ोन के बारे में कुछ नहीं बताती, ताकि दो बार पहुँचने पर भी सर्वर उसे एक ही बार रखे। जो रिपोर्ट आप खुद भेजें से भेजते हैं, वह रखी नहीं जाती: अगर वह न पहुँचे, तो Crosstune आपको बताता है।"],
  "id": ["(paling banyak 300 yang paling banyak memberi tahu, lebih sedikit agar tetap di bawah 48 KB)", "Untuk membatasi berapa banyak laporan yang bisa dikirim dari satu jaringan, server menghitungnya per hari dengan kode yang dibuat memakai kunci rahasia dari bagian alamat yang menunjukkan jaringan, lalu menghapus hitungan itu keesokan harinya; cadangan basis data menyimpannya hingga 30 hari lagi. Tanpa kunci itu, kode tidak bisa dilacak kembali ke jaringannya.", "Setiap laporan dihapus setelah 180 hari, dan cadangan basis data menyimpannya hingga 30 hari lagi.", "Masalah yang ditemukan Crosstune dan lirik yang kamu atur waktunya secara manual dikirim bersama, paling banyak 23 sehari; sisanya menunggu di ponselmu sampai hari lain. Laporan yang dikirim Crosstune sendiri dan tidak sampai menunggu di ponselmu sampai aplikasi dibuka lagi: 20 terakhir yang diatur waktunya secara manual, 20 masalah terakhir yang ditemukan, dan semua waktu yang disimpan versi sebelumnya. Laporan yang tidak pernah diterima server dihapus dan tidak pernah dikirim lagi. Masing-masing membawa ID acak, dibuat hanya untuk laporan itu dan tidak berisi apa pun tentang kamu atau ponselmu, jadi server menyimpannya sekali saja kalau sampai dua kali. Laporan yang kamu kirim sendiri dengan Kirim tidak disimpan: kalau tidak sampai, Crosstune memberitahumu."],
  "it": ["(al massimo le 300 che dicono di più, meno per restare sotto i 48 KB)", "Per limitare quante segnalazioni può inviare una stessa rete, le conta per la giornata con un codice creato con una chiave segreta dalla parte dell'indirizzo che indica la rete, e cancella quei conteggi il giorno dopo; i backup del database li conservano fino a 30 giorni in più. Senza la chiave, da un codice non si può risalire alla rete.", "Ognuna viene cancellata dopo 180 giorni, e i backup del database la conservano fino a 30 giorni in più.", "I problemi che Crosstune trova e i testi che hai messo a tempo a mano partono insieme, al massimo 23 al giorno; il resto aspetta sul tuo telefono un altro giorno. Una segnalazione che Crosstune invia da solo e che non arriva aspetta sul tuo telefono il prossimo avvio: le ultime 20 messe a tempo a mano, gli ultimi 20 problemi trovati e tutti i tempi conservati da una versione precedente. Quella che il server non accetta mai viene cancellata e non parte più. Ognuna porta un ID casuale, creato solo per quella segnalazione, che non dice nulla di te o del tuo telefono, così il server la tiene una volta sola se arriva due volte. Una segnalazione che mandi tu con Invia non viene conservata: se non arriva, Crosstune te lo dice."],
  "ja": ["（最も手がかりになる最大 300 行。48 KB を超えないよう、それより少ないこともあります）", "1 つのネットワークから送れる報告の数を制限するため、アドレスのうちネットワークを示す部分から秘密の鍵で作ったコードでその日の報告数を数え、翌日にその記録を消去します。データベースのバックアップには、さらに最大 30 日間残ります。鍵がなければ、コードからネットワークをたどることはできません。", "報告はそれぞれ 180 日後に削除され、データベースのバックアップには、さらに最大 30 日間残ります。", "Crosstune が見つけた問題と手動でタイミングを合わせた歌詞は、合わせて 1 日に最大 23 件まで送られ、残りは端末に保存されて翌日以降に送られます。Crosstune が自動で送る報告が届かなかった場合は、次の起動まで端末で待ちます。保存されるのは、手動でタイミングを合わせた最新 20 件、見つけた問題の最新 20 件、以前のバージョンが保存したすべてのタイミングです。サーバーが受け付けない報告は削除され、二度と送られません。それぞれの報告には、その報告のためだけに作られたランダムな ID が付きます。あなたや端末については何もわからない ID で、同じ報告が二度届いてもサーバーは一度だけ保存します。「送信」でご自身が送る報告は保存されません。届かなかった場合は、Crosstune がそう表示します。"],
  "ko": ["(가장 많은 것을 알려 주는 최대 300줄, 48KB를 넘지 않도록 더 적을 수도 있어요)", "한 네트워크에서 보낼 수 있는 신고 수를 제한하려고, 주소 중 네트워크를 나타내는 부분으로 비밀 키를 써서 만든 코드로 그날의 신고 수를 세고, 다음 날 그 기록을 지워요. 데이터베이스 백업에는 최대 30일 더 남아요. 키가 없으면 코드로 네트워크를 알아낼 수 없어요.", "신고는 각각 180일 뒤에 삭제되고, 데이터베이스 백업에는 최대 30일 더 남아요.", "Crosstune이 찾은 문제와 직접 타이밍을 맞춘 가사는 합쳐서 하루에 최대 23개까지 전송되고, 나머지는 휴대폰에서 다른 날을 기다려요. Crosstune이 알아서 보내는 신고가 도착하지 못하면 휴대폰에서 다음 시작 때까지 기다려요. 직접 타이밍을 맞춘 최근 20개, 찾은 문제 최근 20개, 이전 버전이 보관한 모든 타이밍이에요. 서버가 절대 받지 않는 신고는 지워지고 다시 전송되지 않아요. 이런 신고에는 그 신고만을 위해 만든 무작위 ID가 붙어요. 나나 휴대폰에 대해서는 아무것도 알려 주지 않는 ID라서, 같은 신고가 두 번 도착해도 서버는 한 번만 보관해요. 보내기로 직접 보내는 신고는 보관되지 않아요. 도착하지 못하면 Crosstune이 알려 줘요."],
  "nl": ["(hooguit de 300 die het meest zeggen, minder om onder 48 KB te blijven)", "Om te beperken hoeveel meldingen één netwerk kan sturen, telt hij ze per dag onder een code die met een geheime sleutel is gemaakt uit het deel van het adres dat het netwerk aangeeft, en wist hij die tellingen de volgende dag; de back-ups van de database bewaren ze tot 30 dagen langer. Zonder de sleutel is een code niet te herleiden tot het netwerk.", "Elke melding wordt na 180 dagen verwijderd, en de back-ups van de database bewaren haar tot 30 dagen langer.", "Problemen die Crosstune vindt en teksten die je met de hand op tijd hebt gezet, gaan samen hooguit 23 per dag; de rest wacht op je telefoon op een latere dag. Een melding die Crosstune uit zichzelf stuurt en die niet aankomt, wacht op je telefoon tot de volgende start: de laatste 20 die met de hand op tijd zijn gezet, de laatste 20 gevonden problemen en alle tijden die een eerdere versie bewaarde. Een melding die de server nooit aanneemt, wordt gewist en nooit meer verstuurd. Elk daarvan heeft een willekeurige ID, alleen voor die melding gemaakt, die niets over jou of je telefoon zegt, zodat de server hem één keer bewaart als hij twee keer aankomt. Een melding die je zelf met Versturen stuurt, wordt niet bewaard: komt hij niet aan, dan zegt Crosstune dat."],
  "pl": ["(najwyżej 300 tych, które mówią najwięcej, mniej, by zmieścić się w 48 KB)", "Aby ograniczyć liczbę zgłoszeń z jednej sieci, serwer liczy je w danym dniu pod kodem utworzonym tajnym kluczem z części adresu wskazującej sieć i usuwa te liczniki następnego dnia; kopie zapasowe bazy danych przechowują je do 30 dni dłużej. Bez klucza nie da się powiązać kodu z siecią.", "Każde jest usuwane po 180 dniach, a kopie zapasowe bazy danych przechowują je do 30 dni dłużej.", "Problemy, które znajdzie Crosstune, i teksty ustawione w czasie ręcznie wysyłają się łącznie najwyżej 23 dziennie; reszta czeka na telefonie na inny dzień. Zgłoszenie, które Crosstune wysyła samo i które nie dotrze, czeka na telefonie do następnego uruchomienia: ostatnie 20 ustawionych ręcznie, ostatnie 20 znalezionych problemów i wszystkie czasy zachowane przez wcześniejszą wersję. Zgłoszenie, którego serwer nigdy nie przyjmuje, jest usuwane i nigdy nie wysyła się ponownie. Każde z nich ma losowy identyfikator, zrobiony tylko dla tego zgłoszenia, który nic nie mówi o tobie ani o twoim telefonie, więc serwer zachowuje je raz, nawet gdy dotrze dwa razy. Zgłoszenie, które wysyłasz sam przyciskiem Wyślij, nie jest zachowywane: jeśli nie dotrze, Crosstune to powie."],
  "pt-br": ["(no máximo as 300 que mais dizem, menos para ficar abaixo de 48 KB)", "Para limitar quantos relatos uma mesma rede pode enviar, ele os conta por dia com um código feito com uma chave secreta a partir da parte do endereço que indica a rede, e apaga essas contagens no dia seguinte; os backups do banco de dados as guardam por até 30 dias a mais. Sem a chave, um código não pode ser associado à rede.", "Cada um é apagado após 180 dias, e os backups do banco de dados o guardam por até 30 dias a mais.", "Os problemas que o Crosstune encontra e as letras que você ajustou no tempo à mão são enviados juntos, no máximo 23 por dia; o resto espera no seu celular por outro dia. Um relato que o Crosstune envia sozinho e que não chega espera no seu celular a próxima abertura do app: os últimos 20 ajustados à mão, os últimos 20 problemas encontrados e todos os tempos que uma versão anterior guardou. Um que o servidor nunca aceita é apagado e nunca mais é enviado. Cada um leva um ID aleatório, feito só para aquele relato, que não diz nada sobre você ou seu celular, para o servidor guardá-lo uma vez só se chegar duas vezes. Um relato que você mesmo envia com Enviar não é guardado: se não chegar, o Crosstune avisa."],
  "ru": ["(не больше 300 самых показательных, меньше, чтобы уложиться в 48 КБ)", "Чтобы ограничить число сообщений из одной сети, сервер считает их за день под кодом, созданным с секретным ключом из части адреса, обозначающей сеть, и на следующий день удаляет эти счётчики; резервные копии базы данных хранят их до 30 дней дольше. Без ключа по коду нельзя определить сеть.", "Каждое удаляется через 180 дней, а резервные копии базы данных хранят его до 30 дней дольше.", "Найденные Crosstune проблемы и тексты, выставленные по времени вручную, отправляются вместе не больше 23 в день; остальные ждут на телефоне другого дня. Отчёт, который Crosstune отправляет сам и который не дошёл, ждёт на телефоне следующего запуска: последние 20 выставленных вручную, последние 20 найденных проблем и все тайминги, сохранённые прежней версией. Отчёт, который сервер никогда не принимает, удаляется и больше не отправляется. У каждого такого отчёта есть случайный идентификатор, созданный только для него и ничего не говорящий о вас или вашем телефоне, поэтому сервер сохраняет отчёт один раз, даже если он дошёл дважды. Отчёт, который вы отправляете сами кнопкой «Отправить», не сохраняется: если он не дошёл, Crosstune сообщит об этом."],
  "tr": ["(en çok şey anlatan en fazla 300 satır, 48 KB'ın altında kalmak için daha az)", "Bir ağdan gönderilebilecek bildirim sayısını sınırlamak için bunları, adresin ağı gösteren kısmından gizli bir anahtarla üretilen bir kodla o gün için sayar ve bu sayımları ertesi gün siler; veritabanının yedekleri bunları 30 güne kadar daha tutar. Anahtar olmadan bir koddan ağa ulaşılamaz.", "Her biri 180 gün sonra silinir ve veritabanının yedekleri onu 30 güne kadar daha tutar.", "Crosstune'un bulduğu sorunlar ve elle zamanladığınız sözler toplamda günde en fazla 23 tane gider; kalanı telefonunuzda başka bir günü bekler. Crosstune'un kendiliğinden gönderdiği ve ulaşamayan bir bildirim telefonunuzda bir sonraki açılışı bekler: elle zamanlanan son 20, bulunan son 20 sorun ve önceki bir sürümün sakladığı tüm zamanlamalar. Sunucunun hiç kabul etmediği bir bildirim silinir ve bir daha gönderilmez. Bunların her biri, yalnızca o bildirim için oluşturulan ve sizin ya da telefonunuz hakkında hiçbir şey söylemeyen rastgele bir kimlik taşır; böylece iki kez ulaşsa da sunucu onu bir kez saklar. Gönder ile kendiniz gönderdiğiniz bir bildirim saklanmaz: ulaşamazsa Crosstune bunu söyler."],
  "zh-cn": ["（最多发送最能说明问题的 300 行，为了不超过 48 KB 可能更少）", "为了限制同一网络能发送多少报告，服务器会用密钥，由地址中表示网络的部分生成代码，记下当天的报告数，并在第二天删除这些计数；数据库的备份最多还会再保留 30 天。没有密钥，就无法从代码追溯到网络。", "每份报告会在 180 天后删除，数据库的备份最多还会再保留 30 天。", "Crosstune 发现的问题和你手动对时的歌词，每天合计最多发送 23 个，其余的留在手机上，等以后某天再发。Crosstune 自动发送的报告如果没送到，会留在手机上，等到下次启动：手动对时的最近 20 个、发现的最近 20 个问题，以及旧版保存的所有时间。服务器始终不接受的报告会被删除，不再发送。这些报告各带一个随机 ID，只为这份报告生成，不包含关于你或你手机的任何信息，因此即使同一份报告送到两次，服务器也只保存一次。你点“发送”自己发出的报告不会保存：没送到时，Crosstune 会告诉你。"],
  "zh-tw": ["（最多傳送最能說明問題的 300 行，為了不超過 48 KB 可能更少）", "為了限制同一網路能傳送多少回報，伺服器會用密鑰，由位址中表示網路的部分產生代碼，記下當天的回報數，並在隔天刪除這些計數；資料庫的備份最多還會再保留 30 天。沒有密鑰，就無法從代碼追溯到網路。", "每份回報會在 180 天後刪除，資料庫的備份最多還會再保留 30 天。", "Crosstune 發現的問題和你手動對時的歌詞，每天合計最多傳送 23 個，其餘的留在手機上，等之後某天再傳。Crosstune 自動傳送的回報如果沒送到，會留在手機上，等到下次啟動：手動對時的最近 20 個、發現的最近 20 個問題，以及舊版保存的所有時間。伺服器始終不接受的回報會被刪除，不再傳送。這些回報各帶一個隨機 ID，只為這份回報產生，不包含任何關於你或你手機的資訊，因此即使同一份回報送到兩次，伺服器也只保存一次。你點「傳送」自己送出的回報不會保存：沒送到時，Crosstune 會告訴你。"]
};

test("every language's privacy page says it too", () => {
  const files = readdirSync(new URL("../site/strings/", import.meta.url)).filter((it) => it.endsWith(".json"));
  assert.deepEqual(files.map((it) => it.replace(".json", "")).sort(), Object.keys(NETWORK_DAY).sort());
  for (const file of files) {
    const body = JSON.parse(repo(`site/strings/${file}`))["privacy_body"].map(([, text]) => text);
    for (const words of NETWORK_DAY[file.replace(".json", "")]) assert.equal(body.filter((text) => text.includes(words)).length, 1, `${file}: ${words}`);
  }
});
