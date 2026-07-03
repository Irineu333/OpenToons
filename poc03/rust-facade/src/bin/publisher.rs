//! Nó publicador/bootstrap de HOST da PoC poc-03 (E2/E3/E4) — rust-libp2p.
//!
//! Escuta TCP+QUIC, roda Kademlia em modo server, anuncia-se provider de um obraId e serve
//! o capítulo de teste (manifesto assinado Ed25519 + 3 blocos) por Request-Response — o lado
//! servidor que o facade client-only (lib.rs) não tem. A verificação fica no app (D7); aqui
//! só assinamos e servimos bytes.
//!
//! uso: publisher <porta> <obraId> [bootstrap_multiaddr]
//!  - identidade libp2p determinística por porta (topologia do E5 do poc-01)
//!  - imprime peerId, multiaddrs e a CHAVE PÚBLICA do publicador (hex) p/ o app verificar

use std::time::Duration;

use futures::StreamExt;
use libp2p::{
    identify, identity,
    kad::{self, store::MemoryStore},
    multiaddr::{Multiaddr, Protocol},
    noise,
    request_response::{self, ProtocolSupport},
    swarm::{NetworkBehaviour, SwarmEvent},
    tcp, yamux, PeerId, StreamProtocol, SwarmBuilder,
};
use sha2::{Digest, Sha256};

const BLOCK_PROTOCOL: &str = "/opentoons/blocks/1.0.0";

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
struct BlocksRequest {
    cids: Vec<String>,
}
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
struct BlocksResponse {
    blocks: Vec<Vec<u8>>,
}

#[derive(NetworkBehaviour)]
struct Behaviour {
    kad: kad::Behaviour<MemoryStore>,
    blocks: request_response::cbor::Behaviour<BlocksRequest, BlocksResponse>,
    identify: identify::Behaviour,
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut args = std::env::args().skip(1);
    let port: u16 = args.next().expect("uso: publisher <porta> <obraId> [bootstrap]").parse()?;
    let obra_id = args.next().expect("faltou obraId");
    let bootstrap = args.next();

    // identidade libp2p determinística pela porta (identidades por porta, design D4/E5)
    let mut seed = [0x11u8; 32];
    seed[0] = (port & 0xff) as u8;
    seed[1] = (port >> 8) as u8;
    let id_keys = identity::Keypair::ed25519_from_bytes(seed)?;
    let local_peer = PeerId::from(id_keys.public());

    // chave de CONTEÚDO (assina o manifesto) — seed fixa p/ o app conhecer a pubkey.
    // SecretKey::try_from_bytes espera 32 bytes (Keypair::try_from_bytes espera 64).
    let mut content_seed = [0x42u8; 32];
    let content_secret = identity::ed25519::SecretKey::try_from_bytes(&mut content_seed)?;
    let content_key = identity::ed25519::Keypair::from(content_secret);
    let content_pub = content_key.public().to_bytes(); // 32 bytes
    let mut chapter = build_test_chapter(&obra_id, &content_key, &content_pub);
    // E4/7.3: se POC3_TAMPER=block, corrompe 1 byte de um bloco de conteúdo (hash diverge do
    // manifesto → o app deve rejeitar por BlockHashMismatch). =sig corrompe a assinatura.
    match std::env::var("POC3_TAMPER").as_deref() {
        Ok("block") => { if chapter.len() > 1 { chapter[1][0] ^= 0x01; } eprintln!("TAMPER=block"); }
        Ok("sig") => { let m = chapter[0].len(); chapter[0][m - 1] ^= 0x01; eprintln!("TAMPER=sig"); }
        _ => {}
    }

    let mut swarm = SwarmBuilder::with_existing_identity(id_keys)
        .with_tokio()
        .with_tcp(tcp::Config::default(), noise::Config::new, yamux::Config::default)?
        .with_quic()
        .with_behaviour(|key| {
            let mut kad = kad::Behaviour::new(local_peer, MemoryStore::new(local_peer));
            kad.set_mode(Some(kad::Mode::Server)); // nó pleno: serve a DHT
            Ok(Behaviour {
                kad,
                blocks: request_response::cbor::Behaviour::new(
                    [(StreamProtocol::new(BLOCK_PROTOCOL), ProtocolSupport::Full)],
                    request_response::Config::default().with_request_timeout(Duration::from_secs(30)),
                ),
                identify: identify::Behaviour::new(identify::Config::new(
                    "/opentoons/0.0.1".into(),
                    key.public(),
                )),
            })
        })?
        .with_swarm_config(|c| c.with_idle_connection_timeout(Duration::from_secs(120)))
        .build();

    swarm.listen_on(format!("/ip4/0.0.0.0/tcp/{port}").parse()?)?;
    swarm.listen_on(format!("/ip4/0.0.0.0/udp/{port}/quic-v1").parse()?)?;

    // E4: se POC3_PUBLIC_IP estiver setado, anuncia o endereço PÚBLICO (port forwarding) como
    // externo → o bootstrap fica discável de fora e o provider record carrega o addr público,
    // então o cliente em outra rede (dados móveis) disca o publicador nunca informado.
    if let Ok(ip) = std::env::var("POC3_PUBLIC_IP") {
        let pub_tcp: Multiaddr = format!("/ip4/{ip}/tcp/{port}").parse()?;
        let pub_quic: Multiaddr = format!("/ip4/{ip}/udp/{port}/quic-v1").parse()?;
        swarm.add_external_address(pub_tcp.clone());
        swarm.add_external_address(pub_quic.clone());
        eprintln!("PUBLIC {pub_tcp}/p2p/{local_peer}");
    }

