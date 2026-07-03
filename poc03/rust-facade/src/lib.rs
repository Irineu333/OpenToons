//! Facade Rust do poc-03 sobre rust-libp2p de referência (design D1, Tier B).
//!
//! Superfície (E2, D2/D3): dial / resolve / get_blocks. A verificação Ed25519 + hash
//! (a "quarta chamada", verify) NÃO cruza a fronteira (D7): é feita em Kotlin, do lado
//! do app. Este facade entrega bytes.
//!
//! Arquitetura: o Swarm roda num ator Tokio dedicado; as chamadas UniFFI (async) enviam
//! comandos por canal e aguardam a resposta por oneshot. Isso mantém `Swarm: !Sync`
//! confinado a uma thread e dá uma fronteira FFI simples (sem expor tipos libp2p).

use std::collections::HashMap;
use std::time::Duration;

use futures::StreamExt;
use libp2p::{
    core::multiaddr::Multiaddr,
    dcutr, identify, identity,
    kad::{self, store::MemoryStore, QueryId},
    noise, relay,
    request_response::{self, ProtocolSupport},
    swarm::{NetworkBehaviour, SwarmEvent},
    tcp, yamux, PeerId, StreamProtocol, SwarmBuilder,
};
use tokio::sync::{mpsc, oneshot};

uniffi::include_scaffolding!("facade");

const BLOCK_PROTOCOL: &str = "/opentoons/blocks/1.0.0";
const DIAL_TIMEOUT: Duration = Duration::from_secs(15);
const REQUEST_TIMEOUT: Duration = Duration::from_secs(30);

#[derive(Debug, thiserror::Error)]
pub enum FacadeError {
    #[error("init: {0}")]
    Init(String),
    #[error("dial: {0}")]
    Dial(String),
    #[error("resolve: {0}")]
    Resolve(String),
    #[error("request: {0}")]
    Request(String),
    #[error("timeout")]
    Timeout,
}

// Request-Response por CBOR (paridade D3): pedido é a lista de CIDs, resposta são os
// blocos; o Kotlin re-empacota length-prefixed, fatia e verifica (D7). relay_client +
// dcutr existem só para os dados de NAT só-coletados (E5/8.4).
#[derive(NetworkBehaviour)]
struct Behaviour {
    kad: kad::Behaviour<MemoryStore>,
    blocks: request_response::cbor::Behaviour<BlocksRequest, BlocksResponse>,
    identify: identify::Behaviour,
    dcutr: dcutr::Behaviour,
    relay_client: relay::client::Behaviour,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
struct BlocksRequest {
    cids: Vec<String>,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
struct BlocksResponse {
    // blocos na ordem pedida; o Kotlin verifica hash de cada um (D7)
    blocks: Vec<Vec<u8>>,
}

// Comandos que as chamadas FFI enviam ao ator do Swarm.
enum Cmd {
    Dial {
        addr: Multiaddr,
        reply: oneshot::Sender<Result<(), FacadeError>>,
    },
    Resolve {
        obra_id: String,
        reply: oneshot::Sender<Result<String, FacadeError>>,
    },
    GetBlocks {
        addr: Multiaddr,
        cids: Vec<String>,
        reply: oneshot::Sender<Result<Vec<u8>, FacadeError>>,
    },
}

/// Handle opaco exposto ao Kotlin. Um objeto atravessa a fronteira; os métodos são as
/// chamadas FFI. Guardado num Arc pela camada UniFFI.
pub struct Node {
    peer_id: String,
    tx: mpsc::Sender<Cmd>,
    // mantido vivo de propósito: dropar o Node dropa o runtime e encerra o ator do swarm.
    #[allow(dead_code)]
    runtime: tokio::runtime::Runtime,
}

impl Node {
    /// Modo client puro (ADR-0005): descobre e disca, não serve blocos nem entra como
    /// server na DHT; sem endereços de escuta.
    ///
    /// SÍNCRONO de propósito (ver facade.udl): o init bloqueia até o nó estar pronto usando
    /// o runtime interno; as chamadas de rede (dial/resolve/get_blocks) é que são async.
    pub fn new(bootstrap_multiaddr: String) -> Result<Self, FacadeError> {
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()
            .map_err(|e| FacadeError::Init(e.to_string()))?;

        let (tx, rx) = mpsc::channel::<Cmd>(32);
        let (ready_tx, ready_rx) = oneshot::channel::<Result<String, FacadeError>>();

        runtime.spawn(async move {
            match build_swarm() {
                Ok(mut swarm) => {
                    let pid = swarm.local_peer_id().to_string();
                    // Modo client no Kademlia: consulta mas não vira servidor da tabela.
                    swarm
                        .behaviour_mut()
                        .kad
                        .set_mode(Some(kad::Mode::Client));
                    let boot_ok = connect_bootstrap(&mut swarm, &bootstrap_multiaddr).await;
                    let _ = ready_tx.send(boot_ok.map(|_| pid));
                    swarm_loop(swarm, rx).await;
                }
                Err(e) => {
                    let _ = ready_tx.send(Err(e));
                }
            }
        });

        // bloqueia o thread chamador até o nó estar pronto (init do client é rápido).
        let peer_id = runtime
            .block_on(ready_rx)
            .map_err(|_| FacadeError::Init("ator do swarm morreu".into()))??;

        Ok(Node { peer_id, tx, runtime })
    }

