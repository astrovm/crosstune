"""The app sources a readings harness reaches, read with the Kotlin compiler's own parser.

SourceTree.java parses each file with the Kotlin compiler's PSI, and Java files with the Java PSI the compiler carries,
in one JVM for the whole run. For each file it gives the declarations, each name the code uses, and which of the
file's own declarations can bind that name where it is used. This module follows the names across files by syntax:
imports and their aliases, packages, nested members, extensions and operators. It knows no types, so it is not the
compiler's binding: where a name could be another file's declaration, that file is taken too. An extra file fails the
readings test loudly, and a missed one would hide a reading input. A class loaded by a name the code computes, a
service loader, native code and a file the parser can't read fail.

In the SELECT_MEMBERS files only what the harness uses is in: each member it names, what that member names, and what
starting its class runs, the class's init blocks, constructors and supertypes. Eager property initializers run with their class or facade too, even when their values are unread:
an initializer can change state used by a reading.
"""
import atexit
import hashlib
import json
import os
import posixpath
import shutil
import subprocess
import sys
import threading
from collections import defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE.parent / "kotlinc"))
import kotlinc  # noqa: E402  the shared compiler jars, next to this folder

SOURCE_TREE = HERE / "SourceTree.java"
SOURCE_SETS = ("main", "test", "debug", "testDebug")
SOURCE_ROOTS = tuple(f"app/src/{name}/{language}" for name in SOURCE_SETS for language in ("java", "kotlin"))
RESOURCE_ROOTS = tuple(f"app/src/{name}/resources" for name in SOURCE_SETS)
# These files also fetch pages or dictionaries, or compare service names. Follow the members the harness uses,
# so a call to SungReadings.matched does not make its unrelated network code a measured reading input.
SELECT_MEMBERS = {"SungReadings.kt", "UniDic.kt", "SongNames.kt"}
CLASSIFIERS = {"class", "interface", "object", "companion"}
TYPES = CLASSIFIERS | {"typealias"}
# Starting a class runs its init blocks, constructors and eager property initializers. Unread getters do not run.
INITIALIZERS = {"init", "constructor", "enumentry"}
# Names every class has, or the standard library gives every value. A use of one keeps the whole class.
EVERY_CLASS = {"javaClass", "toString", "hashCode", "equals", "let", "run", "also", "apply", "with", "takeIf", "takeUnless",
               "values", "valueOf", "entries", "name", "ordinal", "class", "java", "kotlin"}
# The worker's JVM: one parse of every app source fits in this, with room.
JVM_OPTIONS = ("-Xmx1g", "-Xss8m", "-XX:+UseSerialGC", "-Djava.awt.headless=true")
KINDS = {"class": "class", "interface": "interface", "object": "object", "typealias": "typealias", "function": "fun", "property": "val"}


def jdk():
    """java and javac: from $JAVA_HOME, or from the PATH."""
    home = os.environ.get("JAVA_HOME")
    java = Path(home) / "bin" / "java" if home else Path(shutil.which("java") or "java")
    javac = java.with_name("javac.exe" if java.name.endswith(".exe") else "javac")
    if not (java.is_file() and javac.is_file()):
        raise RuntimeError(f"The readings guard parses with the Kotlin compiler and needs a JDK 17 or newer, with javac. {java} or "
                           f"{javac} is not there: install a JDK, or set JAVA_HOME to one. CI's site job sets one up with actions/setup-java.")
    return java, javac


def compiled(javac, jars):
    """SourceTree.java compiled against the locked jars, once for each change to either."""
    key = hashlib.sha256(SOURCE_TREE.read_bytes() + kotlinc.LOCK.read_bytes() + str(javac).encode()).hexdigest()[:16]
    base = Path(os.environ.get("CROSSTUNE_SOURCETREE_DIR") or ROOT / "build" / "readingsaudit" / "sourcetree")
    classes = base / key
    if (classes / "done").is_file():
        return classes
    base.mkdir(parents=True, exist_ok=True)
    part = base / f"{key}.{os.getpid()}"
    shutil.rmtree(part, ignore_errors=True)
    built = subprocess.run([str(javac), "--release", "17", "-Xlint:all,-classfile", "-Werror", "-encoding", "UTF-8", "-d", str(part),
                            "-cp", os.pathsep.join(map(str, jars)), str(SOURCE_TREE)], capture_output=True, text=True)
    if built.returncode:
        shutil.rmtree(part, ignore_errors=True)
        raise RuntimeError(f"javac could not compile {SOURCE_TREE.name}; it needs a JDK 17 or newer:\n{built.stdout}{built.stderr}")
    (part / "done").write_text("")
    try:
        part.rename(classes)
    except OSError:
        # Another run compiled it first: the same source makes the same classes.
        shutil.rmtree(part, ignore_errors=True)
    return classes


