"""The dependency guard reads fake Kotlin and Java sources with the Kotlin compiler's parser. It never compiles or runs them."""
import os
import tempfile
import textwrap
import unittest
from pathlib import Path
from unittest import mock

import source_calls

MAIN = "app/src/main/java/com/astrovm/crosstune/"
TEST = "app/src/test/java/com/astrovm/crosstune/"


def walk(files, harness="Harness.kt"):
    """What the harness reaches in a checkout of [files], each dedented."""
    with tempfile.TemporaryDirectory() as folder:
        root = Path(folder)
        for name, text in files.items():
            (root / name).parent.mkdir(parents=True, exist_ok=True)
            (root / name).write_text(textwrap.dedent(text))
        return source_calls.called(root, harness, source_calls.declared(root))


class KotlinCode(unittest.TestCase):
    """The parser reads the code: comments and string text name nothing, a template's code does."""

    def test_comments_literals_and_characters_are_not_references(self):
        harness = '''// First()
/* Outer /* Nested() */ Last() */
val plain = "Hidden() Class.forName(\\"AlsoHidden\\")"
val raw = """Hidden() /* literal */ // literal"""
val character = '{'
val escaped = "\\$Hidden"
val kept = realCall()
'''
        files = {"Harness.kt": harness, MAIN + "Real.kt": "internal fun realCall() = 1"}
        for word in ("First", "Outer", "Nested", "Last", "Hidden", "AlsoHidden", "literal"):
            files[MAIN + f"{word}.kt"] = f"internal fun {word}() = 1\nclass {word}"
        self.assertEqual({MAIN + "Real.kt"}, walk(files))

    def test_templates_keep_nested_code_and_reflection_keeps_its_class(self):
        harness = '''val value = "Hidden ${if (flag) { first("literal") } else { second() }} $TABLE"
val raw = """Hidden ${third()} $OTHER"""
val loaded = Class.forName("com.astrovm.crosstune.Adapter")
'''
        files = {"Harness.kt": harness, MAIN + "Flag.kt": "internal val flag = true", MAIN + "First.kt": "internal fun first(text: String) = text",
                 MAIN + "Second.kt": "internal fun second() = 1", MAIN + "Third.kt": "internal fun third() = 1",
                 MAIN + "Table.kt": 'internal const val TABLE = "a"', MAIN + "Other.kt": 'internal const val OTHER = "b"',
                 TEST + "Adapter.kt": "package com.astrovm.crosstune\nclass Adapter", MAIN + "Hidden.kt": "class Hidden"}
        self.assertEqual(set(files) - {"Harness.kt", MAIN + "Hidden.kt"}, walk(files))

    def test_incomplete_kotlin_fails_instead_of_dropping_dependencies(self):
        for text in ('/* unfinished', 'val value = "unfinished', 'val value = "${unfinished', "fun of( = 1", "public class Broken {"):
            name = "Harness.java" if "class" in text else "Harness.kt"
            with self.subTest(text=text), self.assertRaisesRegex(ValueError, "parser"):
                walk({name: text}, name)


