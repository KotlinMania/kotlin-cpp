package io.github.kotlinmania.kotlincpp

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.kotlinmania.io.files.SystemPathSeparator

class KotlinCppPluginTest : BasePlatformTestCase() {
    override fun tearDown() {
        try {
            project.getService(KotlinCppService::class.java)?.overwriteConfirmationHandler = null
        } finally {
            super.tearDown()
        }
    }

    fun testActionRegisteredInPluginXml() {
        val action =
            ActionManager
                .getInstance()
                .getAction("io.github.kotlinmania.kotlin-cpp.ConvertKotlinToCpp")
        assertNotNull("ConvertKotlinToCppAction should be registered in plugin.xml", action)
        assertTrue(action is ConvertKotlinToCppAction)
    }

    fun testConvertKotlinToCppActionGeneratesCoLocatedCpp23HeaderAndSource() {
        val ktFile =
            myFixture.tempDirFixture.createFile(
                "src/kotlinx/coroutines/internal/Symbol.kt",
                """
                // port-lint: source internal/Symbol.kt
                package kotlinx.coroutines.internal

                public class Symbol(public val symbol: String) {
                    public fun labelText(): String = "<" + symbol + ">"
                }
                """.trimIndent() + "\n",
            )

        val action = ConvertKotlinToCppAction()
        val dataContext =
            SimpleDataContext
                .builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(CommonDataKeys.VIRTUAL_FILE_ARRAY, arrayOf(ktFile))
                .build()
        val presentation = Presentation()
        val event =
            AnActionEvent.createEvent(
                dataContext,
                presentation,
                ActionPlaces.PROJECT_VIEW_POPUP,
                ActionUiKind.POPUP,
                null,
            )

        action.update(event)
        assertTrue("Action should be enabled and visible for Symbol.kt", presentation.isEnabledAndVisible)

        action.actionPerformed(event)

        val generatedHpp =
            checkNotNull(ktFile.parent.findChild("Symbol.hpp")) {
                "Expected sibling Symbol.hpp to be created"
            }
        val generatedCpp =
            checkNotNull(ktFile.parent.findChild("Symbol.cpp")) {
                "Expected sibling Symbol.cpp to be created"
            }

        val hppText = VfsUtilCore.loadText(generatedHpp)
        assertTrue(hppText.contains("#pragma once"))
        assertTrue(hppText.contains("namespace kotlinx::coroutines::internal"))
        assertTrue(hppText.contains("class Symbol"))

        val cppText = VfsUtilCore.loadText(generatedCpp)
        assertTrue(cppText.contains("#include \"Symbol.hpp\""))
        assertTrue(cppText.contains("std::string Symbol::label_text() const"))
    }

    fun testExistingCppHeaderRequiresOverwriteConfirmationAndRespectsCancel() {
        val existingHeaderContent = "// Handcrafted existing header\n#pragma once\n"
        val existingHpp =
            myFixture.tempDirFixture.createFile(
                "src/kotlinx/coroutines/channels/BufferOverflow.hpp",
                existingHeaderContent,
            )
        val ktFile =
            myFixture.tempDirFixture.createFile(
                "src/kotlinx/coroutines/channels/BufferOverflow.kt",
                """
                package kotlinx.coroutines.channels

                public enum class BufferOverflow {
                    SUSPEND,
                    DROP_OLDEST,
                }
                """.trimIndent() + "\n",
            )

        val service = project.getService(KotlinCppService::class.java)
        var capturedDetails: TargetKotlinFileDetails? = null
        service.overwriteConfirmationHandler = { details ->
            capturedDetails = details
            false
        }

        val cancelledResult = service.transpileVirtualFile(ktFile)
        assertTrue("Expected empty list when user declines overwriting existing .hpp file", cancelledResult.isEmpty())
        val details = checkNotNull(capturedDetails) { "Expected overwrite confirmation handler to be invoked" }
        assertEquals("BufferOverflow.hpp", details.fileName)
        assertTrue(details.exists)
        assertTrue(details.isRegularFile)
        assertTrue(details.isWritable)
        assertFalse(details.isReadOnly)
        assertEquals(existingHeaderContent.encodeToByteArray().size.toLong(), details.sizeBytes)
        if (SystemPathSeparator == '\\') {
            assertTrue(details.win32CreationDisposition != 0u)
            assertTrue(details.win32FileAttributes != 0u)
        } else {
            assertTrue(details.posixOpenFlags != 0)
            assertTrue(details.posixAccessMask != 0)
        }
        assertEquals(existingHeaderContent, VfsUtilCore.loadText(existingHpp))

        service.overwriteConfirmationHandler = { true }
        val confirmedResult = service.transpileVirtualFile(ktFile)
        assertEquals(1, confirmedResult.size)
        val updatedHpp = VfsUtilCore.loadText(existingHpp)
        assertTrue(updatedHpp.contains("enum class BufferOverflow"))
    }

    fun testDirectoryActionRecursivelyTranslatesKotlinFilesAndEmitsCNameCompanionAbi() {
        val enumKt =
            myFixture.tempDirFixture.createFile(
                "src/kotlinx/coroutines/channels/BufferOverflow.kt",
                """
                package kotlinx.coroutines.channels

                public enum class BufferOverflow {
                    SUSPEND,
                    DROP_OLDEST,
                }
                """.trimIndent() + "\n",
            )
        val ffiKt =
            myFixture.tempDirFixture.createFile(
                "src/kotlinx/coroutines/ffi/NativeBridge.kt",
                """
                package kotlinx.coroutines.ffi

                @CName("kxs_bridge_ping")
                public fun bridgePing(code: Int): Int = code + 1
                """.trimIndent() + "\n",
            )

        val rootDir = enumKt.parent.parent
        val action = ConvertKotlinToCppAction()
        val dataContext =
            SimpleDataContext
                .builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(CommonDataKeys.VIRTUAL_FILE_ARRAY, arrayOf(rootDir))
                .build()
        val presentation = Presentation()
        val event =
            AnActionEvent.createEvent(
                dataContext,
                presentation,
                ActionPlaces.PROJECT_VIEW_POPUP,
                ActionUiKind.POPUP,
                null,
            )

        action.update(event)
        assertTrue(presentation.isEnabledAndVisible)
        assertEquals("Convert Kotlin Directory to C++23 Files", presentation.text)

        action.actionPerformed(event)

        assertNotNull(enumKt.parent.findChild("BufferOverflow.hpp"))
        assertNotNull(ffiKt.parent.findChild("NativeBridge.hpp"))
        assertNotNull(ffiKt.parent.findChild("NativeBridge.cpp"))
        val cApiHeader = checkNotNull(ffiKt.parent.findChild("NativeBridge_c_api.h"))
        val defFile = checkNotNull(ffiKt.parent.findChild("NativeBridge.def"))
        assertTrue(VfsUtilCore.loadText(cApiHeader).contains("int32_t kxs_bridge_ping("))
        assertTrue(VfsUtilCore.loadText(defFile).contains("headers = NativeBridge_c_api.h"))
    }
}
