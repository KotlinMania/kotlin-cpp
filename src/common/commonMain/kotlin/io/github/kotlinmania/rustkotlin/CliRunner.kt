package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.io.files.SystemFileSystem
import io.github.kotlinmania.io.files.Path as KmPath

internal const val CLI_VERSION_TEXT: String =
    "kotlin-cpp 0.1.0 (proc-macro2-kotlin 0.1.8, syn-kotlin 0.5.0)"

internal val CLI_USAGE_TEXT: String =
    """
    kotlin-cpp: Kotlin token inspection and inherited Rust-to-Kotlin reference translation
    Usage:
      kotlin-cpp tokens <input.kt | source directory...>
      kotlin-cpp roundtrip <input.kt> <regenerated.kt>
      kotlin-cpp inspect [options] <input.rs | dir...>
      kotlin-cpp translate [options] <input.rs> <output.kt>
      kotlin-cpp [translate] [options] <input.rs | dir...>
      kotlin-cpp --help | --version

    tokens reports lexical Syn associations; it does not yet emit C++ or resolve Kotlin semantics.
    roundtrip regenerates through the existing Syn/Kotlin pipeline and requires exact equality.
    A mismatch keeps the regenerated file for inspection and exits with an error.

    Options:
      -r, --recursive                  Recursively translate/inspect .rs files in directories (skips lib.rs and mod.rs)
      -o, --output <file.kt|dir>       Target .kt file (single input) or output root directory (batch/single)
          --in-place                   Write sibling <PascalCase>.kt next to each input .rs file
          --stdout                     Print translated Kotlin source to stdout without writing disk files
          --dry-run                    Print planned <input.rs> -> <output.kt> actions without writing files
      -f, --force, --overwrite         Overwrite existing target .kt files (default refuses existing files)
          --skip-existing              Skip target .kt files that already exist
      -g, --gradle-properties <file>   Explicit gradle.properties file for group/namespace inference
          --package-properties <file>  Explicit package.properties file for package/crate overrides
      -p, --package <pkg>              Explicit Kotlin package name (appends relative subpackages in recursive mode)
      -n, --namespace <ns>             Explicit crate root Kotlin namespace (e.g. io.github.kotlinmania.serdejson)
          --group <group>              Explicit project group (e.g. io.github.kotlinmania)
      -c, --crate-namespace <k>=<ns>   Map external crate <k> to Kotlin namespace <ns> (repeatable)
          --source-root <dir>          Base directory for computing relative // port-lint paths
          --port-lint <mode>           Port-lint header directive: source (default), tests, or none
          --raw-abi                    Preserve raw C/Win32 ABI struct/constant/function identifiers
          --final-structs              Emit final classes (non-open) for structs/unions
          --split-module-roots         Split implementation mod.rs/lib.rs into focused files and place leaf modules in subpackages
          --win32-dynamic-ffi          Emit multi-target Win32DynamicFfi runtime scaffold and route windows_link::link! calls through it
          --c-dynamic-ffi              Emit multi-target LibcDynamicFfi runtime scaffold and route extern "C" calls/statics through it
          --target-pointer-width <n>   Target pointer width in bits: 16, 32, or 64 (default: 64)
          --target-endian <endian>     Target byte order: little or big (default: little)
          --target-arch <arch>         Target CPU architecture for cfg predicates (e.g. x86_64, aarch64, wasm32)
          --target-os <os>             Target OS for cfg predicates (e.g. macos, linux, windows, ios, wasi)
          --target-env <env>           Target ABI/environment for cfg predicates (e.g. gnu, msvc, musl)
          --target-vendor <vendor>     Target vendor for cfg predicates (e.g. apple, pc, unknown)
          --target-family <family>     Target family for cfg predicates, repeatable (e.g. unix, windows, wasm)
          --feature <name>             Enable Cargo feature <name> for cfg(feature = "...") (repeatable)
          --cfg <spec>                 Enable custom cfg flag or key=value predicate (repeatable)
          --cfg-closed-world           Treat unspecified cfg predicates/features as false instead of open-world
          --timings                    Report lexer/parser/emitter stage timings
      -q, --quiet                      Suppress progress summary lines
      -v, --version                    Show version information
      -h, --help                       Show this help message
    """.trimIndent()

private enum class CliActionKind {
    HELP,
    VERSION,
    INSPECT,
    TRANSLATE,
}

private data class ParsedCliOptions(
    val action: CliActionKind,
    val inputs: List<String> = emptyList(),
    val classicOutputPath: String? = null,
    val outputTarget: String? = null,
    val recursive: Boolean = false,
    val inPlace: Boolean = false,
    val toStdout: Boolean = false,
    val dryRun: Boolean = false,
    val forceOverwrite: Boolean = false,
    val skipExisting: Boolean = false,
    val gradlePropertiesPath: String? = null,
    val packagePropertiesPath: String? = null,
    val packageOverride: String? = null,
    val namespaceOverride: String? = null,
    val groupOverride: String? = null,
    val crateNamespaces: Map<String, String> = emptyMap(),
    val sourceRoot: String? = null,
    val portLintDirective: String = "source",
    val rawAbiMode: Boolean = false,
    val finalStructs: Boolean = false,
    val splitModuleRoots: Boolean = false,
    val win32DynamicFfi: Boolean = false,
    val cDynamicFfi: Boolean = false,
    val cfgTargetProfile: CfgTargetProfile = CfgTargetProfile(),
    val timings: Boolean = false,
    val quiet: Boolean = false,
)

private data class DiscoveredRustInput(
    val inputPath: String,
    val relativeDir: String,
    val discoveryRoot: String?,
)

