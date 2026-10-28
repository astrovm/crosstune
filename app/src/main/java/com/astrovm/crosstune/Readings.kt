package com.astrovm.crosstune

import android.annotation.SuppressLint
import android.icu.text.Transliterator
import android.os.Build
import androidx.annotation.RequiresApi
import com.atilika.kuromoji.ipadic.Tokenizer
import kotlin.math.abs

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
        val given = givenReadings(line)
        val pieces = readAsGiven(together(pieces(withoutGiven(line))), given)
        val parts = pieces.flatMap { if (it.surface.any(::isHan) && it.kana != it.surface) furigana(it.surface, it.kana) else listOf(Ruby(it.surface)) }
        return LineReading(
            joined(withGiven(parts, given)),
            pieces.joinToString("") { it.kana },
            romanizedLine(pieces, typed = false),
            romanizedLine(pieces, typed = true)
        )
    }

    /** UniDic, once it's on the phone: Japanese is read with it too, where it knows better than IPADIC. */
    @Volatile
    var uniDic: com.atilika.kuromoji.unidic.Tokenizer? = null

    // Kanji in their old forms, as 燈 for 灯, read as the new ones the dictionary knows, and a digit
    // counting something, as 2 in 2人, as its kanji, 二人 ふたり, not a number on its own: they stay as written.
    private fun pieces(line: String): List<Piece> {
        val text = line.mapIndexed { at, c -> NEW_FORMS[c] ?: KANJI_DIGITS[c]?.takeIf { counts(line, at) } ?: c }.joinToString("")
        val known = tokenizer.tokenize(text).map { token ->
            // Words the dictionary doesn't know, e.g. English ones, have no reading: they read as written.
            val reading = token.reading?.takeIf { it != "*" }
            Piece(
                line.substring(token.position, token.position + token.surface.length),
                reading?.let(::hiragana) ?: token.surface,
                token.partOfSpeechLevel1,
                token.partOfSpeechLevel2,
                token.baseForm?.takeIf { it != "*" } ?: token.surface,
                token.pronunciation?.takeIf { it != "*" }?.let { drawnOutE(hiragana(it)) },
                token.position + token.surface.length,
                unread = reading == null
            )
        }
        return uniDic?.let { mixed(known, uniDicPieces(it, line, text)) } ?: known
    }

    /** [line] as UniDic splits it. */
    private fun uniDicPieces(uniDic: com.atilika.kuromoji.unidic.Tokenizer, line: String, text: String) = uniDic.tokenize(text).map { token ->
        val surface = line.substring(token.position, token.position + token.surface.length)
        // UniDic reads Latin letters too, "eh" as え and エイチ, which stay as written.
        val said = token.pronunciation.takeIf { it != "*" && surface.any { isHan(it) || isKana(it) } }
        Piece(
            surface,
            if (said != null && surface.any(::isHan)) spelled(said, token.lemmaReadingForm) else hiragana(surface),
            token.partOfSpeechLevel1,
            // A verb that can follow another, 続ける in 見続ける, as IPADIC names it, so it's one word with it.
            if (token.partOfSpeechLevel2 == "非自立可能" && token.partOfSpeechLevel1 == "動詞") "非自立" else token.partOfSpeechLevel2,
            token.writtenBaseForm.takeIf { it != "*" } ?: surface,
            said?.let(::hiragana),
            token.position + token.surface.length,
            unread = said == null
        )
    }

    /**
     * The kana a word UniDic gives as it's [said], ちょーじょー, is spelled with, ちょうじょう: each
     * long vowel as the word's dictionary form, [listed], spells it there, or else as it most often
     * is, こう more than こお; and づ and ぢ, said as ず and じ, where it spells them so.
     */
    private fun spelled(said: String, listed: String): String {
        val spelling = hiragana(listed)
        val out = StringBuilder()
        hiragana(said).forEachIndexed { at, kana ->
            val there = spelling.getOrElse(at) { ' ' }
            out.append(
                when {
                    kana == 'ー' && out.isNotEmpty() -> LONG[romaji(out.last().toString()).lastOrNull()]?.let { if (there in it) there else it[0] } ?: kana
                    (kana == 'ず' && there == 'づ') || (kana == 'じ' && there == 'ぢ') -> there
                    else -> kana
                }
            )
        }
        return out.toString()
    }

    /** The kana a long vowel is spelled with after each vowel, the likelier first. */
    private val LONG = mapOf('a' to "あ", 'i' to "い", 'u' to "う", 'e' to "いえ", 'o' to "うお")

    /**
     * The line as IPADIC splits it, with UniDic's words where it knows better: kanji IPADIC has no
     * reading for, 溢 or 踠; a word IPADIC breaks up, 微笑み into 微 and 笑み; and a word IPADIC
     * takes for a prefix or a suffix to nothing, 君 read くん after a space. Elsewhere IPADIC reads
     * lyrics better: 私 わたし, where UniDic has わたくし, and 不器用 as a word of its own.
     */
    private fun mixed(ours: List<Piece>, theirs: List<Piece>): List<Piece> {
        val out = mutableListOf<Piece>()
        var i = 0
        var j = 0
        while (i < ours.size && j < theirs.size) {
            val before = ours.getOrNull(i - 1)
            // The pieces of each up to the next place both split the line.
            val a = mutableListOf(ours[i++])
            val b = mutableListOf(theirs[j++])
            while (a.last().end != b.last().end) if (a.last().end < b.last().end) a += ours[i++] else b += theirs[j++]
            val better = a.any { it.unknown } || (b.size == 1 && a.size > 1 && b[0].surface.any(::isHan)) || (a.size == 1 && b.size == 1 && a[0].strayAffix(before))
            out += if (better && b.none { it.unknown }) b else a
        }
        return out
    }

    /**
     * A reading the lyrics give in brackets after the kanji it's for, as in 歌（うた） or
     * 悲（かな）しい: it's how they're sung, so it's theirs, and the line is read without it.
     */
    private val givenReading = Regex("""([\p{IsHan}々]+)([（(]([\p{IsHiragana}\p{IsKatakana}ー]+)[）)])""")

    /** A reading the lyrics give: from [start] to [end] in the line without them, the kanji read [kana]; [brackets] as written. */
    private class Given(val start: Int, val end: Int, val kana: String, val brackets: String)

    private fun givenReadings(line: String): List<Given> {
        var cut = 0
        return givenReading.findAll(line).map { match ->
            val start = match.range.first - cut
            cut += match.groupValues[2].length
            Given(start, start + match.groupValues[1].length, hiragana(match.groupValues[3]), match.groupValues[2])
        }.toList()
    }

    private fun withoutGiven(line: String) = line.replace(givenReading, "$1")

    /**
     * [pieces] of the line without its given readings, with those kanji read as [given]: with the
     * kana written after them, 悲しい as かな and しい, or apart from more kanji after them.
     */
    private fun readAsGiven(pieces: List<Piece>, given: List<Given>): List<Piece> {
        if (given.isEmpty()) return pieces
        val left = ArrayDeque(pieces)
        val out = mutableListOf<Piece>()
        var at = 0
        while (left.isNotEmpty()) {
            val reading = given.firstOrNull { it.start == at }
            if (reading == null) {
                out += left.removeFirst().also { at += it.surface.length }
                continue
            }
            val first = left.first()
            var surface = ""
            var kana = ""
            while (at + surface.length < reading.end) left.removeFirst().let { surface += it.surface; kana += it.kana }
            val after = surface.drop(reading.end - at)
            if (after.any(::isHan)) {
                out += Piece(surface.take(reading.end - at), reading.kana, first.kind, first.subkind)
                // The rest as the dictionary read the word, ごえ of 歌声, when it read the kanji so too; and
                // as the lyrics read it, when they give a reading for it too, 声（こえ）.
                left.addFirst(Piece(after, if (kana.startsWith(reading.kana)) kana.drop(reading.kana.length) else together(pieces(after)).joinToString("") { it.kana }, first.kind, first.subkind))
                at = reading.end
            } else {
                out += Piece(surface, reading.kana + hiragana(after), first.kind, first.subkind, first.base)
                at += surface.length
            }
        }
        return out
    }

    /** [parts] of the line without its given readings, with their brackets back where they're written. */
    private fun withGiven(parts: List<Ruby>, given: List<Given>): List<Ruby> {
        if (given.isEmpty()) return parts
        val out = mutableListOf<Ruby>()
        var at = 0
        for (part in parts) {
            var rest = part
            for (reading in given.filter { it.end > at && it.end <= at + part.text.length }) {
                val cut = reading.end - at - (part.text.length - rest.text.length)
                out += Ruby(rest.text.take(cut), rest.reading)
                out += Ruby(reading.brackets)
                rest = Ruby(rest.text.drop(cut))
            }
            if (rest.text.isNotEmpty()) out += rest
            at += part.text.length
        }
        return out
    }

    /** Whether the digit at [at] is one alone before a counter, a kanji or the つ of 1つ, rather than part of a number, 100人. */
    private fun counts(line: String, at: Int) =
        line.getOrNull(at - 1)?.isDigit() != true && line.getOrNull(at + 1)?.let { isHan(it) || it == 'つ' } == true

    /** Digits, also as written full width, and the kanji counting with them. */
    private val KANJI_DIGITS = ("1一2二3三4四5五6六7七8八9九" + "１一２二３三４四５五６六７七８八９九").chunked(2).associate { it[0] to it[1] }

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
     * write, but for 仆 and 梦, which Japan only has for an old word and an old form, and Chinese
     * sites write for 僕 and 夢.
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
        "梦夢顷頃额額饥飢饭飯饮飲饱飽馆館驱駆验験骑騎骚騒鲜鮮鸣鳴齐斉齿歯龄齢龟亀为為乐楽买買亚亜产産亲親亿億仓倉仪儀优優传伝" +
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
    private class Piece(
        val surface: String,
        val kana: String,
        val kind: String,
        val subkind: String,
        val base: String = surface,
        val spoken: String? = null,
        /** Where it ends in the line. */
        val end: Int = 0,
        /** Whether the dictionary has no reading for it. */
        val unread: Boolean = false
    ) {
        /** Kanji the dictionary can't read. */
        val unknown get() = unread && surface.any(::isHan)

        /** A prefix or suffix of kanji with no word to be one of: a suffix fixes to a noun before it, [before]. */
        fun strayAffix(before: Piece?) = surface.any(::isHan) && (kind == "接頭詞" || (subkind == "接尾" && before?.kind != "名詞"))

        /** It read [kana], as a word of [kind] and [subkind] when that changes too. */
        fun read(kana: String, kind: String = this.kind, subkind: String = this.subkind) = Piece(surface, kana, kind, subkind, base, null, end)

        /** It read [kana], and said [said]. */
        fun reading(kana: String, said: String) = Piece(surface, kana, kind, subkind, base, said, end)

        /** How it's said, for romaji, so it's sung right: sensee, not sensei; the particles は and へ are wa and e. */
        val said get() = if (kind == "助詞") particle ?: kana else spoken ?: kana

        /** How it's typed, for romaji as a keyboard spells it: sensei; the particles still wa and e, as romaji writes them. */
        val typed get() = if (kind == "助詞") particle ?: kana else kana

        private val particle get() = when (surface) { "は" -> "わ"; "へ" -> "え"; else -> null }
    }

    /**
     * Pieces the dictionary splits but that read as one, like 二人, ふたり rather than に and にん, also
     * written 2人. Kept to numbers of people, the ones lyrics are full of, and 一 before a counter,
     * which it runs into: 一歩 いっぽ, 一回 いっかい. And words read as lyrics sing them: 側 on its own
     * is そば, 君の側で, but after the side it is, こっち側, がわ; 違く, said for 違って, is ちがく.
     */
    private fun together(pieces: List<Piece>): List<Piece> {
        val out = mutableListOf<Piece>()
        for (piece in pieces) {
            val last = out.lastOrNull()
            val word = last?.let { TOGETHER[(it.surface + piece.surface).map { c -> KANJI_DIGITS[c] ?: c }.joinToString("")] }
            when {
                word != null -> out[out.lastIndex] = Piece(last.surface + piece.surface, word, "名詞", "一般")
                last != null && counted(last, piece) -> out[out.lastIndex] = Piece(last.surface + piece.surface, "いっ" + plosive(piece.kana), "名詞", "一般")
                piece.surface == "側" && (last == null || !sideOf(last)) -> out += piece.read("そば", "名詞", "一般")
                // Before a space, IPADIC takes はね after a noun for a verb, 跳ね: it's 僕らは and ね.
                last?.kind == "名詞" && piece.kind == "動詞" && piece.surface.length == 2 && piece.surface[0] == 'は' && piece.surface[1] in ENDINGS -> {
                    out += Piece("は", "は", "助詞", "係助詞")
                    out += Piece(piece.surface.drop(1), piece.surface.drop(1), "助詞", "終助詞")
                }
                last?.surface == "違" && piece.surface.first() in "くか" -> {
                    out[out.lastIndex] = last.read("ちが")
                    out += piece
                }
                else -> out += piece
            }
        }
        return out
    }

    /** Whether [piece] is a counter [one], いち, runs into: one kanji starting with k, s, t or h. */
    private fun counted(one: Piece, piece: Piece) = one.kana == "いち" && one.subkind in NUMBERS && piece.surface.length == 1 &&
        isHan(piece.surface[0]) && piece.kana.firstOrNull()?.let { it in RUN_INTO } == true

    /** [kana] with an h as p, ほ as ぽ, as it is after a sound run into. */
    private fun plosive(kana: String) = if (kana.first() in "はひふへほ") (kana.first() + 2) + kana.drop(1) else kana

    /** Whether 側 after [piece] is a side of it: after kanji or katakana, 反対側 and サーバー側, or a way, こっち側. */
    private fun sideOf(piece: Piece) = piece.surface in SIDES || piece.surface.last().let { isHan(it) || it in 'ァ'..'ヺ' || it == 'ー' }

    /** Particles a line ends on, ね and よ. */
    private const val ENDINGS = "ねさよな"
    private val NUMBERS = setOf("数", "数詞")
    private const val RUN_INTO = "かきくけこさしすせそたちつてとはひふへほ"
    private val SIDES = setOf("こっち", "そっち", "あっち", "どっち", "こちら", "そちら", "あちら", "どちら", "向こう")

    private val TOGETHER = mapOf("一人" to "ひとり", "二人" to "ふたり")

    /**
     * [read] with [fixes] made: each kanji takes its new reading, on the line and in its kana and
     * romaji. One that changes nothing, or names a kanji the line hasn't, is left out.
     */
    fun corrected(read: List<LineReading>, fixes: List<ReadingFix>): List<LineReading> = read.mapIndexed { index, line ->
        val parts = fixes.filter { it.line == index }.fold(line.parts, ::sungParts)
        if (parts == line.parts) line else readAs(parts)
    }

    /** [parts] with [fix] made, where its kanji are read as the first of them is. */
    private fun sungParts(parts: List<Ruby>, fix: ReadingFix): List<Ruby> {
        val at = parts.indexOfFirst { it.text == fix.kanji && it.reading != null }
        if (at < 0) return parts
        val old = parts[at].reading!!
        val after = hiragana(parts.drop(at + 1).joinToString("") { it.text })
        // A fix may give the kana written after the kanji too, ごし for 越 in 越し, or end in kana that
        // only happen to come next, いま for 今 in 今まで: it's whichever is nearer the length the dictionary read.
        val wanted = hiragana(fix.reading).let { reading ->
            (listOf(reading) + (reading.length - 1 downTo 1).filter { after.startsWith(reading.substring(it)) }.map { reading.substring(0, it) })
                .minBy { abs(it.length - old.length) }
        }
        if (wanted.isEmpty() || wanted == old || wanted.any { it !in 'ぁ'..'ゟ' }) return parts
        // Only where those kanji are: another word read the same, e.g. a く of 君 in くん, stays.
        return parts.map { if (it.text == fix.kanji && it.reading == old) it.copy(reading = wanted) else it }
    }

    /**
     * The line [parts] write, in kana and romaji, with each kanji read as they say: split into words
     * as any line is, so the romaji is too, 頭ん中 as atama n naka, not the dictionary's reading
     * with the new one pasted over it, where it can't be found in a word, atama nchuu.
     */
    private fun readAs(parts: List<Ruby>): LineReading {
        val line = parts.joinToString("") { it.text }
        // What each letter reads as: a kanji's reading on its first letter, nothing on the rest of it.
        val reads = arrayOfNulls<String>(line.length)
        var at = 0
        for (part in parts) {
            if (part.reading != null) part.text.indices.forEach { reads[at + it] = if (it == 0) part.reading else "" }
            at += part.text.length
        }
        val given = givenReadings(line)
        var from = 0
        val pieces = readAsGiven(together(pieces(withoutGiven(line))), given).map { piece ->
            val start = from.also { from += piece.surface.length }
            // Where each letter is in the line, past the readings it gives in brackets.
            val letters = piece.surface.indices.map { i -> (start + i).let { it + given.filter { reading -> reading.end <= it }.sumOf { reading -> reading.brackets.length } } }
            val kana = letters.indices.joinToString("") { reads[letters[it]] ?: piece.surface[it].toString() }
            val said = letters.indices.joinToString("") { reads[letters[it]]?.let(::longVowels) ?: piece.surface[it].toString() }
            if (kana == piece.kana) piece else piece.reading(kana, said)
        }
        return LineReading(
            parts,
            pieces.joinToString("") { it.kana },
            romanizedLine(pieces, typed = false),
            romanizedLine(pieces, typed = true)
        )
    }

    /** A kanji's [kana] as it's said, せんせい as せんせー and しょう as しょー: drawn out, as romaji shows it. */
    private fun longVowels(kana: String) = drawnOutE(kana).replace(Regex("([おこごそぞとどのほぼぽもよろょ])う"), "$1ー")

    /**
     * The words of a line in kana, as romaji spaces them: a verb with its endings is one word, e.g.
     * 分かった is wakatta rather than waka ta, while particles stand alone. Brackets and marks sit
     * against what they're beside.
     */
    private fun romajiWords(pieces: List<Piece>, kana: (Piece) -> String): List<String> = grouped(pieces).map { word -> word.joinToString("", transform = kana) }

    /** The line [pieces] make in romaji, as it's said or [typed], with a space at most between words. */
    private fun romanizedLine(pieces: List<Piece>, typed: Boolean) =
        romajiWords(pieces) { if (typed) it.typed else it.said }.joinToString(" ") { romaji(it, typed) }.replace(Regex("""\s+"""), " ").trim()

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
            // the dictionary splits, "Don't" into Don, ' and t, stays as written. A mark goes with the word
            // before it, also one the dictionary takes for a word, as "!" in "Shock!".
            val english = previous != null && isEnglish(previous.surface) && isEnglish(piece.surface)
            val attaches = previous != null && (opened || english || ((piece.kind == "記号" || piece.surface.none(Char::isLetterOrDigit)) && !opening) || (ending && previous.kind != "助詞"))
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
        Script.JAPANESE -> grouped(readAsGiven(together(pieces(withoutGiven(line))), givenReadings(line))).mapNotNull { word ->
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

    /** What only spaces words in Japanese, a wide space and the ・ of アスファルト・レディ: a space in romaji. */
    private val LATIN_MARKS = mapOf('　' to ' ', '・' to ' ')

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

    /**
     * How [text]'s Chinese is said, in plain letters, "晴天" as "qingtian", as someone who can't type
     * it would; null with none, or before Android 10, which has no pinyin.
     */
    @SuppressLint("NewApi")
    fun pinyinLetters(text: String): String? {
        val han = text.filter(::isHan).ifEmpty { return null }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return synchronized(this) { SongNames.normalize(pinyin().transliterate(han)) }
    }

    /** One line at a time, as Android's transliterator can't be used from two threads at once. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun chinese(line: String): LineReading = synchronized(this) {
        val parts = line.mapIndexed { index, char ->
            if (isHan(char) && !spelledInBopomofo(line, index)) Ruby(char.toString(), pinyin().transliterate(char.toString())) else Ruby(char.toString())
        }
        // Bopomofo is a reading already, so it's left as it's said.
        val said = line.mapIndexed { index, char -> if (spelledInBopomofo(line, index)) 'ㄧ' else char }.joinToString("")
        val romanized = pinyin().transliterate(said).trim()
        LineReading(joined(parts), romanized, romanized)
    }

    /** Whether [line]'s letter at [index] is 一 written for the bopomofo ㄧ it looks like, next to bopomofo: "ㄒ一" is "ㄒㄧ". */
    private fun spelledInBopomofo(line: String, index: Int): Boolean =
        line[index] == '一' && (line.getOrNull(index - 1)?.let(::isBopomofo) == true || line.getOrNull(index + 1)?.let(::isBopomofo) == true)

    /** A bopomofo letter, or a tone mark written with them. */
    private fun isBopomofo(c: Char) = c in 'ㄅ'..'ㄯ' || c in 'ㆠ'..'ㆿ' || c in "ˊˇˋ˙"

    /** Android's own letters-to-Latin, made once, the first time it's needed. */
    private var latinizer: Transliterator? = null

    /** [text] in plain Latin letters, as Cyrillic, Greek, Arabic, Hebrew, Indic and Thai are said. Words are only ever in these from Android 10 on, see displayScriptOf. */
    @SuppressLint("NewApi")
    private fun latin(text: String): String = synchronized(this) {
        val letters = latinizer ?: Transliterator.getInstance("Any-Latin; Latin-ASCII").also { latinizer = it }
        letters.transliterate(lettersAsSaid(text)).trim()
    }

    /** Android's own letters-to-Latin alone, which writes an Indic word letter by letter, marks and all. */
    private var indicLatinizer: Transliterator? = null

    /**
     * [text] with what Android writes in letters with marks that, once those are taken off, no one
     * reads as said: Russian's я, ж and ч are "ya", "zh" and "ch", not "a", "z" and "c"; Hindi's श
     * is "sh", and its words lose the a they aren't said with.
     */
    @SuppressLint("NewApi")
    private fun lettersAsSaid(text: String): String {
        val cyrillic = text.map { c -> CYRILLIC[c.lowercaseChar()]?.let { if (c.isUpperCase()) it.replaceFirstChar(Char::uppercaseChar) else it } ?: c.toString() }.joinToString("")
        val out = StringBuilder()
        var at = 0
        while (at < cyrillic.length) {
            val script = Character.UnicodeScript.of(cyrillic.codePointAt(at))
            if (script !in INDIC_SCRIPTS) {
                out.append(cyrillic[at++])
                continue
            }
            // The word, with its vowel signs and joiners.
            var end = at
            while (end < cyrillic.length && Character.UnicodeScript.of(cyrillic.codePointAt(end)).let { it == script || it == Character.UnicodeScript.INHERITED }) end++
            val word = cyrillic.substring(at, end)
            val letters = (indicLatinizer ?: Transliterator.getInstance("Any-Latin").also { indicLatinizer = it }).transliterate(word)
            out.append(indic(letters, hindiLike = script in HINDI_LIKE))
            at = end
        }
        return out.toString()
    }

    /** An Indic word's [letters], as Android writes them, the way they're said and typed. */
    private fun indic(letters: String, hindiLike: Boolean): String {
        var word = letters
        if (hindiLike) {
            // A word ending in a consonant's own a, after another vowel, isn't said with it: pyāra is pyār.
            word = FINAL_A.replace(word, "")
            // Nor is one between a vowel's consonant and the next's, taken from the end, as each one
            // taken changes the next: samajhanā is samajhnā, not samjhnā.
            while (true) word = MEDIAL_A.findAll(word).lastOrNull()?.let { word.removeRange(it.range) } ?: break
            // ड़ is typed d, as in "Ud Chala"; ऋ is ri, as in "kripa".
            word = word.replace("ṛ", "d").replace("r\u0325", "ri")
        }
        return word.replace(Regex("ch?")) { if (it.value == "ch") "chh" else "ch" }
            .replace("ś", "sh").replace("ṣ", "sh")
            .replace(Regex("ṁ(?=[pbm])"), "m").replace("ṁ", "n").replace("m\u0310", "n")
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
                    else -> out.append(LATIN_MARKS[c] ?: c)
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

    /**
     * Hangul in Latin letters, the Revised Romanization way: as it's sung, where the end of a syllable
     * changes with the start of the next in the same word. 있어 is "isseo", 좋아 "joa", 같이 "gachi", 몰라 "molla".
     * A number in digits is said too, as the word it's part of: 1년 "illyeon".
     */
    fun romaja(hangul: String): String {
        val spelled = NUMBER_IN_A_WORD.replace(hangul) { number -> inHangul(number.value, hangul.substring(number.range.last + 1)) }
        return HANGUL_WORD.replace(spelled) { said(it.value) }
    }

    /**
     * [digits] as they're said before [counted]: in Korean's own numbers before what's counted with
     * them, as 12시 is 열두 시 and 2살 두 살, and otherwise in the ones from Chinese, as 7월 is 칠월.
     */
    private fun inHangul(digits: String, counted: String): String =
        // After another syllable, six's y sound takes an n: 십육 "simnyuk", 열여섯 "yeollyeoseot".
        number(digits.toLong(), counted).replace(SIX_AFTER) { if (it.value == "육") "뉵" else "녀" }

    private fun number(number: Long, counted: String): String {
        if (number in 1..99 && COUNTED_IN_KOREAN.any(counted::startsWith) && !counted.startsWith("개월")) {
            return if (number == 20L) "스무" else KOREAN_TENS[number.toInt() / 10] + KOREAN_ONES[number.toInt() % 10]
        }
        // June and October drop a sound: 유월, 시월.
        if (counted.startsWith("월") && number == 6L) return "유"
        if (counted.startsWith("월") && number == 10L) return "시"
        if (number == 0L) return "영"
        // In fours, as Korean counts: 만 is ten thousand, 억 a hundred million, 조 a trillion.
        var left = number
        var said = ""
        for (big in listOf("", "만", "억", "조")) {
            val four = (left % 10_000).toInt()
            left /= 10_000
            if (four > 0) said = (if (four == 1 && big == "만") "" else upTo9999(four)) + big + said
        }
        return said
    }

    /** A number up to 9999 in Korean's numbers from Chinese: 백칠십육 for 176, with no 일 before a ten, a hundred or a thousand. */
    private fun upTo9999(number: Int): String = listOf(1000 to "천", 100 to "백", 10 to "십", 1 to "").joinToString("") { (place, unit) ->
        when (val digit = number / place % 10) {
            0 -> ""
            1 -> if (unit.isEmpty()) "일" else unit
            else -> SINO_DIGITS[digit] + unit
        }
    }

    private fun said(word: String): String {
        val starts = word.map { CONSONANTS[(it - '가') / (21 * 28)] }.toMutableList()
        val vowels = word.map { VOWELS[(it - '가') % (21 * 28) / 28] }
        val ends = word.map { ENDS[(it - '가') % 28] }.toMutableList()
        for (i in 1 until word.length) {
            val (end, start) = joined(ends[i - 1], starts[i], vowels[i])
            ends[i - 1] = end
            starts[i] = start
        }
        return word.indices.joinToString("") { i ->
            // ㄹ after ㄹ is an l too.
            val start = if (starts[i] == 'ㄹ' && heard(ends.getOrElse(i - 1) { "" }) == 'ㄹ') "l" else STARTS.getValue(starts[i])
            start + vowels[i] + (heard(ends[i])?.let(LAST_SOUNDS::getValue) ?: "")
        }
    }

    /** The end of one syllable and the start of the next, as they're said together. */
    private fun joined(end: String, start: Char, vowel: String): Pair<String, Char> {
        // ㄷ and ㅌ before an i sound become ㅈ and ㅊ: 굳이 "guji", 같이 "gachi", 닫혀 "dachyeo".
        fun softened(c: Char) = if (vowel == "i" || vowel == "yeo") SOFTER[c] ?: c else c
        if (start == 'ㅇ') {
            // A vowel takes the sound before it, and ㅎ goes quiet: 있어 "isseo", 없이 "eopsi", 좋아 "joa".
            val left = end.removeSuffix("ㅎ")
            if (left.isEmpty() || left == "ㅇ") return left to start
            return left.dropLast(1) to softened(left.last())
        }
        // ㅎ and the sound next to it make one harder sound: 이렇게 "ireoke", 축하 "chuka".
        if (end.endsWith('ㅎ')) HARDER_AFTER_H[start]?.let { return end.dropLast(1) to it }
        if (start == 'ㅎ') end.lastOrNull()?.let(HARDER_BEFORE_H::get)?.let { return end.dropLast(1) to softened(it) }
        // Otherwise ㅎ goes quiet, or is said ㄴ before ㄴ: 놓는 "nonneun", 않는 "anneun".
        var heard = heard(if (end == "ㅎ" && start == 'ㄴ') "ㄴ" else end.removeSuffix("ㅎ"), start) ?: return "" to start
        var next = start
        when {
            // ㄴ and ㄹ together are both l: 신라 "silla", 설날 "seollal".
            next == 'ㄹ' && heard == 'ㄴ' -> heard = 'ㄹ'
            next == 'ㄴ' && heard == 'ㄹ' -> next = 'ㄹ'
            // ㄹ after any other sound is said n: 심리 "simni", 독립 "dongnip".
            next == 'ㄹ' && heard != 'ㄹ' -> next = 'ㄴ'
        }
        // And k, t and p go through the nose before n and m: 국물 "gungmul", 있는 "inneun", 합니다 "hamnida".
        if (next == 'ㄴ' || next == 'ㅁ') heard = NASAL[heard] ?: heard
        return heard.toString() to next
    }

    /** The one sound [end] makes closing a syllable before [next]: ㄺ is a k, but an l before ㄱ, as in 맑게 "malge". */
    private fun heard(end: String, next: Char? = null): Char? {
        val sound = when {
            end.isEmpty() -> return null
            end.length == 2 && end[0] == 'ㄹ' && end[1] in "ㄱㅁㅍ" && !(end[1] == 'ㄱ' && next == 'ㄱ') -> end[1]
            else -> end[0]
        }
        return CLOSED[sound] ?: sound
    }

    private val HANGUL_WORD = Regex("[가-힣]+")

    /** Russian's letters, with Ukrainian's and Belarusian's own, as they're written in English. */
    private val CYRILLIC = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "yo", 'ж' to "zh", 'з' to "z", 'и' to "i",
        'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t",
        'у' to "u", 'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "shch", 'ъ' to "", 'ы' to "y", 'ь' to "",
        'э' to "e", 'ю' to "yu", 'я' to "ya", 'є' to "ye", 'і' to "i", 'ї' to "yi", 'ґ' to "g", 'ў' to "u"
    )

    private val INDIC_SCRIPTS = setOf(
        Character.UnicodeScript.DEVANAGARI, Character.UnicodeScript.BENGALI, Character.UnicodeScript.GURMUKHI, Character.UnicodeScript.GUJARATI,
        Character.UnicodeScript.TAMIL, Character.UnicodeScript.TELUGU, Character.UnicodeScript.KANNADA, Character.UnicodeScript.MALAYALAM
    )

    /** Hindi's, Punjabi's and Gujarati's, whose words don't say all the a's their consonants are written with; Telugu's and Tamil's do. */
    private val HINDI_LIKE = setOf(Character.UnicodeScript.DEVANAGARI, Character.UnicodeScript.GURMUKHI, Character.UnicodeScript.GUJARATI)

    /** A vowel and a consonant, with its h if it's said with a breath, as Android writes them. */
    private const val VOWEL = "[aāiīuūeēoō]"
    private const val CONSONANT = "(?:[kgcjṭḍtdpbṛ]h|[kgġṅcjñṭḍṇtdnpbmyrlvśṣshṛqzf])"

    /** A last consonant's own a, after another vowel. */
    private val FINAL_A = Regex("(?<=$VOWEL[^aāiīuūeēoō]{1,6})a$")

    /** A consonant's own a between a vowel's consonant and a consonant with a vowel of its own. */
    private val MEDIAL_A = Regex("(?<=${VOWEL}ṁ?$CONSONANT)a(?=$CONSONANT$VOWEL)")

    /** Digits a word in Hangul goes on from, 1년, but not ones in English, MP3를, or the end of a time or a decimal, 11:11, 3.5배. */
    private val NUMBER_IN_A_WORD = Regex("""(?<![A-Za-z0-9:.,])(?:0|[1-9][0-9]{0,15})(?=[가-힣])""")

    /** What's counted in Korean's own numbers rather than those from Chinese: hours, years of age, people, things. */
    private val COUNTED_IN_KOREAN = listOf("시", "살", "명", "개", "마리", "잔", "병", "사람", "가지", "곡", "송이", "걸음")
    private val KOREAN_TENS = listOf("", "열", "스물", "서른", "마흔", "쉰", "예순", "일흔", "여든", "아흔")
    private val KOREAN_ONES = listOf("", "한", "두", "세", "네", "다섯", "여섯", "일곱", "여덟", "아홉")
    private const val SINO_DIGITS = "영일이삼사오육칠팔구"
    private val SIX_AFTER = Regex("(?<=.)(?:육|여(?=[섯덟]))")
    private const val CONSONANTS = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
    private val STARTS = CONSONANTS.toList().zip(listOf("g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s", "ss", "", "j", "jj", "ch", "k", "t", "p", "h")).toMap()
    private val VOWELS = listOf("a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae", "oe", "yo", "u", "wo", "we", "wi", "yu", "eu", "ui", "i")
    private val ENDS = listOf(
        "", "ㄱ", "ㄲ", "ㄱㅅ", "ㄴ", "ㄴㅈ", "ㄴㅎ", "ㄷ", "ㄹ", "ㄹㄱ", "ㄹㅁ", "ㄹㅂ", "ㄹㅅ", "ㄹㅌ",
        "ㄹㅍ", "ㄹㅎ", "ㅁ", "ㅂ", "ㅂㅅ", "ㅅ", "ㅆ", "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ"
    )

    /** What a consonant sounds like closing a syllable, where only seven are told apart. */
    private val CLOSED = mapOf('ㄲ' to 'ㄱ', 'ㅋ' to 'ㄱ', 'ㅅ' to 'ㄷ', 'ㅆ' to 'ㄷ', 'ㅈ' to 'ㄷ', 'ㅊ' to 'ㄷ', 'ㅌ' to 'ㄷ', 'ㅎ' to 'ㄷ', 'ㅍ' to 'ㅂ')
    private val LAST_SOUNDS = mapOf('ㄱ' to "k", 'ㄴ' to "n", 'ㄷ' to "t", 'ㄹ' to "l", 'ㅁ' to "m", 'ㅂ' to "p", 'ㅇ' to "ng")
    private val SOFTER = mapOf('ㄷ' to 'ㅈ', 'ㅌ' to 'ㅊ')
    private val HARDER_AFTER_H = mapOf('ㄱ' to 'ㅋ', 'ㄷ' to 'ㅌ', 'ㅈ' to 'ㅊ', 'ㅅ' to 'ㅆ')
    private val HARDER_BEFORE_H = mapOf(
        'ㄱ' to 'ㅋ', 'ㄲ' to 'ㅋ', 'ㅋ' to 'ㅋ', 'ㄷ' to 'ㅌ', 'ㅅ' to 'ㅌ', 'ㅆ' to 'ㅌ', 'ㅌ' to 'ㅌ',
        'ㅈ' to 'ㅊ', 'ㅊ' to 'ㅊ', 'ㅂ' to 'ㅍ', 'ㅍ' to 'ㅍ'
    )
    private val NASAL = mapOf('ㄱ' to 'ㅇ', 'ㄷ' to 'ㄴ', 'ㅂ' to 'ㅁ')
}
