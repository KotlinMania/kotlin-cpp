package io.github.kotlinmania.kotlincpp

import io.github.kotlinmania.kotlincpp.kcpp.CppFileArtifact
import io.github.kotlinmania.kotlincpp.kcpp.CppGcMode
import io.github.kotlinmania.kotlincpp.kcpp.CppTargetConfig
import io.github.kotlinmania.kotlincpp.kcpp.translateKotlinToCpp

internal const val CLI_VERSION_TEXT: String =
    "kotlin-cpp 1.1.0 (antlr4-kotlin 0.2.1, proc-macro2-kotlin 0.1.8, syn-kotlin 0.5.0, C++23)"

internal val CLI_USAGE_TEXT: String =
    """
    kotlin-cpp: Deterministic Kotlin-to-C++23 transliterator grounded on ANTLR4 Kotlin grammars and universal syn-kotlin tokens
    Usage:
      kotlin-cpp [kcpp | translate] [options] <input.kt | source directory...>
      kotlin-cpp roundtrip [-f|--force] <input.kt> <regenerated.kt>
      kotlin-cpp packets [-r|--recursive] <input.kt | source directory...>
      kotlin-cpp tokens [-r|--recursive] <input.kt | source directory...>
      kotlin-cpp --help | --version

    Commands:
      kcpp / translate  Transliterate Kotlin source files into co-located C++23 .hpp/.cpp artifacts (default command)
      roundtrip         Parse Kotlin via ANTLR4 + universal syn-kotlin PacketStream, emit Kotlin, and assert byte equality
      packets           Inspect lossless PacketStream frames, comments, KDoc links, and suspend declarations
      tokens            Inspect universal syn-kotlin + proc-macro2-kotlin token stream and keyword associations

    C++23 Transliteration Options:
      -o, --output <dir>               Output root directory (default: .)
      -r, --recursive                  Recursively discover .kt files in input directories
      -f, --force, --overwrite         Overwrite existing .hpp/.cpp/.kt target files
          --skip-existing              Skip target files that already exist
          --stdout                     Print generated C++23 files to stdout without writing disk files
          --dry-run                    Print planned output paths without writing disk files
          --gc-mode <none|konan>       Memory/GC target mode: none (default RAII) or konan (Docking-Ring GC)
          --port-lint-prefix <prefix>  Synthesize // port-lint: source <prefix>/<relPath> when absent
          --source-root <dir>          Base directory stripped when computing provenance paths
      -n, --namespace <ns>             Override root C++ namespace (e.g. kotlinx::coroutines)
          --file-stem-suffix <suffix>  Append multiplatform stem suffix (e.g. .native, .concurrent)
          --source-set-subdir <dir>    Insert source-set subdirectory under kotlinx/coroutines (e.g. native, concurrent)
          --companion-c-abi            Emit <Stem>_c_api.h and <Stem>.def for @CName exports
      -q, --quiet                      Suppress progress summary lines
      -v, --version                    Show version information
      -h, --help                       Show this help message
    """.trimIndent()

public fun runSingleFileCommand(
    args: Array<String>,
    read: (String) -> String = ::readInput,
    write: (String, String) -> Unit = ::writeNew,
    writeForce: (String, String) -> Unit = ::writeOverwrite,
    exists: (String) -> Boolean = { probeTargetKotlinFile(it).exists },
    isDirectory: (String) -> Boolean = { probeTargetKotlinFile(it).isDirectory },
    collectKtFiles: (List<String>) -> List<String> = ::collectKotlinFilePaths,
    report: (String) -> Unit = ::println,
) {
    if (args.isEmpty()) {
        throw IllegalArgumentException("usage: kotlin-cpp [options] <input.kt | dir...>; try --help")
    }
    when (args[0]) {
        "-h", "--help", "help" -> report(CLI_USAGE_TEXT)
        "-v", "-V", "--version", "version" -> report(CLI_VERSION_TEXT)
        "kcpp", "translate" ->
            runKotlinToCppCommand(
                rawArgs = args.drop(1),
                read = read,
                write = write,
                writeForce = writeForce,
                exists = exists,
                isDirectory = isDirectory,
                collectKtFiles = collectKtFiles,
                report = report,
            )
        "roundtrip" ->
            runRoundTripCommand(
                rawArgs = args.drop(1),
                read = read,
                write = write,
                writeForce = writeForce,
                report = report,
            )
        "packets" ->
            runInspectCommand(
                commandName = "packets",
                rawArgs = args.drop(1),
                read = read,
                isDirectory = isDirectory,
                collectKtFiles = collectKtFiles,
                report = report,
            ) { source, path ->
                report(inspectKotlinPackets(source, sourcePath = path))
            }
        "tokens" ->
            runInspectCommand(
                commandName = "tokens",
                rawArgs = args.drop(1),
                read = read,
                isDirectory = isDirectory,
                collectKtFiles = collectKtFiles,
                report = report,
            ) { source, path ->
                for (line in inspectKotlinTokens(source, sourcePath = path)) {
                    report(line)
                }
            }
        else ->
            runKotlinToCppCommand(
                rawArgs = args.toList(),
                read = read,
                write = write,
                writeForce = writeForce,
                exists = exists,
                isDirectory = isDirectory,
                collectKtFiles = collectKtFiles,
                report = report,
            )
    }
}