/** IO is injected for focused invariant tests; renderers receive source text, never a writer. */
internal fun runSingleFileCommand(
    args: Array<String>,
    read: (String) -> String = ::readInput,
    write: (String, String) -> Unit = ::writeNew,
    writeForce: (String, String) -> Unit = ::writeOverwrite,
    exists: (String) -> Boolean = { probeTargetKotlinFile(it).exists },
    isDirectory: (String) -> Boolean = { SystemFileSystem.metadataOrNull(KmPath(it))?.isDirectory == true },
    collectRustFiles: ((List<String>) -> List<String>)? = null,
    report: (String) -> Unit = ::println,
    resolveGradleProperties: (String?) -> GradleProjectProperties? = ::findNearestGradleProperties,
    resolvePackageProperties: (String?) -> FolderPackageProperties? = ::findNearestPackageProperties,
    resolveCrateMacroSources: (String?) -> List<String> = ::discoverCrateMacroSourcesFromDisk,
    resolveCrateFlatFileModules: (String?, String?) -> Set<String> = ::discoverCrateFlatFileModulesFromDisk,
    resolveCrateRawAbiMetadata: (String?, String?, Boolean, Boolean) -> CrateRawAbiMetadata = { inp, root, raw, split ->
        discoverCrateRawAbiMetadataFromDisk(inp, root, raw, split, TranslationSession.finalStructs)
    },
) {
    if (args.firstOrNull() == "roundtrip") {
        require(args.size == 3 && args[1].endsWith(".kt") && args[2].endsWith(".kt")) {
            "usage: kotlin-cpp roundtrip <input.kt> <regenerated.kt>"
        }
        require(args[1] != args[2]) { "roundtrip output must not replace its input" }
        val original = read(args[1])
        val regenerated = renderKotlinRoundTrip(original)
        write(args[2], regenerated.text)
        require(original == regenerated.text) { "roundtrip mismatch; regenerated Kotlin written to ${args[2]}" }
        report("roundtrip\t${args[1]}\t${args[2]}\tMATCH")
        return
    }
    if (args.firstOrNull() == "tokens") {
        inspectKotlinTokens(args.drop(1), read, isDirectory, report)
        return
    }
    val parsed = parseCliOptions(args)
    when (parsed.action) {
        CliActionKind.HELP -> {
            report(CLI_USAGE_TEXT)
            return
        }
        CliActionKind.VERSION -> {
            report(CLI_VERSION_TEXT)
            return
        }
        CliActionKind.INSPECT,
        CliActionKind.TRANSLATE,
        -> {}
    }

    val effectiveCollectRustFiles: (List<String>) -> List<String> =
        collectRustFiles
            ?: if (parsed.splitModuleRoots) {
                ::collectAllRustFilePathsIncludingModuleRoots
            } else {
                ::collectTranspilableRustFilePaths
            }

    val prevTimings = TranslationSession.timings
    val prevRawAbi = TranslationSession.rawAbiMode
    val prevFinalStructs = TranslationSession.finalStructs
    val prevSplitModRoots = TranslationSession.splitModuleRoots
    val prevWin32DynFfi = TranslationSession.win32DynamicFfi
    val prevCDynFfi = TranslationSession.cDynamicFfi
    val prevCfgProfile = TranslationSession.cfgTargetProfile
    val prevEmitted128 = TranslationSession.emittedInt128Support
    if (parsed.timings) {
        TranslationSession.timings = true
    }
    TranslationSession.rawAbiMode = parsed.rawAbiMode
    TranslationSession.finalStructs = parsed.finalStructs
    TranslationSession.splitModuleRoots = parsed.splitModuleRoots
    TranslationSession.win32DynamicFfi = parsed.win32DynamicFfi
    TranslationSession.cDynamicFfi = parsed.cDynamicFfi
    TranslationSession.cfgTargetProfile = parsed.cfgTargetProfile
    TranslationSession.emittedInt128Support = false
    try {
        if (parsed.action == CliActionKind.INSPECT) {
            executeInspect(
                parsed = parsed,
                read = read,
                isDirectory = isDirectory,
                collectRustFiles = effectiveCollectRustFiles,
                report = report,
            )
        } else {
            executeTranslate(
                parsed = parsed,
                read = read,
                write = write,
                writeForce = writeForce,
                exists = exists,
                isDirectory = isDirectory,
                collectRustFiles = effectiveCollectRustFiles,
                report = report,
                resolveGradleProperties = resolveGradleProperties,
                resolvePackageProperties = resolvePackageProperties,
                resolveCrateMacroSources = resolveCrateMacroSources,
                resolveCrateFlatFileModules = resolveCrateFlatFileModules,
                resolveCrateRawAbiMetadata = resolveCrateRawAbiMetadata,
            )
        }
    } finally {
        TranslationSession.timings = prevTimings
        TranslationSession.rawAbiMode = prevRawAbi
        TranslationSession.finalStructs = prevFinalStructs
        TranslationSession.splitModuleRoots = prevSplitModRoots
        TranslationSession.win32DynamicFfi = prevWin32DynFfi
        TranslationSession.cDynamicFfi = prevCDynFfi
        TranslationSession.cfgTargetProfile = prevCfgProfile
        TranslationSession.emittedInt128Support = prevEmitted128
    }
}