class SourceCalls(unittest.TestCase):

    def walk(self, files):
        return walk(files)

    def test_cycles_finish_and_generic_extension_functions_and_properties_are_followed(self):
        files = {
            "Harness.kt": "val result = Readings.of()",
            MAIN + "Readings.kt": "object Readings { fun of() = listOf(TABLE).readOne() }",
            MAIN + "Helpers.kt": "internal fun <T> List<T>.readOne() = Readings.of()",
            MAIN + "Table.kt": 'internal const val TABLE = "fake"',
        }
        self.assertEqual(set(files) - {"Harness.kt"}, self.walk(files))

    def test_test_adapters_loaded_by_name_are_followed(self):
        files = {
            "Harness.kt": 'val result = Class.forName("com.astrovm.crosstune.Adapter")',
            TEST + "Adapter.kt": "package com.astrovm.crosstune\ninternal class Adapter",
        }
        self.assertEqual({TEST + "Adapter.kt"}, self.walk(files))

    def test_import_aliases_wildcards_and_qualified_calls_use_their_packages(self):
        for imported, used in (("import app.Reader as Alias", "Alias.read()"), ("import app.*", "Reader.read()"), ("", "app.Reader.read()")):
            files = {
                "Harness.kt": f"package harness\n{imported}\nval result = {used}",
                MAIN + "Reader.kt": "package app\nobject Reader { fun read() = check(true) }",
                TEST + "Other.kt": "package unrelated\ninternal fun check(value: Boolean) = value",
            }
            with self.subTest(imported=imported):
                self.assertEqual({MAIN + "Reader.kt"}, self.walk(files))

    def test_a_qualified_call_is_not_hidden_by_a_local_of_the_same_name(self):
        files = {
            "Harness.kt": "package app\nval helper = 1\nval result = app.helper()",
            MAIN + "Helper.kt": "package app\ninternal fun helper() = 1",
        }
        self.assertEqual({MAIN + "Helper.kt"}, self.walk(files))

    def test_a_local_type_or_method_does_not_call_an_unrelated_namesake(self):
        for local, other in (("private class Piece; fun of() = Piece()", "class Piece"),
                             ("private fun helper() = 1; fun of() = helper()", "internal fun helper() = 2")):
            files = {
                "Harness.kt": "val result = Readings.of()",
                MAIN + "Readings.kt": f"object Readings {{ {local} }}",
                MAIN + "Other.kt": other,
            }
            with self.subTest(local=local):
                self.assertEqual({MAIN + "Readings.kt"}, self.walk(files))

    def test_measured_members_follow_earlier_helpers_and_constants_but_not_fetching(self):
        files = {
            "Harness.kt": "val result = SungReadings.matched()",
            MAIN + "SungReadings.kt": '''
                class SungReadings {
                    fun fetch() {
                        val best = Network.fetch()
                        println(best)
                    }
                    private fun cached() = try { Network.fetch() } catch (error: Exception) { null }
                    companion object {
                        private const val TOKEN = RULE
                        private fun earlier(value: String) = normalized(value)
                        fun matched(): String {
                            val best = TOKEN
                            return earlier(best)
                        }
                    }
                }
                ''',
            MAIN + "Network.kt": "object Network { fun fetch() = 1 }",
            MAIN + "Helpers.kt": "internal fun normalized(value: String) = value",
            MAIN + "Table.kt": 'internal const val RULE = "fake"',
        }
        self.assertEqual(set(files) - {"Harness.kt", MAIN + "Network.kt"}, self.walk(files))

    def test_separate_calls_to_measured_members_add_both_dependencies(self):
        files = {
            "Harness.kt": "val a = First.read()\nval b = Second.read()",
            MAIN + "First.kt": "object First { fun read() = SungReadings.matched() }",
            MAIN + "Second.kt": "object Second { fun read() = SungReadings.extra() }",
            MAIN + "SungReadings.kt": '''
                object SungReadings {
                    fun matched() = one()
                    fun extra() = two()
                }
                ''',
            MAIN + "One.kt": "internal fun one() = 1",
            MAIN + "Two.kt": "internal fun two() = 2",
        }
        self.assertEqual(set(files) - {"Harness.kt"}, self.walk(files))

    def test_a_missing_selected_member_fails(self):
        files = {
            "Harness.kt": "val result = SungReadings.matched()",
            MAIN + "SungReadings.kt": "object SungReadings",
        }
        with self.assertRaisesRegex(ValueError, "matched"):
            self.walk(files)

    def test_calls_with_trailing_lambdas_type_arguments_and_infix_syntax(self):
        cases = (
            ('timed { 1 }', 'internal inline fun <T> timed(block: () -> T) = block()'),
            ('parsed<Map<String, List<Int>>>("1")', 'internal fun <T> parsed(value: String): T = error(value)'),
            ('1 joinedWith 2', 'internal infix fun Int.joinedWith(other: Int) = this + other'),
            ('`second reading`()', 'internal fun `second reading`() = 1'),
        )
        for expression, declaration in cases:
            with self.subTest(expression=expression):
                files = {'Harness.kt': 'val result = Readings.of()', MAIN + 'Readings.kt': f'object Readings {{ fun of() = {expression} }}', MAIN + 'Helper.kt': declaration}
                self.assertEqual(set(files) - {'Harness.kt'}, self.walk(files))

    def test_functional_interfaces_and_same_line_annotations_are_declared(self):
        for helper, expression in (('fun interface Writer { fun write(value: Int): Int }', 'Writer { it }'), ('@VisibleForTesting internal fun helper() = 1', 'helper()')):
            files = {'Harness.kt': 'val result = Readings.of()', MAIN + 'Readings.kt': f'object Readings {{ fun of() = {expression} }}', MAIN + 'Helper.kt': helper}
            with self.subTest(helper=helper):
                self.assertEqual(set(files) - {'Harness.kt'}, self.walk(files))

    def test_a_local_in_another_function_cannot_hide_a_shared_helper(self):
        files = {'Harness.kt': 'val result = Readings.of()', MAIN + 'Readings.kt': '''
            object Readings {
                fun a() { val words = 1; println(words) }
                fun of() = words("a b")
            }
            ''', MAIN + 'Words.kt': 'internal fun words(value: String) = value.split(" ")'}
        self.assertEqual(set(files) - {'Harness.kt'}, self.walk(files))

    def test_local_bindings_hide_names_only_in_their_own_scope(self):
        files = {'Harness.kt': 'val result = Readings.of()', MAIN + 'Readings.kt': '''
            object Readings {
                fun of(): Int { val words = { 1 }; return words() }
            }
            ''', MAIN + 'Words.kt': 'internal fun words() = 2'}
        self.assertEqual({MAIN + 'Readings.kt'}, self.walk(files))

    def test_generic_extension_properties_are_followed(self):
        files = {'Harness.kt': 'val result = Readings.of()', MAIN + 'Readings.kt': 'object Readings { fun of() = listOf(1, 2).second }', MAIN + 'Second.kt': 'internal val <T> List<T>.second: T get() = this[1]'}
        self.assertEqual(set(files) - {'Harness.kt'}, self.walk(files))

    def test_operator_extensions_are_followed_with_their_own_dependencies(self):
        files = {'Harness.kt': 'val result = Readings.of()', MAIN + 'Readings.kt': 'object Readings { fun of() = Ruby() + Ruby() }', MAIN + 'Ruby.kt': 'class Ruby', MAIN + 'Add.kt': 'internal operator fun Ruby.plus(other: Ruby) = combine(this, other)', MAIN + 'Combine.kt': 'internal fun combine(a: Ruby, b: Ruby) = a'}
        self.assertEqual(set(files) - {'Harness.kt'}, self.walk(files))

    def test_selected_members_keep_imported_object_members_and_aliases(self):
        for imported, call in (('app.Kana.fold', 'fold'), ('app.Kana.fold as folded', 'folded')):
            files = {'Harness.kt': 'package app\nval result = SongNames.normalize()', MAIN + 'SongNames.kt': f'package app\nimport {imported}\nobject SongNames {{ fun normalize() = {call}("x") }}', MAIN + 'Kana.kt': 'package app\nobject Kana { fun fold(value: String) = value }'}
            with self.subTest(imported=imported):
                self.assertEqual(set(files) - {'Harness.kt'}, self.walk(files))

    def test_java_test_adapters_and_their_types_are_followed(self):
        files = {'Harness.kt': 'val result = Readings.of()', MAIN + 'Readings.kt': 'object Readings { fun of() = Adapter.read() }', TEST + 'Adapter.java': 'public class Adapter { public static String read() { return Table.VALUE; } }', TEST + 'Table.java': 'public class Table { public static final String VALUE = "fake"; }'}
        self.assertEqual(set(files) - {'Harness.kt'}, self.walk(files))

    def test_literal_reflection_names_can_use_a_loader_and_concatenation(self):
        for call in ('loader.loadClass("com.astrovm.crosstune.Adapter")', 'Class.forName("com.astrovm.crosstune.Ada" + "pter")'):
            files = {'Harness.kt': f'val result = {call}', TEST + 'Adapter.kt': 'package com.astrovm.crosstune\nclass Adapter'}
            with self.subTest(call=call):
                self.assertEqual({TEST + 'Adapter.kt'}, self.walk(files))

    def test_computed_reflection_names_fail_closed_when_reached(self):
        files = {'Harness.kt': 'val result = Class.forName(provider())'}
        with self.assertRaisesRegex(ValueError, 'reflection'):
            self.walk(files)


