package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.io.files.SystemFileSystem
import io.github.kotlinmania.io.files.resolve
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.github.kotlinmania.io.files.Path as KmPath

class JvmNativeIoTest {
    private val scratchDir = KmPath("build/test-scratch/jvm-io")

    private fun prepareScratchDir(): KmPath {
        SystemFileSystem.createDirectories(scratchDir, mustCreate = false)
        return SystemFileSystem.resolve(scratchDir)
    }

    @Test
    fun probeTargetKotlinFileAndExclusiveWriteRoundTrip() {
        val base = prepareScratchDir()
        val suffix = Random.nextLong().toULong().toString()
        val target = base.resolve("rust-kotlin-jvm-probe-$suffix.kt").toString()
        val initialContent = "package sample\npublic fun initial(): Int = 1\n"
        val updatedContent = "package sample\npublic fun updated(): Int = 2\n"
        try {
            val missingDetails = probeTargetKotlinFile(target)
            assertFalse(missingDetails.exists)
            assertNull(missingDetails.resolvedRealPath)
            if (isJvmWindowsHost()) {
                assertTrue(missingDetails.win32CreationDisposition != 0u)
            } else {
                assertTrue(missingDetails.posixOpenFlags != 0)
            }

            writeNew(target, initialContent)
            assertFailsWith<Exception> {
                writeNew(target, updatedContent)
            }

            val existingDetails = probeTargetKotlinFile(target)
            assertTrue(existingDetails.exists)
            assertTrue(existingDetails.isRegularFile)
            assertFalse(existingDetails.isDirectory)
            assertFalse(existingDetails.isSymbolicLink)
            assertTrue(existingDetails.isReadable)
            assertTrue(existingDetails.isWritable)
            assertFalse(existingDetails.isReadOnly)
            assertNotNull(existingDetails.resolvedRealPath)
            assertEquals(initialContent.encodeToByteArray().size.toLong(), existingDetails.sizeBytes)

            writeOverwrite(target, updatedContent)
            assertEquals(updatedContent, readInput(target))
        } finally {
            SystemFileSystem.delete(KmPath(target), mustExist = false)
        }
    }

    @Test
    fun probeTargetKotlinFileHandlesCaseInsensitivePathsWithoutFalseSymlink() {
        val base = prepareScratchDir()
        val suffix = Random.nextLong().toULong().toString()
        val upperTarget = base.resolve("JvmCaseProbe$suffix.kt").toString()
        val lowerAlias = base.resolve("jvmcaseprobe$suffix.kt").toString()
        val initialContent = "package sample\npublic fun initialCase(): Int = 1\n"
        val updatedContent = "package sample\npublic fun updatedCase(): Int = 2\n"
        try {
            writeNew(upperTarget, initialContent)
            val lowerDetails = probeTargetKotlinFile(lowerAlias)
            assertFalse(lowerDetails.isSymbolicLink)
            if (lowerDetails.exists) {
                assertTrue(lowerDetails.isRegularFile)
                writeOverwrite(lowerAlias, updatedContent)
                assertEquals(updatedContent, readInput(upperTarget))
            }
        } finally {
            SystemFileSystem.delete(KmPath(upperTarget), mustExist = false)
            SystemFileSystem.delete(KmPath(lowerAlias), mustExist = false)
        }
    }
}