private fun parseCliOptions(args: Array<String>): ParsedCliOptions {
    require(args.isNotEmpty()) {
        "usage: rust-kotlin inspect input.rs | translate [options] input.rs [output.kt]; try --help"
    }

    var showHelp = false
    var showVersion = false
    var recursive = false
    var outputTarget: String? = null
    var inPlace = false
    var toStdout = false
    var dryRun = false
    var forceOverwrite = false
    var skipExisting = false
    var gradlePropertiesPath: String? = null
    var packagePropertiesPath: String? = null
    var packageOverride: String? = null
    var namespaceOverride: String? = null
    var groupOverride: String? = null
    val crateNamespaces = linkedMapOf<String, String>()
    var sourceRoot: String? = null
    var portLintDirective = "source"
    var rawAbiMode = false
    var finalStructs = false
    var splitModuleRoots = false
    var win32DynamicFfi = false
    var cDynamicFfi = false
    var targetPointerWidth = 64
    var targetEndian = "little"
    var targetArch: String? = null
    var targetOs: String? = null
    var targetEnv: String? = null
    var targetVendor: String? = null
    val targetFamilies = linkedSetOf<String>()
    val enabledFeatures = linkedSetOf<String>()
    val enabledCfgFlags = linkedSetOf<String>()
    var cfgClosedWorld = false
    var explicitTargetSelection = false
    var timings = false
    var quiet = false
    val positionals = mutableListOf<String>()

    var idx = 0
    var onlyPositionals = false
    while (idx < args.size) {
        val raw = args[idx]
        if (onlyPositionals || !raw.startsWith("-") || raw == "-") {
            positionals += raw
            idx++
            continue
        }
        if (raw == "--") {
            onlyPositionals = true
            idx++
            continue
        }

        fun nextValue(flagName: String): String {
            idx++
            require(idx < args.size) { "option $flagName requires a value" }
            return args[idx]
        }

        val eqIdx = raw.indexOf('=')
        val optName = if (eqIdx > 0 && raw.startsWith("--")) raw.substring(0, eqIdx) else raw
        val inlineVal = if (eqIdx > 0 && raw.startsWith("--")) raw.substring(eqIdx + 1) else null

        fun optValue(): String = inlineVal ?: nextValue(optName)

        when (optName) {
            "-h", "--help" -> showHelp = true
            "-v", "--version" -> showVersion = true
            "-r", "--recursive" -> {
                require(inlineVal == null) { "$optName does not take a value" }
                recursive = true
            }
            "-o", "--output" -> outputTarget = optValue()
            "--in-place" -> {
                require(inlineVal == null) { "$optName does not take a value" }
                inPlace = true
            }
            "--stdout" -> {
                require(inlineVal == null) { "$optName does not take a value" }
                toStdout = true
            }
            "--dry-run" -> {
                require(inlineVal == null) { "$optName does not take a value" }
                dryRun = true
            }
            "-f", "--force", "--overwrite" -> {
                require(inlineVal == null) { "$optName does not take a value" }
                forceOverwrite = true
            }
            "--skip-existing" -> {
                require(inlineVal == null) { "$optName does not take a value" }
                skipExisting = true
            }
            "-g", "--gradle-properties" -> gradlePropertiesPath = optValue()
            "--package-properties" -> packagePropertiesPath = optValue()
            "-p", "--package" -> packageOverride = optValue()
            "-n", "--namespace" -> namespaceOverride = optValue()
            "--group" -> groupOverride = optValue()
            "-c", "--crate-namespace" -> {
                val spec = optValue()
                val sep = spec.indexOf('=')
                require(sep > 0 && sep < spec.lastIndex) {
                    "invalid --crate-namespace '$spec'; expected <crate>=<kotlin.package>"
                }
                val crateKey = rustModuleIdentToKotlin(spec.substring(0, sep).trim())
                val cratePkg = spec.substring(sep + 1).trim()
                require(crateKey.isNotEmpty() && cratePkg.isNotEmpty()) {
                    "invalid --crate-namespace '$spec'; expected <crate>=<kotlin.package>"
                }
                crateNamespaces[crateKey] = cratePkg
            }
            "--source-root" -> sourceRoot = optValue()
            "--port-lint" -> {
                val mode = optValue().trim().lowercase()
                require(mode == "source" || mode == "tests" || mode == "none") {
                    "invalid --port-lint '$mode'; expected source, tests, or none"
                }
                portLintDirective = mode
            }
            "--raw-abi" -> {
                require(inlineVal == null) { "$optName does not take a value" }
                rawAbiMode = true
            }
            "--final-structs" -> {
                require(inlineVal == null) { "$optName does not take a value" }
                finalStructs = true
            }
            "--split-module-roots" -> {
                require(inlineVal == null) { "$optName does not take a value" }
                splitModuleRoots = true
            }
            "--win32-dynamic-ffi" -> {
                require(inlineVal == null) { "$optName does not take a value" }
                win32DynamicFfi = true
            }
            "--c-dynamic-ffi" -> {
                require(inlineVal == null) { "$optName does not take a value" }
                cDynamicFfi = true
            }
            "--target-pointer-width" -> {
                val width = optValue().trim().toIntOrNull()
                require(width == 16 || width == 32 || width == 64) {
                    "invalid --target-pointer-width; expected 16, 32, or 64"
                }
                targetPointerWidth = width
                explicitTargetSelection = true
            }
            "--target-endian" -> {
                val endian = optValue().trim().lowercase()
                require(endian == "little" || endian == "big") {
                    "invalid --target-endian '$endian'; expected little or big"
                }
                targetEndian = endian
                explicitTargetSelection = true
            }
            "--target-arch" -> {
                targetArch = optValue().trim().takeIf { it.isNotEmpty() }
                explicitTargetSelection = true
            }
            "--target-os" -> {
                targetOs = optValue().trim().takeIf { it.isNotEmpty() }
                explicitTargetSelection = true
            }
            "--target-env" -> {
                targetEnv = optValue().trim().takeIf { it.isNotEmpty() }
                explicitTargetSelection = true
            }
            "--target-vendor" -> {
                targetVendor = optValue().trim().takeIf { it.isNotEmpty() }
                explicitTargetSelection = true
            }
            "--target-family" -> {
                val fam = optValue().trim()
                if (fam.isNotEmpty()) targetFamilies += fam
                explicitTargetSelection = true
            }
            "--feature" -> {
                val feat = optValue().trim()
                if (feat.isNotEmpty()) enabledFeatures += feat
            }
            "--cfg" -> {
                val spec = optValue().trim()
                require(spec.isNotEmpty()) { "--cfg requires a non-empty predicate" }
                val kvSep = spec.indexOf('=')
                if (kvSep > 0) {
                    val cfgKey = normalizeCliCfgKey(spec.substring(0, kvSep))
                    require(cfgKey.isNotEmpty()) { "--cfg requires a non-empty key before '='" }
                    val cfgVal = unquoteCliCfgValue(spec.substring(kvSep + 1))
                    when (cfgKey) {
                        "target_pointer_width" -> {
                            val w = cfgVal.toIntOrNull()
                            require(w == 16 || w == 32 || w == 64) {
                                "invalid --cfg target_pointer_width='$cfgVal'"
                            }
                            targetPointerWidth = w
                            explicitTargetSelection = true
                        }
                        "target_endian" -> {
                            require(cfgVal == "little" || cfgVal == "big") {
                                "invalid --cfg target_endian='$cfgVal'"
                            }
                            targetEndian = cfgVal
                            explicitTargetSelection = true
                        }
                        "target_arch" -> {
                            targetArch = cfgVal
                            explicitTargetSelection = true
                        }
                        "target_os" -> {
                            targetOs = cfgVal
                            explicitTargetSelection = true
                        }
                        "target_env" -> {
                            targetEnv = cfgVal
                            explicitTargetSelection = true
                        }
                        "target_vendor" -> {
                            targetVendor = cfgVal
                            explicitTargetSelection = true
                        }
                        "target_family" -> {
                            if (cfgVal.isNotEmpty()) targetFamilies += cfgVal
                            explicitTargetSelection = true
                        }
                        "feature" -> {
                            if (cfgVal.isNotEmpty()) enabledFeatures += cfgVal
                        }
                        else -> enabledCfgFlags += "$cfgKey=$cfgVal"
                    }
                } else {
                    val normFlag = normalizeCliCfgKey(spec)
                    if (normFlag.isNotEmpty()) enabledCfgFlags += normFlag
                }
            }
            "--cfg-closed-world" -> {
                require(inlineVal == null) { "$optName does not take a value" }
                cfgClosedWorld = true
            }
            "--timings" -> {
                require(inlineVal == null) { "$optName does not take a value" }
                timings = true
            }
            "-q", "--quiet" -> {
                require(inlineVal == null) { "$optName does not take a value" }
                quiet = true
            }
            else -> throw IllegalArgumentException("unknown option: $raw")
        }
        idx++
    }

    if (showHelp) {
        return ParsedCliOptions(action = CliActionKind.HELP)
    }
    if (showVersion) {
        return ParsedCliOptions(action = CliActionKind.VERSION)
    }

    val outputModesCount = listOf(outputTarget != null, inPlace, toStdout).count { it }
    require(outputModesCount <= 1) {
        "--output, --in-place, and --stdout are mutually exclusive"
    }
    require(!(forceOverwrite && skipExisting)) {
        "--force and --skip-existing are mutually exclusive"
    }
    require(positionals.isNotEmpty()) {
        "usage: rust-kotlin inspect input.rs | translate [options] input.rs [output.kt]; try --help"
    }

    val builtCfgProfile =
        CfgTargetProfile(
            pointerWidth = targetPointerWidth,
            targetEndian = targetEndian,
            targetArch = targetArch,
            targetOs = targetOs,
            targetEnv = targetEnv,
            targetVendor = targetVendor,
            targetFamilies =
                when {
                    targetFamilies.isNotEmpty() -> targetFamilies
                    cfgClosedWorld -> emptySet()
                    else -> null
                },
            enabledFeatures =
                when {
                    enabledFeatures.isNotEmpty() -> enabledFeatures
                    cfgClosedWorld -> emptySet()
                    else -> null
                },
            enabledCfgFlags = enabledCfgFlags,
            unknownPolicy = if (cfgClosedWorld) UnknownCfgPolicy.CLOSED_WORLD_FALSE else UnknownCfgPolicy.OPEN_WORLD_NULL,
            useRelativeDirPlatformHints = recursive && !explicitTargetSelection && !cfgClosedWorld,
        )

    val first = positionals[0]
    if (first == "inspect") {
        val inputs = positionals.drop(1)
        require(inputs.isNotEmpty()) {
            "inspect requires an input .rs file (or directory with -r)"
        }
        require(outputModesCount == 0 && !dryRun && !forceOverwrite && !skipExisting) {
            "inspect does not accept output write options"
        }
        if (!recursive) {
            require(inputs.size == 1 && inputs[0].endsWith(".rs", ignoreCase = true)) {
                "inspect requires a single .rs file (pass -r for directories)"
            }
        }
        return ParsedCliOptions(
            action = CliActionKind.INSPECT,
            inputs = inputs,
            recursive = recursive,
            rawAbiMode = rawAbiMode,
            finalStructs = finalStructs,
            splitModuleRoots = splitModuleRoots,
            win32DynamicFfi = win32DynamicFfi,
            cDynamicFfi = cDynamicFfi,
            cfgTargetProfile = builtCfgProfile,
            timings = timings,
            quiet = quiet,
        )
    }

    val translatePositionals =
        if (first == "translate") {
            positionals.drop(1)
        } else {
            require(outputModesCount > 0 || dryRun) {
                "unknown command '$first'; expected 'inspect' or 'translate' (or pass -o / --in-place / --stdout / --dry-run)"
            }
            positionals
        }

    if (outputModesCount == 0 && !dryRun) {
        require(!recursive && translatePositionals.size == 2) {
            "usage: rust-kotlin inspect input.rs | translate input.rs output.kt; one input, one output (or pass -o / --in-place / --stdout / --dry-run)"
        }
        val inputPath = translatePositionals[0]
        val outputPath = translatePositionals[1]
        validateSingleRustInputPath(inputPath)
        validateExplicitKotlinOutputPath(inputPath, outputPath)
        return ParsedCliOptions(
            action = CliActionKind.TRANSLATE,
            inputs = listOf(inputPath),
            classicOutputPath = outputPath,
            forceOverwrite = forceOverwrite,
            skipExisting = skipExisting,
            gradlePropertiesPath = gradlePropertiesPath,
            packagePropertiesPath = packagePropertiesPath,
            packageOverride = packageOverride,
            namespaceOverride = namespaceOverride,
            groupOverride = groupOverride,
            crateNamespaces = crateNamespaces,
            sourceRoot = sourceRoot,
            portLintDirective = portLintDirective,
            rawAbiMode = rawAbiMode,
            finalStructs = finalStructs,
            splitModuleRoots = splitModuleRoots,
            win32DynamicFfi = win32DynamicFfi,
            cDynamicFfi = cDynamicFfi,
            cfgTargetProfile = builtCfgProfile,
            timings = timings,
            quiet = quiet,
        )
    }

    require(translatePositionals.isNotEmpty()) {
        "translate requires at least one input .rs file (or directory with -r)"
    }
    if (!recursive) {
        for (inp in translatePositionals) {
            if (splitModuleRoots && outputTarget?.endsWith(".kt", ignoreCase = true) != true) {
                require(inp.endsWith(".rs", ignoreCase = true)) {
                    "input must be a Rust .rs file: $inp (pass -r for directories)"
                }
            } else {
                validateSingleRustInputPath(inp)
            }
        }
    }
    if (outputTarget != null && outputTarget.endsWith(".kt", ignoreCase = true)) {
        require(!recursive && translatePositionals.size == 1) {
            "-o <file.kt> requires a single .rs input file; pass an output directory for batch/recursive translation"
        }
        validateSingleRustInputPath(translatePositionals[0])
        validateExplicitKotlinOutputPath(translatePositionals[0], outputTarget)
    }

    return ParsedCliOptions(
        action = CliActionKind.TRANSLATE,
        inputs = translatePositionals,
        outputTarget = outputTarget,
        recursive = recursive,
        inPlace = inPlace,
        toStdout = toStdout,
        dryRun = dryRun,
        forceOverwrite = forceOverwrite,
        skipExisting = skipExisting,
        gradlePropertiesPath = gradlePropertiesPath,
        packagePropertiesPath = packagePropertiesPath,
        packageOverride = packageOverride,
        namespaceOverride = namespaceOverride,
        groupOverride = groupOverride,
        crateNamespaces = crateNamespaces,
        sourceRoot = sourceRoot,
        portLintDirective = portLintDirective,
        rawAbiMode = rawAbiMode,
        finalStructs = finalStructs,
        splitModuleRoots = splitModuleRoots,
        win32DynamicFfi = win32DynamicFfi,
        cDynamicFfi = cDynamicFfi,
        cfgTargetProfile = builtCfgProfile,
        timings = timings,
        quiet = quiet,
    )
}

