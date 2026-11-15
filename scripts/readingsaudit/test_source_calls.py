"""The dependency guard reads fake Kotlin sources. It never compiles or runs them."""
import tempfile
import textwrap
import unittest
from pathlib import Path

import source_calls


MAIN = "app/src/main/java/com/astrovm/crosstune/"
TEST = "app/src/test/java/com/astrovm/crosstune/"


class KotlinCode(unittest.TestCase):

    def test_comments_literals_and_characters_are_not_references(self):
        text = '''// First
/* Outer /* Nested */ Last */
val plain = "Hidden Class.forName(\\"AlsoHidden\\")"
val raw = """Hidden /* literal */ // literal"""
val character = '{'
val escaped = "\\$Hidden"
val kept = realCall()
'''
        code = source_calls.code_of(text)
        for word in ("First", "Outer", "Nested", "Last", "Hidden", "AlsoHidden", "literal"):
            self.assertNotIn(word, code)
        self.assertIn("realCall()", code)
        self.assertEqual(len(text), len(code))
        self.assertEqual(text.count("\n"), code.count("\n"))

    def test_templates_keep_nested_code_and_reflection_keeps_its_class(self):
        text = '''val value = "Hidden ${if (flag) { first("literal") } else { second() }} $TABLE"
val raw = """Hidden ${third()} $OTHER"""
val loaded = Class.forName("com.example.Adapter")
'''
        code = source_calls.code_of(text)
        for word in ("first", "second", "third", "TABLE", "OTHER", "com.example.Adapter"):
            self.assertIn(word, code)
        self.assertNotIn("Hidden", code)
        self.assertNotIn("literal", code)

    def test_incomplete_kotlin_fails_instead_of_dropping_dependencies(self):
        for text in ('/* unfinished', 'val value = "unfinished', 'val value = "${unfinished'):
            with self.subTest(text=text), self.assertRaises(ValueError):
                source_calls.code_of(text)


class SourceCalls(unittest.TestCase):

    def walk(self, files):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            for name, text in files.items():
                (root / name).parent.mkdir(parents=True, exist_ok=True)
                (root / name).write_text(textwrap.dedent(text))
            return source_calls.called(root, "Harness.kt", source_calls.declared(root))

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
