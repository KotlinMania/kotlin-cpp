package io.github.kotlinmania.rustkotlin

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

/** Native CLI entry point for Kotlin token inspection and reference translation. */
public fun main(args: Array<String>) {
    try {
        runSingleFileCommand(args)
    } catch (error: Exception) {
        reportFatalErrorAndExit("kotlin-cpp: ${error.message}\n")
    } finally {
        TranslationSession.clear()
    }
}