private fun validateSingleRustInputPath(inputPath: String) {
    val fileName = inputPath.replace('\\', '/').substringAfterLast('/')
    require(inputPath.endsWith(".rs", ignoreCase = true)) {
        "input must be a Rust .rs file: $inputPath (pass -r for directories)"
    }
    require(!isRustModuleRootFileName(fileName)) {
        "crate/module root files ($fileName) must never be emitted as Lib.kt or Mod.kt"
    }
}

private fun validateExplicitKotlinOutputPath(inputPath: String, outputPath: String) {
    val outputBaseName = outputPath.replace('\\', '/').substringAfterLast('/')
    require(outputPath.endsWith(".kt") && outputPath != inputPath) {
        "output must be a distinct Kotlin .kt file, not a directory"
    }
    require(!outputBaseName.equals("Lib.kt", ignoreCase = true) && !outputBaseName.equals("Mod.kt", ignoreCase = true)) {
        "crate/module root files must never be emitted as Lib.kt or Mod.kt"
    }
}

private fun discoverInputs(
    rawInputs: List<String>,
    recursive: Boolean,
    splitModuleRoots: Boolean,
    isDirectory: (String) -> Boolean,
    collectRustFiles: (List<String>) -> List<String>,
): List<DiscoveredRustInput> {
    if (!recursive) {
        return rawInputs.map { inp ->
            if (!splitModuleRoots) {
                validateSingleRustInputPath(inp)
            }
            val normInp = inp.replace('\\', '/')
            val parent = normInp.substringBeforeLast('/', missingDelimiterValue = "")
            val lastSeg = parent.substringAfterLast('/', missingDelimiterValue = parent)
            val relDir =
                if (splitModuleRoots && isRustModuleRootFileName(normInp.substringAfterLast('/')) && lastSeg != "src" && lastSeg.isNotEmpty()) {
                    lastSeg
                } else {
                    ""
                }
            DiscoveredRustInput(inputPath = inp, relativeDir = relDir, discoveryRoot = null)
        }
    }
    val discovered = mutableListOf<DiscoveredRustInput>()
    val seenPaths = hashSetOf<String>()
    for (rawInput in rawInputs) {
        if (rawInput.endsWith(".rs", ignoreCase = true) && !isDirectory(rawInput)) {
            val fileName = rawInput.replace('\\', '/').substringAfterLast('/')
            if ((splitModuleRoots || !isRustModuleRootFileName(fileName)) && seenPaths.add(rawInput.replace('\\', '/'))) {
                discovered += DiscoveredRustInput(inputPath = rawInput, relativeDir = "", discoveryRoot = null)
            }
        } else {
            val normRoot = rawInput.replace('\\', '/').trimEnd('/')
            val collected = collectRustFiles(listOf(rawInput))
            for (filePath in collected) {
                val normFile = filePath.replace('\\', '/')
                if (!seenPaths.add(normFile)) continue
                val relPath =
                    if (normFile.startsWith("$normRoot/")) {
                        normFile.removePrefix("$normRoot/")
                    } else {
                        normFile.substringAfterLast('/')
                    }
                val relDir = relPath.substringBeforeLast('/', missingDelimiterValue = "")
                discovered +=
                    DiscoveredRustInput(
                        inputPath = filePath,
                        relativeDir = relDir,
                        discoveryRoot = normRoot,
                    )
            }
        }
    }
    require(discovered.isNotEmpty()) {
        "no transpilable Rust (.rs) files found in ${rawInputs.joinToString(", ")}"
    }
    return discovered
}

