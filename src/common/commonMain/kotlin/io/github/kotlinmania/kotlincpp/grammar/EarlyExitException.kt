package io.github.kotlinmania.kotlincpp.grammar

import io.github.kotlinmania.antlr4.Parser
import io.github.kotlinmania.antlr4.RecognitionException

/**
 * Signifies that a positive closure `(...)+` in `KotlinParser.g4` did not match at least once.
 */
internal class EarlyExitException(
    recognizer: Parser,
) : RecognitionException(recognizer, recognizer.inputStream, recognizer.ctx) {
    init {
        offendingToken = recognizer.currentToken
    }
}