class Scopes(unittest.TestCase):
    """A name is the innermost declaration that can bind it where it is used, and only from where it is seen."""

    def reaches_words(self, body):
        files = {"Harness.kt": "val result = Readings.of()", MAIN + "Readings.kt": f"object Readings {{\n{body}\n}}",
                 MAIN + "Words.kt": "internal fun words(text: String = \"\") = text"}
        return MAIN + "Words.kt" in walk(files)

    def test_an_initializer_runs_before_its_own_local_is_seen(self):
        self.assertTrue(self.reaches_words('fun of(): String { val words = words("a"); return words }'))
        self.assertTrue(self.reaches_words('fun of(): String { val a = words(); val words = { "b" }; return a + words() }'))

    def test_local_functions_parameters_and_function_typed_values_hide_a_call_with_no_arguments(self):
        for body in ('fun of(): String {\n fun words() = "a"\n return words()\n}',
                     'fun of(words: () -> String) = words()',
                     'fun of(): String { val words: () -> String = { "a" }; return words() }',
                     'fun words() = "member"\nfun of() = words()'):
            with self.subTest(body=body):
                self.assertFalse(self.reaches_words(body))

    def test_what_may_still_be_the_shared_function_keeps_it(self):
        for body in ('fun of(words: (String) -> String) = words("a")',
                     'class Other { fun words() = "a" }\nfun of() = words()',
                     'fun a(words: () -> String) = words()\nfun of() = words()',
                     # A lambda's parameter has no type written: it may be no function at all.
                     'fun of() = listOf({ "a" }).map { words -> words() }'):
            with self.subTest(body=body):
                self.assertTrue(self.reaches_words(body))

    def test_a_private_function_of_another_file_is_its_own(self):
        files = {"Harness.kt": "val result = words()", MAIN + "Words.kt": "private fun words() = 1"}
        self.assertEqual(set(), walk(files))

    def test_a_call_is_held_to_the_arguments_each_namesake_takes(self):
        files = {"Harness.kt": "val result = Readings.of()",
                 MAIN + "Readings.kt": "object Readings {\n private class Sung(a: Int, b: Int, c: Int)\n fun of() = listOf(Sung(1, 2, 3), \"\".done(), many(1, 2, 3))\n}",
                 MAIN + "Sung.kt": "internal class Sung(a: Int, b: Int)", MAIN + "Done.kt": "internal fun String.done(need: Int) = need",
                 MAIN + "Many.kt": "internal fun many(vararg values: Int) = values.size"}
        self.assertEqual({MAIN + "Readings.kt", MAIN + "Many.kt"}, walk(files))

    def test_generic_receivers_and_qualified_types(self):
        files = {"Harness.kt": "package harness\nval result: app.Ruby? = 1.described().let { null }",
                 MAIN + "Described.kt": "package harness\ninternal fun <T : Any> T.described() = toString()",
                 MAIN + "Ruby.kt": "package app\nclass Ruby"}
        self.assertEqual({MAIN + "Described.kt", MAIN + "Ruby.kt"}, walk(files))


