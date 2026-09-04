package com.virtualpcvm
class CheckArch {
    fun check() {
        println(android.os.Build.SUPPORTED_ABIS.joinToString())
    }
}
