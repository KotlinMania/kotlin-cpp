package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.rustkotlin.kcpp.CppFileArtifact
import io.github.kotlinmania.rustkotlin.kcpp.CppGcMode
import io.github.kotlinmania.rustkotlin.kcpp.CppTargetConfig
import io.github.kotlinmania.rustkotlin.kcpp.translateKotlinToCpp

internal val KCPP_CLI_USAGE_TEXT: String =
    """
    Usage:
      kotlin-cpp kcpp [options] <input.kt | source directory...>

    Transliterates Kotlin source files into co-located C++23 .hpp/.cpp artifacts.

    Options:
      -o, --output <dir>               Output root directory (default: .)
      -r, --recursive                  Recursively discover .kt files in input directories
      -f, --force, --overwrite         Overwrite existing .hpp/.cpp files
          --skip-existing              Skip target files that already exist
          --stdout                     Print generated C++23 files to stdout without writing disk files
          --dry-run                    Print planned output paths without writing disk files
          --gc-mode <none|konan>       Memory/GC target mode: none (default RAII) or konan (Docking-Ring GC)
          --port-lint-prefix <prefix>  Synthesize // port-lint: source <prefix>/<relPath> when absent
          --source-root <dir>          Base directory stripped when computing provenance paths
      -n, --namespace <ns>             Override root C++ namespace (e.g. kotlinx::coroutines)
          --companion-c-abi            Emit <Stem>_c_api.h and <Stem>.def for @CName exports
      -q, --quiet                      Suppress progress summary lines
      -h, --help                       Show this help message
    """.trimIndent()

internal fun runKotlinToCppCommand(
    rawArgs: List<String>,
    read: (String) -> String,
    write: (String, String) -> Unit,
    writeForce: (String, String) -> Unit,
    exists: (String) -> Boolean,
    isDirectory: (String) -> Boolean,
    collectKtFiles: (List<String>) -> List<String> = ::collectKotlinFilePaths,
    report: (String) -> Unit,
) {
    if (rawArgs.isEmpty()) {
        throw IllegalArgumentException("usage: kotlin-cpp kcpp [options] <input.kt | dir...>; try --help")
    }

    var outputDir = "."
    var recursive = false
    var forceOverwrite = false
    var skipExisting = false
    var toStdout = false
    var dryRun = false
    var gcMode = CppGcMode.NONE
    var portLintPrefix: String? = null
    var sourceRoot: String? = null
    var namespaceOverride: String? = null
    var companionCAbi = false
    var quiet = false
    val inputs = ArrayList<String>()

    var idx = 0
    var onlyPositional = false
    while (idx < rawArgs.size) {
        val tok = rawArgs[idx]
        if (onlyPositional || !tok.startsWith("-") || tok == "-") {
            inputs.add(tok)
            idx += 1
            continue
        }
        if (tok == "--") {
            onlyPositional = true
            idx += 1
            continue
        }

        val eqIdx = tok.indexOf('=')
        val optName = if (eqIdx > 0 && tok.startsWith("--")) tok.substring(0, eqIdx) else tok
        val inlineVal = if (eqIdx > 0 && tok.startsWith("--")) tok.substring(eqIdx + 1) else null

        fun nextVal(): String {
            if (inlineVal != null) return inlineVal
            idx += 1
            require(idx < rawArgs.size) { "option $optName requires a value" }
            return rawArgs[idx]
        }

        when (optName) {
            "-h", "--help" -> {
                report(KCPP_CLI_USAGE_TEXT)
                return
            }
            "-o", "--output" -> outputDir = nextVal()
            "-r", "--recursive" -> recursive = true
            "-f", "--force", "--overwrite" -> forceOverwrite = true
            "--skip-existing" -> skipExisting = true
            "--stdout" -> toStdout = true
            "--dry-run" -> dryRun = true
            "--gc-mode" -> {
                val modeStr = nextVal().trim().lowercase()
                gcMode =
                    when (modeStr) {
                        "none", "raii", "standalone" -> CppGcMode.NONE
                        "konan", "konan_gc", "konan-gc" -> CppGcMode.KONAN_GC
                        else -> throw IllegalArgumentException("invalid --gc-mode '$modeStr'; expected none or konan")
                    }
            }
            "--port-lint-prefix" -> portLintPrefix = nextVal()
            "--source-root" -> sourceRoot = nextVal()
            "-n", "--namespace" -> namespaceOverride = nextVal()
            "--companion-c-abi" -> companionCAbi = true
            "-q", "--quiet" -> quiet = true
            else -> throw IllegalArgumentException("unknown kcpp option '$optName'")
        }
        idx += 1
    }

    require(inputs.isNotEmpty()) { "no Kotlin input files provided to kcpp" }

    val resolvedFiles =
        inputs
            .flatMap { input ->
                if (isDirectory(input)) {
                    require(recursive) { "input '$input' is a directory; pass -r / --recursive" }
                    collectKtFiles(listOf(input))
                } else {
                    require(input.endsWith(".kt", ignoreCase = true)) { "not a Kotlin source file: $input" }
                    listOf(input)
                }
            }.distinct()
            .sorted()
    require(resolvedFiles.isNotEmpty()) { "no Kotlin source files found" }

    val config =
        CppTargetConfig(
            cppStandard = 23,
            gcMode = gcMode,
            portLintPrefix = portLintPrefix,
            sourceRootPrefix = sourceRoot,
            rootNamespaceOverride = namespaceOverride,
            emitCompanionCAbiFiles = companionCAbi,
        )

    val cleanOutRoot = outputDir.replace('\\', '/').trimEnd('/')
    for (ktPath in resolvedFiles) {
        val result = translateKotlinToCpp(source = read(ktPath), sourcePath = ktPath, config = config)
        val allArtifacts = ArrayList<CppFileArtifact>(2 + result.companionArtifacts.size)
        allArtifacts.add(result.headerArtifact)
        result.sourceArtifact?.let { allArtifacts.add(it) }
        allArtifacts.addAll(result.companionArtifacts)

        if (toStdout) {
            for (art in allArtifacts) {
                report("// === ${art.relativePath} ===")
                report(art.content.trimEnd())
            }
            continue
        }

        for (art in allArtifacts) {
            val destPath =
                if (cleanOutRoot.isEmpty() || cleanOutRoot == ".") {
                    art.relativePath
                } else {
                    "$cleanOutRoot/${art.relativePath}"
                }
            if (dryRun) {
                if (!quiet) {
                    report("dry-run\t$ktPath\t->\t$destPath")
                }
                continue
            }
            if (skipExisting && exists(destPath)) {
                continue
            }
            if (forceOverwrite) {
                writeForce(destPath, art.content)
            } else {
                write(destPath, art.content)
            }
            if (!quiet) {
                report("kcpp\t$ktPath\t->\t$destPath")
            }
        }
    }
}
