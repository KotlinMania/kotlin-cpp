package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.procmacro2.Delimiter
import io.github.kotlinmania.procmacro2.Group
import io.github.kotlinmania.procmacro2.Ident
import io.github.kotlinmania.procmacro2.TokenStream
import io.github.kotlinmania.procmacro2.TokenTree
import io.github.kotlinmania.syn.parseKotlinTokenStream
import io.github.kotlinmania.syn.token.Fn
import io.github.kotlinmania.syn.token.RArrow

/** Regenerates Kotlin through shared Syn syntax and the existing Kotlin emitter. */
public fun renderKotlinRoundTrip(source: String): SingleFileTranslation {
    val kotlinTokens = parseKotlinTokenStream(source).getOrThrow()
    val (associated, header) = associateKotlinFunctionTokens(kotlinTokens)
    // Compiler-lexer handles and standalone proc-macro2 spans are distinct
    // domains. Enter the existing standalone frontend through the shared
    // vocabulary's canonical encoding, unconditionally (never as a fallback).
    // No original Rust input is retained or supplied to this operation.
    val recognized = recognizeRust(associated.toString())
    val emitted = translateRustSourceToKotlinArtifacts(recognized, header)
    return SingleFileTranslation(emitted.first, "${recognized.file.items.size} items round-tripped",
        companionFiles = emitted.second)
}

private fun associateKotlinFunctionTokens(input: TokenStream): Pair<TokenStream, FileHeaderContext> {
    val tokens = input.toList()
    val result = TokenStream.new()
    var firstDeclaration = 0
    var packageName: String? = null
    if (tokens.firstOrNull()?.toString() == "package") {
        val segments = mutableListOf<String>()
        var cursor = 1
        while (true) {
            val segment = tokens.getOrNull(cursor) as? TokenTree.Ident
            require(segment != null) { "expected Kotlin package name" }
            segments += segment.value.toString()
            cursor++
            if (tokens.getOrNull(cursor)?.toString() != ".") break
            cursor++
        }
        if (tokens.getOrNull(cursor)?.toString() == ";") cursor++
        packageName = segments.joinToString(".")
        firstDeclaration = cursor
    }
    var functionSignature = false
    var parametersSeen = false
    for ((index, token) in tokens.withIndex()) {
        if (index < firstDeclaration) continue
        val word = (token as? TokenTree.Ident)?.value?.toString()
        when {
            word == "public" && tokens.getOrNull(index + 1)?.toString() == "fun" ->
                result.extendIdents(listOf(Ident.new("pub", token.span())))
            word == "fun" -> {
                Fn(token.span()).toTokens(result)
                functionSignature = true
                parametersSeen = false
            }
            functionSignature && token is TokenTree.Group && token.value.delimiter() == Delimiter.Parenthesis -> {
                parametersSeen = true
                result.extendTokenTrees(listOf(token))
            }
            functionSignature && parametersSeen && token is TokenTree.Punct && token.value.asChar() == ':' ->
                RArrow(token.span()).toTokens(result)
            token is TokenTree.Group && token.value.delimiter() == Delimiter.Brace -> {
                result.extendGroups(listOf(Group(Delimiter.Brace, associateKotlinExpressionTokens(token.value.stream()))))
                functionSignature = false
                parametersSeen = false
            }
            else -> result.extendTokenTrees(listOf(token))
        }
    }
    return result to FileHeaderContext(packageName = packageName)
}

private fun associateKotlinExpressionTokens(input: TokenStream): TokenStream {
    val result = TokenStream.new()
    var previousWord: String? = null
    for (token in input) {
        if (token is TokenTree.Group) {
            val nested = associateKotlinExpressionTokens(token.value.stream())
            if (token.value.delimiter() == Delimiter.Parenthesis && previousWord == "if") {
                // Kotlin requires these condition delimiters; Syn's If AST does
                // not. Retaining them would make the existing emitter add a
                // second pair on every round trip.
                result.extendTokenTrees(nested)
            } else {
                result.extendGroups(listOf(Group(token.value.delimiter(), nested)))
            }
        } else {
            result.extendTokenTrees(listOf(token))
        }
        previousWord = (token as? TokenTree.Ident)?.value?.toString()
    }
    return result
}
