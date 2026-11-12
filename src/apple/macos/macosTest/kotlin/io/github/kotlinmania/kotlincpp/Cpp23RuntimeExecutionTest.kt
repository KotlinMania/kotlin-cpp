package io.github.kotlinmania.kotlincpp

import io.github.kotlinmania.io.files.SystemFileSystem
import io.github.kotlinmania.io.files.resolve
import io.github.kotlinmania.io.files.writeString
import io.github.kotlinmania.kotlincpp.kcpp.translateKotlinToCpp
import io.github.kotlinmania.libc.unix.getpid
import platform.posix.system
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import io.github.kotlinmania.io.files.Path as KmPath

class Cpp23RuntimeExecutionTest {
    @Test
    fun generatedCpp23ArtifactsCompileWithClangAndExecuteCorrectly() {
        val baseDir = KmPath("build/test-scratch/cpp23-exec-${getpid()}-${Random.nextLong().toULong()}")
        SystemFileSystem.createDirectories(baseDir, mustCreate = false)
        val absRoot = SystemFileSystem.resolve(baseDir)

        val kotlinSource =
            """
            // port-lint: source runtime/GridKernel.kt
            package kotlinx.coroutines.grid

            public enum class CellMode {
                IDLE,
                ACTIVE,
            }

            public sealed class GridEvent {
                public data class Shifted(public val row: Int, public val col: Int) : GridEvent()
            }

            public class MatrixCell(public val baseOffset: Int) {
                public operator fun get(r: Int, c: Int): Int = baseOffset + r * 10 + c
            }

            public fun evaluateCell(cell: MatrixCell, mode: CellMode, ev: GridEvent): Int {
                if (mode == CellMode.IDLE) {
                    return 0
                }
                if (ev is GridEvent.Shifted) {
                    return cell[ev.row, ev.col]
                }
                return -1
            }
            """.trimIndent() + "\n"

        try {
            assertEquals(kotlinSource, renderKotlinRoundTrip(kotlinSource).text)

            val result = translateKotlinToCpp(kotlinSource, sourcePath = "GridKernel.kt")
            val hppRel = result.headerArtifact.relativePath
            val cppArtifact = assertNotNull(result.sourceArtifact)

            val hppPath = absRoot.resolve(hppRel)
            val cppPath = absRoot.resolve(cppArtifact.relativePath)
            ensureParentDirectories(hppPath.toString())
            ensureParentDirectories(cppPath.toString())
            SystemFileSystem.writeString(hppPath, result.headerArtifact.content)
            SystemFileSystem.writeString(cppPath, cppArtifact.content)

            val mainCppPath = absRoot.resolve("main.cpp")
            val outBinPath = absRoot.resolve("grid_exec_check")
            val stdoutCapture = absRoot.resolve("stdout.txt")

            val mainCpp =
                """
                #include <cassert>
                #include <iostream>
                #include "$hppRel"

                int main() {
                    using namespace kotlinx::coroutines::grid;
                    MatrixCell cell{5};
                    GridEvent::Shifted ev{3, 7};
                    const auto idle_val = evaluate_cell(cell, CellMode::IDLE, ev);
                    const auto active_val = evaluate_cell(cell, CellMode::ACTIVE, ev);
                    assert(idle_val == 0);
                    assert(active_val == 42);
                    const auto copy_ev = ev.copy(4, 2);
                    assert(!(copy_ev == ev));
                    std::cout << "CPP23_OK:" << active_val << "\n";
                    return 0;
                }
                """.trimIndent() + "\n"
            SystemFileSystem.writeString(mainCppPath, mainCpp)

            val compileCmd =
                "/usr/bin/clang++ -std=c++23 -Wall -Wextra -Werror " +
                    "-I\"$absRoot\" \"$cppPath\" \"$mainCppPath\" -o \"$outBinPath\""
            assertEquals(0, system(compileCmd), "clang++ -std=c++23 compilation failed: $compileCmd")

            val runCmd = "\"$outBinPath\" > \"$stdoutCapture\""
            assertEquals(0, system(runCmd), "compiled C++23 binary execution failed")
            assertEquals("CPP23_OK:42\n", readInput(stdoutCapture.toString()))
        } finally {
            deleteTreeRecursively(absRoot)
        }
    }

    private fun deleteTreeRecursively(dir: KmPath) {
        if (!SystemFileSystem.exists(dir)) return
        val meta = SystemFileSystem.metadataOrNull(dir)
        if (meta?.isDirectory == true) {
            for (child in SystemFileSystem.list(dir)) {
                deleteTreeRecursively(child)
            }
        }
        SystemFileSystem.delete(dir, mustExist = false)
    }
}
