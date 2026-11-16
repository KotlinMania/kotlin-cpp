@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.kotlinmania.kotlincpp

import io.github.kotlinmania.io.files.SystemFileSystem
import io.github.kotlinmania.libc.unix.access
import io.github.kotlinmania.libc.unix.bsd.O_CREAT
import io.github.kotlinmania.libc.unix.bsd.O_EXCL
import io.github.kotlinmania.libc.unix.bsd.O_NOFOLLOW
import io.github.kotlinmania.libc.unix.bsd.O_TRUNC
import io.github.kotlinmania.libc.unix.bsd.O_WRONLY
import io.github.kotlinmania.libc.unix.bsd.apple.F_OK
import io.github.kotlinmania.libc.unix.bsd.apple.R_OK
import io.github.kotlinmania.libc.unix.bsd.apple.W_OK
import io.github.kotlinmania.libc.unix.fclose
import io.github.kotlinmania.libc.unix.fopen
import io.github.kotlinmania.libc.unix.free
import io.github.kotlinmania.libc.unix.fwrite
import io.github.kotlinmania.libc.unix.readlink
import io.github.kotlinmania.libc.unix.realpath
import io.github.kotlinmania.rustlang.alloc.ffi.CString
import io.github.kotlinmania.rustlang.core.ffi.CStr
import io.github.kotlinmania.rustlang.core.ptr.ConstPtr
import io.github.kotlinmania.rustlang.core.ptr.MutPtr
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import io.github.kotlinmania.io.files.Path as KmPath

private inline fun <R> withPosixCString(
    text: String,
    block: (ConstPtr) -> R,
): R {
    val cstr = CString.new(text).unwrap()
    return try {
        block(cstr.asPtr())
    } finally {
        cstr.deallocate()
    }
}

private fun posixAccess(path: String, mode: Int): Int =
    withPosixCString(path) { pathPtr ->
        access(pathPtr, mode)
    }

private fun posixRealpath(path: String): String? =
    withPosixCString(path) { pathPtr ->
        val raw = realpath(pathPtr, MutPtr(0L))
        if (raw.isNull()) {
            null
        } else {
            try {
                CStr.fromPtr(raw.castConst()).toStringLossy()
            } finally {
                free(raw)
            }
        }
    }

private fun isPosixPathSymbolicLink(path: String): Boolean {
    val probe = ByteArray(1)
    return withPosixCString(path) { pathPtr ->
        probe.usePinned { pinned ->
            readlink(pathPtr, MutPtr(pinned.addressOf(0).rawValue.toLong()), 1uL) >= 0
        }
    }
}

public actual fun probeTargetKotlinFile(path: String): TargetKotlinFileDetails {
    val normalizedPath = path.replace('\\', '/')
    val fileName = normalizedPath.substringAfterLast('/')
    val kmPath = KmPath(path)
    val kmMetadata = SystemFileSystem.metadataOrNull(kmPath)
    val kmExists = SystemFileSystem.exists(kmPath)

    val posixFOk = posixAccess(path, F_OK) == 0
    val posixROk = posixAccess(path, R_OK) == 0
    val posixWOk = posixAccess(path, W_OK) == 0

    val exists = kmExists || posixFOk
    val resolvedRealPath = if (exists) posixRealpath(path) else null
    val isSymbolicLink = isPosixPathSymbolicLink(path)

    val isDirectory = !isSymbolicLink && (kmMetadata?.isDirectory == true)
    val isRegularFile = !isSymbolicLink && (kmMetadata?.isRegularFile == true)
    val isReadable = exists && posixROk
    val isWritable =
        if (!exists) {
            val parentPath = kmPath.parent?.toString()
            if (parentPath != null) posixAccess(parentPath, W_OK) == 0 else true
        } else {
            posixWOk
        }
    val isReadOnly = exists && !isWritable
    val sizeBytes = if (kmMetadata != null && kmMetadata.size >= 0L) kmMetadata.size else 0L

    val posixAccessMask =
        (if (exists) F_OK else 0) or
            (if (isReadable) R_OK else 0) or
            (if (exists && isWritable) W_OK else 0)
    val posixOpenFlags =
        if (exists) {
            O_WRONLY or O_CREAT or O_TRUNC or O_NOFOLLOW
        } else {
            O_WRONLY or O_CREAT or O_EXCL or O_NOFOLLOW
        }

    return TargetKotlinFileDetails(
        path = path,
        fileName = fileName,
        resolvedRealPath = resolvedRealPath,
        exists = exists,
        isRegularFile = isRegularFile,
        isDirectory = isDirectory,
        isSymbolicLink = isSymbolicLink,
        isReadable = isReadable,
        isWritable = isWritable,
        isReadOnly = isReadOnly,
        sizeBytes = sizeBytes,
        posixAccessMask = posixAccessMask,
        posixOpenFlags = posixOpenFlags,
        win32FileAttributes = 0u,
        win32CreationDisposition = 0u,
        win32DesiredAccess = 0u,
        win32ShareMode = 0u,
    )
}

public actual fun describeVirtualKotlinFile(
    path: String,
    fileName: String,
    isDirectory: Boolean,
    isWritable: Boolean,
    sizeBytes: Long,
): TargetKotlinFileDetails {
    val isReadOnly = !isWritable
    val posixAccessMask = F_OK or R_OK or (if (isWritable) W_OK else 0)
    val posixOpenFlags = O_WRONLY or O_CREAT or O_TRUNC or O_NOFOLLOW
    return TargetKotlinFileDetails(
        path = path,
        fileName = fileName,
        resolvedRealPath = path,
        exists = true,
        isRegularFile = !isDirectory,
        isDirectory = isDirectory,
        isSymbolicLink = false,
        isReadable = true,
        isWritable = isWritable,
        isReadOnly = isReadOnly,
        sizeBytes = sizeBytes,
        posixAccessMask = posixAccessMask,
        posixOpenFlags = posixOpenFlags,
        win32FileAttributes = 0u,
        win32CreationDisposition = 0u,
        win32DesiredAccess = 0u,
        win32ShareMode = 0u,
    )
}

public actual fun writeNew(path: String, text: String) {
    ensureParentDirectories(path)
    val details = probeTargetKotlinFile(path)
    require(!details.exists && !details.isSymbolicLink) {
        "cannot exclusively create $path (posixFlags=0x${details.posixOpenFlags.toString(16)})"
    }
    val file =
        withPosixCString(path) { pathPtr ->
            withPosixCString("wx") { modePtr ->
                fopen(pathPtr, modePtr)
            }
        }
    check(!file.isNull()) { "cannot exclusively create $path" }
    var complete = false
    try {
        val bytes = text.encodeToByteArray()
        if (bytes.isNotEmpty()) {
            bytes.usePinned { pinned ->
                var offset = 0
                while (offset < bytes.size) {
                    val address = ConstPtr(pinned.addressOf(offset).rawValue.toLong())
                    val count = fwrite(address, 1uL, (bytes.size - offset).toULong(), file)
                    check(count > 0uL && count <= (bytes.size - offset).toULong()) {
                        "output may be partial at $path"
                    }
                    offset += count.toInt()
                }
            }
        }
        complete = true
    } finally {
        val result = fclose(file)
        check(result == 0 || !complete) { "failed to close output $path" }
    }
}
