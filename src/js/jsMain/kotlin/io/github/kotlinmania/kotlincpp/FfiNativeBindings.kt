package io.github.kotlinmania.kotlincpp

@JsModule("@kotlinmania/ffi-native-bindings")
public external interface FfiNativeBindings {
    public fun getpid(): Int
}
