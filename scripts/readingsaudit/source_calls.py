"""The local Kotlin sources a readings harness uses, including shared functions and JVM adapters."""
import re
from collections import defaultdict


def code_of(text):
    """Keep Kotlin code, string templates and reflected class names, with comments and literal text blanked."""
    out = list(text)

    def blank(start, end):
        for at in range(start, end):
            if text[at] != "\n":
                out[at] = " "

    def code(at, template=False):
        depth = 0
        while at < len(text):
            if text.startswith("//", at):
                end = text.find("\n", at)
                end = len(text) if end < 0 else end
                blank(at, end)
                at = end
            elif text.startswith("/*", at):
                start, nested = at, 1
                at += 2
                while at < len(text) and nested:
                    if text.startswith("/*", at):
                        nested += 1
                        at += 2
                    elif text.startswith("*/", at):
                        nested -= 1
                        at += 2
                    else:
                        at += 1
                if nested:
                    raise ValueError("Unclosed Kotlin comment")
                blank(start, at)
            elif text[at] in "\"'":
                delimiter = '"""' if text.startswith('"""', at) else text[at]
                reflected = bool(re.search(r"\bClass\s*\.\s*forName\s*\(\s*$", "".join(out[max(0, at - 100):at])))
                blank(at, at + len(delimiter))
                at += len(delimiter)
                while at < len(text) and not text.startswith(delimiter, at):
                    if delimiter != '"""' and text[at] == "\\":
                        blank(at, min(at + 2, len(text)))
                        at += 2
                    elif delimiter != "'" and text.startswith("${", at):
                        blank(at, at + 1)
                        at = code(at + 2, template=True)
                    elif delimiter != "'" and text[at] == "$" and re.match(r"\w", text[at + 1:at + 2]):
                        blank(at, at + 1)
                        at += 1
                        while at < len(text) and re.match(r"\w", text[at]):
                            at += 1
                    else:
                        if not reflected:
                            blank(at, at + 1)
                        at += 1
                if at >= len(text):
                    raise ValueError("Unclosed Kotlin string")
                blank(at, at + len(delimiter))
                at += len(delimiter)
            elif template and text[at] == "}" and depth == 0:
                return at + 1
            else:
                if text[at] == "{":
                    depth += 1
                elif text[at] == "}":
                    depth -= 1
                at += 1
        if template:
            raise ValueError("Unclosed Kotlin template")
        return at

    code(0)
    return "".join(out)


MODIFIERS = r"(?:(?:internal|private|public|protected|inline|suspend|tailrec|operator|infix|const|lateinit|data|sealed|enum|annotation|value|expect|actual|open|abstract|final|external|override|companion)[ \t]+)*"
DECLARATION = re.compile(r"^(?P<indent>[ \t]*)(?P<modifiers>" + MODIFIERS + r")(?P<kind>class|object|interface|typealias|fun|val|var)\s+", re.MULTILINE)


def declarations(code):
    """Each named declaration, including members that can shadow a name from another file."""
    found = []
    for match in DECLARATION.finditer(code):
        tail = code[match.end():].split("\n", 1)[0]
        if match["kind"] == "fun":
            name = re.search(r"(\w+)\s*\(", tail)
        elif match["kind"] in ("val", "var"):
            name = re.match(r"(?:[\w<>?]+\.)?(\w+)", tail)
        else:
            name = re.match(r"(\w+)", tail)
        if name:
            found.append((name[1], match["kind"], match.start(), match.end(), len(match["indent"]), "private" in match["modifiers"].split()))
        elif match["kind"] == "object":
            found.append(("", "object", match.start(), match.end(), len(match["indent"]), False))
    return found