    pub fn peer_id(&self) -> String {
        self.peer_id.clone()
    }

    /// dial (chamada FFI 1)
    pub async fn dial(&self, multiaddr_with_peer: String) -> Result<(), FacadeError> {
        let addr: Multiaddr = multiaddr_with_peer
            .parse()
            .map_err(|e| FacadeError::Dial(format!("multiaddr: {e}")))?;
        let (reply, rx) = oneshot::channel();
        self.send(Cmd::Dial { addr, reply }).await?;
        rx.await.map_err(|_| FacadeError::Timeout)?
    }

    /// resolve (chamada FFI 2) — descoberta fria via Kademlia real (E3/6.2)
    pub async fn resolve(&self, obra_id: String) -> Result<String, FacadeError> {
        let (reply, rx) = oneshot::channel();
        self.send(Cmd::Resolve { obra_id, reply }).await?;
        rx.await.map_err(|_| FacadeError::Timeout)?
    }

    /// get_blocks (chamada FFI 3) — Request-Response; blocos length-prefixed p/ o Kotlin
    pub async fn get_blocks(
        &self,
        peer_multiaddr: String,
        cids: String,
    ) -> Result<Vec<u8>, FacadeError> {
        let addr: Multiaddr = peer_multiaddr
            .parse()
            .map_err(|e| FacadeError::Request(format!("multiaddr: {e}")))?;
        let cids: Vec<String> = cids
            .lines()
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(String::from)
            .collect();
        if cids.is_empty() {
            return Err(FacadeError::Request("nenhum cid pedido".into()));
        }
        let (reply, rx) = oneshot::channel();
        self.send(Cmd::GetBlocks { addr, cids, reply }).await?;
        rx.await.map_err(|_| FacadeError::Timeout)?
    }