    if let Some(b) = bootstrap {
        let addr: Multiaddr = b.parse()?;
        if let Some(peer) = addr.iter().find_map(|p| if let Protocol::P2p(id) = p { Some(id) } else { None }) {
            swarm.behaviour_mut().kad.add_address(&peer, addr.clone());
            swarm.dial(addr)?;
            let _ = swarm.behaviour_mut().kad.bootstrap();
            eprintln!("bootstrap: {b}");
        }
    }

    // anuncia-se provider do obraId (descoberta fria do E3)
    let key = kad::RecordKey::new(&obra_id.clone().into_bytes());
    swarm.behaviour_mut().kad.start_providing(key)?;

    eprintln!("=== PUBLISHER RUST ===");
    eprintln!("peerId={local_peer}");
    eprintln!("obraId={obra_id}");
    eprintln!("contentPubKeyHex={}", hex(&content_pub));
    eprintln!("blocos do capítulo: {} (manifesto + {} conteúdo)", chapter.len(), chapter.len() - 1);

    loop {
        match swarm.select_next_some().await {
            SwarmEvent::NewListenAddr { address, .. } => {
                eprintln!("LISTEN {}/p2p/{}", address, local_peer);
                // publica os endereços de escuta como externos SÓ no modo local (sem
                // POC3_PUBLIC_IP): assim o provider record carrega o addr certo. No modo
                // público, só o addr público é anunciado (acima) — senão o cliente em outra
                // rede tenta discar 127.0.0.1/192.168.x do provider e falha.
                if std::env::var("POC3_PUBLIC_IP").is_err() {
                    swarm.add_external_address(address);
                }
            }
            SwarmEvent::Behaviour(BehaviourEvent::Blocks(request_response::Event::Message {
                message: request_response::Message::Request { request, channel, .. },
                peer,
                ..
            })) => {
                eprintln!("REQUEST de {peer}: {:?}", request.cids);
                // serve o capítulo inteiro (manifesto + blocos) — o obraId identifica o pedido
                let _ = swarm.behaviour_mut().blocks.send_response(
                    channel,
                    BlocksResponse { blocks: chapter.clone() },
                );
            }
            SwarmEvent::Behaviour(BehaviourEvent::Kad(kad::Event::InboundRequest { request })) => {
                eprintln!("KAD inbound: {:?}", request);
            }
            SwarmEvent::ConnectionEstablished { peer_id, .. } => eprintln!("CONN {peer_id}"),
            _ => {}
        }
    }
}

/// Constrói o capítulo de teste: [manifestBlock, b1, b2, b3]. manifestBlock no formato do
/// Kotlin `ManifestCodec`: [pkLen][pk][sigLen][sig][canonical]; canonical length-prefixed.
fn build_test_chapter(
    obra_id: &str,
    content_key: &identity::ed25519::Keypair,
    content_pub: &[u8],
) -> Vec<Vec<u8>> {
    let contents: Vec<Vec<u8>> = (1..=3)
        .map(|i| format!("pagina-{i}-").repeat(200).into_bytes())
        .collect();
    let content_cids: Vec<String> = contents.iter().map(|b| sha256_hex(b)).collect();

    // blockCids = [label do manifesto, cid1, cid2, cid3]
    let mut block_cids = vec![format!("{obra_id}-manifest")];
    block_cids.extend(content_cids.iter().cloned());

    let canonical = canonical_bytes(obra_id, 1, &block_cids);
    let sig = content_key.sign(&canonical); // 64 bytes Ed25519
    let manifest_block = encode_manifest(content_pub, &sig, &canonical);

    let mut chapter = vec![manifest_block];
    chapter.extend(contents);
    chapter
}

/// Espelha `Manifest.canonicalBytes()` do Kotlin: [count u32][ (len u32)(campo) ]...
/// campos = [chapterId utf8, seq(8 bytes BE), *blockCids utf8].
fn canonical_bytes(chapter_id: &str, seq: i64, block_cids: &[String]) -> Vec<u8> {
    let mut fields: Vec<Vec<u8>> = vec![chapter_id.as_bytes().to_vec(), seq.to_be_bytes().to_vec()];
    fields.extend(block_cids.iter().map(|c| c.as_bytes().to_vec()));
    let mut out = Vec::new();
    out.extend_from_slice(&(fields.len() as u32).to_be_bytes());
    for f in &fields {
        out.extend_from_slice(&(f.len() as u32).to_be_bytes());
        out.extend_from_slice(f);
    }
    out
}

/// Espelha `ManifestCodec.encode`: [pkLen][pk][sigLen][sig][canonical].
fn encode_manifest(pk: &[u8], sig: &[u8], canonical: &[u8]) -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(&(pk.len() as u32).to_be_bytes());
    out.extend_from_slice(pk);
    out.extend_from_slice(&(sig.len() as u32).to_be_bytes());
    out.extend_from_slice(sig);
    out.extend_from_slice(canonical);
    out
}

fn sha256_hex(data: &[u8]) -> String {
    hex(&Sha256::digest(data))
}
fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}
