package org.opentoons.poc7.probe

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import poc07cffi.poc07_cffi_add
import poc07cffi.poc07_cffi_free
import poc07cffi.poc07_cffi_sha256_hex

/**
 * poc-07 célula 2 — chama o `.a` Rust (cross-compilado) via cinterop C-ABI, o mecanismo NOVO
 * de binding em Kotlin/Native (o Android usou JNI/UniFFI). Uma soma e um sha256 reais provam
 * que o código Rust EXECUTA e é interoperável byte-a-byte.
 */
object Ffi {
    @OptIn(ExperimentalForeignApi::class)
    fun run(): String {
        val add = poc07_cffi_add(40, 2)
        val vec = "opentoons-poc07".encodeToByteArray()
        val hex = vec.usePinned { pinned ->
            val ptr = poc07_cffi_sha256_hex(pinned.addressOf(0).reinterpret(), vec.size.convert())
            val s = ptr?.toKString() ?: "<null>"
            poc07_cffi_free(ptr)
            s
        }
        return "POC07-FFI add=$add rust_sha256(opentoons-poc07)=$hex"
    }
}
