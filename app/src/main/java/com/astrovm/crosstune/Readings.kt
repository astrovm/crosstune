package com.astrovm.crosstune

import android.annotation.SuppressLint
import android.icu.text.Transliterator
import android.os.Build
import androidx.annotation.RequiresApi
import com.atilika.kuromoji.ipadic.Tokenizer

/**
 * The writing systems that aren't Latin, so their words can be shown in Latin letters. Each has
 * the [glyph] that stands for it in the words menu, a letter of its own.
 */
internal enum class Script(val glyph: String) {
    JAPANESE("日"), CHINESE("中"), KOREAN("한"), CYRILLIC("Я"), GREEK("Ω"), ARABIC("ع"), HEBREW("א"), INDIC("अ"), THAI("ก")
}

/** A piece of a line, with how it's read when that isn't plain from it, e.g. kanji with their kana. */
internal data class Ruby(val text: String, val reading: String? = null)

/**
 * How a line reads: in [parts], each piece with its reading over it; [reading] the whole line as
 * read, in kana or pinyin; and [romanized], in Latin letters, as it's said. Japanese has them
 * [typed] too, as a keyboard spells them: sensei, not sensee, and を as wo.
 */
internal data class LineReading(val parts: List<Ruby>, val reading: String, val romanized: String, val typed: String = romanized) {
    /** Its Latin letters as it's said, or as it's [typed]. */
    fun latin(typed: Boolean) = if (typed) this.typed else romanized
}

/**
 * A word of a line: as written, how it reads when that isn't plain from it, and what to look up
 * for its meaning, e.g. 分かる for 分かった; its Latin letters as it's said, and as it's [typed].
 */
internal data class Word(val text: String, val reading: String? = null, val romanized: String? = null, val lookup: String = text, val typed: String? = romanized)

/**
 * Romaji as it's [said] and as it's [typed], the one picked, typed when [typedFirst], first; the
 * other only where it's spelled otherwise, sensee and sensei, but wa once.
 */
internal fun bothRomaji(said: String?, typed: String?, typedFirst: Boolean): List<String> =
    (if (typedFirst) listOf(typed, said) else listOf(said, typed)).filterNotNull().filter(String::isNotBlank).distinct()

/** A reading put right: on line [line], from 0, [kanji] reads [reading], in hiragana. */
internal data class ReadingFix(val line: Int, val kanji: String, val reading: String)

/** Readings of lyrics in Japanese, Chinese and Korean, and Latin letters for other scripts, all worked out on the phone. */
internal object Readings {

    /** The script [lines] are in: any kana is Japanese, as Japanese mixes kana with kanji, which Chinese never does. */
    fun scriptOf(lines: List<String>): Script? {
        val text = lines.joinToString("")
        return when {
            text.any(::isKana) -> Script.JAPANESE
            text.any(::isHangul) -> Script.KOREAN
            text.any(::isHan) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> Script.CHINESE
            else -> null
        }
    }

