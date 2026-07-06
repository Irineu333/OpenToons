// Facade C-ABI mínimo: prova que um .a Rust cross-compilado para aarch64-apple-ios é
// chamável de Kotlin/Native via cinterop. Uma computação real (sha256) prova execução Rust.
use sha2::{Digest, Sha256};
use std::os::raw::c_char;
use std::ffi::CString;

#[no_mangle]
pub extern "C" fn poc07_cffi_add(a: i32, b: i32) -> i32 { a + b }

/// sha256(hex) de `len` bytes começando em `data`. Retorna string C (o chamador libera com
/// poc07_cffi_free). Prova: computação real Rust no device, verificável contra vetor conhecido.
#[no_mangle]
pub extern "C" fn poc07_cffi_sha256_hex(data: *const u8, len: usize) -> *mut c_char {
    let slice = unsafe { std::slice::from_raw_parts(data, len) };
    let digest = Sha256::digest(slice);
    let hex: String = digest.iter().map(|b| format!("{:02x}", b)).collect();
    CString::new(hex).unwrap().into_raw()
}

#[no_mangle]
pub extern "C" fn poc07_cffi_free(s: *mut c_char) {
    if !s.is_null() { unsafe { drop(CString::from_raw(s)); } }
}