private fun runRoundTripCommand(
    rawArgs: List<String>,
    read: (String) -> String,
    write: (String, String) -> Unit,
    writeForce: (String, String) -> Unit,
    report: (String) -> Unit,
) {
    var forceOverwrite = false
    val positional = ArrayList<String>()
    var onlyPositional = false
    for (tok in rawArgs) {
        if (onlyPositional || !tok.startsWith("-") || tok == "-") {
            positional.add(tok)
            continue
        }
        when (tok) {
            "--" -> onlyPositional = true
            "-h", "--help" -> {
                report(CLI_USAGE_TEXT)
                return
            }
            "-f", "--force", "--overwrite" -> forceOverwrite = true
            else -> throw IllegalArgumentException("unknown roundtrip option '$tok'")
        }
    }
    require(positional.size == 2) {
        "usage: kotlin-cpp roundtrip [-f|--force] <input.kt> <regenerated.kt>"
    }
    val inputPath = positional[0]
    val outputPath = positional[1]
    require(inputPath.endsWith(".kt", ignoreCase = true)) { "not a Kotlin source file: $inputPath" }
    require(outputPath.endsWith(".kt", ignoreCase = true)) { "not a Kotlin output file: $outputPath" }
    require(inputPath != outputPath) { "roundtrip output path must differ from input path: $inputPath" }

    val original = read(inputPath)
    val regenerated = renderKotlinRoundTrip(original)
    if (forceOverwrite) {
        writeForce(outputPath, regenerated.text)
    } else {
        write(outputPath, regenerated.text)
    }
    check(regenerated.text == original) {
        "roundtrip mismatch for $inputPath -> $outputPath (regenerated file kept for inspection)"
    }
    report("roundtrip\t$inputPath\t$outputPath\tMATCH")
}

private fun runInspectCommand(
    commandName: String,
    rawArgs: List<String>,
    read: (String) -> String,
    isDirectory: (String) -> Boolean,
    collectKtFiles: (List<String>) -> List<String>,
    report: (String) -> Unit,
    inspectFile: (source: String, path: String) -> Unit,
) {
    if (rawArgs.isEmpty()) {
        throw IllegalArgumentException("usage: kotlin-cpp $commandName [-r|--recursive] <input.kt | dir...>")
    }
    var recursive = false
    val inputs = ArrayList<String>()
    var onlyPositional = false
    for (tok in rawArgs) {
        if (onlyPositional || !tok.startsWith("-") || tok == "-") {
            inputs.add(tok)
            continue
        }
        when (tok) {
            "--" -> onlyPositional = true
            "-h", "--help" -> {
                report(CLI_USAGE_TEXT)
                return
            }
            "-r", "--recursive" -> recursive = true
            else -> throw IllegalArgumentException("unknown $commandName option '$tok'")
        }
    }
    require(inputs.isNotEmpty()) { "no Kotlin input files provided to $commandName" }
    val resolvedFiles = resolveKotlinInputs(inputs, recursive, isDirectory, collectKtFiles)
    for (ktPath in resolvedFiles) {
        inspectFile(read(ktPath), ktPath)
    }
}

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
        throw IllegalArgumentException("usage: kotlin-cpp [options] <input.kt | dir...>; try --help")
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
    var fileStemSuffix: String? = null
    var sourceSetSubdir: String? = null
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
                report(CLI_USAGE_TEXT)
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
            "--file-stem-suffix" -> fileStemSuffix = nextVal()
            "--source-set-subdir" -> sourceSetSubdir = nextVal()
            "--companion-c-abi" -> companionCAbi = true
            "-q", "--quiet" -> quiet = true
            else -> throw IllegalArgumentException("unknown kcpp option '$optName'")
        }
        idx += 1
    }

    require(inputs.isNotEmpty()) { "no Kotlin input files provided to kcpp" }
    val resolvedFiles = resolveKotlinInputs(inputs, recursive, isDirectory, collectKtFiles)

    val config =
        CppTargetConfig(
            cppStandard = 23,
            gcMode = gcMode,
            portLintPrefix = portLintPrefix,
            sourceRootPrefix = sourceRoot,
            rootNamespaceOverride = namespaceOverride,
            fileStemSuffix = fileStemSuffix,
            sourceSetSubdir = sourceSetSubdir,
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

private fun resolveKotlinInputs(
    inputs: List<String>,
    recursive: Boolean,
    isDirectory: (String) -> Boolean,
    collectKtFiles: (List<String>) -> List<String>,
): List<String> {
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
    return resolvedFiles
}
