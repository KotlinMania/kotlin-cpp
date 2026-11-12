package io.github.kotlinmania.rustkotlin

import kotlin.test.Test
import kotlin.test.assertEquals

class KotlinTokenInspectionTest {
    @Test
    fun tokensCommandUsesSynAssociationsWithoutWritingFiles() {
        val source = "fun answer(): Int { /* when */ val `when` = \"if fun\"; return 42 }"
        fun inspect(): List<String> {
            val messages = mutableListOf<String>()
            runSingleFileCommand(
                arrayOf("tokens", "Answer.kt"),
                read = { path ->
                    assertEquals("Answer.kt", path)
                    source
                },
                isDirectory = { false },
                write = { _, _ -> error("Token inspection must not write files") },
                writeForce = { _, _ -> error("Token inspection must not overwrite files") },
                report = messages::add,
            )
            return messages
        }
        val messages = inspect()
        assertEquals(
            listOf("association\tfun\tToken![fn]\t1", "association\treturn\tToken![return]\t1"),
            messages.filter { it.startsWith("association\t") },
        )
        assertEquals(messages, inspect())
    }
}