class SelectedMembers(unittest.TestCase):
    """In a SELECT_MEMBERS file, what the harness uses is in, with what starting its class runs."""

    def test_a_companion_brings_its_eager_properties_and_init_not_the_class_s_constructor(self):
        files = {"Harness.kt": "val result = SungReadings.matched()",
                 MAIN + "SungReadings.kt": '''
                     class SungReadings(val client: Client = Network.client()) {
                         val cache = Cache.load()
                         companion object {
                             private val unused = Pages.load()
                             private val rule = RULE
                             init { Setup.run() }
                             fun matched() = rule
                         }
                     }
                     ''',
                 MAIN + "Table.kt": 'internal const val RULE = "a"', MAIN + "Setup.kt": "object Setup { fun run() = 1 }",
                 MAIN + "Network.kt": "object Network { fun client() = Client() }", MAIN + "Client.kt": "class Client",
                 MAIN + "Cache.kt": "object Cache { fun load() = 1 }", MAIN + "Pages.kt": "object Pages { fun load() = 1 }"}
        self.assertEqual({MAIN + "SungReadings.kt", MAIN + "Table.kt", MAIN + "Setup.kt", MAIN + "Pages.kt"}, walk(files))

    def test_a_class_that_is_made_brings_its_constructor_properties_and_init_blocks(self):
        files = {"Harness.kt": "val result = Built()",
                 MAIN + "UniDic.kt": '''
                     class Built(val x: Int = first()) {
                         val y = second()
                         init { third() }
                     }
                     class Other { fun fetch() = Network.fetch() }
                     ''',
                 MAIN + "First.kt": "internal fun first() = 1", MAIN + "Second.kt": "internal fun second() = 2",
                 MAIN + "Third.kt": "internal fun third() = 3", MAIN + "Network.kt": "object Network { fun fetch() = 1 }"}
        self.assertEqual(set(files) - {"Harness.kt", MAIN + "Network.kt"}, walk(files))

    def test_an_instance_member_brings_how_its_instance_was_made(self):
        files = {"Harness.kt": "val result = SongNames.Table.of().cut()",
                 MAIN + "SongNames.kt": '''
                     object SongNames {
                         class Table(val rows: Int = rows()) {
                             fun cut() = 1
                             fun fetch() = Network.fetch()
                             companion object { fun of(): Table = error("fake") }
                         }
                     }
                     ''',
                 MAIN + "Rows.kt": "internal fun rows() = 1", MAIN + "Network.kt": "object Network { fun fetch() = 1 }"}
        # The Table type that of() returns names the whole class: an extra file is loud, a missed one is not.
        self.assertEqual(set(files) - {"Harness.kt"}, walk(files))

    def test_wildcard_imports_of_an_object_s_members_and_qualified_names(self):
        files = {"Harness.kt": "package app\nval result = SongNames.normalize()",
                 MAIN + "SongNames.kt": "package app\nimport app.Kana.*\nobject SongNames { fun normalize() = fold(\"x\") + app.Width.wide() }",
                 MAIN + "Kana.kt": "package app\nobject Kana { fun fold(value: String) = value }",
                 MAIN + "Width.kt": "package app\nobject Width { fun wide() = \"\" }"}
        self.assertEqual(set(files) - {"Harness.kt"}, walk(files))


