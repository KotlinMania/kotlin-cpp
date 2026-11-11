package io.github.kotlinmania.rustkotlin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class KotlinRoundTripTest {
    @Test
    fun generatedKotlinReentersTheExistingSynAstAndKotlinEmitter() {
        val canonical = renderSingleKotlinFile(
            "pub fn add(left: i32, right: i32) -> i32 { left + right }",
        ).text
        val regenerated = renderKotlinRoundTrip(canonical)
        assertEquals(canonical, regenerated.text)
        assertEquals("1 items round-tripped", regenerated.summary)
    }

    @Test
    fun roundTripCommandWritesRegeneratedKotlinAndChecksExactBytes() {
        val canonical = renderSingleKotlinFile("pub fn answer() -> i32 { 42 }").text
        val writes = linkedMapOf<String, String>()
        val messages = mutableListOf<String>()
        runSingleFileCommand(arrayOf("roundtrip", "Answer.kt", "Regenerated.kt"),
            read = { path -> assertEquals("Answer.kt", path); canonical },
            write = { path, text -> writes[path] = text },
            report = messages::add)
        assertEquals(mapOf("Regenerated.kt" to canonical), writes)
        assertEquals(listOf("roundtrip\tAnswer.kt\tRegenerated.kt\tMATCH"), messages)
    }

    @Test
    fun formattingDifferenceIsDetectedInsteadOfPassingInputThrough() {
        val original = "public fun answer(): Int { return 41 }"
        val expected = renderSingleKotlinFile("pub fn answer() -> i32 { 41 }").text
        assertNotEquals(original, expected)
        val writes = linkedMapOf<String, String>()
        assertFailsWith<IllegalArgumentException> {
            runSingleFileCommand(arrayOf("roundtrip", "Answer.kt", "Regenerated.kt"),
                read = { original }, write = { path, text -> writes[path] = text })
        }
        assertEquals(mapOf("Regenerated.kt" to expected), writes)
    }

    @Test
    fun changedKotlinValueControlsTheReconstructedAst() {
        val canonical = renderSingleKotlinFile("pub fn answer() -> i32 { 42 }").text
        val changed = canonical.replace("42", "43")
        assertEquals(renderSingleKotlinFile("pub fn answer() -> i32 { 43 }").text,
            renderKotlinRoundTrip(changed).text)
    }

    @Test
    fun branchesStringsAndMultipleFunctionsRoundTripExactly() {
        val canonical = renderSingleKotlinFile("""
            pub fn choose(flag: bool) -> i32 { if flag { 42 } else { 7 } }
            pub fn greeting() -> &'static str { "hello" }
        """.trimIndent()).text
        assertEquals(canonical, renderKotlinRoundTrip(canonical).text)
    }

    @Test
    fun kotlinPackageUsesTheExistingEmitterHeaderContext() {
        val canonical = renderSingleKotlinFile("pub fn answer() -> i32 { 42 }",
            FileHeaderContext(packageName = "proof.roundtrip")).text
        assertEquals(canonical, renderKotlinRoundTrip(canonical).text)
    }
}
