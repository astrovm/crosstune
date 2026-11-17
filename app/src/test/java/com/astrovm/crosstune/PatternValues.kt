package com.astrovm.crosstune

/** Reads immutable string properties. Unsupported expressions, cycles and ambiguous names stay unknown. */
internal class PatternValues(
    files: List<Pair<String, String>>,
    private val literalText: (String) -> String,
    private val literals: Regex,
    private val template: Regex,
) {
    private data class Key(val file: String, val name: String)
    private data class Binding(val file: String, val name: String, val expression: String?, val constant: Boolean)

    private val bindings = files.flatMap { (file, source) ->
        declaration.findAll(source).map { match ->
            Binding(file, match.groupValues[1], expressionAt(source, match.range.last + 1), "const" in match.value.substringBefore("val"))
        }.toList()
    }
    private val local = bindings.groupBy { Key(it.file, it.name) }
    private val global = bindings.filter { it.constant }.groupBy { it.name }
    private val owners = files.flatMap { (file, source) -> owner.findAll(source).map { it.groupValues[1] to file }.toList() }
        .groupBy({ it.first }, { it.second })
    private val memo = mutableMapOf<Key, String?>()
    private val reading = mutableSetOf<Key>()

    fun get(file: String, name: String): String? {
        val key = if ('.' in name) {
            val owner = name.substringBeforeLast('.').substringAfterLast('.')
            val source = owners[owner]?.distinct()?.singleOrNull() ?: return null
            Key(source, name.substringAfterLast('.'))
        } else Key(file, name)
        local[key]?.let { return group(key, it) }
        if ('.' in name) return null
        return global[name]?.let { group(Key("", name), it) }
    }

    private fun group(key: Key, candidates: List<Binding>): String? {
        if (key in memo) return memo[key]
        if (!reading.add(key)) return null
        return try {
            val values = candidates.map { value(it.file, it.expression) }
            val result = values.firstOrNull()?.takeIf { first -> values.all { it == first } }
            memo[key] = result
            result
        } finally {
            reading.remove(key)
        }
    }

    private fun value(file: String, expression: String?): String? {
        val text = expression?.trim() ?: return null
        val parts = pieces(text)
        if (parts.size > 1) {
            val values = parts.map { value(file, it) }
            if (values.any { it == null }) return null
            return values.joinToString("")
        }
        if (literals.matches(text)) {
            var complete = true
            val expanded = template.replace(literalText(text)) { part ->
                part.groupValues[1].ifEmpty {
                    val name = part.groupValues[2].ifEmpty { part.groupValues[3] }
                    get(file, name) ?: run { complete = false; part.value }
                }
            }
            return expanded.takeIf { complete }
        }
        take.matchEntire(text)?.let { match ->
            val count = match.groupValues[2].toIntOrNull() ?: return null
            return value(file, match.groupValues[1])?.take(count)
        }
        if (text.startsWith(CLASS_CALL) && text.endsWith(')')) {
            val chars = value(file, text.substring(CLASS_CALL.length, text.length - 1)) ?: return null
            return chars.map { if (it in "[]-\\") "\\$it" else it.toString() }.joinToString("")
        }
        return if (reference.matches(text)) get(file, text) else null
    }

    /** A property's expression ends outside strings and calls. A continued sum stays together. */
    private fun expressionAt(source: String, start: Int): String? {
        var at = start
        var depth = 0
        while (at < source.length) {
            val quoted = literals.matchAt(source, at)
            if (quoted != null) { at = quoted.range.last + 1; continue }
            if (source.startsWith("//", at)) {
                val end = source.indexOf('\n', at).takeIf { it >= 0 } ?: source.length
                val before = source.substring(start, at).trimEnd()
                return before.takeUnless { continued(it, source.substring(end).trimStart()) }
            }
            when (source[at]) {
                '(' -> depth++
                ')' -> if (depth > 0) depth-- else return source.substring(start, at)
                ';', '}' -> if (depth == 0) return source.substring(start, at)
                '\n' -> if (depth == 0) {
                    val before = source.substring(start, at).trimEnd()
                    val after = source.substring(at + 1).trimStart()
                    if (!continued(before, after)) return before
                }
            }
            at++
        }
        return source.substring(start)
    }

    private fun continued(before: String, after: String) = before.endsWith('+') || after.firstOrNull() in listOf('+', '.', '?', '[', '!')

    private fun pieces(text: String): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        var at = 0
        var depth = 0
        while (at < text.length) {
            val quoted = literals.matchAt(text, at)
            if (quoted != null) { at = quoted.range.last + 1; continue }
            when (text[at]) {
                '(' -> depth++
                ')' -> depth--
                '+' -> if (depth == 0) { result += text.substring(start, at); start = at + 1 }
            }
            at++
        }
        result += text.substring(start)
        return result
    }

    private companion object {
        // Local vals cannot have visibility modifiers. They must not replace a field with the same name.
        val declaration = Regex("""\b(?:(?:private|internal|public|protected)\s+(?:const\s+)?val|const\s+val)\s+(\w+)(?:\s*:\s*String)?\s*=\s*""")
        val reference = Regex("""(?:\w+\.)*\w+""")
        val owner = Regex("""\b(?:object|class)\s+(\w+)""")
        val take = Regex("""(.+)\.take\((\d+)\)""", RegexOption.DOT_MATCHES_ALL)
        const val CLASS_CALL = "TitleDelimiters.regexClass("
    }
}
