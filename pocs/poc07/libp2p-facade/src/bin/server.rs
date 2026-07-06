//! poc-07 célula 2 — servidor libp2p de campanha (VPS). Full node rust-libp2p (Kademlia server
//! + request-response) servindo o capítulo de 768 KiB a partir de arquivos gerados pelo Kotlin
//! (mesmo manifesto assinado + blocos por CID da CampaignVectors). Anuncia IP público (ADR-0006)
//! para o leitor iOS (libp2p) discar por dados móveis. Reusa o ServerNode do facade poc-04.

use futures::executor::block_on;
use std::fs;
use std::path::PathBuf;
use uniffi_facade::{BlockstoreCallback, ServerConfig, ServerNode};

/// Identidade Ed25519 determinística (sha256("poc7-libp2p-vps")) → PeerId estável p/ o cliente.
const SEED_HEX: &str = "6bd3aa0cdf33dec6b9742d16471e81b851ad05c38ade44551460c66d58315251";
const OBRA: &str = "opentoons/serie-teste";

/// Blockstore neutro lendo do diretório: manifest.bin (por obra) e block_<cid>.bin (por CID).
struct FileStore {
    dir: PathBuf,
}

impl BlockstoreCallback for FileStore {
    fn manifest(&self, obra_id: String) -> Option<Vec<u8>> {
        if obra_id != OBRA {
            return None;
        }
        fs::read(self.dir.join("manifest.bin")).ok()
    }

    fn block(&self, cid: String) -> Option<Vec<u8>> {
        // cid é sha256 hex; nome de arquivo saneado
        if !cid.chars().all(|c| c.is_ascii_hexdigit()) {
            return None;
        }
        fs::read(self.dir.join(format!("block_{cid}.bin"))).ok()
    }
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let dir = args.get(1).cloned().unwrap_or_else(|| ".".into());
    let listen_port: u16 = args.get(2).and_then(|s| s.parse().ok()).unwrap_or(6080);
    let public_ip = args.get(3).cloned();
    let public_port: Option<u16> = args.get(4).and_then(|s| s.parse().ok()).or(Some(listen_port));

    let cfg = ServerConfig {
        identity_seed_hex: SEED_HEX.to_string(),
        listen_port,
        public_ip: public_ip.clone(),
        public_port,
        ttl_ms: 10 * 60_000,
        republish_ms: 15_000,
    };

    let store = FileStore { dir: PathBuf::from(&dir) };
    let server = ServerNode::new(cfg, Box::new(store)).expect("ServerNode::new");
    println!(
        "LP-SERVER-UP peer_id={} listen_port={} public={}:{}",
        server.peer_id(),
        server.listen_port(),
        public_ip.clone().unwrap_or_else(|| "0.0.0.0".into()),
        public_port.unwrap_or(listen_port),
    );

    block_on(server.start_providing(OBRA.to_string())).expect("start_providing");
    println!("LP-SERVER-PROVIDING obra={OBRA}");
    // fica no ar
    loop {
        std::thread::sleep(std::time::Duration::from_secs(3600));
    }
}