private fun executeInspect(
    parsed: ParsedCliOptions,
    read: (String) -> String,
    isDirectory: (String) -> Boolean,
    collectRustFiles: (List<String>) -> List<String>,
    report: (String) -> Unit,
) {
    val discovered = discoverInputs(parsed.inputs, parsed.recursive, parsed.splitModuleRoots, isDirectory, collectRustFiles)
    val multiFile = discovered.size > 1
    for (entry in discovered) {
        val recognized = recognizeRust(read(entry.inputPath))
        val tokenCount = recognized.tokens.clone().count()
        val itemCount = recognized.file.items.size
        val prefix = if (multiFile) "${entry.inputPath}: " else ""
        report("${prefix}lexer=proc-macro2-kotlin:0.1.8 parser=syn-kotlin:0.5.0 tokens=$tokenCount items=$itemCount")
        if (!parsed.quiet) {
            recognized.file.items.forEachIndexed { index, item ->
                report("$prefix$index ${item::class.simpleName}")
            }
        }
    }
}

private fun computeEffectiveRelativePackageSegments(
    entry: DiscoveredRustInput,
    parsed: ParsedCliOptions,
    sharedInlineModuleRelFiles: Set<String> = emptySet(),
): List<String> {
    val dirSegs =
        if (entry.relativeDir.isEmpty()) {
            emptyList()
        } else {
            entry.relativeDir
                .split('/')
                .filter { it.isNotEmpty() }
                .map { seg -> rustModuleIdentToKotlin(seg.replace('-', '_')) }
        }
    if (!parsed.splitModuleRoots) return dirSegs
    val rustFileName = entry.inputPath.replace('\\', '/').substringAfterLast('/')
    if (isRustModuleRootFileName(rustFileName)) return dirSegs
    val relFile = if (entry.relativeDir.isEmpty()) rustFileName else "${entry.relativeDir}/$rustFileName"
    if (relFile in sharedInlineModuleRelFiles) return dirSegs
    val stem = rustFileName.removeSuffix(".rs").removeSuffix(".RS")
    if (stem.isEmpty()) return dirSegs
    return dirSegs + rustModuleIdentToKotlin(stem.replace('-', '_'))
}

private fun computeModuleRootOutputDirectory(
    entry: DiscoveredRustInput,
    parsed: ParsedCliOptions,
    sharedInlineModuleRelFiles: Set<String> = emptySet(),
): String? {
    if (parsed.toStdout) return null
    val normInput = entry.inputPath.replace('\\', '/')
    if (parsed.inPlace || (parsed.outputTarget == null && parsed.dryRun)) {
        return normInput.substringBeforeLast('/', missingDelimiterValue = "")
    }
    val outTarget = requireNotNull(parsed.outputTarget)
    val normOutRoot = outTarget.replace('\\', '/').trimEnd('/')
    val outRelDir =
        if (entry.relativeDir.isEmpty()) {
            ""
        } else if (normOutRoot.contains("/kotlin/") || parsed.splitModuleRoots) {
            computeEffectiveRelativePackageSegments(entry, parsed, sharedInlineModuleRelFiles).joinToString("/")
        } else {
            entry.relativeDir
        }
    return if (outRelDir.isEmpty()) normOutRoot else "$normOutRoot/$outRelDir"
}

private fun computeBatchOutputPath(
    entry: DiscoveredRustInput,
    parsed: ParsedCliOptions,
    sharedInlineModuleRelFiles: Set<String> = emptySet(),
): String? {
    if (parsed.toStdout) return null
    if (parsed.classicOutputPath != null) return parsed.classicOutputPath

    val normInput = entry.inputPath.replace('\\', '/')
    val rustFileName = normInput.substringAfterLast('/')
    val kotlinFileName = computeTargetKotlinFileName(rustFileName)

    if (parsed.inPlace || (parsed.outputTarget == null && parsed.dryRun)) {
        val parentDir = normInput.substringBeforeLast('/', missingDelimiterValue = "")
        return if (parentDir.isEmpty()) kotlinFileName else "$parentDir/$kotlinFileName"
    }

    val outTarget = requireNotNull(parsed.outputTarget)
    if (outTarget.endsWith(".kt", ignoreCase = true) && !parsed.recursive && parsed.inputs.size == 1) {
        return outTarget
    }

    val normOutRoot = outTarget.replace('\\', '/').trimEnd('/')
    val outRelDir =
        if (normOutRoot.contains("/kotlin/") || parsed.splitModuleRoots) {
            computeEffectiveRelativePackageSegments(entry, parsed, sharedInlineModuleRelFiles).joinToString("/")
        } else {
            entry.relativeDir
        }
    return if (outRelDir.isEmpty()) {
        "$normOutRoot/$kotlinFileName"
    } else {
        "$normOutRoot/$outRelDir/$kotlinFileName"
    }
}

