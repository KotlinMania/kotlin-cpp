package io.github.kotlinmania.kotlincpp

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.components.BorderLayoutPanel
import javax.swing.JButton
import javax.swing.JPanel

class MyToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun shouldBeAvailable(project: Project): Boolean = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val myToolWindow = MyToolWindow(project)
        val content = ContentFactory.getInstance().createContent(myToolWindow.getContent(), null, false)
        toolWindow.contentManager.addContent(content)
    }

    class MyToolWindow(private val project: Project) {
        private val content =
            BorderLayoutPanel(8, 8).apply {
                val topBar =
                    JPanel().apply {
                        add(JBLabel(MyMessageBundle.message("toolwindow.MyToolWindow.header.label")))
                    }

                val kotlinInput =
                    JBTextArea(12, 40).apply {
                        text =
                            "package kotlinx.coroutines.internal\n\n" +
                            "public class Symbol(public val symbol: String) {\n" +
                            "    public fun labelText(): String = \"<\" + symbol + \">\"\n" +
                            "}\n"
                    }
                val cppOutput =
                    JBTextArea(12, 40).apply {
                        isEditable = false
                    }

                val roundTripButton =
                    JButton(MyMessageBundle.message("toolwindow.MyToolWindow.roundtrip.button")).apply {
                        addActionListener {
                            val rendered = renderKotlinRoundTrip(kotlinInput.text)
                            val status = if (rendered.text == kotlinInput.text) "MATCH" else "NORMALIZED"
                            cppOutput.text = "// Round-trip ($status): ${rendered.summary}\n${rendered.text}"
                        }
                    }
                topBar.add(roundTripButton)

                val transpileButton =
                    JButton(MyMessageBundle.message("toolwindow.MyToolWindow.transpile.button")).apply {
                        addActionListener {
                            val service = project.getService(KotlinCppService::class.java)
                            val activeKtFile =
                                FileEditorManager
                                    .getInstance(project)
                                    .selectedFiles
                                    .firstOrNull { service.isTranspilableKotlinFile(it) }
                            cppOutput.text =
                                service.transpileSourcePreview(
                                    source = kotlinInput.text,
                                    sourceFilePath = activeKtFile?.name ?: "Preview.kt",
                                )
                        }
                    }
                topBar.add(transpileButton)

                val editorsPanel =
                    BorderLayoutPanel(8, 8).apply {
                        addToTop(JBScrollPane(kotlinInput))
                        addToCenter(JBScrollPane(cppOutput))
                    }

                addToTop(topBar)
                addToCenter(editorsPanel)
            }

        fun getContent(): JBPanel<*> = content
    }
}