class JavaAdapters(unittest.TestCase):

    def test_static_imports_of_a_kotlin_facade_and_qualified_java_names(self):
        files = {"Harness.kt": "package com.astrovm.crosstune\nval result = Adapter.read()",
                 TEST + "Adapter.java": "package com.astrovm.crosstune;\nimport static com.astrovm.crosstune.HelperKt.helper;\n"
                                        "public class Adapter { public static int read() { return helper() + com.astrovm.crosstune.Table.VALUE; } }",
                 TEST + "Table.java": "package com.astrovm.crosstune;\npublic class Table { public static final int VALUE = 1; }",
                 MAIN + "Helper.kt": "package com.astrovm.crosstune\nfun helper() = 1"}
        self.assertEqual(set(files) - {"Harness.kt"}, walk(files))


class Reflection(unittest.TestCase):

    def test_member_lookups_on_a_named_class_keep_the_class(self):
        files = {"Harness.kt": 'val result = Adapter::class.java.getDeclaredMethod("read")', TEST + "Adapter.kt": "class Adapter { fun read() = 1 }"}
        self.assertEqual({TEST + "Adapter.kt"}, walk(files))

    def test_what_the_guard_cannot_follow_fails_when_reached(self):
        for code in ("java.util.ServiceLoader.load(Runnable::class.java)", 'System.loadLibrary("words")', 'value().javaClass.getMethod("a")',
                     'Class.forName("com.astrovm.crosstune.Gone")', "Readings::class.java.getResource(name())"):
            with self.subTest(code=code), self.assertRaisesRegex(ValueError, "reflection"):
                walk({"Harness.kt": f"package com.astrovm.crosstune\nval result = {code}", MAIN + "Readings.kt": "package com.astrovm.crosstune\nobject Readings"})

    def test_native_methods_fail_when_reached_and_only_then(self):
        for name, text in (("Native.kt", "object Native { external fun read(): Int }"),
                           ("Native.java", "public class Native { public static native int read(); }")):
            with self.subTest(name=name):
                files = {"Harness.kt": "val result = Native.read()", MAIN + name: text}
                with self.assertRaisesRegex(ValueError, "native"):
                    walk(files)
                self.assertEqual(set(), walk({**files, "Harness.kt": "val result = 1"}))

    def test_resources_named_in_the_code_or_by_a_constant_are_reached(self):
        files = {"Harness.kt": 'val a = Readings::class.java.getResourceAsStream(LISTED)\nval b = javaClass.classLoader.getResource("com/astrovm/crosstune/b.txt")',
                 MAIN + "Listed.kt": 'internal const val LISTED = "/com/astrovm/crosstune/a.txt"', MAIN + "Readings.kt": "object Readings",
                 "app/src/main/resources/com/astrovm/crosstune/a.txt": "a", "app/src/test/resources/com/astrovm/crosstune/b.txt": "b",
                 "app/src/main/resources/com/astrovm/crosstune/c.txt": "c"}
        self.assertEqual(set(files) - {"Harness.kt", "app/src/main/resources/com/astrovm/crosstune/c.txt"}, walk(files))