def measured_members(code, entries):
    """The named entry points and their local helpers, without a class's download or page-fetching methods."""
    declared = declarations(code)
    bodies = defaultdict(list)
    ranges = []
    for index, (name, _kind, start, header, indent, _private) in enumerate(declared):
        end = next((row[2] for row in declared[index + 1:] if row[4] <= indent), len(code))
        parens = 0
        for at in range(header, end):
            char = code[at]
            if char in "([":
                parens += 1
            elif char in ")]":
                parens -= 1
            elif parens == 0 and char == "=":
                break
            elif parens == 0 and char == "{":
                depth, stop = 1, at + 1
                while stop < len(code) and depth:
                    depth += (code[stop] == "{") - (code[stop] == "}")
                    stop += 1
                end = min(end, stop)
                break
        ranges.append((name, _kind, start, end))
    functions = [(start, end) for _name, kind, start, end in ranges if kind == "fun"]
    for name, _kind, start, end in ranges:
        # A local called `best` must not select another method's local `best`.
        if name and not any(first < start < last for first, last in functions):
            bodies[name].append(code[start:end])
    missing = entries - bodies.keys()
    if missing:
        raise ValueError(f"Reading entry points are missing: {', '.join(sorted(missing))}")
    pending, seen, kept = list(entries), set(), []
    while pending:
        name = pending.pop()
        if name in seen:
            continue
        seen.add(name)
        text = "\n".join(bodies[name])
        kept.append(text)
        pending.extend(set(re.findall(r"\b\w+\b", text)) & bodies.keys() - seen)
    return "\n".join(kept)


# These files also fetch pages or dictionaries, or compare service names. Follow the members the harness uses,
# so a call to SungReadings.matched does not make its unrelated network code a measured reading input.
SELECT_MEMBERS = {"SungReadings.kt", "UniDic.kt", "SongNames.kt"}


def declared(root):
    """Top-level types, functions and properties in the app and its test adapters, by name and file."""
    found = defaultdict(dict)
    for source in ("main", "test"):
        folder = root / f"app/src/{source}/java/com/astrovm/crosstune"
        for path in sorted(folder.rglob("*.kt")):
            for name, kind, _start, _header, indent, private in declarations(code_of(path.read_text(encoding="utf-8"))):
                if name and indent == 0 and (not private or kind in ("class", "object", "interface")):
                    found[name][path.relative_to(root).as_posix()] = kind
    return dict(found)


def called(root, harness, names):
    """Follow sources until no new local dependency remains. Kotlin compilation still checks the actual bindings."""
    pending, seen = [(harness, None)], {}
    packages = {}

    def package(path):
        if path not in packages:
            found = re.search(r"^package\s+([\w.]+)", (root / path).read_text(encoding="utf-8"), re.MULTILINE)
            packages[path] = found[1] if found else ""
        return packages[path]

    while pending:
        path, entries = pending.pop()
        if path in seen and (seen[path] is None or entries is not None and entries <= seen[path]):
            continue
        if path in seen and entries is not None:
            entries |= seen[path]
        seen[path] = entries
        text = code_of((root / path).read_text(encoding="utf-8"))
        own = {row[0] for row in declarations(text)}
        # A compact local class or method can share a name with a declaration in another file.
        own.update(re.findall(r"\b(?:class|object|interface|typealias)\s+(\w+)", text))
        own.update(re.findall(r"\bfun\s+(?:<[^\n]+?>\s*)?(?:[\w<>?.]+\.)?(\w+)\s*\(", text))
        imported = re.findall(r"^import\s+([\w.*]+)(?:\s+as\s+(\w+))?", text, re.MULTILINE)
        imports = {name for name, _alias in imported}
        aliases = {alias: name.rsplit(".", 1)[-1] for name, alias in imported if alias}
        if entries is not None:
            text = measured_members(text, entries)
        used = set(re.findall(r"\b\w+\b", text))
        functions = set(re.findall(r"\b(\w+)\s*\(", text)) | set(re.findall(r"::\s*(\w+)", text))
        for name in used:
            original = aliases.get(name, name)
            for target, kind in names.get(original, {}).items():
                namespace = package(target)
                full = f"{namespace}.{original}" if namespace else original
                qualified = namespace and re.search(rf"\b{re.escape(full)}\b", text)
                if name in own and not qualified:
                    continue
                if namespace != package(path) and full not in imports and f"{namespace}.*" not in imports and not re.search(rf"\b{re.escape(full)}\b", text):
                    continue
                if kind == "fun" and name not in functions:
                    continue
                members = None
                if target.rsplit("/", 1)[-1] in SELECT_MEMBERS:
                    members = set(re.findall(rf"\b{re.escape(name)}\s*(?:\.|::)\s*(\w+)", text)) - {"class"}
                    members = members or {name}
                pending.append((target, members))
    return set(seen) - {harness}
