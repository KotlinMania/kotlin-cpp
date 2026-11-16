package io.github.kotlinmania.kotlincpp

import io.github.kotlinmania.io.files.SystemFileSystem
import io.github.kotlinmania.io.files.Path as KmPath

public data class SingleFileTranslation(
    public val text: String,
    public val summary: String,
    public val diagnostics: List<String> = emptyList(),
) {
    public constructor(text: String, summary: String) : this(text, summary, emptyList())
}

private val SKIPPED_DIRECTORY_NAMES: Set<String> =
    setOf(".git", ".gradle", ".idea", ".android-sdk", "build", "node_modules")

public fun collectKotlinFilePaths(roots: List<String>): List<String> {
    val results = LinkedHashSet<String>()

    fun visit(pathStr: String) {
        val details = probeTargetKotlinFile(pathStr)
        if (!details.exists || details.isSymbolicLink) return
        if (details.isRegularFile) {
            if (details.fileName.endsWith(".kt", ignoreCase = true)) {
                results.add(pathStr)
            }
            return
        }
        if (!details.isDirectory) return
        val children =
            SystemFileSystem
                .list(KmPath(pathStr))
                .map { it.toString() }
                .sorted()
        for (childPath in children) {
            val childName = childPath.replace('\\', '/').substringAfterLast('/')
            if (childName in SKIPPED_DIRECTORY_NAMES) continue
            visit(childPath)
        }
    }

    for (root in roots) {
        visit(root)
    }
    return results.sorted()
}
