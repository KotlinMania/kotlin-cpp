package io.github.kotlinmania.kotlincpp

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.fflush
import platform.posix.fprintf
import platform.posix.stderr
import kotlin.system.exitProcess

@OptIn(ExperimentalForeignApi::class)
private fun reportFatalErrorAndExit(message: String): Nothing {
    fprintf(stderr, "%s", message)
    fflush(stderr)
    exitProcess(2)
}

/** Native CLI entry point for Kotlin-to-C++23 transliteration, lossless round-trip, and universal token inspection. */
public fun main(args: Array<String>) {
    try {
        runSingleFileCommand(args)
    } catch (error: Exception) {
        reportFatalErrorAndExit("kotlin-cpp: ${error.message}\n")
    }
}