    /** The script [lines] show in, which also has any other script most of the letters are in, as Android 10 on can write those in Latin letters. */
    fun displayScriptOf(lines: List<String>): Script? = scriptOf(lines) ?: mostlyIn(lines.joinToString("")).takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q }

    /** The other script most of [text]'s letters are in, so a Russian word in an English song doesn't make it Russian. */
    private fun mostlyIn(text: String): Script? {
        val letters = text.filter(Char::isLetter)
        val (script, count) = letters.groupingBy(::scriptOfLetter).eachCount().filterKeys { it != null }.maxByOrNull { it.value } ?: return null
        return script.takeIf { count * 2 > letters.length }
    }

    private fun scriptOfLetter(c: Char): Script? = when (Character.UnicodeScript.of(c.code)) {
        Character.UnicodeScript.CYRILLIC -> Script.CYRILLIC
        Character.UnicodeScript.GREEK -> Script.GREEK
        Character.UnicodeScript.ARABIC -> Script.ARABIC
        Character.UnicodeScript.HEBREW -> Script.HEBREW
        Character.UnicodeScript.THAI -> Script.THAI
        Character.UnicodeScript.DEVANAGARI, Character.UnicodeScript.BENGALI, Character.UnicodeScript.GURMUKHI, Character.UnicodeScript.GUJARATI,
        Character.UnicodeScript.TAMIL, Character.UnicodeScript.TELUGU, Character.UnicodeScript.KANNADA, Character.UnicodeScript.MALAYALAM -> Script.INDIC
        else -> null
    }

    /** How each of [lines] reads, in [script]. Japanese takes a moment the first time, to load its dictionary. */
    // Words are only ever Chinese from Android 10 on, see scriptOf.
    @SuppressLint("NewApi")
    fun of(lines: List<String>, script: Script): List<LineReading> = when (script) {
        Script.JAPANESE -> lines.map(::japanese)
        Script.CHINESE -> lines.map(::chinese)
        Script.KOREAN -> lines.map(::korean)
        else -> lines.map(::latinized)
    }.zip(lines) { reading, line ->
        // A line already in Latin letters, as an English one in a Korean song, isn't written out again.
        if (letters(reading.romanized) == letters(line)) reading.copy(romanized = "", typed = "") else reading
    }

    private fun letters(text: String) = text.filter(Char::isLetterOrDigit).lowercase()

    private val tokenizer by lazy { Tokenizer() }

    private fun japanese(line: String): LineReading {
        val pieces = together(pieces(line))
        val parts = pieces.flatMap { if (it.surface.any(::isHan) && it.kana != it.surface) furigana(it.surface, it.kana) else listOf(Ruby(it.surface)) }
        return LineReading(
            joined(parts),
            pieces.joinToString("") { it.kana },
            romajiWords(pieces) { it.said }.joinToString(" ") { romaji(it) },
            romajiWords(pieces) { it.typed }.joinToString(" ") { romaji(it, typed = true) }
        )
    }

    // Kanji in their old forms, as 燈 for 灯, read as the new ones the dictionary knows, and stay as written.
    private fun pieces(line: String): List<Piece> = tokenizer.tokenize(line.map { NEW_FORMS[it] ?: it }.joinToString("")).map { token ->
            // Words the dictionary doesn't know, e.g. English ones, have no reading: they read as written.
            Piece(
                line.substring(token.position, token.position + token.surface.length),
                token.reading?.takeIf { it != "*" }?.let(::hiragana) ?: token.surface,
                token.partOfSpeechLevel1,
                token.partOfSpeechLevel2,
                token.baseForm?.takeIf { it != "*" } ?: token.surface,
                token.pronunciation?.takeIf { it != "*" }?.let { drawnOutE(hiragana(it)) }
            )
        }

    /** Old forms of kanji, each before its new one, as lyrics keep them for their feel. */
    private val NEW_FORMS = ("燈灯戀恋櫻桜聲声體体國国學学氣気會会來来晝昼黑黒發発傳伝實実廣広獨独歸帰圓円驛駅舊旧" +
        "樂楽萬万與与爭争戰戦繪絵戲戯聽聴譯訳變変邊辺鐵鉄靜静顏顔惠恵濱浜擧挙寫写對対數数斷断曉暁歡歓歲歳殘残滿満" +
        "燒焼爲為畫画當当盡尽眞真禮礼經経綠緑總総續続處処號号螢蛍觀観讀読豐豊賣売輕軽辭辞醉酔黃黄齒歯淚涙")
        .chunked(2).associate { it[0] to it[1] }

    /**
     * [line] of a Japanese song as Japan writes it, when a Chinese site wrote it China's way: kanji
     * Japan doesn't use, as 间 or 仆, as Japan's own, 間 and 僕, which have readings, and the comma
     * Chinese ends a line with, "，", left off.
     */
    fun asJapanWrites(line: String): String = inJapansKanji(line).let { if (it.trimEnd().endsWith('，')) it.trimEnd().dropLast(1).trimEnd() else it }

    /** [text] with China's kanji as Japan's, one for one, so its timings stay where they were. */
    fun inJapansKanji(text: String): String = if (text.none { it in JAPANS_KANJI }) text else text.map { JAPANS_KANJI[it] ?: it }.joinToString("")

    /**
     * Kanji as China writes them, each before Japan's own. Each of China's is one Japan doesn't
     * write, but for 仆, which Japan only has for an old word, and Chinese sites write for 僕.
     */
    internal val JAPANS_KANJI = ("仆僕终終间間爱愛话話时時门門见見记記忆憶让譲这這远遠还還过過进進连連达達运運边辺对対发発东東车車" +
        "长長开開关関问問闻聞闪閃风風飞飛马馬鸟鳥鱼魚龙竜头頭颜顔题題认認讲講识識语語读読谁誰谢謝词詞试試诗詩请請调調" +
        "谈談论論设設许許证証评評访訪计計议議译訳诉訴课課实実汉漢满満线線红紅纸紙经経结結给給继継续続练練组組细細约約" +
        "级級纯純织織统統绘絵罗羅编編刚剛别別则則剑剣创創动動劳労势勢华華单単卖売压圧县県变変响響团団围囲园園图図场場" +
        "块塊坚堅处処备備复復夺奪奋奮孙孫宁寧宫宮寻尋导導岛島带帯庆慶应応废廃张張弹弾归帰彻徹怀懐态態恶悪恼悩惊驚惯慣" +
        "戏戯战戦扑撲执執扩拡扫掃扬揚护護报報拥擁择択挥揮损損换換摇揺摄摂敌敵显顕晓暁暂暫术術杀殺杂雑权権杨楊极極构構" +
        "树樹样様桥橋检検欢歓毕畢泽沢洁潔测測济済浓濃润潤渐漸渔漁灭滅灵霊灾災炼錬烦煩烧焼热熱爷爺牵牽猎猟环環现現电電" +
        "畅暢疗療盘盤码碼础礎确確离離种種积積稳穏穷窮竞競笔筆笼籠简簡紧緊纪紀纷紛绍紹络絡绩績绪緒维維绵綿缓緩缠纏缩縮" +
        "罚罰职職聪聡肠腸肤膚胜勝脑脳腾騰舰艦艺芸节節荣栄药薬获獲营営萤蛍蓝藍虑慮虽雖补補观観规規视視览覧觉覚讨討训訓" +
        "诚誠诞誕该該详詳误誤诸諸谊誼谜謎谦謙谱譜贝貝负負贡貢财財责責败敗货貨质質贫貧购購贯貫贵貴贸貿费費贺賀资資赏賞" +
        "赞賛赠贈跃躍轨軌轮輪软軟转転轻軽载載较較辈輩辉輝输輸迁遷违違迟遅适適选選遗遺邮郵邻隣释釈针針钟鐘钢鋼钱銭铁鉄" +
        "铃鈴银銀锁鎖错錯锦錦键鍵镜鏡闭閉队隊阳陽阴陰阵陣阶階际際陆陸险険隐隠难難雾霧韩韓项項顺順须須顽頑预預领領频頻" +
        "额額饥飢饭飯饮飲饱飽馆館驱駆验験骑騎骚騒鲜鮮鸣鳴齐斉齿歯龄齢龟亀为為乐楽买買亚亜产産亲親亿億仓倉仪儀优優传伝" +
        "伤傷伦倫伪偽侦偵侧側俭倹债債倾傾偿償兰蘭兴興养養兽獣军軍农農冻凍净浄减減凤鳳剂剤剧劇劝勧务務协協厅庁厌厭叹嘆" +
        "员員圆円圣聖坟墳坠墜垒塁壳殻奖奨妇婦婴嬰宠寵审審宪憲尔爾尘塵层層岭嶺币幣师師帐帳库庫庙廟异異强強总総恳懇惩懲" +
        "愤憤扰擾抚撫拟擬挚摯斋斎标標栋棟栏欄毁毀沟溝滥濫滨浜灿燦烛燭牺犠狮獅狱獄盐塩监監矫矯矿鉱类類绳縄缔締缚縛聋聾" +
        "肃粛肾腎艰艱艳艶苍蒼苏蘇茧繭莲蓮虏虜蚁蟻袭襲谓謂说説绿緑绝絶缘縁历歴宽寛岁歳录録赖頼闲閑阅閲鸡鶏众衆击撃" +
        "伞傘乡郷书書习習丽麗举挙义義仅僅侣侶储儲冈岡卫衛叠畳吓嚇吴呉哑唖唤喚喷噴宾賓帅帥忧憂悬懸户戸斩斬昙曇晕暈" +
        "枪槍栈桟汤湯浊濁涡渦涩渋溃潰滤濾滩灘烂爛疯瘋皱皺瞒瞞硕碩祸禍秃禿窝窩竖竪纠糾纤繊纬緯纱紗纲綱纳納纵縦纹紋" +
        "纺紡纽紐绅紳绊絆绞絞绢絹绣繍绮綺综綜绽綻缀綴缝縫罢罷耸聳肿腫胀脹胁脅胧朧脓膿荡蕩莺鶯萧蕭萨薩蔷薔蕴蘊蚀蝕蜗蝸" +
        "蝇蝿衔銜衬襯觅覓订訂讯訊讳諱讶訝讽諷诀訣诈詐诊診询詢诫誡诱誘诵誦诺諾谅諒谋謀谐諧谣謡谨謹贞貞贤賢贩販贪貪贱賤" +
        "贴貼贼賊赋賦赌賭赐賜赔賠赵趙趋趨轩軒轰轟辅輔辆輌辖轄辽遼迈邁逊遜递逓酱醤酿醸鉴鑑钓釣钝鈍钦欽铅鉛铜銅铭銘铺舗" +
        "锅鍋锋鋒锐鋭锻鍛镇鎮闷悶阀閥阁閣阔闊陈陳雏雛页頁顶頂顾顧顿頓颁頒颇頗颠顛颤顫飘飄饰飾饲飼饶饒饼餅驰馳驳駁" +
        "驻駐驾駕骂罵骏駿骗騙骤驟鲁魯鲸鯨鳞鱗鸥鴎鸭鴨鹅鵝鹤鶴鹰鷹")
        .chunked(2).associate { it[0] to it[1] }

    /** The dictionary draws out おう as おー, but not えい, as in せんせい, said せんせー. */
    private fun drawnOutE(kana: String) = kana.replace(Regex("([えけげせぜてでねへべぺめれ])い"), "$1ー")

    /**
     * A word as the dictionary splits a line, with how it reads and what part of speech it is, and
     * how it's [spoken], when that's known: 先生, read せんせい, is said せんせー.
     */
    private class Piece(val surface: String, val kana: String, val kind: String, val subkind: String, val base: String = surface, val spoken: String? = null) {
        /** How it's said, for romaji, so it's sung right: sensee, not sensei; the particles は and へ are wa and e. */
        val said get() = if (kind == "助詞") particle ?: kana else spoken ?: kana

        /** How it's typed, for romaji as a keyboard spells it: sensei; the particles still wa and e, as romaji writes them. */
        val typed get() = if (kind == "助詞") particle ?: kana else kana

        private val particle get() = when (surface) { "は" -> "わ"; "へ" -> "え"; else -> null }
    }

    /**
     * Pieces the dictionary splits but that read as one, like 二人, ふたり rather than に and にん.
     * Kept to numbers of people, the ones lyrics are full of.
     */
    private fun together(pieces: List<Piece>): List<Piece> {
        val out = mutableListOf<Piece>()
        for (piece in pieces) {
            val last = out.lastOrNull()
            val word = last?.let { TOGETHER[it.surface + piece.surface] }
            if (word != null) out[out.lastIndex] = Piece(last.surface + piece.surface, word, "名詞", "一般") else out += piece
        }
        return out
    }

    private val TOGETHER = mapOf("一人" to "ひとり", "二人" to "ふたり")

    /**
     * [read] with [fixes] made: each kanji takes its new reading, on the line and in its kana and
     * romaji. A fix that also gives the kana written after the kanji, ごし for 越 in 越し, has
     * those left off; one that changes nothing, or names a kanji the line hasn't, is left out.
     */
    fun corrected(read: List<LineReading>, fixes: List<ReadingFix>): List<LineReading> = read.mapIndexed { index, line ->
        fixes.filter { it.line == index }.fold(line) { fixed, fix ->
            val at = fixed.parts.indexOfFirst { it.text == fix.kanji && it.reading != null }
            if (at < 0) return@fold fixed
            val old = fixed.parts[at].reading!!
            val after = hiragana(fixed.parts.drop(at + 1).joinToString("") { it.text })
            val wanted = hiragana(fix.reading).let { reading ->
                (reading.length - 1 downTo 1).firstOrNull { cut -> after.startsWith(reading.substring(cut)) }?.let { reading.substring(0, it) } ?: reading
            }
            if (wanted.isEmpty() || wanted == old || wanted.any { it !in 'ぁ'..'ゟ' }) return@fold fixed
            // Only where those kanji are: another word read the same, e.g. a く of 君 in くん, stays.
            val fixing = fixed.parts.indices.filter { fixed.parts[it].text == fix.kanji && fixed.parts[it].reading == old }
            val starts = fixed.parts.runningFold(0) { at, part -> at + (part.reading ?: part.text).length }
            val inReading = fixing.map { starts[it] }.filter { fixed.reading.startsWith(old, it) }
            // Which times the old reading comes in the line, counted as romaji counts them, are these.
            val times = Regex(Regex.escape(old)).findAll(fixed.reading).map { it.range.first }.toList()
            val nth = inReading.map(times::indexOf).filter { it >= 0 }.toSet()
            var seen = -1
            var typedSeen = -1
            LineReading(
                fixed.parts.mapIndexed { at, part -> if (at in fixing) part.copy(reading = wanted) else part },
                inReading.sortedDescending().fold(fixed.reading) { line, at -> line.replaceRange(at, at + old.length, wanted) },
                // Romaji is as it's said, so the reading may be there drawn out, sensee for sensei.
                fixed.romanized.replace(Regex("""\b(${Regex.escape(drawnOut(romaji(old)))}|${Regex.escape(romaji(old))})""")) {
                    if (++seen !in nth) it.value else if (it.value == romaji(old)) romaji(wanted) else drawnOut(romaji(wanted))
                },
                // Typed, it's as the dictionary spells it.
                fixed.typed.replace(Regex("""\b${Regex.escape(romaji(old, typed = true))}""")) {
                    if (++typedSeen in nth) romaji(wanted, typed = true) else it.value
                }
            )
        }
    }

    /** Romaji with its long vowels as they're said: ei and ou drawn out, as ee and oo. */
    private fun drawnOut(romaji: String) = romaji.replace("ei", "ee").replace("ou", "oo")

    /**
     * The words of a line in kana, as romaji spaces them: a verb with its endings is one word, e.g.
     * 分かった is wakatta rather than waka ta, while particles stand alone. Brackets and marks sit
     * against what they're beside.
     */
    private fun romajiWords(pieces: List<Piece>, kana: (Piece) -> String): List<String> = grouped(pieces).map { word -> word.joinToString("", transform = kana) }

    /** [pieces] in words, as [romajiWords] spaces them. */
    private fun grouped(pieces: List<Piece>): List<List<Piece>> {
        val words = mutableListOf<MutableList<Piece>>()
        var previous: Piece? = null
        var opened = false
        for (piece in pieces) {
            if (piece.surface.isBlank()) {
                previous = null
                continue
            }
            val opening = piece.surface in OPENING
            val ending = piece.kind == "助動詞" || (piece.kind == "助詞" && piece.subkind == "接続助詞") ||
                (piece.kind == "動詞" && piece.subkind == "非自立") || piece.subkind == "接尾"
            // An ending after a particle starts a word of its own: だけだった is dake datta. English
            // the dictionary splits, "Don't" into Don, ' and t, stays as written.
            val english = previous != null && isEnglish(previous.surface) && isEnglish(piece.surface)
            val attaches = previous != null && (opened || english || (piece.kind == "記号" && !opening) || (ending && previous.kind != "助詞"))
            if (attaches) words.last() += piece else words += mutableListOf(piece)
            opened = opening
            previous = piece
        }
        return words
    }

    /** Latin letters, digits and apostrophes, as English is written in Japanese lyrics. */
    private fun isEnglish(text: String) = text.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '\'' || it == '’' }

    /**
     * The words of [line], to look up one by one: Japanese as its dictionary splits it, with each
     * word's kana and the form it's listed under; Chinese, which has no spaces, as Android's own
     * dictionary splits it, with its pinyin; anything else between its spaces.
     */
    // Words are only ever Chinese from Android 10 on, see scriptOf.
    @SuppressLint("NewApi")
    fun words(line: String, script: Script?): List<Word> = when (script) {
        Script.JAPANESE -> grouped(together(pieces(line))).mapNotNull { word ->
            val text = word.joinToString("") { it.surface }.trim { !it.isLetterOrDigit() }
            if (text.none(Char::isLetter)) return@mapNotNull null
            val kana = word.joinToString("") { it.kana }.trim { !it.isLetterOrDigit() }
            // Kana reads as written; the reading's only worth showing for kanji.
            val said = word.joinToString("") { it.said }.trim { !it.isLetterOrDigit() }
            val typed = word.joinToString("") { it.typed }.trim { !it.isLetterOrDigit() }
            Word(text, kana.takeIf { it != text }, romaji(said), lookup = word.first { it.kind != "記号" }.base, typed = romaji(typed, typed = true))
        }
        else -> segments(line).map { text ->
            when (script) {
                Script.CHINESE -> chinese(text).let { Word(text, it.reading, null) }
                Script.KOREAN -> Word(text, null, romaja(text))
                null -> Word(text)
                else -> Word(text, null, latin(text))
            }
        }
    }

    /** The words between [line]'s spaces and marks, or, in Chinese, between its words. */
    private fun segments(line: String): List<String> {
        val breaks = android.icu.text.BreakIterator.getWordInstance()
        breaks.setText(line)
        val words = mutableListOf<String>()
        var start = breaks.first()
        var end = breaks.next()
        while (end != android.icu.text.BreakIterator.DONE) {
            line.substring(start, end).takeIf { it.any(Char::isLetter) }?.let(words::add)
            start = end
            end = breaks.next()
        }
        return words
    }

    private const val OPENING = "「『（(【〈《“"

    /** Kana over only the kanji of [surface]: the kana it starts or ends with read as written. */
    private fun furigana(surface: String, kana: String): List<Ruby> {
        val tail = surface.reversed().zip(kana.reversed()).takeWhile { (s, k) -> s == k && isKana(s) }.size
        val head = surface.zip(kana).takeWhile { (s, k) -> s == k && isKana(s) }.size
        val core = surface.substring(head, surface.length - tail)
        val coreReading = kana.substring(head, kana.length - tail)
        return listOfNotNull(
            surface.take(head).takeIf { it.isNotEmpty() }?.let { Ruby(it) },
            Ruby(core, coreReading),
            surface.takeLast(tail).takeIf { it.isNotEmpty() }?.let { Ruby(it) }
        )
    }

    private var transliterator: Transliterator? = null

    /** Android's own pinyin, made once, the first time it's needed. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun pinyin(): Transliterator = transliterator ?: Transliterator.getInstance("Han-Latin").also { transliterator = it }

    private var simplifier: Transliterator? = null

    /** [text] in simplified characters, "小幸運" as "小幸运", as sites write the same Chinese title either way. Unchanged before Android 10. */
    @SuppressLint("NewApi")
    fun simplified(text: String): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || text.none(::isHan)) return text
        return synchronized(this) { (simplifier ?: Transliterator.getInstance("Hant-Hans").also { simplifier = it }).transliterate(text) }
    }

    /** One line at a time, as Android's transliterator can't be used from two threads at once. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun chinese(line: String): LineReading = synchronized(this) {
        val parts = line.map { char -> if (isHan(char)) Ruby(char.toString(), pinyin().transliterate(char.toString())) else Ruby(char.toString()) }
        val romanized = pinyin().transliterate(line).trim()
        LineReading(joined(parts), romanized, romanized)
    }

    /** Android's own letters-to-Latin, made once, the first time it's needed. */
    private var latinizer: Transliterator? = null

    /** [text] in plain Latin letters, as Cyrillic, Greek, Arabic, Hebrew, Indic and Thai are said. Words are only ever in these from Android 10 on, see displayScriptOf. */
    @SuppressLint("NewApi")
    private fun latin(text: String): String = synchronized(this) {
        val letters = latinizer ?: Transliterator.getInstance("Any-Latin; Latin-ASCII").also { latinizer = it }
        letters.transliterate(text).trim()
    }

    private fun latinized(line: String): LineReading = latin(line).let { LineReading(listOf(Ruby(line)), it, it) }

    private fun korean(line: String): LineReading {
        val romanized = romaja(line)
        return LineReading(listOf(Ruby(line)), romanized, romanized)
    }

    /** Neighboring pieces read as written join into one. */
    private fun joined(parts: List<Ruby>): List<Ruby> = parts.fold(mutableListOf()) { joined, part ->
        val last = joined.lastOrNull()
        if (last != null && last.reading == null && part.reading == null) {
            joined[joined.lastIndex] = Ruby(last.text + part.text)
        } else {
            joined += part
        }
        joined
    }

    fun isKana(c: Char) = c in 'ぁ'..'ゟ' || c in '゠'..'ヿ'
    private fun isHangul(c: Char) = c in '가'..'힣' || c in 'ᄀ'..'ᇿ' || c in '㄰'..'㆏'
    fun isHan(c: Char) = c in '一'..'鿿' || c in '㐀'..'䶿' || c == '々'

    /** Katakana as hiragana, which is how furigana are written. */
    fun hiragana(katakana: String): String = katakana.map { if (it in 'ァ'..'ヶ') it - 0x60 else it }.joinToString("")

    /**
     * Kana in Latin letters, the Hepburn way: しゃ is sha, っ doubles what follows, ー lengthens the
     * vowel before; を is o, as it's said, or wo, as it's [typed].
     */
    fun romaji(kana: String, typed: Boolean = false): String {
        val hira = hiragana(kana)
        val out = StringBuilder()
        var double = false
        var afterN = false
        var i = 0
        while (i < hira.length) {
            val two = if (i + 1 < hira.length) hira.substring(i, i + 2) else null
            val syllable = two?.let(YOUON::get)?.also { i += 2 } ?: (if (typed && hira[i] == 'を') "wo" else KANA[hira[i]])?.also { i++ }
            if (syllable == null) {
                when (val c = hira[i]) {
                    'っ' -> double = true
                    'ー' -> out.lastOrNull { it in "aeiou" }?.let(out::append)
                    else -> out.append(c)
                }
                afterN = false
                i++
                continue
            }
            if (double) {
                out.append(if (syllable.startsWith("ch")) 't' else syllable[0])
                double = false
            }
            // ん before a vowel or y is n', so it isn't read with them.
            if (afterN && syllable[0] in "aeiouy") out.append('\'')
            afterN = hira[i - 1] == 'ん'
            out.append(syllable)
        }
        return out.toString()
    }

    private val KANA: Map<Char, String> = buildMap {
        val rows = listOf(
            "あいうえお" to listOf("a", "i", "u", "e", "o"),
            "かきくけこ" to listOf("ka", "ki", "ku", "ke", "ko"),
            "がぎぐげご" to listOf("ga", "gi", "gu", "ge", "go"),
            "さしすせそ" to listOf("sa", "shi", "su", "se", "so"),
            "ざじずぜぞ" to listOf("za", "ji", "zu", "ze", "zo"),
            "たちつてと" to listOf("ta", "chi", "tsu", "te", "to"),
            "だぢづでど" to listOf("da", "ji", "zu", "de", "do"),
            "なにぬねの" to listOf("na", "ni", "nu", "ne", "no"),
            "はひふへほ" to listOf("ha", "hi", "fu", "he", "ho"),
            "ばびぶべぼ" to listOf("ba", "bi", "bu", "be", "bo"),
            "ぱぴぷぺぽ" to listOf("pa", "pi", "pu", "pe", "po"),
            "まみむめも" to listOf("ma", "mi", "mu", "me", "mo"),
            "やゆよ" to listOf("ya", "yu", "yo"),
            "らりるれろ" to listOf("ra", "ri", "ru", "re", "ro"),
            "わをん" to listOf("wa", "o", "n"),
            "ぁぃぅぇぉ" to listOf("a", "i", "u", "e", "o"),
            "ゃゅょゎ" to listOf("ya", "yu", "yo", "wa"),
            "ゔ" to listOf("vu")
        )
        rows.forEach { (kana, latin) -> kana.forEachIndexed { index, c -> put(c, latin[index]) } }
    }

    private val YOUON: Map<String, String> = buildMap {
        val small = listOf('ゃ' to "a", 'ゅ' to "u", 'ょ' to "o")
        listOf("き" to "ky", "ぎ" to "gy", "し" to "sh", "じ" to "j", "ち" to "ch", "ぢ" to "j", "に" to "ny", "ひ" to "hy", "び" to "by", "ぴ" to "py", "み" to "my", "り" to "ry")
            .forEach { (kana, start) -> small.forEach { (s, vowel) -> put(kana + s, start + vowel) } }
        put("ふぁ", "fa"); put("ふぃ", "fi"); put("ふぇ", "fe"); put("ふぉ", "fo")
        put("てぃ", "ti"); put("でぃ", "di"); put("うぃ", "wi"); put("うぇ", "we"); put("ゔぁ", "va")
    }

    /** Hangul in Latin letters, the Revised Romanization way, syllable by syllable. */
    fun romaja(hangul: String): String = hangul.map { c ->
        if (c !in '가'..'힣') return@map c.toString()
        val index = c - '가'
        INITIALS[index / (21 * 28)] + MEDIALS[(index % (21 * 28)) / 28] + FINALS[index % 28]
    }.joinToString("")

    private val INITIALS = listOf("g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s", "ss", "", "j", "jj", "ch", "k", "t", "p", "h")
    private val MEDIALS = listOf("a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae", "oe", "yo", "u", "wo", "we", "wi", "yu", "eu", "ui", "i")
    private val FINALS = listOf("", "k", "k", "k", "n", "n", "n", "t", "l", "k", "m", "l", "l", "l", "p", "l", "m", "p", "p", "t", "t", "ng", "t", "t", "k", "t", "p", "t")
}
