//! poc-07 célula 2 — C-ABI da libp2p para Kotlin/Native (iOS). Embrulha o `Node` cliente do
//! facade poc-04 (rust-libp2p 0.54): dial / resolve / get_manifest / get_blocks. Os métodos
//! async do Node são dirigidos por `futures::executor::block_on` de fora (o ator do swarm roda
//! no runtime tokio interno do Node). Retornos por ponteiro+tamanho; o Kotlin libera.

use futures::executor::block_on;
use std::collections::HashMap;
use std::ffi::{CStr, CString};
use std::os::raw::c_char;
use std::sync::{Arc, Mutex};
use uniffi_facade::{peer_id_from_ed25519, BlockstoreCallback, Node, ServerConfig, ServerNode};

unsafe fn cstr(p: *const c_char) -> String {
    if p.is_null() { return String::new(); }
    CStr::from_ptr(p).to_string_lossy().into_owned()
}

fn into_cstr(s: String) -> *mut c_char {
    CString::new(s).unwrap_or_default().into_raw()
}

/// PeerId libp2p a partir da pubkey Ed25519 (hex) do seam — para montar o multiaddr de dial.
#[no_mangle]
pub extern "C" fn poc07_lp_peer_id_from_ed25519(pubkey_hex: *const c_char) -> *mut c_char {
    let hex = unsafe { cstr(pubkey_hex) };
    match peer_id_from_ed25519(hex) {
        Ok(pid) => into_cstr(pid),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Cria o Node cliente já discando o bootstrap. Retorna ponteiro opaco (ou null em erro).
#[no_mangle]
pub extern "C" fn poc07_lp_new(bootstrap_multiaddr: *const c_char) -> *mut Node {
    let boot = unsafe { cstr(bootstrap_multiaddr) };
    match Node::new(boot) {
        Ok(node) => Box::into_raw(Box::new(node)),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "C" fn poc07_lp_peer_id(node: *mut Node) -> *mut c_char {
    if node.is_null() { return std::ptr::null_mut(); }
    let n = unsafe { &*node };
    into_cstr(n.peer_id())
}

/// dial explícito de um multiaddr `/ip4/.../tcp/.../p2p/<peer>`. 0 = ok, -1 = erro.
#[no_mangle]
pub extern "C" fn poc07_lp_dial(node: *mut Node, multiaddr: *const c_char) -> i32 {
    if node.is_null() { return -1; }
    let n = unsafe { &*node };
    match block_on(n.dial(unsafe { cstr(multiaddr) })) {
        Ok(_) => 0,
        Err(_) => -1,
    }
}

/// resolve por Kademlia. Retorna o multiaddr do provider, "" se nenhum, null em erro.
#[no_mangle]
pub extern "C" fn poc07_lp_resolve(node: *mut Node, obra_id: *const c_char) -> *mut c_char {
    if node.is_null() { return std::ptr::null_mut(); }
    let n = unsafe { &*node };
    match block_on(n.resolve(unsafe { cstr(obra_id) })) {
        Ok(s) => into_cstr(s),
        Err(_) => std::ptr::null_mut(),
    }
}

/// manifesto assinado (bytes crus). out_len recebe o tamanho; retorna o ponteiro (ou null).
#[no_mangle]
pub extern "C" fn poc07_lp_get_manifest(
    node: *mut Node,
    provider_multiaddr: *const c_char,
    obra_id: *const c_char,
    out_len: *mut usize,
) -> *mut u8 {
    if node.is_null() { return std::ptr::null_mut(); }
    let n = unsafe { &*node };
    match block_on(n.get_manifest(unsafe { cstr(provider_multiaddr) }, unsafe { cstr(obra_id) })) {
        Ok(bytes) => bytes_out(bytes, out_len),
        Err(_) => std::ptr::null_mut(),
    }
}

/// blocos length-prefixed `[u32 BE len][bloco]...` (o Kotlin fatia). cids separados por '\n'.
#[no_mangle]
pub extern "C" fn poc07_lp_get_blocks(
    node: *mut Node,
    provider_multiaddr: *const c_char,
    cids_newline: *const c_char,
    out_len: *mut usize,
) -> *mut u8 {
    if node.is_null() { return std::ptr::null_mut(); }
    let n = unsafe { &*node };
    match block_on(n.get_blocks(unsafe { cstr(provider_multiaddr) }, unsafe { cstr(cids_newline) })) {
        Ok(bytes) => bytes_out(bytes, out_len),
        Err(_) => std::ptr::null_mut(),
    }
}

fn bytes_out(mut v: Vec<u8>, out_len: *mut usize) -> *mut u8 {
    v.shrink_to_fit();
    let len = v.len();
    let ptr = v.as_mut_ptr();
    std::mem::forget(v);
    unsafe { *out_len = len; }
    ptr
}

#[no_mangle]
pub extern "C" fn poc07_lp_free_bytes(ptr: *mut u8, len: usize) {
    if !ptr.is_null() { unsafe { drop(Vec::from_raw_parts(ptr, len, len)); } }
}

#[no_mangle]
pub extern "C" fn poc07_lp_free_str(s: *mut c_char) {
    if !s.is_null() { unsafe { drop(CString::from_raw(s)); } }
}

#[no_mangle]
pub extern "C" fn poc07_lp_free_node(node: *mut Node) {
    if !node.is_null() { unsafe { drop(Box::from_raw(node)); } }
}

// ===================== SERVER C-ABI (task 5.3: TCK libp2p in-process) =====================
// Sobe full nodes libp2p (bootstrap + publicador) DENTRO do processo Kotlin/Native, servindo
// de um blockstore em memória alimentado pelo Kotlin. Permite montar a TOPOLOGIA TCK COMPLETA
// (bootstrap + publicador como full nodes libp2p reais + cliente) no alvo iOS (simulador, D-rules)
// e rodar os cenários de conformidade sobre libp2p de verdade — não só o E2E remoto.

struct StoreInner {
    manifests: HashMap<String, Vec<u8>>,
    blocks: HashMap<String, Vec<u8>>,
}

/// Handle opaco do blockstore compartilhado (Kotlin alimenta antes de subir os servidores).
pub struct LpStore {
    inner: Arc<Mutex<StoreInner>>,
}

/// Impl neutra do callback do facade poc-04 lendo do mapa em memória.
struct StoreHandle {
    inner: Arc<Mutex<StoreInner>>,
}
impl BlockstoreCallback for StoreHandle {
    fn manifest(&self, obra_id: String) -> Option<Vec<u8>> {
        self.inner.lock().unwrap().manifests.get(&obra_id).cloned()
    }
    fn block(&self, cid: String) -> Option<Vec<u8>> {
        self.inner.lock().unwrap().blocks.get(&cid).cloned()
    }
}

#[no_mangle]
pub extern "C" fn poc07_lp_store_new() -> *mut LpStore {
    Box::into_raw(Box::new(LpStore {
        inner: Arc::new(Mutex::new(StoreInner {
            manifests: HashMap::new(),
            blocks: HashMap::new(),
        })),
    }))
}

#[no_mangle]
pub extern "C" fn poc07_lp_store_set_manifest(
    store: *mut LpStore,
    obra_id: *const c_char,
    data: *const u8,
    len: usize,
) {
    if store.is_null() || data.is_null() {
        return;
    }
    let s = unsafe { &*store };
    let obra = unsafe { cstr(obra_id) };
    let bytes = unsafe { std::slice::from_raw_parts(data, len) }.to_vec();
    s.inner.lock().unwrap().manifests.insert(obra, bytes);
}

#[no_mangle]
pub extern "C" fn poc07_lp_store_put_block(
    store: *mut LpStore,
    cid: *const c_char,
    data: *const u8,
    len: usize,
) {
    if store.is_null() || data.is_null() {
        return;
    }
    let s = unsafe { &*store };
    let cid = unsafe { cstr(cid) };
    let bytes = unsafe { std::slice::from_raw_parts(data, len) }.to_vec();
    s.inner.lock().unwrap().blocks.insert(cid, bytes);
}

#[no_mangle]
pub extern "C" fn poc07_lp_free_store(store: *mut LpStore) {
    if !store.is_null() {
        unsafe { drop(Box::from_raw(store)); }
    }
}

/// Sobe um full node libp2p (bootstrap ou publicador) servindo do LpStore. Modo loopback
/// (sem IP público → anuncia os endereços de escuta locais como externos). Devolve ponteiro
/// opaco ou null em erro. `seed_hex` = 32 bytes hex (identidade Ed25519 determinística).
#[no_mangle]
pub extern "C" fn poc07_lp_server_new(
    store: *mut LpStore,
    seed_hex: *const c_char,
    listen_port: u16,
) -> *mut ServerNode {
    if store.is_null() {
        return std::ptr::null_mut();
    }
    let s = unsafe { &*store };
    let handle = StoreHandle { inner: Arc::clone(&s.inner) };
    let cfg = ServerConfig {
        identity_seed_hex: unsafe { cstr(seed_hex) },
        listen_port,
        public_ip: None,
        public_port: None,
        ttl_ms: 600_000,
        republish_ms: 10_000,
    };
    match ServerNode::new(cfg, Box::new(handle)) {
        Ok(n) => Box::into_raw(Box::new(n)),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "C" fn poc07_lp_server_peer_id(node: *mut ServerNode) -> *mut c_char {
    if node.is_null() {
        return std::ptr::null_mut();
    }
    into_cstr(unsafe { &*node }.peer_id())
}

#[no_mangle]
pub extern "C" fn poc07_lp_server_listen_port(node: *mut ServerNode) -> u16 {
    if node.is_null() {
        return 0;
    }
    unsafe { &*node }.listen_port()
}

/// disca outro full node como bootstrap (o publicador chama isto apontando p/ o bootstrap).
#[no_mangle]
pub extern "C" fn poc07_lp_server_bootstrap(node: *mut ServerNode, multiaddr: *const c_char) -> i32 {
    if node.is_null() {
        return -1;
    }
    match block_on(unsafe { &*node }.bootstrap(unsafe { cstr(multiaddr) })) {
        Ok(_) => 0,
        Err(_) => -1,
    }
}

/// anuncia a obra na DHT (Kademlia start_providing).
#[no_mangle]
pub extern "C" fn poc07_lp_server_start_providing(node: *mut ServerNode, obra_id: *const c_char) -> i32 {
    if node.is_null() {
        return -1;
    }
    match block_on(unsafe { &*node }.start_providing(unsafe { cstr(obra_id) })) {
        Ok(_) => 0,
        Err(_) => -1,
    }
}

#[no_mangle]
pub extern "C" fn poc07_lp_free_server(node: *mut ServerNode) {
    if !node.is_null() {
        unsafe { drop(Box::from_raw(node)); }
    }
}
