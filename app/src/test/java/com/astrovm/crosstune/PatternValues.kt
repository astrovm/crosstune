package com.astrovm.crosstune

/** Reads constant strings and immutable fields in plain singleton objects or at the top level. Other values stay unknown. */
internal class PatternValues(
    files: List<Pair<String, String>>,
    private val literalText: (String) -> String,
    private val literals: Regex,
    private val template: Regex,
) {
    private data class Key(val file: String, val owner: String, val name: String)
    private data class Binding(val key: Key, val expression: String?, val constant: Boolean)

    private val scopes = files.associate { (file, source) -> file to PatternScopes(source, literals) }
    private val bindings = files.flatMap { (file, source) ->
        declaration.findAll(source).mapNotNull { match ->
            val scope = scopes.getValue(file).at(match.range.first)
            val constant = "const" in match.value.substringBefore("val")
            if (!scope.code || !scope.properties && !constant) null
            else Binding(Key(file, scope.owner, match.groupValues[1]), expressionAt(source, match.range.last + 1), constant)
        }.toList()
    }
    private val local = bindings.groupBy { it.key }
    private val global = bindings.filter { it.constant }.groupBy { it.key.name }
    private val memo = mutableMapOf<Key, String?>()
    private val reading = mutableSetOf<Key>()

    fun get(file: String, name: String, at: Int, property: Boolean): String? {
        val scope = scopes.getValue(file).at(at)
        return lookup(file, scope.owner, name, property && scope.properties && scope.code)
    }

    private fun lookup(file: String, owner: String, name: String, derived: Boolean): String? {
        if ('.' in name) {
            val namedOwner = name.substringBeforeLast('.')
            val candidates = local.keys.filter { key ->
                key.name == name.substringAfterLast('.') && key.owner.isNotEmpty() &&
                    (key.owner == namedOwner || namedOwner.endsWith("." + key.owner))
            }
            val longest = candidates.maxOfOrNull { it.owner.length } ?: return null
            val key = candidates.filter { it.owner.length == longest }.singleOrNull() ?: return null
            val values = local.getValue(key)
            return if (derived || values.all { it.constant }) group(key, values) else null
        }
        var current = owner
        while (true) {
            val key = Key(file, current, name)
            local[key]?.let { values -> return if (derived || values.all { it.constant }) group(key, values) else null }
            if (current.isEmpty()) break
            current = current.substringBeforeLast('.', "")
        }
        return global[name]?.let { group(Key("", "", name), it) }
    }

    private fun group(key: Key, candidates: List<Binding>): String? {
        if (key in memo) return memo[key]
        if (!reading.add(key)) return null
        return try {
            val values = candidates.map { value(it.key.file, it.key.owner, it.expression, !it.constant) }
            val result = values.firstOrNull()?.takeIf { first -> values.all { it == first } }
            memo[key] = result
            result
        } finally {
            reading.remove(key)
        }
    }

    private fun value(file: String, owner: String, expression: String?, derived: Boolean): String? {
        val text = expression?.trim() ?: return null
        val parts = pieces(text)
        if (parts.size > 1) {
            val values = parts.map { value(file, owner, it, derived) }
            if (values.any { it == null }) return null
            return values.joinToString("")
        }
        if (literals.matches(text)) {
            var complete = true
            val expanded = template.replace(literalText(text)) { part ->
                part.groupValues[1].ifEmpty {
                    val name = part.groupValues[2].ifEmpty { part.groupValues[3] }
                    lookup(file, owner, name, derived) ?: run { complete = false; part.value }
                }
            }
            return expanded.takeIf { complete }
        }
        take.matchEntire(text)?.let { match ->
            val count = match.groupValues[2].toIntOrNull() ?: return null
            return value(file, owner, match.groupValues[1], derived)?.take(count)
        }
        if (text.startsWith(CLASS_CALL) && text.endsWith(')')) {
            val chars = value(file, owner, text.substring(CLASS_CALL.length, text.length - 1), derived) ?: return null
            return chars.map { if (it in "[]-\\") "\\$it" else it.toString() }.joinToString("")
        }
        return if (reference.matches(text)) lookup(file, owner, text, derived) else null
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
        val take = Regex("""(.+)\.take\((\d+)\)""", RegexOption.DOT_MATCHES_ALL)
        const val CLASS_CALL = "TitleDelimiters.regexClass("
    }
}

/** Only top-level fields and plain singleton-object fields can supply derived values. */
private class PatternScopes(source: String, literals: Regex) {
    data class Context(val owner: String, val properties: Boolean, val code: Boolean = true)

    private val positions = mutableListOf(0)
    private val contexts = mutableListOf(Context("", true))

    init {
        val stack = mutableListOf<String?>()
        var parentheses = 0
        fun context(code: Boolean = true) = Context(stack.filterNotNull().joinToString("."), parentheses == 0 && stack.none { it == null }, code)
        fun keep(at: Int, code: Boolean = true) {
            if (positions.last() == at) contexts[contexts.lastIndex] = context(code)
            else { positions += at; contexts += context(code) }
        }
        var at = 0
        while (at < source.length) {
            val quoted = literals.matchAt(source, at)
            if (quoted != null) {
                keep(at, false)
                at = quoted.range.last + 1
                keep(at)
                continue
            }
            if (source.startsWith("//", at) || source.startsWith("/*", at) || source[at] == '\'') {
                keep(at, false)
                at = skipped(source, at)
                keep(at)
                continue
            }
            val named = objectStart.matchAt(source, at)
            if (named != null) {
                stack += named.groupValues[1]
                at = named.range.last + 1
                keep(at)
                continue
            }
            when (source[at]) {
                '{' -> stack += null
                '}' -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                '(' -> parentheses++
                ')' -> parentheses = maxOf(0, parentheses - 1)
            }
            if (source[at] in "{}()") keep(at + 1)
            at++
        }
    }

    fun at(position: Int): Context {
        val found = positions.binarySearch(position)
        return contexts[if (found >= 0) found else maxOf(0, -found - 2)]
    }

    private fun skipped(source: String, start: Int): Int {
        if (source.startsWith("//", start)) return source.indexOf('\n', start).takeIf { it >= 0 } ?: source.length
        if (source[start] == '\'') {
            var at = start + 1
            while (at < source.length) {
                if (source[at] == '\\') at += 2
                else if (source[at++] == '\'') break
            }
            return minOf(at, source.length)
        }
        var depth = 1
        var at = start + 2
        while (at < source.length && depth > 0) {
            when {
                source.startsWith("/*", at) -> { depth++; at += 2 }
                source.startsWith("*/", at) -> { depth--; at += 2 }
                else -> at++
            }
        }
        return at
    }

    private companion object {
        val objectStart = Regex("""\bobject\s+(\w+)\s*\{""")
    }
}