class Parser:
    """One JVM that parses each file it is sent, started on first use. It ends when Python closes its input."""

    def __init__(self):
        self.process = None
        self.trees = {}

    def start(self):
        java, javac = jdk()
        jars = kotlinc.fetch()
        classes = compiled(javac, jars)
        self.process = subprocess.Popen([str(java), *JVM_OPTIONS, "-cp", os.pathsep.join(map(str, [classes, *jars])), "SourceTree"],
                                        stdin=subprocess.PIPE, stdout=subprocess.PIPE)
        ready = self.process.stdout.readline()
        if not ready.startswith(b'{"ready"'):
            self.close()
            raise RuntimeError(f"SourceTree did not start: {ready[:300]!r}")
        atexit.register(self.close)

    def close(self):
        if self.process is None:
            return
        process, self.process = self.process, None
        process.stdin.close()
        try:
            process.wait(timeout=30)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
        process.stdout.close()

    def parse(self, files):
        """The tree of each (name, bytes) in [files]. A file's tree is kept by its name and text, for the next call."""
        keys = [(name, hashlib.sha256(text).hexdigest()) for name, text in files]
        wanted = {}
        for key, (_name, text) in zip(keys, files):
            if key not in self.trees:
                wanted[key] = text
        if wanted:
            if self.process is None:
                self.start()
            stdin = self.process.stdin

            def send():
                for (name, _digest), text in wanted.items():
                    path = name.encode()
                    stdin.write(f"{len(path)} {len(text)}\n".encode() + path + text)
                stdin.flush()

            # Sent from a thread while the answers are read: a big batch fills both pipes otherwise.
            sender = threading.Thread(target=send, daemon=True)
            sender.start()
            for key in wanted:
                answer = self.process.stdout.readline()
                if not answer:
                    code = self.process.poll()
                    self.process = None
                    raise RuntimeError(f"SourceTree stopped (exit {code}) while it read {key[0]}")
                self.trees[key] = json.loads(answer)
            sender.join()
        return [self.trees[key] for key in keys]


PARSER = Parser()


class Source:
    """A file's tree, with its declarations indexed."""

    def __init__(self, path, tree):
        if "crash" in tree:
            raise ValueError(f"{path}: SourceTree could not read it: {tree['crash']}")
        self.path = path
        self.tree = tree
        self.language = tree["language"]
        self.package = tree["package"]
        self.facade = tree["facade"]
        self.decls = tree["decls"]
        self.refs = tree["refs"]
        self.children = defaultdict(list)
        self.named = defaultdict(list)
        for decl in self.decls:
            self.children[decl["parent"]].append(decl["id"])
            self.named[decl["name"]].append(decl["id"])
        self.selectable = self.language == "kotlin" and Path(path).name in SELECT_MEMBERS

    def chain(self, decl):
        """[decl] and the declarations around it, innermost first."""
        found = []
        while decl != -1:
            found.append(decl)
            decl = self.decls[decl]["parent"]
        return found

    def top(self):
        return self.children[-1]


def fits(row, arguments):
    """Whether a call that passes [arguments] can call [row]: a count it can't take is never this one."""
    if arguments < 0 or not row.get("arity"):
        return True
    return any(least <= arguments and (most < 0 or arguments <= most) for least, most in row["arity"])


def scanned(root):
    """Every app and test source, Kotlin and Java, by its path from [root]."""
    found = []
    for folder in SOURCE_ROOTS:
        base = root / folder
        if base.is_dir():
            found += sorted(path for path in base.rglob("*") if path.suffix in (".kt", ".java") and path.is_file())
    return [path.relative_to(root).as_posix() for path in found]


def sources(root, paths):
    trees = PARSER.parse([(Path(path).name, (root / path).read_bytes()) for path in paths])
    return {path: Source(path, tree) for path, tree in zip(paths, trees)}


def declared(root):
    """Top-level types, functions and properties in the app and its test adapters, by name and file. A private function
    or property is its own file's only, so it is left out; a private class is kept, as a harness may still name it."""
    found = defaultdict(dict)
    for path, source in sources(root, scanned(root)).items():
        for decl in source.top():
            row = source.decls[decl]
            if row["name"] and row["kind"] in KINDS and (row["kind"] in TYPES or "private" not in row["flags"]):
                found[row["name"]][path] = KINDS[row["kind"]]
    return dict(found)