private fun computeModuleRootPascalName(
    entry: DiscoveredRustInput,
    finalNamespace: String?,
): String {
    val lastDirSeg =
        entry.relativeDir
            .split('/')
            .lastOrNull { it.isNotEmpty() }
    if (lastDirSeg != null) {
        return KotlinNamingPolicy.Default.toTypeIdentifier(lastDirSeg.replace('-', '_'), allowRawAbi = false)
    }
    val nsTail = finalNamespace?.substringAfterLast('.')?.takeIf { it.isNotEmpty() }
    if (nsTail != null) {
        return KotlinNamingPolicy.Default.toTypeIdentifier(nsTail, allowRawAbi = false)
    }
    return "Crate"
}

private fun executeTranslate(
    parsed: ParsedCliOptions,
    read: (String) -> String,
    write: (String, String) -> Unit,
    writeForce: (String, String) -> Unit,
    exists: (String) -> Boolean,
    isDirectory: (String) -> Boolean,
    collectRustFiles: (List<String>) -> List<String>,
    report: (String) -> Unit,
    resolveGradleProperties: (String?) -> GradleProjectProperties?,
    resolvePackageProperties: (String?) -> FolderPackageProperties?,
    resolveCrateMacroSources: (String?) -> List<String>,
    resolveCrateFlatFileModules: (String?, String?) -> Set<String>,
    resolveCrateRawAbiMetadata: (String?, String?, Boolean, Boolean) -> CrateRawAbiMetadata,
) {
    val explicitGradleProps = parsed.gradlePropertiesPath?.let { parseGradleProperties(read(it)) }
    val explicitPkgProps = parsed.packagePropertiesPath?.let { parsePackageProperties(read(it)) }
    val discovered = discoverInputs(parsed.inputs, parsed.recursive, parsed.splitModuleRoots, isDirectory, collectRustFiles)
    var resolvedProjectNamespaceForScaffold: String? = parsed.namespaceOverride ?: explicitGradleProps?.projectNamespace
    val isBatchOutput =
        !parsed.toStdout &&
            parsed.classicOutputPath == null &&
            parsed.outputTarget != null &&
            !parsed.outputTarget.endsWith(".kt", ignoreCase = true) &&
            (parsed.recursive || parsed.splitModuleRoots || discovered.size > 1)
    val shouldScanCrateMetadata =
        parsed.rawAbiMode || parsed.splitModuleRoots || parsed.finalStructs || parsed.win32DynamicFfi || parsed.cDynamicFfi
    var cachedMetadataKey: String? = null
    var cachedMetadata = CrateRawAbiMetadata()

    for (entry in discovered) {
        val effectiveRoot = parsed.sourceRoot ?: entry.discoveryRoot
        val metaKey = "${effectiveRoot ?: entry.inputPath}|${parsed.rawAbiMode}|${parsed.splitModuleRoots}|${parsed.cfgTargetProfile}"
        val crateMetadata =
            if (shouldScanCrateMetadata) {
                if (metaKey == cachedMetadataKey) {
                    cachedMetadata
                } else {
                    val resolved =
                        resolveCrateRawAbiMetadata(
                            entry.inputPath,
                            effectiveRoot,
                            parsed.rawAbiMode,
                            parsed.splitModuleRoots,
                        )
                    if (effectiveRoot != null) {
                        cachedMetadataKey = metaKey
                        cachedMetadata = resolved
                    }
                    resolved
                }
            } else {
                CrateRawAbiMetadata()
            }
        val sharedInlineFiles = crateMetadata.sharedInlineModuleRelFiles
        val rustFileName = entry.inputPath.replace('\\', '/').substringAfterLast('/')
        val isModRoot = isRustModuleRootFileName(rustFileName)
        val modRootOutDir =
            if (isModRoot && parsed.splitModuleRoots) {
                computeModuleRootOutputDirectory(entry, parsed, sharedInlineFiles)
            } else {
                null
            }
        val outputPath =
            if (isModRoot && parsed.splitModuleRoots) {
                modRootOutDir?.let { if (it.isEmpty()) "__module__.kt" else "$it/__module__.kt" }
            } else {
                computeBatchOutputPath(entry, parsed, sharedInlineFiles)
            }
        if (!isModRoot && outputPath != null && parsed.skipExisting && !parsed.dryRun && exists(outputPath)) {
            if (!parsed.quiet) {
                report("skip existing $outputPath")
            }
            continue
        }

        val source = read(entry.inputPath)
        val inferredHeader =
            inferFileHeaderContextWithResolvers(
                inputPath = entry.inputPath,
                outputPath = outputPath,
                gradleProperties = explicitGradleProps,
                packageProperties = explicitPkgProps,
                sourceRoot = parsed.sourceRoot,
                fallbackSourceRoot = entry.discoveryRoot,
                portLintDirective = if (isModRoot) "none" else parsed.portLintDirective,
                resolveGradleProperties = resolveGradleProperties,
                resolvePackageProperties = resolvePackageProperties,
                resolveCrateMacroSources = resolveCrateMacroSources,
                resolveCrateFlatFileModules = resolveCrateFlatFileModules,
            )

        val finalGroup = parsed.groupOverride ?: inferredHeader.projectGroup
        val finalNamespace = parsed.namespaceOverride ?: inferredHeader.projectNamespace
        if (resolvedProjectNamespaceForScaffold == null && finalNamespace != null) {
            resolvedProjectNamespaceForScaffold = finalNamespace
        }
        val finalCrateNamespaces =
            if (parsed.crateNamespaces.isEmpty()) {
                inferredHeader.crateNamespaces
            } else {
                inferredHeader.crateNamespaces + parsed.crateNamespaces
            }
        val effectiveSegs = computeEffectiveRelativePackageSegments(entry, parsed, sharedInlineFiles)
        val finalPackage =
            when {
                parsed.packageOverride != null -> {
                    val basePkg = parsed.packageOverride.trim().removeSuffix(".")
                    val subPkg = effectiveSegs.joinToString(".")
                    if (subPkg.isEmpty()) basePkg else "$basePkg.$subPkg"
                }
                parsed.namespaceOverride != null ->
                    outputPath?.let { inferKotlinPackageName(it) }
                        ?: run {
                            val basePkg = parsed.namespaceOverride.trim().removeSuffix(".")
                            val subPkg = effectiveSegs.joinToString(".")
                            if (subPkg.isEmpty()) basePkg else "$basePkg.$subPkg"
                        }
                inferredHeader.packageName != null -> inferredHeader.packageName
                finalNamespace != null -> {
                    val basePkg = finalNamespace.trim().removeSuffix(".")
                    val subPkg = effectiveSegs.joinToString(".")
                    if (subPkg.isEmpty()) basePkg else "$basePkg.$subPkg"
                }
                else -> null
            }

        val effectiveFfiMeta =
            if (shouldScanCrateMetadata) {
                crateMetadata
            } else if (effectiveRoot != null && ("extern " in source || "c!" in source || "cpp!" in source)) {
                if (metaKey == cachedMetadataKey) {
                    cachedMetadata
                } else {
                    val resolved =
                        resolveCrateRawAbiMetadata(
                            entry.inputPath,
                            effectiveRoot,
                            parsed.rawAbiMode,
                            parsed.splitModuleRoots,
                        )
                    cachedMetadataKey = metaKey
                    cachedMetadata = resolved
                    resolved
                }
            } else {
                null
            }

        val headerContext =
            FileHeaderContext(
                portLintSourcePath = if (isModRoot) null else inferredHeader.portLintSourcePath,
                packageName = finalPackage,
                projectGroup = finalGroup,
                projectNamespace = finalNamespace,
                crateNamespaces = finalCrateNamespaces,
                portLintDirective = if (isModRoot) "none" else parsed.portLintDirective,
                crateMacroSources = inferredHeader.crateMacroSources,
                crateFlatFileModules = inferredHeader.crateFlatFileModules,
                rawAbiMode = parsed.rawAbiMode,
                finalClasses = parsed.finalStructs,
                win32DynamicFfi = parsed.win32DynamicFfi,
                cDynamicFfi = parsed.cDynamicFfi,
                selfCrateAliases = crateMetadata.selfCrateAliases,
                includedSubdirModuleMap = crateMetadata.includedSubdirModuleMap,
                crateTypeAliasDefaults = crateMetadata.typeAliasDefaults,
                crateOptionFnTypes = crateMetadata.optionFnTypes,
                crateNumericTypeKinds = effectiveFfiMeta?.numericTypeKinds.orEmpty(),
                cratePointerTypeAliases = effectiveFfiMeta?.pointerTypeAliases.orEmpty(),
                crateStructFieldTypes = crateMetadata.structFieldTypes,
                crateFnReturnTypes = crateMetadata.fnReturnTypes,
                crateSymbolSubpackages = crateMetadata.symbolSubpackages,
                crateSubpackageRawLeafNames = crateMetadata.subpackageRawLeafNames,
                crateValueParamPackTemplates = crateMetadata.valueParamPackTemplates,
                crateValueReturnUnpackTemplates = crateMetadata.valueReturnUnpackTemplates,
                crateFnPtrInvokeTemplates = crateMetadata.fnPtrInvokeTemplates,
                crateFnParamNumericKinds = crateMetadata.fnParamNumericKinds,
                cfgTargetProfile = parsed.cfgTargetProfile,
                isBatchCrateOutput = isBatchOutput,
                currentRelativeSourceDir = entry.relativeDir.ifEmpty { null },
            )

        if (isModRoot && parsed.splitModuleRoots) {
            val modulePascalName = computeModuleRootPascalName(entry, finalNamespace)
            val chunks = translateModuleRootIntoFocusedChunks(source, modulePascalName, headerContext)
            if (chunks.isEmpty()) {
                continue
            }
            for (chunk in chunks) {
                val chunkPath =
                    modRootOutDir?.let { dir ->
                        if (dir.isEmpty()) chunk.fileName else "$dir/${chunk.fileName}"
                    }
                when {
                    parsed.toStdout -> {
                        report("// --- ${chunk.fileName} ---")
                        report(chunk.text.trimEnd('\n'))
                    }
                    parsed.dryRun -> {
                        if (!parsed.quiet) {
                            report("dry-run: ${entry.inputPath} -> $chunkPath; ${chunk.itemCount} items translated")
                        }
                    }
                    else -> {
                        val targetOut = requireNotNull(chunkPath)
                        if (parsed.skipExisting && exists(targetOut)) {
                            if (!parsed.quiet) {
                                report("skip existing $targetOut")
                            }
                            continue
                        }
                        if (parsed.forceOverwrite) {
                            writeForce(targetOut, chunk.text)
                        } else {
                            write(targetOut, chunk.text)
                        }
                        for (companion in chunk.companionFiles) {
                            val companionOutPath = resolveCompanionOutputPath(targetOut, companion)
                            if (parsed.skipExisting && exists(companionOutPath)) continue
                            if (parsed.forceOverwrite) {
                                writeForce(companionOutPath, companion.content)
                            } else {
                                write(companionOutPath, companion.content)
                            }
                        }
                        if (!parsed.quiet) {
                            report("${entry.inputPath} -> $targetOut; ${chunk.itemCount} items translated")
                        }
                    }
                }
            }
            continue
        }

        val result = renderSingleKotlinFile(source, headerContext)
        if (parsed.splitModuleRoots &&
            !parsed.toStdout &&
            !hasEmittedKotlinDeclarations(result.text) &&
            result.companionFiles.isEmpty()
        ) {
            continue
        }
        when {
            parsed.toStdout -> {
                report(result.text.trimEnd('\n'))
            }
            parsed.dryRun -> {
                if (!parsed.quiet) {
                    report("dry-run: ${entry.inputPath} -> $outputPath; ${result.summary}")
                }
            }
            else -> {
                val targetOut = requireNotNull(outputPath)
                if (parsed.forceOverwrite) {
                    writeForce(targetOut, result.text)
                } else {
                    write(targetOut, result.text)
                }
                for (companion in result.companionFiles) {
                    val companionOutPath = resolveCompanionOutputPath(targetOut, companion)
                    if (parsed.skipExisting && exists(companionOutPath)) continue
                    if (parsed.forceOverwrite) {
                        writeForce(companionOutPath, companion.content)
                    } else {
                        write(companionOutPath, companion.content)
                    }
                }
                if (!parsed.quiet) {
                    report("${entry.inputPath} -> $targetOut; ${result.summary}")
                }
            }
        }
        result.diagnostics.forEach(report)
    }

    if (isBatchOutput && TranslationSession.emittedInt128Support && !parsed.toStdout) {
        val normOutRoot = requireNotNull(parsed.outputTarget).replace('\\', '/').trimEnd('/')
        val supportOutPath = "$normOutRoot/Int128Support.kt"
        val supportPkg =
            resolvedProjectNamespaceForScaffold
                ?: inferKotlinPackageName(supportOutPath)
                ?: parsed.packageOverride
        val supportContent = renderSynthesizedInt128SupportFile(supportPkg)
        if (parsed.dryRun) {
            if (!parsed.quiet) {
                report("dry-run: int128-support -> $supportOutPath")
            }
        } else if (!(parsed.skipExisting && exists(supportOutPath))) {
            if (parsed.forceOverwrite) {
                writeForce(supportOutPath, supportContent)
            } else {
                write(supportOutPath, supportContent)
            }
            if (!parsed.quiet) {
                report("int128-support -> $supportOutPath")
            }
        }
    }

    if (parsed.win32DynamicFfi && !parsed.toStdout) {
        val outTarget = parsed.outputTarget?.replace('\\', '/')?.trimEnd('/')
        if (outTarget != null) {
            val commonMarker = "/src/common/commonMain/kotlin/"
            val markerIdx = outTarget.indexOf(commonMarker)
            val projectRoot =
                when {
                    markerIdx > 0 -> outTarget.substring(0, markerIdx)
                    outTarget.startsWith("src/common/commonMain/kotlin/") -> "."
                    else -> outTarget
                }
            val inferredNsFromPath =
                when {
                    markerIdx >= 0 ->
                        outTarget
                            .substring(markerIdx + commonMarker.length)
                            .trim('/')
                            .replace('/', '.')
                            .takeIf { it.isNotEmpty() }
                    outTarget.startsWith("src/common/commonMain/kotlin/") ->
                        outTarget
                            .removePrefix("src/common/commonMain/kotlin/")
                            .trim('/')
                            .replace('/', '.')
                            .takeIf { it.isNotEmpty() }
                    else -> null
                }
            val scaffoldNs =
                resolvedProjectNamespaceForScaffold
                    ?: inferredNsFromPath
                    ?: parsed.packageOverride
                    ?: "io.github.kotlinmania.windowssys"
            val ffiName =
                scaffoldNs
                    .substringAfterLast('.')
                    .takeIf { it.isNotEmpty() }
                    ?.replaceFirstChar { it.uppercase() }
                    ?: "Windowssys"
            val scaffoldFiles = emitWin32DynamicFfiScaffold(projectNamespace = scaffoldNs, ffiName = ffiName)
            for (sf in scaffoldFiles) {
                val fullPath =
                    if (projectRoot == "." || projectRoot.isEmpty()) {
                        sf.projectRelativePath
                    } else {
                        "$projectRoot/${sf.projectRelativePath}"
                    }
                if (parsed.dryRun) {
                    if (!parsed.quiet) {
                        report("dry-run: win32-ffi-scaffold -> $fullPath")
                    }
                } else {
                    if (parsed.skipExisting && exists(fullPath)) continue
                    if (parsed.forceOverwrite) {
                        writeForce(fullPath, sf.content)
                    } else {
                        write(fullPath, sf.content)
                    }
                    if (!parsed.quiet) {
                        report("win32-ffi-scaffold -> $fullPath")
                    }
                }
            }
        }
    }

    if (parsed.cDynamicFfi && !parsed.toStdout) {
        val outTarget = parsed.outputTarget?.replace('\\', '/')?.trimEnd('/')
        if (outTarget != null) {
            val commonMarker = "/src/common/commonMain/kotlin/"
            val markerIdx = outTarget.indexOf(commonMarker)
            val projectRoot =
                when {
                    markerIdx > 0 -> outTarget.substring(0, markerIdx)
                    outTarget.startsWith("src/common/commonMain/kotlin/") -> "."
                    else -> outTarget
                }
            val inferredNsFromPath =
                when {
                    markerIdx >= 0 ->
                        outTarget
                            .substring(markerIdx + commonMarker.length)
                            .trim('/')
                            .replace('/', '.')
                            .takeIf { it.isNotEmpty() }
                    outTarget.startsWith("src/common/commonMain/kotlin/") ->
                        outTarget
                            .removePrefix("src/common/commonMain/kotlin/")
                            .trim('/')
                            .replace('/', '.')
                            .takeIf { it.isNotEmpty() }
                    else -> null
                }
            val scaffoldNs =
                resolvedProjectNamespaceForScaffold
                    ?: inferredNsFromPath
                    ?: parsed.packageOverride
                    ?: "io.github.kotlinmania.libc"
            val ffiName =
                scaffoldNs
                    .substringAfterLast('.')
                    .takeIf { it.isNotEmpty() }
                    ?.replaceFirstChar { it.uppercase() }
                    ?: "Libc"
            val scaffoldFiles = emitCDynamicFfiScaffold(projectNamespace = scaffoldNs, ffiName = ffiName)
            for (sf in scaffoldFiles) {
                val fullPath =
                    if (projectRoot == "." || projectRoot.isEmpty()) {
                        sf.projectRelativePath
                    } else {
                        "$projectRoot/${sf.projectRelativePath}"
                    }
                if (parsed.dryRun) {
                    if (!parsed.quiet) {
                        report("dry-run: c-ffi-scaffold -> $fullPath")
                    }
                } else {
                    if (parsed.skipExisting && exists(fullPath)) continue
                    if (parsed.forceOverwrite) {
                        writeForce(fullPath, sf.content)
                    } else {
                        write(fullPath, sf.content)
                    }
                    if (!parsed.quiet) {
                        report("c-ffi-scaffold -> $fullPath")
                    }
                }
            }
        }
    }
}

