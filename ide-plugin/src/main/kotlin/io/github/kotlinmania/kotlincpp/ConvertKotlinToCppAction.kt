package io.github.kotlinmania.kotlincpp

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement

class ConvertKotlinToCppAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val files = extractSelectedVirtualFiles(e)
        if (project == null || files.isEmpty()) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val service = project.getService(KotlinCppService::class.java)
        val enabled = service.hasTranspilableKotlinFiles(files)
        e.presentation.isEnabledAndVisible = enabled
        if (enabled) {
            e.presentation.text =
                if (files.any { it.isDirectory }) {
                    MyMessageBundle.message("command.convert.kotlin.directory.to.cpp")
                } else {
                    MyMessageBundle.message("command.convert.kotlin.to.cpp")
                }
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val selectedFiles = extractSelectedVirtualFiles(e)
        if (selectedFiles.isEmpty()) return
        val service = project.getService(KotlinCppService::class.java)
        val generatedCppFiles = service.transpileVirtualFiles(selectedFiles)
        val editorManager = FileEditorManager.getInstance(project)
        generatedCppFiles.firstOrNull()?.let { headerFile ->
            editorManager.openFile(headerFile, true)
        }
    }

    private fun extractSelectedVirtualFiles(e: AnActionEvent): Array<VirtualFile> {
        val array = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        if (!array.isNullOrEmpty()) return array

        val single = e.getData(CommonDataKeys.VIRTUAL_FILE)
        if (single != null) return arrayOf(single)

        val psiElement: PsiElement? = e.getData(CommonDataKeys.PSI_ELEMENT)
        if (psiElement is PsiDirectory) {
            return arrayOf(psiElement.virtualFile)
        }
        val psiFile = psiElement?.containingFile?.virtualFile
        if (psiFile != null) return arrayOf(psiFile)

        val ideView = e.getData(LangDataKeys.IDE_VIEW)
        if (ideView != null) {
            val dirs = ideView.directories.mapNotNull { it.virtualFile }
            if (dirs.isNotEmpty()) return dirs.toTypedArray()
        }

        return emptyArray()
    }
}
