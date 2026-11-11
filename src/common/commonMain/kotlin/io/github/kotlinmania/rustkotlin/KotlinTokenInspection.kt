package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.procmacro2.TokenStream
import io.github.kotlinmania.procmacro2.TokenTree
import io.github.kotlinmania.syn.kotlinKeywordRustToken
import io.github.kotlinmania.syn.parseKotlinTokenStream

internal fun inspectKotlinTokens(
    inputs: List<String>,
    read: (String) -> String,
    isDirectory: (String) -> Boolean,
    report: (String) -> Unit,
) {
    require(inputs.isNotEmpty()) { "usage: kotlin-cpp tokens <input.kt | source directory...>" }
    require(inputs.none { it.startsWith("-") }) { "tokens accepts Kotlin files and source directories, not options" }
    val files = inputs.flatMap { input ->
        if (isDirectory(input)) {
            collectKotlinFilePaths(listOf(input))
        } else {
            require(input.endsWith(".kt", ignoreCase = true)) { "not a Kotlin source file: $input" }
            listOf(input)
        }
    }.distinct().sorted()
    require(files.isNotEmpty()) { "no Kotlin source files found" }
    for (path in files) {
        val result = parseKotlinTokenStream(read(path))
        val stream = result.getOrNull() ?: error("$path: ${result.exceptionOrNull()}")
        var leaves = 0
        var groups = 0
        val associations = linkedMapOf<Pair<String, String>, Int>()
        fun visit(tokens: TokenStream) {
            for (token in tokens) {
                if (token is TokenTree.Group) {
                    groups++
                    visit(token.value.stream())
                } else {
                    leaves++
                    val shared = kotlinKeywordRustToken(token) ?: continue
                    val key = token.toString() to shared.toString()
                    associations[key] = (associations[key] ?: 0) + 1
                }
            }
        }
        visit(stream)
        report("tokens\t$path\tleaves=$leaves\tgroups=$groups")
        for ((key, count) in associations.entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))) {
            report("association\t${key.first}\t${key.second}\t$count")
        }
    }
}