private fun resolveCompanionOutputPath(
    targetKotlinPath: String,
    companion: CompanionInteropFile,
): String {
    val normalizedTarget = targetKotlinPath.replace('\\', '/')
    val commonMarker = "/src/common/commonMain/kotlin/"
    val markerIdx = normalizedTarget.indexOf(commonMarker)
    if (markerIdx >= 0) {
        val projectRoot = normalizedTarget.substring(0, markerIdx)
        val afterKotlin = normalizedTarget.substring(markerIdx + commonMarker.length)
        val pkgDir = afterKotlin.substringBeforeLast('/', missingDelimiterValue = "")
        return when (companion.kind) {
            is CompanionInteropKind.NativeKotlinActuals -> {
                val relDir = if (pkgDir.isEmpty()) companion.relativePath else "$pkgDir/${companion.relativePath}"
                "$projectRoot/src/native/nativeMain/kotlin/$relDir"
            }
            else -> "$projectRoot/src/native/interop/cinterop/${companion.relativePath}"
        }
    }
    val parentDir = normalizedTarget.substringBeforeLast('/', missingDelimiterValue = "")
    return if (parentDir.isEmpty()) companion.relativePath else "$parentDir/${companion.relativePath}"
}

private fun normalizeCliCfgKey(rawKey: String): String =
    rawKey
        .split("::")
        .map { it.trim().removePrefix("r#") }
        .filter { it.isNotEmpty() }
        .joinToString("::")

private fun unquoteCliCfgValue(rawVal: String): String {
    val trimmed = rawVal.trim()
    if (trimmed.startsWith('r')) {
        val quoteStart = trimmed.indexOf('"')
        val quoteEnd = trimmed.lastIndexOf('"')
        if (quoteStart > 0 && quoteEnd > quoteStart) {
            val prefixHashes = quoteStart - 1
            val suffixHashes = trimmed.length - 1 - quoteEnd
            val validPrefix = (1 until quoteStart).all { trimmed[it] == '#' }
            val validSuffix = (quoteEnd + 1 until trimmed.length).all { trimmed[it] == '#' }
            if (prefixHashes == suffixHashes && validPrefix && validSuffix) {
                return trimmed.substring(quoteStart + 1, quoteEnd)
            }
        }
    }
    if (trimmed.length >= 2 && trimmed.startsWith('"') && trimmed.endsWith('"')) {
        return trimmed
            .substring(1, trimmed.length - 1)
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }
    if (trimmed.length >= 2 && trimmed.startsWith('\'') && trimmed.endsWith('\'')) {
        return trimmed.substring(1, trimmed.length - 1)
    }
    return trimmed
}
