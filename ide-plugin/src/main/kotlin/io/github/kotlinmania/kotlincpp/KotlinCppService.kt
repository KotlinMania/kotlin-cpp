package io.github.kotlinmania.kotlincpp

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.ReadonlyStatusHandler
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import io.github.kotlinmania.io.IOException
import io.github.kotlinmania.kotlincpp.kcpp.CppFileArtifact
import io.github.kotlinmania.kotlincpp.kcpp.CppGcMode
import io.github.kotlinmania.kotlincpp.kcpp.CppTargetConfig
import io.github.kotlinmania.kotlincpp.kcpp.translateKotlinToCpp

private enum class BatchOverwriteDecision {
    OVERWRITE_ONE,
    OVERWRITE_ALL,
    SKIP_ONE,
    CANCEL_BATCH,
}

@Service(Service.Level.PROJECT)
class KotlinCppService(
    private val project: Project,
) {
    var overwriteConfirmationHandler: ((TargetKotlinFileDetails) -> Boolean)? = null

    fun isTranspilableKotlinFile(file: VirtualFile): Boolean {
        if (file.isDirectory) return false
        return file.name.endsWith(".kt", ignoreCase = true)
    }

    fun hasTranspilableKotlinFiles(files: Array<out VirtualFile>): Boolean {
        for (file in files) {
            if (isTranspilableKotlinFile(file)) return true
            if (file.isDirectory && collectTranspilableKotlinFiles(arrayOf(file)).isNotEmpty()) {
                return true
            }
        }
        return false
    }

    fun collectTranspilableKotlinFiles(files: Array<out VirtualFile>): List<VirtualFile> {
        val collectedByPath = linkedMapOf<String, VirtualFile>()
        for (root in files) {
            if (isTranspilableKotlinFile(root)) {
                collectedByPath[root.path] = root
            } else if (root.isDirectory) {
                VfsUtilCore.visitChildrenRecursively(
                    root,
                    object : VirtualFileVisitor<Unit>() {
                        override fun visitFile(file: VirtualFile): Boolean {
                            if (isTranspilableKotlinFile(file)) {
                                collectedByPath[file.path] = file
                            }
                            return true
                        }
                    },
                )
                if (root.fileSystem is LocalFileSystem) {
                    val localFs = LocalFileSystem.getInstance()
                    for (entryPath in collectKotlinFilePaths(listOf(root.path))) {
                        if (entryPath !in collectedByPath) {
                            val vf =
                                localFs.findFileByPath(entryPath)
                                    ?: localFs.refreshAndFindFileByPath(entryPath)
                            if (vf != null && isTranspilableKotlinFile(vf)) {
                                collectedByPath[vf.path] = vf
                            }
                        }
                    }
                }
            }
        }
        return collectedByPath.values.sortedBy { it.path }
    }

    fun transpileSourcePreview(
        source: String,
        sourceFilePath: String = "Preview.kt",
        gcMode: CppGcMode = CppGcMode.NONE,
        emitCompanionCAbiFiles: Boolean = false,
    ): String {
        val config =
            CppTargetConfig(
                cppStandard = 23,
                gcMode = gcMode,
                emitCompanionCAbiFiles = emitCompanionCAbiFiles,
            )
        val result = translateKotlinToCpp(source = source, sourcePath = sourceFilePath, config = config)
        return buildString {
            appendLine("// === ${result.headerArtifact.relativePath} ===")
            appendLine(result.headerArtifact.content.trimEnd())
            result.sourceArtifact?.let { cpp ->
                appendLine()
                appendLine("// === ${cpp.relativePath} ===")
                appendLine(cpp.content.trimEnd())
            }
            for (comp in result.companionArtifacts) {
                appendLine()
                appendLine("// === ${comp.relativePath} ===")
                appendLine(comp.content.trimEnd())
            }
        }
    }

    fun inspectTargetFile(parent: VirtualFile, targetFileName: String): TargetKotlinFileDetails {
        val targetPath = "${parent.path.trimEnd('/')}/$targetFileName"
        val existingVirtualFile = parent.findChild(targetFileName)
        val diskProbe = probeTargetKotlinFile(targetPath)
        if (diskProbe.exists || existingVirtualFile == null) {
            return diskProbe
        }
        return describeVirtualKotlinFile(
            path = targetPath,
            fileName = targetFileName,
            isDirectory = existingVirtualFile.isDirectory,
            isWritable = existingVirtualFile.isWritable,
            sizeBytes = existingVirtualFile.length,
        )
    }

    fun confirmOverwriteTargetFile(targetDetails: TargetKotlinFileDetails): Boolean {
        overwriteConfirmationHandler?.let { return it(targetDetails) }
        val accessStatus =
            if (targetDetails.isReadOnly) {
                MyMessageBundle.message("dialog.overwrite.cpp.readonly")
            } else {
                MyMessageBundle.message("dialog.overwrite.cpp.writable")
            }
        val displayPath = targetDetails.resolvedRealPath ?: targetDetails.path
        return MessageDialogBuilder
            .yesNo(
                MyMessageBundle.message("dialog.overwrite.cpp.title"),
                MyMessageBundle.message(
                    "dialog.overwrite.cpp.message",
                    targetDetails.fileName,
                    targetDetails.sizeBytes,
                    accessStatus,
                    displayPath,
                ),
            ).yesText(MyMessageBundle.message("dialog.overwrite.cpp.overwrite.button"))
            .noText(MyMessageBundle.message("dialog.overwrite.cpp.cancel.button"))
            .icon(Messages.getWarningIcon())
            .ask(project)
    }

    private fun promptBatchOverwriteDecision(targetDetails: TargetKotlinFileDetails): BatchOverwriteDecision {
        val handler = overwriteConfirmationHandler
        if (handler != null) {
            return if (handler(targetDetails)) {
                BatchOverwriteDecision.OVERWRITE_ONE
            } else {
                BatchOverwriteDecision.SKIP_ONE
            }
        }
        val accessStatus =
            if (targetDetails.isReadOnly) {
                MyMessageBundle.message("dialog.overwrite.cpp.readonly")
            } else {
                MyMessageBundle.message("dialog.overwrite.cpp.writable")
            }
        val displayPath = targetDetails.resolvedRealPath ?: targetDetails.path
        val options =
            arrayOf(
                MyMessageBundle.message("dialog.overwrite.cpp.overwrite.button"),
                MyMessageBundle.message("dialog.overwrite.cpp.overwrite.all.button"),
                MyMessageBundle.message("dialog.overwrite.cpp.skip.button"),
                MyMessageBundle.message("dialog.overwrite.cpp.cancel.button"),
            )
        val choice =
            Messages.showDialog(
                project,
                MyMessageBundle.message(
                    "dialog.overwrite.cpp.message",
                    targetDetails.fileName,
                    targetDetails.sizeBytes,
                    accessStatus,
                    displayPath,
                ),
                MyMessageBundle.message("dialog.overwrite.cpp.title"),
                options,
                0,
                Messages.getWarningIcon(),
            )
        return when (choice) {
            0 -> BatchOverwriteDecision.OVERWRITE_ONE
            1 -> BatchOverwriteDecision.OVERWRITE_ALL
            2 -> BatchOverwriteDecision.SKIP_ONE
            else -> BatchOverwriteDecision.CANCEL_BATCH
        }
    }

    fun transpileDirectoryRecursively(
        directory: VirtualFile,
        gcMode: CppGcMode = CppGcMode.NONE,
        emitCompanionCAbiFiles: Boolean = false,
    ): List<VirtualFile> {
        require(directory.isDirectory) {
            "Expected a directory for recursive translation: ${directory.path}"
        }
        return transpileVirtualFiles(arrayOf(directory), gcMode, emitCompanionCAbiFiles)
    }

    fun transpileVirtualFiles(
        selectedFiles: Array<out VirtualFile>,
        gcMode: CppGcMode = CppGcMode.NONE,
        emitCompanionCAbiFiles: Boolean = false,
    ): List<VirtualFile> {
        val kotlinFiles = collectTranspilableKotlinFiles(selectedFiles)
        if (kotlinFiles.isEmpty()) return emptyList()
        if (kotlinFiles.size == 1) {
            return transpileVirtualFile(
                kotlinFile = kotlinFiles.single(),
                gcMode = gcMode,
                emitCompanionCAbiFiles = emitCompanionCAbiFiles,
            )
        }

        val generatedFiles = mutableListOf<VirtualFile>()
        var overwriteAll = false
        var cancelledBatch = false

        for (kotlinFile in kotlinFiles) {
            if (cancelledBatch) break
            generatedFiles +=
                transpileVirtualFile(
                    kotlinFile = kotlinFile,
                    gcMode = gcMode,
                    emitCompanionCAbiFiles = emitCompanionCAbiFiles,
                    confirmOverwrite = { details ->
                        if (overwriteAll) {
                            true
                        } else {
                            when (promptBatchOverwriteDecision(details)) {
                                BatchOverwriteDecision.OVERWRITE_ONE -> true
                                BatchOverwriteDecision.OVERWRITE_ALL -> {
                                    overwriteAll = true
                                    true
                                }
                                BatchOverwriteDecision.SKIP_ONE -> false
                                BatchOverwriteDecision.CANCEL_BATCH -> {
                                    cancelledBatch = true
                                    false
                                }
                            }
                        }
                    },
                )
        }
        return generatedFiles
    }

    fun transpileVirtualFile(
        kotlinFile: VirtualFile,
        gcMode: CppGcMode = CppGcMode.NONE,
        emitCompanionCAbiFiles: Boolean = false,
        confirmOverwrite: (TargetKotlinFileDetails) -> Boolean = ::confirmOverwriteTargetFile,
    ): List<VirtualFile> {
        require(isTranspilableKotlinFile(kotlinFile)) {
            "Not a transpilable Kotlin source file: ${kotlinFile.path}"
        }
        val parent =
            requireNotNull(kotlinFile.parent) {
                "Kotlin file has no parent directory: ${kotlinFile.path}"
            }

        val kotlinSource = VfsUtilCore.loadText(kotlinFile)
        val hasCNameExport = kotlinSource.contains("@CName(")
        val config =
            CppTargetConfig(
                cppStandard = 23,
                gcMode = gcMode,
                emitCompanionCAbiFiles = emitCompanionCAbiFiles || hasCNameExport,
            )
        val result = translateKotlinToCpp(source = kotlinSource, sourcePath = kotlinFile.name, config = config)

        val siblingArtifacts = ArrayList<Pair<String, String>>(2 + result.companionArtifacts.size)
        val headerFileName = result.headerArtifact.relativePath.substringAfterLast('/')
        val headerContent =
            result.headerArtifact.content.replace(
                "#include \"${result.headerArtifact.relativePath}\"",
                "#include \"$headerFileName\"",
            )
        siblingArtifacts.add(headerFileName to headerContent)

        result.sourceArtifact?.let { srcArt: CppFileArtifact ->
            val srcFileName = srcArt.relativePath.substringAfterLast('/')
            val srcContent =
                srcArt.content.replace(
                    "#include \"${result.headerArtifact.relativePath}\"",
                    "#include \"$headerFileName\"",
                )
            siblingArtifacts.add(srcFileName to srcContent)
        }

        for (companion in result.companionArtifacts) {
            val compName = companion.relativePath.substringAfterLast('/')
            siblingArtifacts.add(compName to companion.content)
        }

        for ((fileName, _) in siblingArtifacts) {
            val details = inspectTargetFile(parent, fileName)
            require(!details.isDirectory && !details.isSymbolicLink) {
                "Target path cannot be a directory or symbolic link: ${details.path}"
            }
            if (details.exists) {
                if (!confirmOverwrite(details)) {
                    return emptyList()
                }
                parent.findChild(fileName)?.let { existingVf ->
                    if (!existingVf.isWritable) {
                        val status =
                            ReadonlyStatusHandler
                                .getInstance(project)
                                .ensureFilesWritable(listOf(existingVf))
                        if (status.hasReadonlyFiles()) {
                            return emptyList()
                        }
                    }
                }
            }
        }

        return WriteCommandAction
            .writeCommandAction(project)
            .withName(MyMessageBundle.message("command.convert.kotlin.to.cpp"))
            .compute<List<VirtualFile>, IOException> {
                val written = ArrayList<VirtualFile>(siblingArtifacts.size)
                for ((fileName, text) in siblingArtifacts) {
                    val targetVf = parent.findOrCreateChildData(this, fileName)
                    VfsUtil.saveText(targetVf, text)
                    written.add(targetVf)
                }
                written
            }
    }
}