class InitializationAndImportedLoads(unittest.TestCase):

    def test_unread_eager_initializers_still_change_the_read_value(self):
        for declared in ('object SongNames { private val setup = initialize(); fun normalize() = VALUE }',
                         'private val setup = initialize(); fun normalize() = VALUE'):
            call = 'SongNames.normalize()' if declared.startswith('object') else 'normalize()'
            files = {'Harness.kt': f'val result = {call}', MAIN + 'SongNames.kt': declared,
                     MAIN + 'Initialize.kt': 'fun initialize(): Int { VALUE = "after"; return 1 }',
                     MAIN + 'State.kt': 'var VALUE = "before"'}
            with self.subTest(declared=declared):
                self.assertEqual(set(files) - {'Harness.kt'}, walk(files))

    def test_unread_getters_are_not_eager_initializers(self):
        files = {'Harness.kt': 'val result = SongNames.normalize()',
                 MAIN + 'SongNames.kt': 'object SongNames { private val unused get() = fetch(); fun normalize() = "read" }',
                 MAIN + 'Fetch.kt': 'fun fetch() = "not called"'}
        self.assertEqual({MAIN + 'SongNames.kt'}, walk(files))

    def test_aliases_for_class_loads_follow_literal_classes(self):
        for imported, call in (('import java.lang.Class.forName as lookup', 'lookup("app.Adapter")'),
                               ('import java.lang.Class as Type', 'Type.forName("app.Adapter")')):
            files = {'Harness.kt': f'{imported}\nval result = {call}', TEST + 'Adapter.kt': 'package app\nclass Adapter'}
            with self.subTest(imported=imported):
                self.assertEqual({TEST + 'Adapter.kt'}, walk(files))

    def test_aliased_computed_loads_and_service_loaders_fail(self):
        for imported, call in (('import java.lang.Class.forName as lookup', 'lookup(name)'),
                               ('import java.util.ServiceLoader as Services', 'Services.load(Runnable::class.java)'),
                               ('import java.util.ServiceLoader.load as services', 'services(Runnable::class.java)'),
                               ('import java.lang.System.loadLibrary as nativeCode', 'nativeCode("fake")'),
                               ('import java.lang.Runtime as Native', 'Native.getRuntime().load("fake")'),
                               ('import java.util.ServiceLoader.*', 'load(Runnable::class.java)')):
            with self.subTest(imported=imported), self.assertRaisesRegex(ValueError, 'reflection|native'):
                walk({'Harness.kt': f'{imported}\nval result = {call}'})

    def test_static_java_service_loader_imports_fail_too(self):
        files = {'Harness.kt': 'val result = Adapter.read()', TEST + 'Adapter.java':
                 'import static java.util.ServiceLoader.load; class Adapter { static Object read() { return load(Runnable.class); } }'}
        with self.assertRaisesRegex(ValueError, 'reflection'):
            walk(files)
        files[TEST + 'Adapter.java'] = files[TEST + 'Adapter.java'].replace('ServiceLoader.load;', 'ServiceLoader.*;')
        with self.assertRaisesRegex(ValueError, 'reflection'):
            walk(files)

    def test_java_method_references_to_loaders_fail_closed(self):
        fields = (('interface Loader { Class<?> load(String name) throws ClassNotFoundException; }\n'
                   'class Adapter { static Loader value = Class::forName; static Object read() { return value; } }'),
                  'class Adapter { static java.util.function.Function<Class<Runnable>, java.util.ServiceLoader<Runnable>> value = java.util.ServiceLoader::load; static Object read() { return value; } }',
                  'class Adapter { static java.util.function.Consumer<String> value = System::load; static Object read() { return value; } }',
                  'class Adapter { static java.util.function.Function<String, java.net.URL> value = ClassLoader::getSystemResource; static Object read() { return value; } }')
        for code in fields:
            with self.subTest(code=code), self.assertRaisesRegex(ValueError, 'reflection|native'):
                walk({'Harness.kt': 'val result = Adapter.read()', TEST + 'Adapter.java': code})

    def test_imported_resource_loads_and_callable_loaders(self):
        files = {'Harness.kt': 'import java.lang.ClassLoader.getSystemResourceAsStream as readResource\nval result = readResource("app/rules.txt")',
                 'app/src/main/resources/app/rules.txt': 'used'}
        self.assertEqual({'app/src/main/resources/app/rules.txt'}, walk(files))
        for imported, reference in (('import java.lang.Class.forName as lookup', '::lookup'),
                                    ('import java.util.ServiceLoader as Services', 'Services::load'),
                                    ('import java.lang.ClassLoader.getSystemResourceAsStream as readResource', '::readResource')):
            with self.subTest(imported=imported), self.assertRaisesRegex(ValueError, 'reflection'):
                walk({'Harness.kt': f'{imported}\nval result = {reference}'})

    def test_runtime_load_forms_and_references_fail_closed(self):
        kotlin = ('fun read() = listOf("fake").forEach(Runtime.getRuntime()::load)',
                  'fun read() = java.lang.Runtime.getRuntime().load("fake")',
                  'import java.lang.Runtime.getRuntime\nfun read() = getRuntime().load("fake")',
                  'import java.lang.Runtime.*\nfun read() = getRuntime().load("fake")')
        java = ('class Adapter { static void read() { java.lang.Runtime.getRuntime().load("fake"); } }',
                'import static java.lang.Runtime.getRuntime; class Adapter { static void read() { getRuntime().load("fake"); } }',
                'import static java.lang.Runtime.*; class Adapter { static void read() { getRuntime().load("fake"); } }',
                'class Adapter { static java.util.function.BiConsumer<Runtime,String> value = Runtime::load; static Object read() { return value; } }')
        for code in kotlin:
            with self.subTest(code=code), self.assertRaisesRegex(ValueError, 'native'):
                walk({'Harness.kt': code + '\nval result = read()'})
        for code in java:
            with self.subTest(code=code), self.assertRaisesRegex(ValueError, 'native'):
                walk({'Harness.kt': 'val result = Adapter.read()', TEST + 'Adapter.java': code})

    def test_an_explicit_receiver_does_not_use_an_unrelated_static_import(self):
        for expression in ('dictionary().load()', '(if (true) Dictionary() else Dictionary()).load()'):
            files = {'Harness.kt': f'import java.util.ServiceLoader.load\nval result = {expression}',
                     MAIN + 'Dictionary.kt': 'class Dictionary { fun load() = 1 }', MAIN + 'Factory.kt': 'fun dictionary() = Dictionary()'}
            wanted = {MAIN + 'Dictionary.kt'} | ({MAIN + 'Factory.kt'} if expression.startswith('dictionary') else set())
            with self.subTest(expression=expression):
                self.assertEqual(wanted, walk(files))

    def test_relative_resource_names_keep_other_receiver_packages(self):
        for name, code in (('Harness.kt', 'package harness\nval result = app.Reader::class.java.getResourceAsStream("rules.txt")'),
                           ('Harness.java', 'package harness; class Harness { Object result = app.Reader.class.getResourceAsStream("rules.txt"); }')):
            files = {name: code, MAIN + 'Reader.kt': 'package app\nclass Reader',
                     'app/src/main/resources/app/rules.txt': 'used', 'app/src/main/resources/harness/rules.txt': 'possible',
                     'app/src/main/resources/app/other.txt': 'unused'}
            with self.subTest(name=name):
                self.assertEqual(set(files) - {name, 'app/src/main/resources/app/other.txt'}, walk(files, name))

    def test_absolute_resource_names_stay_at_the_resource_root(self):
        files = {'Harness.kt': 'package harness\nval result = app.Reader::class.java.getResourceAsStream("/app/rules.txt")',
                 MAIN + 'Reader.kt': 'package app\nclass Reader', 'app/src/main/resources/app/rules.txt': 'used',
                 'app/src/main/resources/harness/app/rules.txt': 'unused'}
        self.assertEqual(set(files) - {'Harness.kt', 'app/src/main/resources/harness/app/rules.txt'}, walk(files))


class Worker(unittest.TestCase):

    def test_one_jvm_parses_a_batch_keeps_each_tree_and_ends_with_its_input(self):
        parser = source_calls.Parser()
        try:
            trees = parser.parse([("A.kt", b"fun a() = 1"), ("B.java", b"class B {}"), ("A.kt", b"fun a() = 1")])
            process = parser.process
            self.assertEqual(["kotlin", "java", "kotlin"], [tree["language"] for tree in trees])
            self.assertEqual(2, len(parser.trees))
            self.assertIs(trees[0], parser.parse([("A.kt", b"fun a() = 1")])[0])
        finally:
            parser.close()
        self.assertIsNotNone(process.poll())
        self.assertIsNone(parser.process)

    def test_a_missing_jdk_says_what_to_install(self):
        with tempfile.TemporaryDirectory() as folder, mock.patch.dict(os.environ, {"JAVA_HOME": folder}):
            with self.assertRaisesRegex(RuntimeError, "JDK 17 or newer"):
                source_calls.jdk()
