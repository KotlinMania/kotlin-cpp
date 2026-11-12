package io.github.kotlinmania.kotlincpp

import io.github.kotlinmania.io.files.SystemFileSystem
import io.github.kotlinmania.io.files.writeString
import io.github.kotlinmania.libc.unix.access
import io.github.kotlinmania.libc.unix.bsd.O_CREAT
import io.github.kotlinmania.libc.unix.bsd.O_EXCL
import io.github.kotlinmania.libc.unix.bsd.O_NOFOLLOW
import io.github.kotlinmania.libc.unix.bsd.O_TRUNC
import io.github.kotlinmania.libc.unix.bsd.O_WRONLY
import io.github.kotlinmania.libc.unix.bsd.apple.F_OK
import io.github.kotlinmania.libc.unix.bsd.apple.R_OK
import io.github.kotlinmania.libc.unix.bsd.apple.W_OK
import io.github.kotlinmania.libc.unix.realpath
import io.github.kotlinmania.io.files.Path as KmPath

internal fun probeTargetKotlinFileJvmPosix(path: String): TargetKotlinFileDetails {
    val normalizedPath = path.replace('\\', '/')
    val fileName = normalizedPath.substringAfterLast('/')
    val kmPath = KmPath(path)
    val kmMetadata = SystemFileSystem.metadataOrNull(kmPath)
    val kmExists = SystemFileSystem.exists(kmPath)

    val posixFOk = access(path, F_OK) == 0
    val posixROk = access(path, R_OK) == 0
    val posixWOk = access(path, W_OK) == 0

    val exists = kmExists || posixFOk
    val resolvedRealPath = if (exists) realpath(path, null) else null

    val isSymbolicLink =
        if (!exists) {
            false
        } else {
            val parentReal = kmPath.parent?.let { realpath(it.toString(), null) }
            val expectedRealInParent = if (parentReal != null) "${parentReal.trimEnd('/')}/$fileName" else null
            resolvedRealPath != null &&
                expectedRealInParent != null &&
                resolvedRealPath != expectedRealInParent &&
                !isCaseInsensitiveCanonicalMatch(parentReal, fileName, resolvedRealPath) { candidate ->
                    realpath(candidate, null)
                }
        }

    val isDirectory = !isSymbolicLink && (kmMetadata?.isDirectory == true)
    val isRegularFile = !isSymbolicLink && (kmMetadata?.isRegularFile == true)
    val isReadable = exists && posixROk
    val isWritable =
        if (!exists) {
            val parentPath = kmPath.parent?.toString()
            if (parentPath != null) access(parentPath, W_OK) == 0 else true
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

internal fun describeVirtualKotlinFileJvmPosix(
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

internal fun writeNewJvmPosix(path: String, text: String) {
    ensureParentDirectories(path)
    val details = probeTargetKotlinFileJvmPosix(path)
    require(!details.exists && !details.isSymbolicLink) {
        "cannot exclusively create $path (posixFlags=0x${details.posixOpenFlags.toString(16)})"
    }
    SystemFileSystem.writeString(KmPath(path), text)
}