class Walk:
    """What one harness reaches: whole files, and in the SELECT_MEMBERS files only the declarations it uses."""

    def __init__(self, root, sources, names):
        self.root = root
        self.sources = sources
        self.names = names
        self.selected = {}
        self.headers = defaultdict(set)
        self.inited = defaultdict(set)
        self.done = defaultdict(set)
        self.wanted = defaultdict(set)
        self.missing = set()
        self.resources = set()
        self.version = 0
        # Where each file was first reached from: (path, line, name), to say why a file is in.
        self.why = {}
        self.via = None
        self.top = defaultdict(list)
        self.facades = {}
        self.extensions = defaultdict(list)
        self.operators = defaultdict(list)
        self.packages = set()
        for path, source in sources.items():
            self.packages.add(source.package)
            if source.facade:
                self.facades[(source.package, source.facade)] = path
            for decl in source.top():
                row = source.decls[decl]
                self.top[(source.package, row["name"])].append((path, decl))
                if "extension" in row["flags"]:
                    self.extensions[row["name"]].append((path, decl))
                if "operator" in row["flags"]:
                    self.operators[row["name"]].append((path, decl))

    # Selection.

    def reach(self, path):
        if path not in self.selected:
            source = self.sources[path]
            if source.tree["errors"]:
                raise ValueError(f"{path}: the parser can't read all of it, so a use could hide in what it can't: {source.tree['errors'][:3]}")
            self.selected[path] = set() if source.selectable else None
            self.why[path] = self.via
            self.version += 1

    def select(self, path, decl):
        """[decl] of [path] and what running it runs; None is the whole file."""
        self.reach(path)
        chosen = self.selected[path]
        if chosen is None:
            return
        if decl is None:
            self.selected[path] = None
            self.version += 1
            return
        if decl in chosen:
            return
        chosen.add(decl)
        self.version += 1
        source = self.sources[path]
        row = source.decls[decl]
        child, parent = row, row["parent"]
        while parent != -1:
            outer = source.decls[parent]
            if "enum" in outer["flags"]:
                self.select(path, parent)
            elif outer["kind"] in ("object", "companion"):
                self.initialize(path, parent)
            elif outer["kind"] in ("class", "interface"):
                if child["kind"] == "companion":
                    # A companion's fields are its class's statics: loading it runs the class's header, its supertypes.
                    self.headers[path].add(parent)
                elif child["kind"] not in CLASSIFIERS:
                    # An instance member: an instance was made, with the class's initializers.
                    self.initialize(path, parent)
            child, parent = outer, outer["parent"]
        if row["parent"] == -1 and row["kind"] in ("function", "property"):
            # The file's facade class, and its annotations, start with it.
            self.initialize(path, -1)

    def initialize(self, path, container):
        if container in self.inited[path]:
            return
        self.inited[path].add(container)
        self.headers[path].add(container)
        source = self.sources[path]
        for child in source.children[container]:
            row = source.decls[child]
            if row["kind"] in INITIALIZERS or "eager" in row["flags"]:
                self.select(path, child)

    def active(self, path, owner):
        chosen = self.selected[path]
        if chosen is None or owner in self.headers[path]:
            return True
        return any(decl in chosen for decl in self.sources[path].chain(owner))

    # Names.

    def visible(self, source, name, path, decl):
        """Whether the top-level [decl] named [name] in [path] can be named from [source] without its package."""
        return path == source.path or path in self.names.get(name, {})

    def qualified(self, parts):
        """The declarations a dotted name can be: (path, decl, the member names left), decl None for a file's facade."""
        found = []
        for split in range(1, len(parts)):
            package = ".".join(parts[:split])
            if package not in self.packages:
                continue
            found += [(path, decl, parts[split + 1:]) for path, decl in self.top.get((package, parts[split]), [])]
            if (package, parts[split]) in self.facades:
                found.append((self.facades[(package, parts[split])], None, parts[split + 1:]))
        return found

    def imported(self, source, name):
        """What [name] means in [source] through its imports: (path, decl, the member names left)."""
        found = []
        for target, alias, kind in source.tree["imports"]:
            parts = target.split(".")
            if kind in (None, "static") and (alias or parts[-1]) == name:
                found += self.qualified(parts)
            elif kind in ("*", "static*"):
                found += self.qualified([*parts, name])
        return found

    def candidates(self, source, ref):
        """The other files' declarations a use can be: (path, decl, the member names left)."""
        name, form = ref["n"], ref["f"]
        types_only = ref["c"] == 2 or source.language == "java" and form != "type"
        found = []
        for path, decl in self.top.get((source.package, name), []):
            kind = self.sources[path].decls[decl]["kind"]
            if not self.visible(source, name, path, decl):
                continue
            if form == "type" and kind not in TYPES or form in ("name", "recv") and kind == "function":
                continue
            if types_only and kind not in TYPES:
                continue
            found.append((path, decl, []))
        if (source.package, name) in self.facades:
            found.append((self.facades[(source.package, name)], None, []))
        found += self.imported(source, name)
        return found

    def walk(self, path, decl, rest, strict=True):
        """The declarations [rest] names inside [decl], one member after another; the class itself when it can't tell."""
        source = self.sources[path]
        if decl is None:
            if not rest:
                return [(path, None)]
            found = [end for top in source.top() if source.decls[top]["name"] == rest[0] for end in self.walk(path, top, rest[1:], strict)]
            return found or [(path, None)]
        row = source.decls[decl]
        if not rest or row["kind"] not in CLASSIFIERS:
            return [(path, decl)]
        name = rest[0]
        companions = [child for child in source.children[decl] if source.decls[child]["kind"] == "companion"]
        if name == "Companion" and companions:
            return [end for companion in companions for end in self.walk(path, companion, rest[1:], strict)]
        if name == "INSTANCE":
            return self.walk(path, decl, rest[1:], strict)
        members = [child for holder in (decl, *companions) for child in source.children[holder] if source.decls[child]["name"] == name]
        if members:
            return [end for member in members for end in self.walk(path, member, rest[1:], strict)]
        quiet = not strict or name in EVERY_CLASS or "supertypes" in row["flags"] or "enum" in row["flags"] or name in self.extensions
        if source.selectable and not quiet:
            self.missing.add(name)
        return [(path, decl)]

    def targets(self, source, ref):
        """Each declaration a use can be, in its own file and in others. A call is held to the counts each can take."""
        found = self.named_by(source, ref)
        if ref["f"] != "call":
            return found
        return [(path, decl) for path, decl in found if decl is None or fits(self.sources[path].decls[decl], ref.get("a", -1))]

    def named_by(self, source, ref):
        chain = ref["q"] or [ref["n"]]
        found = [end for decl in ref["b"] for end in self.walk(source.path, decl, chain[1:])]
        if ref["c"] == 1:
            return found
        for path, decl, rest in self.candidates(source, ref):
            found += self.walk(path, decl, rest + chain[1:])
        if len(chain) > 1:
            for path, decl, rest in self.qualified(chain):
                found += self.walk(path, decl, rest)
        return found

    def members(self, source, name, table, operator, arguments):
        """A selector or an operator: an extension the file sees, or a member of a class the file reaches."""
        for path, decl in table.get(name, []):
            other = self.sources[path]
            seen = path == source.path or other.package == source.package and path in self.names.get(name, {})
            if seen and fits(other.decls[decl], arguments):
                self.select(path, decl)
        for path, decl, rest in self.imported(source, name):
            for end in self.walk(path, decl, rest, strict=False):
                if end[1] is None or fits(self.sources[end[0]].decls[end[1]], arguments):
                    self.select(*end)
        if source.selectable:
            self.wanted[source.path].add((name, operator, arguments))

    def use(self, source, ref):
        if ref["f"] == "sel":
            self.members(source, ref["n"], self.extensions, False, ref["a"])
        elif ref["f"] == "op":
            self.members(source, ref["n"], self.operators, True, -1)
        else:
            for end in self.targets(source, ref):
                self.select(*end)

    # Reflection and resources.

    def class_name(self, source, row, whole=True):
        """A class the code names in a string: its declaration, or nothing for a library's class."""
        text = row["name"].strip()
        parts = text.replace("$", ".").split(".")
        found = [end for path, decl, rest in self.qualified(parts) for end in self.walk(path, decl, rest, strict=False)]
        if not found and any(text.startswith(package + ".") for package in self.packages if package):
            raise ValueError(f"{source.path}:{row['l']}: reflection names {text}, which no source declares")
        return found

    def reflection(self, source, row):
        where = f"{source.path}:{row['l']}"
        kind = row["kind"]
        if kind == "class" and row["name"] is not None:
            for path, decl in self.class_name(source, row):
                self.select(path, decl)
        elif kind == "member" and row["chain"] == ["this"]:
            outer = source.chain(row["o"])
            self.select(source.path, outer[-1] if outer else None)
        elif kind == "member" and row["chain"]:
            chain = row["chain"]
            ref = {"n": chain[0], "f": "type", "c": 0, "b": [decl for decl in source.named[chain[0]] if source.decls[decl]["kind"] in TYPES], "q": chain}
            for path, decl in self.targets(source, ref):
                self.select(path, decl)
        elif kind == "member" and row["name"] is not None:
            for path, decl in self.class_name(source, row):
                self.select(path, decl)
        elif kind == "member":
            raise ValueError(f"{where}: reflection reaches the members of a class the code does not name: {row['text']}")
        elif kind == "service":
            raise ValueError(f"{where}: reflection through a service loader loads classes no source names: {row['text']}")
        elif kind == "native":
            raise ValueError(f"{where}: native code is out of the guard's reach, like reflection by a computed name: {row['text']}")
        else:
            raise ValueError(f"{where}: reflection loads a {kind} by a name the code computes: {row['text']}")

    def constant(self, source, index):
        """The text of the constant a use names, when the code spells it out."""
        values = {self.sources[path].decls[decl]["value"] for path, decl in self.targets(source, source.refs[index]) if decl is not None}
        return values.pop() if len(values) == 1 else None

    def resource(self, source, row):
        name = row["name"]
        if name is None and row["ref"] >= 0:
            name = self.constant(source, row["ref"])
        if name is None:
            raise ValueError(f"{source.path}:{row['l']}: a resource is loaded by a name the code computes, as reflection would: {row['text']}")
        normalized = posixpath.normpath(name)
        # Class.getResource resolves against its receiver's package, which need not be the caller's.
        # The syntax walk has no receiver types. Keep every matching relative resource conservatively.
        suffix = tuple(part for part in normalized.split("/") if part not in ("", ".", ".."))
        for folder in RESOURCE_ROOTS:
            base = self.root / folder
            if name.startswith("/"):
                candidates = [base / normalized.lstrip("/")]
            else:
                candidates = [found for found in base.rglob("*") if suffix and found.relative_to(base).parts[-len(suffix):] == suffix]
            for found in candidates:
                if found.is_file():
                    self.resources.add(found.relative_to(self.root).as_posix())

    # The walk.

    def step(self, path):
        source = self.sources[path]
        done = self.done[path]
        for index, ref in enumerate(source.refs):
            if index not in done and self.active(path, ref["o"]):
                done.add(index)
                self.via = (path, ref["l"], ref["n"])
                self.use(source, ref)
        for key in ("reflections", "resources"):
            for index, row in enumerate(source.tree[key]):
                if (key, index) not in done and self.active(path, row["o"]):
                    done.add((key, index))
                    self.via = (path, row["l"], row["name"])
                    (self.reflection if key == "reflections" else self.resource)(source, row)
        for row in source.decls:
            if "native" in row["flags"] and self.active(path, row["id"]):
                raise ValueError(f"{path}:{row['line']}: {row['name']} is native code, out of the guard's reach, like reflection by a computed name")
        chosen = self.selected[path]
        if chosen and self.wanted[path]:
            reached = {outer for decl in chosen for outer in source.chain(decl)}
            for name, operator, arguments in sorted(self.wanted[path]):
                for decl in source.named[name]:
                    row = source.decls[decl]
                    if row["parent"] in reached and fits(row, arguments) and (not operator or "operator" in row["flags"] or "override" in row["flags"]):
                        self.select(path, decl)

    def trail(self, path):
        """How [path] was reached: each file, the line and the name that led on, from the harness."""
        steps = []
        while self.why.get(path):
            path, line, name = self.why[path]
            steps.append(f"{path}:{line} {name}")
        return " <- ".join(steps)

    def run(self, harness):
        self.select(harness, None)
        seen = -1
        while seen != self.version:
            seen = self.version
            for path in list(self.selected):
                self.step(path)
        if self.missing:
            raise ValueError(f"Reading entry points are missing: {', '.join(sorted(self.missing))}")
        return set(self.selected) | self.resources


def called(root, harness, names):
    """The app and test sources [harness] reaches, and the resources they load, [harness] left out. [names] is what
    declared() gives: the top-level declarations another file may name."""
    paths = scanned(root)
    if harness not in paths:
        paths.append(harness)
    return Walk(root, sources(root, paths), names).run(harness) - {harness}
