package io.github.kotlinmania.kotlincpp.internal

internal object FfiJni {
    init {
        System.loadLibrary("ffi_jni")
    }

    @JvmStatic
    external fun getpid(): Int
}