    async fn send(&self, cmd: Cmd) -> Result<(), FacadeError> {
        self.tx
            .send(cmd)
            .await
            .map_err(|_| FacadeError::Request("swarm indisponível".into()))
    }
}

fn build_swarm() -> Result<libp2p::Swarm<Behaviour>, FacadeError> {
    // Sem with_dns: o bootstrap e o publicador são discados por IP (E4, port forwarding),
    // então evitamos a dependência de DNS e o passo async do builder.
    let swarm = SwarmBuilder::with_new_identity()
        .with_tokio()
        .with_tcp(
            tcp::Config::default(),
            noise::Config::new,
            yamux::Config::default,
        )
        .map_err(|e| FacadeError::Init(e.to_string()))?
        .with_quic()
        .with_relay_client(noise::Config::new, yamux::Config::default)
        .map_err(|e| FacadeError::Init(e.to_string()))?
        .with_behaviour(|key, relay_client| Ok(build_behaviour(key, relay_client)))
        .map_err(|e| FacadeError::Init(e.to_string()))?
        .with_swarm_config(|c| c.with_idle_connection_timeout(Duration::from_secs(60)))
        .build();
    Ok(swarm)
}

fn build_behaviour(key: &identity::Keypair, relay_client: relay::client::Behaviour) -> Behaviour {
    let peer_id = PeerId::from(key.public());
    let kad = kad::Behaviour::new(peer_id, MemoryStore::new(peer_id));
    let blocks = request_response::cbor::Behaviour::new(
        [(
            StreamProtocol::new(BLOCK_PROTOCOL),
            ProtocolSupport::Outbound,
        )],
        request_response::Config::default().with_request_timeout(REQUEST_TIMEOUT),
    );
    let identify = identify::Behaviour::new(identify::Config::new(
        "/opentoons/0.0.1".into(),
        key.public(),
    ));
    let dcutr = dcutr::Behaviour::new(peer_id);
    Behaviour {
        kad,
        blocks,
        identify,
        dcutr,
        relay_client,
    }
}

async fn connect_bootstrap(
    swarm: &mut libp2p::Swarm<Behaviour>,
    bootstrap: &str,
) -> Result<(), FacadeError> {
    if bootstrap.is_empty() {
        return Ok(());
    }
    let addr: Multiaddr = bootstrap
        .parse()
        .map_err(|e| FacadeError::Init(format!("bootstrap multiaddr: {e}")))?;
    let peer = extract_peer(&addr)
        .ok_or_else(|| FacadeError::Init("bootstrap sem /p2p/<id>".into()))?;
    swarm.behaviour_mut().kad.add_address(&peer, addr.clone());
    swarm
        .dial(addr)
        .map_err(|e| FacadeError::Init(format!("dial bootstrap: {e}")))?;
    let _ = swarm.behaviour_mut().kad.bootstrap();
    Ok(())
}

// Loop do ator: traduz eventos do Swarm e comandos FFI. Rastreia queries/requests
// pendentes para casar a resposta com o oneshot certo.
async fn swarm_loop(mut swarm: libp2p::Swarm<Behaviour>, mut rx: mpsc::Receiver<Cmd>) {
    // pending_resolve: get_providers (acha QUEM tem). pending_findpeer: get_closest_peers /
    // FIND_NODE (acha os ENDEREÇOS do provider) — 2 passos, senão o cliente tem só o PeerId e
    // o request_response não sabe discar (E4).
    let mut pending_resolve: HashMap<QueryId, oneshot::Sender<Result<String, FacadeError>>> =
        HashMap::new();
    let mut pending_findpeer: HashMap<QueryId, (oneshot::Sender<Result<String, FacadeError>>, PeerId)> =
        HashMap::new();
    let mut pending_blocks: HashMap<
        request_response::OutboundRequestId,
        oneshot::Sender<Result<Vec<u8>, FacadeError>>,
    > = HashMap::new();

    loop {
        tokio::select! {
            cmd = rx.recv() => match cmd {
                None => break, // Node dropado → canal fechado → encerra o ator
                Some(Cmd::Dial { addr, reply }) => {
                    if let Some(peer) = extract_peer(&addr) {
                        swarm.behaviour_mut().kad.add_address(&peer, addr.clone());
                    }
                    let res = tokio::time::timeout(DIAL_TIMEOUT, async {
                        swarm.dial(addr).map_err(|e| FacadeError::Dial(e.to_string()))
                    }).await.unwrap_or(Err(FacadeError::Timeout));
                    let _ = reply.send(res);
                }
                Some(Cmd::Resolve { obra_id, reply }) => {
                    let key = kad::RecordKey::new(&obra_id.into_bytes());
                    let qid = swarm.behaviour_mut().kad.get_providers(key);
                    pending_resolve.insert(qid, reply);
                }
                Some(Cmd::GetBlocks { addr, cids, reply }) => {
                    match extract_peer(&addr) {
                        Some(peer) => {
                            // resolve devolve multiaddr completo (2 passos) → registra o endereço
                            // no Kademlia p/ o request_response discar.
                            if has_transport(&addr) {
                                swarm.behaviour_mut().kad.add_address(&peer, addr);
                            }
                            let rid = swarm.behaviour_mut().blocks.send_request(
                                &peer, BlocksRequest { cids },
                            );
                            pending_blocks.insert(rid, reply);
                        }
                        None => { let _ = reply.send(Err(FacadeError::Request("sem /p2p/<id>".into()))); }
                    }
                }
            },
            event = swarm.select_next_some() => match event {
                // passo 1: get_providers achou o provider → dispara FIND_NODE p/ obter endereços
                SwarmEvent::Behaviour(BehaviourEvent::Kad(kad::Event::OutboundQueryProgressed {
                    id, result: kad::QueryResult::GetProviders(Ok(ok)), ..
                })) => match ok {
                    kad::GetProvidersOk::FoundProviders { providers, .. } if !providers.is_empty() => {
                        if let Some(reply) = pending_resolve.remove(&id) {
                            let peer = providers.into_iter().next().unwrap();
                            let qid = swarm.behaviour_mut().kad.get_closest_peers(peer);
                            pending_findpeer.insert(qid, (reply, peer));
                        }
                    }
                    kad::GetProvidersOk::FinishedWithNoAdditionalRecord { .. } => {
                        if let Some(reply) = pending_resolve.remove(&id) {
                            let _ = reply.send(Err(FacadeError::Resolve("nenhum provider".into())));
                        }
                    }
                    _ => {}
                },
                // passo 2: FIND_NODE trouxe os endereços do provider → devolve multiaddr discável
                SwarmEvent::Behaviour(BehaviourEvent::Kad(kad::Event::OutboundQueryProgressed {
                    id, result: kad::QueryResult::GetClosestPeers(res), ..
                })) => {
                    if let Some((reply, target)) = pending_findpeer.remove(&id) {
                        let addr = match res {
                            Ok(ok) => ok.peers.into_iter()
                                .find(|p| p.peer_id == target)
                                .and_then(|p| best_addr(&p.addrs)),
                            Err(_) => None,
                        };
                        let _ = reply.send(Ok(
                            addr.map(|a| format!("{a}/p2p/{target}"))
                                .unwrap_or_else(|| format!("/p2p/{target}")),
                        ));
                    }
                }
                SwarmEvent::Behaviour(BehaviourEvent::Blocks(
                    request_response::Event::Message { message, .. },
                )) => {
                    if let request_response::Message::Response { request_id, response } = message {
                        if let Some(reply) = pending_blocks.remove(&request_id) {
                            let _ = reply.send(Ok(encode_length_prefixed(&response.blocks)));
                        }
                    }
                }
                SwarmEvent::Behaviour(BehaviourEvent::Blocks(
                    request_response::Event::OutboundFailure { request_id, error, .. },
                )) => {
                    if let Some(reply) = pending_blocks.remove(&request_id) {
                        let _ = reply.send(Err(FacadeError::Request(error.to_string())));
                    }
                }
                _ => {}
            }
        }
    }
}

// escolhe o melhor endereço discável de um provider: prefere não-loopback (público/LAN) com
// transporte, cai para qualquer um com transporte.
fn best_addr(addrs: &[Multiaddr]) -> Option<Multiaddr> {
    use libp2p::multiaddr::Protocol::*;
    let is_loopback = |a: &Multiaddr| a.iter().any(|p| matches!(p, Ip4(ip) if ip.is_loopback()));
    addrs.iter().filter(|a| has_transport(a)).find(|a| !is_loopback(a))
        .or_else(|| addrs.iter().find(|a| has_transport(a)))
        .cloned()
}

// Mesmo formato de wire do go-facade: [len u32 BE][bloco]... — paridade p/ o Kotlin (D7).
fn encode_length_prefixed(blocks: &[Vec<u8>]) -> Vec<u8> {
    let mut out = Vec::with_capacity(blocks.iter().map(|b| b.len() + 4).sum());
    for b in blocks {
        out.extend_from_slice(&(b.len() as u32).to_be_bytes());
        out.extend_from_slice(b);
    }
    out
}

fn extract_peer(addr: &Multiaddr) -> Option<PeerId> {
    addr.iter().find_map(|p| match p {
        libp2p::multiaddr::Protocol::P2p(id) => Some(id),
        _ => None,
    })
}

// true se o multiaddr tem um endereço de transporte discável (ip/dns), não só `/p2p/<id>`.
fn has_transport(addr: &Multiaddr) -> bool {
    use libp2p::multiaddr::Protocol::*;
    addr.iter().any(|p| matches!(p, Ip4(_) | Ip6(_) | Dns(_) | Dns4(_) | Dns6(_)))
}
