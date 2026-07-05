//! Facade Rust do poc-04 sobre rust-libp2p — o facade client do poc-03 ESTENDIDO com o
//! lado FULL NODE via FFI (E2): listen (TCP+QUIC), Kademlia `Mode::Server`,
//! `start_providing` e serve-blocks por Request-Response, dirigido pela interface Kotlin
//! `FullNode` do seam (`:poc04:api`) — nunca por binário avulso.
//!
//! Superfície client (provada no poc-03): dial / resolve / get_manifest / get_blocks.
//! A verificação Ed25519 + hash NÃO cruza a fronteira (D7): é Kotlin, do lado do app.
//!
//! Arquitetura: cada nó (client ou server) roda o Swarm num ator Tokio dedicado; as
//! chamadas UniFFI (async) enviam comandos por canal e aguardam por oneshot. O CONTEÚDO
//! servido vem de um callback Kotlin ([BlockstoreCallback]) — o blockstore é neutro e
//! vive fora do backend (dual-stack E5; adulteração neutra do TCK).

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

mod socks;

uniffi::include_scaffolding!("facade");

const BLOCK_PROTOCOL: &str = "/opentoons/blocks/1.0.0";
const PUSH_PROTOCOL: &str = "/opentoons/push/1.0.0"; // poc-05 E2: replicação por empurrão
const DIAL_TIMEOUT: Duration = Duration::from_secs(60); // circuito Tor: dial frio é lento (D7)
const REQUEST_TIMEOUT: Duration = Duration::from_secs(90); // push de 768 KiB pelo circuito (D7)

/// poc-05 (D2): proxy SOCKS do daemon Tor local, passado como config de fábrica do backend.
pub struct SocksProxy {
    pub host: String,
    pub port: u16,
}

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

/// Fonte de conteúdo do lado Kotlin (Blockstore neutro do `:api`). O servidor chama de
/// volta a JVM a cada request — trait gerado pelo scaffolding UniFFI (callback interface).
pub trait BlockstoreCallback: Send + Sync {
    fn manifest(&self, obra_id: String) -> Option<Vec<u8>>;
    fn block(&self, cid: String) -> Option<Vec<u8>>;
    /// poc-05 (D1): recepção de push. O Kotlin valida a assinatura da editora ([PushPolicy])
    /// e, se aceita, grava manifesto+blocos no Blockstore neutro; devolve `accepted`. Manter
    /// a decisão no Kotlin conserva a política IDÊNTICA nos dois backends e o verify fora do
    /// rust (D7). O rust só transporta e devolve o veredito ao publicador.
    fn accept_push(&self, obra_id: String, manifest: Vec<u8>, blocks: Vec<Vec<u8>>) -> bool;
}

pub struct ServerConfig {
    pub identity_seed_hex: String,
    pub listen_port: u16,
    pub public_ip: Option<String>,
    pub public_port: Option<u16>,
    pub onion_host: Option<String>, // poc-05 C2 dual-homed
    pub ttl_ms: u64,
    pub republish_ms: u64,
}

/// Deriva o PeerId libp2p (base58) da chave pública Ed25519 neutra do seam.
pub fn peer_id_from_ed25519(pubkey_hex: String) -> Result<String, FacadeError> {
    let bytes = decode_hex(&pubkey_hex).map_err(FacadeError::Init)?;
    let pk = identity::ed25519::PublicKey::try_from_bytes(&bytes)
        .map_err(|e| FacadeError::Init(format!("pubkey: {e}")))?;
    Ok(PeerId::from(identity::PublicKey::from(pk)).to_string())
}

// Request-Response por CBOR. Wire do poc-04: o pedido pode ser de manifesto (por obraId)
// ou de blocos (por cid sha-256) — formato interno ao backend, invisível ao seam.
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
struct BlocksRequest {
    manifest_for: Option<String>,
    cids: Vec<String>,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
struct BlocksResponse {
    manifest: Option<Vec<u8>>,
    // blocos na ordem pedida; o Kotlin verifica hash de cada um (D7)
    blocks: Vec<Vec<u8>>,
}

// poc-05 E2: frame de PUSH (o publicador empurra). Sem campo de origem (D1) — só obra +
// manifesto + blocos. A aceitação (assinatura da editora, PushPolicy) roda no Kotlin, via
// o callback accept_push — o rust só transporta, como o verify de leitura (D7).
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
struct PushRequest {
    obra_id: String,
    #[serde(with = "serde_bytes")]
    manifest: Vec<u8>,
    // byte-strings CBOR (não array-de-inteiros): cabe no limite de 1 MiB do request
    blocks: Vec<serde_bytes::ByteBuf>,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
struct PushResponse {
    accepted: bool,
}

// ============================= CLIENT (poc-03, mantido) =============================

// relay_client + dcutr existem para hole-punch (capability só-libp2p do E3).
#[derive(NetworkBehaviour)]
struct Behaviour {
    kad: kad::Behaviour<MemoryStore>,
    blocks: request_response::cbor::Behaviour<BlocksRequest, BlocksResponse>,
    // poc-05 E2: o client também EMPURRA (publicador). Outbound: só envia push.
    push: request_response::cbor::Behaviour<PushRequest, PushResponse>,
    identify: identify::Behaviour,
    dcutr: dcutr::Behaviour,
    relay_client: relay::client::Behaviour,
}

/// poc-05 E2 (D6) — behaviour do publicador ANÔNIMO: MÍNIMO por contenção. Sem identify
/// (não anuncia endereços observados), sem dcutr/relay (não faz hole-punch/dial direto),
/// sem mDNS (nunca presente). Só o essencial: kad (client, p/ descoberta via Tor no E3),
/// blocks (leitura eventual) e push. A contenção real, porém, é o TRANSPORTE (SOCKS-only):
/// nenhum destes behaviours consegue discar fora do circuito porque não há outro transporte.
#[derive(NetworkBehaviour)]
struct AnonBehaviour {
    kad: kad::Behaviour<MemoryStore>,
    blocks: request_response::cbor::Behaviour<BlocksRequest, BlocksResponse>,
    push: request_response::cbor::Behaviour<PushRequest, PushResponse>,
}

// Comandos que as chamadas FFI do client enviam ao ator do Swarm.
enum Cmd {
    Dial {
        addr: Multiaddr,
        reply: oneshot::Sender<Result<(), FacadeError>>,
    },
    Resolve {
        obra_id: String,
        reply: oneshot::Sender<Result<String, FacadeError>>,
    },
    GetManifest {
        addr: Multiaddr,
        obra_id: String,
        reply: oneshot::Sender<Result<Vec<u8>, FacadeError>>,
    },
    GetBlocks {
        addr: Multiaddr,
        cids: Vec<String>,
        reply: oneshot::Sender<Result<Vec<u8>, FacadeError>>,
    },
    Push {
        addr: Multiaddr,
        obra_id: String,
        manifest: Vec<u8>,
        blocks: Vec<Vec<u8>>,
        reply: oneshot::Sender<Result<(), FacadeError>>,
    },
}

pub struct Node {
    peer_id: String,
    tx: mpsc::Sender<Cmd>,
    // mantido vivo de propósito: dropar o Node dropa o runtime e encerra o ator do swarm.
    #[allow(dead_code)]
    runtime: tokio::runtime::Runtime,
}

impl Node {
    /// Client (ADR-0005). SÍNCRONO de propósito (ver facade.udl). [socks] = `Some` ativa o
    /// modo ANÔNIMO (poc-05 D2): transporte SOCKS-only, QUIC off, swarm mínimo contido (D6);
    /// `None` = o client clearnet do poc-04 (TCP+QUIC, reader mobile). O `push` funciona nos
    /// dois; o publicador do produto é `Some`.
    pub fn new(bootstrap_multiaddr: String, socks: Option<SocksProxy>) -> Result<Self, FacadeError> {
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()
            .map_err(|e| FacadeError::Init(e.to_string()))?;

        let (tx, rx) = mpsc::channel::<Cmd>(32);
        let (ready_tx, ready_rx) = oneshot::channel::<Result<String, FacadeError>>();

        match socks {
            None => {
                runtime.spawn(async move {
                    match build_client_swarm() {
                        Ok(mut swarm) => {
                            let pid = swarm.local_peer_id().to_string();
                            swarm.behaviour_mut().kad.set_mode(Some(kad::Mode::Client));
                            let boot_ok = connect_bootstrap(&mut swarm, &bootstrap_multiaddr).await;
                            let _ = ready_tx.send(boot_ok.map(|_| pid));
                            client_loop(swarm, rx).await;
                        }
                        Err(e) => { let _ = ready_tx.send(Err(e)); }
                    }
                });
            }
            Some(proxy) => {
                let proxy_addr: std::net::SocketAddr =
                    format!("{}:{}", proxy.host, proxy.port)
                        .parse()
                        .map_err(|e| FacadeError::Init(format!("endpoint SOCKS: {e}")))?;
                runtime.spawn(async move {
                    match build_anon_client_swarm(proxy_addr) {
                        Ok(mut swarm) => {
                            let pid = swarm.local_peer_id().to_string();
                            // bootstrap opcional pelo túnel (E3); dial preguiçoso pelo SOCKS
                            let boot_ok = connect_bootstrap_anon(&mut swarm, &bootstrap_multiaddr);
                            let _ = ready_tx.send(boot_ok.map(|_| pid));
                            anon_client_loop(swarm, rx).await;
                        }
                        Err(e) => { let _ = ready_tx.send(Err(e)); }
                    }
                });
            }
        }

        let peer_id = runtime
            .block_on(ready_rx)
            .map_err(|_| FacadeError::Init("ator do swarm morreu".into()))??;

        Ok(Node { peer_id, tx, runtime })
    }

    pub fn peer_id(&self) -> String {
        self.peer_id.clone()
    }

    pub async fn dial(&self, multiaddr_with_peer: String) -> Result<(), FacadeError> {
        let addr: Multiaddr = multiaddr_with_peer
            .parse()
            .map_err(|e| FacadeError::Dial(format!("multiaddr: {e}")))?;
        let (reply, rx) = oneshot::channel();
        self.send(Cmd::Dial { addr, reply }).await?;
        rx.await.map_err(|_| FacadeError::Timeout)?
    }

    /// Descoberta fria via Kademlia real. Devolve "" quando não há provider vivo.
    pub async fn resolve(&self, obra_id: String) -> Result<String, FacadeError> {
        let (reply, rx) = oneshot::channel();
        self.send(Cmd::Resolve { obra_id, reply }).await?;
        rx.await.map_err(|_| FacadeError::Timeout)?
    }

    pub async fn get_manifest(
        &self,
        peer_multiaddr: String,
        obra_id: String,
    ) -> Result<Vec<u8>, FacadeError> {
        let addr: Multiaddr = peer_multiaddr
            .parse()
            .map_err(|e| FacadeError::Request(format!("multiaddr: {e}")))?;
        let (reply, rx) = oneshot::channel();
        self.send(Cmd::GetManifest { addr, obra_id, reply }).await?;
        rx.await.map_err(|_| FacadeError::Timeout)?
    }

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

    /// poc-05 E2: empurra a obra para [target_multiaddr] (o replicador). Pelo modo anônimo,
    /// o dial vai pelo SOCKS (o multiaddr é `/dns/<onion>.onion/tcp/<port>/p2p/<id>`).
    /// Erro se o receptor rejeitar (`accepted:false`) ou em falha de rede/circuito.
    pub async fn push(
        &self,
        target_multiaddr: String,
        obra_id: String,
        manifest: Vec<u8>,
        blocks: Vec<Vec<u8>>,
    ) -> Result<(), FacadeError> {
        let addr: Multiaddr = target_multiaddr
            .parse()
            .map_err(|e| FacadeError::Request(format!("multiaddr: {e}")))?;
        let (reply, rx) = oneshot::channel();
        self.send(Cmd::Push { addr, obra_id, manifest, blocks, reply }).await?;
        rx.await.map_err(|_| FacadeError::Timeout)?
    }

    async fn send(&self, cmd: Cmd) -> Result<(), FacadeError> {
        self.tx
            .send(cmd)
            .await
            .map_err(|_| FacadeError::Request("swarm indisponível".into()))
    }
}

fn build_client_swarm() -> Result<libp2p::Swarm<Behaviour>, FacadeError> {
    // Sem with_dns: bootstrap/publicador discados por IP (E4, port forwarding).
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
        .with_behaviour(|key, relay_client| Ok(build_client_behaviour(key, relay_client)))
        .map_err(|e| FacadeError::Init(e.to_string()))?
        .with_swarm_config(|c| c.with_idle_connection_timeout(Duration::from_secs(60)))
        .build();
    Ok(swarm)
}

fn build_client_behaviour(
    key: &identity::Keypair,
    relay_client: relay::client::Behaviour,
) -> Behaviour {
    let peer_id = PeerId::from(key.public());
    let kad = kad::Behaviour::new(peer_id, MemoryStore::new(peer_id));
    let blocks = request_response::cbor::Behaviour::new(
        [(
            StreamProtocol::new(BLOCK_PROTOCOL),
            ProtocolSupport::Outbound,
        )],
        request_response::Config::default().with_request_timeout(REQUEST_TIMEOUT),
    );
    let push = request_response::cbor::Behaviour::new(
        [(StreamProtocol::new(PUSH_PROTOCOL), ProtocolSupport::Outbound)],
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
        push,
        identify,
        dcutr,
        relay_client,
    }
}

/// Swarm do publicador ANÔNIMO: transporte SOCKS-only (QUIC OFF por construção — nem é
/// adicionado), upgrade Noise+Yamux, [AnonBehaviour] mínimo. `build` do transporte é o
/// coração do E2 (D6): o dial custom por SOCKS que "não vem pronto".
fn build_anon_client_swarm(proxy: std::net::SocketAddr) -> Result<libp2p::Swarm<AnonBehaviour>, FacadeError> {
    use libp2p::core::muxing::StreamMuxerBox;
    use libp2p::core::upgrade::Version;
    use libp2p::core::Transport as _; // traz o método provido `.upgrade()` ao escopo
    let swarm = SwarmBuilder::with_new_identity()
        .with_tokio()
        .with_other_transport(|key| {
            let noise = noise::Config::new(key).expect("noise a partir de keypair válida");
            socks::SocksTransport::new(proxy)
                .upgrade(Version::V1)
                .authenticate(noise)
                .multiplex(yamux::Config::default())
                .map(|(peer, muxer), _| (peer, StreamMuxerBox::new(muxer)))
        })
        .map_err(|e| FacadeError::Init(format!("transporte SOCKS: {e}")))?
        .with_behaviour(|key| {
            let peer_id = PeerId::from(key.public());
            let mut kad = kad::Behaviour::new(peer_id, MemoryStore::new(peer_id));
            kad.set_mode(Some(kad::Mode::Client));
            Ok(AnonBehaviour {
                kad,
                blocks: request_response::cbor::Behaviour::new(
                    [(StreamProtocol::new(BLOCK_PROTOCOL), ProtocolSupport::Outbound)],
                    request_response::Config::default().with_request_timeout(REQUEST_TIMEOUT),
                ),
                push: request_response::cbor::Behaviour::new(
                    [(StreamProtocol::new(PUSH_PROTOCOL), ProtocolSupport::Outbound)],
                    request_response::Config::default().with_request_timeout(REQUEST_TIMEOUT),
                ),
            })
        })
        .map_err(|e| FacadeError::Init(e.to_string()))?
        .with_swarm_config(|c| c.with_idle_connection_timeout(Duration::from_secs(120)))
        .build();
    Ok(swarm)
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

// Loop do ator client: traduz eventos do Swarm e comandos FFI.
async fn client_loop(mut swarm: libp2p::Swarm<Behaviour>, mut rx: mpsc::Receiver<Cmd>) {
    // resolve em 2 passos (poc-03): get_providers (QUEM tem) + get_closest_peers (ENDEREÇOS).
    let mut pending_resolve: HashMap<QueryId, oneshot::Sender<Result<String, FacadeError>>> =
        HashMap::new();
    let mut pending_findpeer: HashMap<QueryId, (oneshot::Sender<Result<String, FacadeError>>, PeerId)> =
        HashMap::new();
    let mut pending_blocks: HashMap<
        request_response::OutboundRequestId,
        oneshot::Sender<Result<Vec<u8>, FacadeError>>,
    > = HashMap::new();
    let mut pending_manifest: HashMap<
        request_response::OutboundRequestId,
        oneshot::Sender<Result<Vec<u8>, FacadeError>>,
    > = HashMap::new();
    let mut pending_push: HashMap<
        request_response::OutboundRequestId,
        oneshot::Sender<Result<(), FacadeError>>,
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
                Some(Cmd::GetManifest { addr, obra_id, reply }) => {
                    match extract_peer(&addr) {
                        Some(peer) => {
                            if has_transport(&addr) {
                                swarm.behaviour_mut().kad.add_address(&peer, addr);
                            }
                            let rid = swarm.behaviour_mut().blocks.send_request(
                                &peer,
                                BlocksRequest { manifest_for: Some(obra_id), cids: vec![] },
                            );
                            pending_manifest.insert(rid, reply);
                        }
                        None => { let _ = reply.send(Err(FacadeError::Request("sem /p2p/<id>".into()))); }
                    }
                }
                Some(Cmd::GetBlocks { addr, cids, reply }) => {
                    match extract_peer(&addr) {
                        Some(peer) => {
                            if has_transport(&addr) {
                                swarm.behaviour_mut().kad.add_address(&peer, addr);
                            }
                            let rid = swarm.behaviour_mut().blocks.send_request(
                                &peer,
                                BlocksRequest { manifest_for: None, cids },
                            );
                            pending_blocks.insert(rid, reply);
                        }
                        None => { let _ = reply.send(Err(FacadeError::Request("sem /p2p/<id>".into()))); }
                    }
                }
                Some(Cmd::Push { addr, obra_id, manifest, blocks, reply }) => {
                    match extract_peer(&addr) {
                        Some(peer) => {
                            if has_transport(&addr) {
                                swarm.behaviour_mut().kad.add_address(&peer, addr);
                            }
                            let rid = swarm.behaviour_mut().push.send_request(
                                &peer,
                                PushRequest { obra_id, manifest, blocks: blocks.into_iter().map(serde_bytes::ByteBuf::from).collect() },
                            );
                            pending_push.insert(rid, reply);
                        }
                        None => { let _ = reply.send(Err(FacadeError::Request("sem /p2p/<id>".into()))); }
                    }
                }
            },
            event = swarm.select_next_some() => match event {
                SwarmEvent::Behaviour(BehaviourEvent::Push(
                    request_response::Event::Message { message, .. },
                )) => {
                    if let request_response::Message::Response { request_id, response } = message {
                        if let Some(reply) = pending_push.remove(&request_id) {
                            let _ = reply.send(if response.accepted {
                                Ok(())
                            } else {
                                Err(FacadeError::Request("push rejeitado pelo receptor".into()))
                            });
                        }
                    }
                }
                SwarmEvent::Behaviour(BehaviourEvent::Push(
                    request_response::Event::OutboundFailure { request_id, error, .. },
                )) => {
                    if let Some(reply) = pending_push.remove(&request_id) {
                        let _ = reply.send(Err(FacadeError::Request(error.to_string())));
                    }
                }
                SwarmEvent::Behaviour(BehaviourEvent::Kad(kad::Event::OutboundQueryProgressed {
                    id, result: kad::QueryResult::GetProviders(Ok(ok)), ..
                })) => match ok {
                    kad::GetProvidersOk::FoundProviders { providers, .. } if !providers.is_empty() => {
                        if let Some(reply) = pending_resolve.remove(&id) {
                            let peer = providers.into_iter().next().unwrap();
                            // o `discovered()` do kad roda ANTES deste evento: os endereços
                            // que vieram no provider record já estão nos kbuckets — usa-os
                            // direto; FIND_NODE fica como fallback (2º passo do poc-03).
                            // poc-05 C2 dual-homed: devolve TODOS os endereços discáveis do
                            // provider (uma multiaddr por linha) — o consumidor escolhe (o
                            // anônimo casa /dns onion; o clearnet casa /ip4). Fallback FIND_NODE
                            // só se o record não trouxe endereço algum.
                            let known: Vec<Multiaddr> = kbucket_addrs(&mut swarm.behaviour_mut().kad, &peer)
                                .into_iter().filter(|a| has_transport(a)).collect();
                            if !known.is_empty() {
                                let joined = known.iter()
                                    .map(|a| {
                                        let s = a.to_string();
                                        if s.contains("/p2p/") { s } else { format!("{s}/p2p/{peer}") }
                                    })
                                    .collect::<Vec<_>>().join("\n");
                                let _ = reply.send(Ok(joined));
                            } else {
                                let qid = swarm.behaviour_mut().kad.get_closest_peers(peer);
                                pending_findpeer.insert(qid, (reply, peer));
                            }
                        }
                    }
                    kad::GetProvidersOk::FinishedWithNoAdditionalRecord { .. } => {
                        if let Some(reply) = pending_resolve.remove(&id) {
                            // sem provider vivo NÃO é erro: anúncio expirado é estado normal
                            let _ = reply.send(Ok(String::new()));
                        }
                    }
                    _ => {}
                },
                SwarmEvent::Behaviour(BehaviourEvent::Kad(kad::Event::OutboundQueryProgressed {
                    id, result: kad::QueryResult::GetClosestPeers(res), ..
                })) => {
                    if let Some((reply, target)) = pending_findpeer.remove(&id) {
                        let addr = match res {
                            Ok(ok) => {
                                ok.peers.into_iter()
                                    .find(|p| p.peer_id == target)
                                    .and_then(|p| best_addr(&p.addrs))
                            }
                            Err(_) => None,
                        };
                        // sem endereço com transporte = ainda não descoberto de verdade;
                        // devolve "" para o consumidor re-tentar (nunca um /p2p/ sem addr)
                        let _ = reply.send(Ok(
                            addr.map(|a| format!("{a}/p2p/{target}")).unwrap_or_default(),
                        ));
                    }
                }
                SwarmEvent::Behaviour(BehaviourEvent::Blocks(
                    request_response::Event::Message { message, .. },
                )) => {
                    if let request_response::Message::Response { request_id, response } = message {
                        if let Some(reply) = pending_blocks.remove(&request_id) {
                            let _ = reply.send(Ok(encode_length_prefixed(&response.blocks)));
                        } else if let Some(reply) = pending_manifest.remove(&request_id) {
                            let _ = reply.send(match response.manifest {
                                Some(m) => Ok(m),
                                None => Err(FacadeError::Request("manifesto desconhecido".into())),
                            });
                        }
                    }
                }
                SwarmEvent::Behaviour(BehaviourEvent::Blocks(
                    request_response::Event::OutboundFailure { request_id, error, .. },
                )) => {
                    if let Some(reply) = pending_blocks.remove(&request_id)
                        .or_else(|| pending_manifest.remove(&request_id))
                    {
                        let _ = reply.send(Err(FacadeError::Request(error.to_string())));
                    }
                }
                // ponte identify→kad (padrão rust-libp2p; kubo faz o mesmo): sem isto os
                // kbuckets nunca ganham os endereços de ESCUTA dos pares — conexões de
                // entrada registram só a porta efêmera, que não é discável.
                SwarmEvent::Behaviour(BehaviourEvent::Identify(identify::Event::Received {
                    peer_id, info, ..
                })) => {
                    for addr in info.listen_addrs.iter().filter(|a| has_transport(a)) {
                        swarm.behaviour_mut().kad.add_address(&peer_id, addr.clone());
                    }
                }
                _ => {}
            }
        }
    }
}

// ===================== ANON CLIENT (poc-05 E2: publicador sobre Tor) =====================

/// bootstrap opcional pelo túnel: só registra o endereço + dispara o dial preguiçoso (via
/// SOCKS). Sem await — o dial roda quando o loop poll-a o transporte. `""` = sem bootstrap
/// (o C1 empurra para IP/onion conhecido, não precisa de descoberta).
fn connect_bootstrap_anon(swarm: &mut libp2p::Swarm<AnonBehaviour>, bootstrap: &str) -> Result<(), FacadeError> {
    if bootstrap.is_empty() {
        return Ok(());
    }
    let addr: Multiaddr = bootstrap
        .parse()
        .map_err(|e| FacadeError::Init(format!("bootstrap multiaddr: {e}")))?;
    let peer = extract_peer(&addr).ok_or_else(|| FacadeError::Init("bootstrap sem /p2p/<id>".into()))?;
    swarm.behaviour_mut().kad.add_address(&peer, addr.clone());
    let _ = swarm.dial(addr);
    let _ = swarm.behaviour_mut().kad.bootstrap();
    Ok(())
}

/// Loop do publicador anônimo. Espelha o `client_loop` (Dial/Resolve/Push) sobre o
/// [AnonBehaviour] e o transporte SOCKS-only. Sem ponte identify→kad (o behaviour não tem
/// identify, por contenção): a descoberta do E3 se apoia nos endereços do provider record.
/// GetManifest/GetBlocks retornam erro — o publicador EMPURRA, não lê (o leitor é clearnet).
async fn anon_client_loop(mut swarm: libp2p::Swarm<AnonBehaviour>, mut rx: mpsc::Receiver<Cmd>) {
    let mut pending_resolve: HashMap<QueryId, oneshot::Sender<Result<String, FacadeError>>> = HashMap::new();
    let mut pending_findpeer: HashMap<QueryId, (oneshot::Sender<Result<String, FacadeError>>, PeerId)> = HashMap::new();
    let mut pending_push: HashMap<
        request_response::OutboundRequestId,
        oneshot::Sender<Result<(), FacadeError>>,
    > = HashMap::new();
    // push adiado: dial lento por Tor sobe primeiro; o send_request espera a conexão viva
    // (evita a corrida kad-dial × request-response-dial → DialFailure prematuro).
    let mut pending_dial_push: HashMap<
        PeerId,
        (PushRequest, oneshot::Sender<Result<(), FacadeError>>),
    > = HashMap::new();
    // dial adiado: a resposta só volta quando a conexão SOBE (o connect por Tor é lento; o
    // resolve/push posterior precisa da conexão viva a B, não só do dial enfileirado).
    let mut pending_dial: HashMap<PeerId, oneshot::Sender<Result<(), FacadeError>>> = HashMap::new();
    // poc-06 E-fase: o AnonBehaviour JÁ tem o request-response de blocos; para libp2p-sobre-I2P
    // o MESMO cliente também LÊ (a trava "leitor é clearnet" era política do poc-05, não limite).
    let mut pending_blocks: HashMap<request_response::OutboundRequestId, oneshot::Sender<Result<Vec<u8>, FacadeError>>> = HashMap::new();
    let mut pending_manifest: HashMap<request_response::OutboundRequestId, oneshot::Sender<Result<Vec<u8>, FacadeError>>> = HashMap::new();

    loop {
        tokio::select! {
            cmd = rx.recv() => match cmd {
                None => break,
                Some(Cmd::Dial { addr, reply }) => {
                    match extract_peer(&addr) {
                        Some(peer) => {
                            // kad precisa do endereço de B p/ rotear as queries de descoberta
                            swarm.behaviour_mut().kad.add_address(&peer, addr.clone());
                            if swarm.is_connected(&peer) {
                                let _ = reply.send(Ok(()));
                            } else {
                                let _ = swarm.dial(addr); // Err "já dialando" é ok: espera Established
                                pending_dial.insert(peer, reply);
                            }
                        }
                        None => { let _ = reply.send(Err(FacadeError::Dial("sem /p2p/<id>".into()))); }
                    }
                }
                Some(Cmd::Resolve { obra_id, reply }) => {
                    let key = kad::RecordKey::new(&obra_id.into_bytes());
                    let qid = swarm.behaviour_mut().kad.get_providers(key);
                    pending_resolve.insert(qid, reply);
                }
                Some(Cmd::Push { addr, obra_id, manifest, blocks, reply }) => {
                    match extract_peer(&addr) {
                        Some(peer) => {
                            let req = PushRequest {
                                obra_id,
                                manifest,
                                blocks: blocks.into_iter().map(serde_bytes::ByteBuf::from).collect(),
                            };
                            if swarm.is_connected(&peer) {
                                let rid = swarm.behaviour_mut().push.send_request(&peer, req);
                                pending_push.insert(rid, reply);
                            } else {
                                // dial explícito (NÃO via kad, p/ não competir) e adia o push
                                match swarm.dial(addr) {
                                    Ok(()) => { pending_dial_push.insert(peer, (req, reply)); }
                                    Err(e) => { let _ = reply.send(Err(FacadeError::Dial(e.to_string()))); }
                                }
                            }
                        }
                        None => { let _ = reply.send(Err(FacadeError::Request("sem /p2p/<id>".into()))); }
                    }
                }
                // poc-06 E-fase: leitura sobre I2P (espelha o client_loop clearnet)
                Some(Cmd::GetManifest { addr, obra_id, reply }) => {
                    match extract_peer(&addr) {
                        Some(peer) => {
                            if has_transport(&addr) { swarm.behaviour_mut().kad.add_address(&peer, addr); }
                            let rid = swarm.behaviour_mut().blocks.send_request(
                                &peer, BlocksRequest { manifest_for: Some(obra_id), cids: vec![] });
                            pending_manifest.insert(rid, reply);
                        }
                        None => { let _ = reply.send(Err(FacadeError::Request("sem /p2p/<id>".into()))); }
                    }
                }
                Some(Cmd::GetBlocks { addr, cids, reply }) => {
                    match extract_peer(&addr) {
                        Some(peer) => {
                            if has_transport(&addr) { swarm.behaviour_mut().kad.add_address(&peer, addr); }
                            let rid = swarm.behaviour_mut().blocks.send_request(
                                &peer, BlocksRequest { manifest_for: None, cids });
                            pending_blocks.insert(rid, reply);
                        }
                        None => { let _ = reply.send(Err(FacadeError::Request("sem /p2p/<id>".into()))); }
                    }
                }
            },
            event = swarm.select_next_some() => match event {
                SwarmEvent::Behaviour(AnonBehaviourEvent::Kad(kad::Event::OutboundQueryProgressed {
                    id, result: kad::QueryResult::GetProviders(Ok(ok)), ..
                })) => match ok {
                    kad::GetProvidersOk::FoundProviders { providers, .. } if !providers.is_empty() => {
                        if let Some(reply) = pending_resolve.remove(&id) {
                            let peer = providers.into_iter().next().unwrap();
                            // poc-05 C2 dual-homed: devolve TODOS os endereços discáveis do
                            // provider (uma multiaddr por linha) — o consumidor escolhe (o
                            // anônimo casa /dns onion; o clearnet casa /ip4). Fallback FIND_NODE
                            // só se o record não trouxe endereço algum.
                            let known: Vec<Multiaddr> = kbucket_addrs(&mut swarm.behaviour_mut().kad, &peer)
                                .into_iter().filter(|a| has_transport(a)).collect();
                            if !known.is_empty() {
                                let joined = known.iter()
                                    .map(|a| {
                                        let s = a.to_string();
                                        if s.contains("/p2p/") { s } else { format!("{s}/p2p/{peer}") }
                                    })
                                    .collect::<Vec<_>>().join("\n");
                                let _ = reply.send(Ok(joined));
                            } else {
                                let qid = swarm.behaviour_mut().kad.get_closest_peers(peer);
                                pending_findpeer.insert(qid, (reply, peer));
                            }
                        }
                    }
                    kad::GetProvidersOk::FinishedWithNoAdditionalRecord { .. } => {
                        if let Some(reply) = pending_resolve.remove(&id) {
                            let _ = reply.send(Ok(String::new()));
                        }
                    }
                    _ => {}
                },
                SwarmEvent::Behaviour(AnonBehaviourEvent::Kad(kad::Event::OutboundQueryProgressed {
                    id, result: kad::QueryResult::GetClosestPeers(res), ..
                })) => {
                    if let Some((reply, target)) = pending_findpeer.remove(&id) {
                        let addr = match res {
                            Ok(ok) => ok.peers.into_iter().find(|p| p.peer_id == target).and_then(|p| best_addr(&p.addrs)),
                            Err(_) => None,
                        };
                        let _ = reply.send(Ok(addr.map(|a| format!("{a}/p2p/{target}")).unwrap_or_default()));
                    }
                }
                SwarmEvent::Behaviour(AnonBehaviourEvent::Push(
                    request_response::Event::Message { message, .. },
                )) => {
                    if let request_response::Message::Response { request_id, response } = message {
                        if let Some(reply) = pending_push.remove(&request_id) {
                            let _ = reply.send(if response.accepted {
                                Ok(())
                            } else {
                                Err(FacadeError::Request("push rejeitado pelo receptor".into()))
                            });
                        }
                    }
                }
                SwarmEvent::Behaviour(AnonBehaviourEvent::Push(
                    request_response::Event::OutboundFailure { request_id, error, .. },
                )) => {
                    if let Some(reply) = pending_push.remove(&request_id) {
                        let _ = reply.send(Err(FacadeError::Request(error.to_string())));
                    }
                }
                // poc-06 E-fase: resposta/falha do request-response de BLOCOS (leitura sobre I2P)
                SwarmEvent::Behaviour(AnonBehaviourEvent::Blocks(
                    request_response::Event::Message { message, .. },
                )) => {
                    if let request_response::Message::Response { request_id, response } = message {
                        if let Some(reply) = pending_blocks.remove(&request_id) {
                            let _ = reply.send(Ok(encode_length_prefixed(&response.blocks)));
                        } else if let Some(reply) = pending_manifest.remove(&request_id) {
                            let _ = reply.send(match response.manifest {
                                Some(m) => Ok(m),
                                None => Err(FacadeError::Request("manifesto desconhecido".into())),
                            });
                        }
                    }
                }
                SwarmEvent::Behaviour(AnonBehaviourEvent::Blocks(
                    request_response::Event::OutboundFailure { request_id, error, .. },
                )) => {
                    if let Some(reply) = pending_blocks.remove(&request_id)
                        .or_else(|| pending_manifest.remove(&request_id))
                    {
                        let _ = reply.send(Err(FacadeError::Request(error.to_string())));
                    }
                }
                SwarmEvent::OutgoingConnectionError { peer_id, error, .. } => {
                    if let Some(pid) = peer_id {
                        if let Some(reply) = pending_dial.remove(&pid) {
                            let _ = reply.send(Err(FacadeError::Dial(error.to_string())));
                        }
                        if let Some((_, reply)) = pending_dial_push.remove(&pid) {
                            let _ = reply.send(Err(FacadeError::Dial(error.to_string())));
                        }
                    }
                }
                SwarmEvent::ConnectionEstablished { peer_id, .. } => {
                    // conexão viva → resolve o dial adiado e dispara o push adiado (o
                    // request-response usa a conexão estabelecida, sem novo dial)
                    if let Some(reply) = pending_dial.remove(&peer_id) {
                        let _ = reply.send(Ok(()));
                    }
                    if let Some((req, reply)) = pending_dial_push.remove(&peer_id) {
                        let rid = swarm.behaviour_mut().push.send_request(&peer_id, req);
                        pending_push.insert(rid, reply);
                    }
                }
                _ => {}
            }
        }
    }
}

// ============================= SERVER (novo no poc-04, E2) =============================

/// MemoryStore com filtro de expiração em `providers()`: o kad de referência responde
/// queries de REDE com `store.providers()` SEM filtrar registros expirados (o filtro do
/// upstream só existe no lookup local — behaviour.rs:1026 vs 1207 do libp2p-kad 0.46) e o
/// MemoryStore nunca purga. Sem isto, um publicador morto continuaria anunciado para
/// sempre. Correção interna ao backend; registrada no relatório como atrito do E2.
struct ExpiringStore {
    inner: MemoryStore,
}

impl kad::store::RecordStore for ExpiringStore {
    type RecordsIter<'a> = <MemoryStore as kad::store::RecordStore>::RecordsIter<'a>;
    type ProvidedIter<'a> = <MemoryStore as kad::store::RecordStore>::ProvidedIter<'a>;

    fn get(&self, k: &kad::RecordKey) -> Option<std::borrow::Cow<'_, kad::Record>> {
        self.inner.get(k)
    }
    fn put(&mut self, r: kad::Record) -> kad::store::Result<()> {
        self.inner.put(r)
    }
    fn remove(&mut self, k: &kad::RecordKey) {
        self.inner.remove(k)
    }
    fn records(&self) -> Self::RecordsIter<'_> {
        self.inner.records()
    }
    fn add_provider(&mut self, record: kad::ProviderRecord) -> kad::store::Result<()> {
        self.inner.add_provider(record)
    }
    fn providers(&self, key: &kad::RecordKey) -> Vec<kad::ProviderRecord> {
        let now = std::time::Instant::now();
        self.inner
            .providers(key)
            .into_iter()
            .filter(|r| !r.is_expired(now))
            .collect()
    }
    fn provided(&self) -> Self::ProvidedIter<'_> {
        self.inner.provided()
    }
    fn remove_provider(&mut self, k: &kad::RecordKey, p: &PeerId) {
        self.inner.remove_provider(k, p)
    }
}

// Full node: sem relay/dcutr (o servidor da PoC é alcançável por port forwarding).
#[derive(NetworkBehaviour)]
struct ServerBehaviour {
    kad: kad::Behaviour<ExpiringStore>,
    blocks: request_response::cbor::Behaviour<BlocksRequest, BlocksResponse>,
    // poc-05 E2: recebe push do publicador não-discável (ProtocolSupport::Full)
    push: request_response::cbor::Behaviour<PushRequest, PushResponse>,
    identify: identify::Behaviour,
}

enum ServerCmd {
    Bootstrap {
        addr: Multiaddr,
        reply: oneshot::Sender<Result<(), FacadeError>>,
    },
    StartProviding {
        obra_id: String,
        reply: oneshot::Sender<Result<(), FacadeError>>,
    },
}

pub struct ServerNode {
    peer_id: String,
    listen_port: u16,
    tx: mpsc::Sender<ServerCmd>,
    #[allow(dead_code)]
    runtime: tokio::runtime::Runtime,
}

impl ServerNode {
    /// Constrói e SOBE o full node (bloqueia até o listen TCP estar ativo).
    pub fn new(
        cfg: ServerConfig,
        store: Box<dyn BlockstoreCallback>,
    ) -> Result<Self, FacadeError> {
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()
            .map_err(|e| FacadeError::Init(e.to_string()))?;

        let (tx, rx) = mpsc::channel::<ServerCmd>(32);
        let (ready_tx, ready_rx) = oneshot::channel::<Result<(String, u16), FacadeError>>();

        runtime.spawn(async move {
            let mut swarm = match build_server_swarm(&cfg) {
                Ok(s) => s,
                Err(e) => {
                    let _ = ready_tx.send(Err(e));
                    return;
                }
            };
            let pid = swarm.local_peer_id().to_string();

            if let Err(e) = swarm.listen_on(
                format!("/ip4/0.0.0.0/tcp/{}", cfg.listen_port).parse().unwrap(),
            ) {
                let _ = ready_tx.send(Err(FacadeError::Init(format!("listen: {e}"))));
                return;
            }

            // aguarda o listener TCP subir para conhecer a porta efetiva (listen_port=0)
            let public_mode = cfg.public_ip.is_some();
            let tcp_port = loop {
                match swarm.select_next_some().await {
                    SwarmEvent::NewListenAddr { address, .. } => {
                        if !public_mode {
                            swarm.add_external_address(address.clone());
                        }
                        if let Some(p) = tcp_port_of(&address) {
                            break p;
                        }
                    }
                    _ => {}
                }
            };

            // QUIC na mesma porta (paridade com o poc-03); falha não é fatal na PoC
            let _ = swarm.listen_on(
                format!("/ip4/0.0.0.0/udp/{tcp_port}/quic-v1").parse().unwrap(),
            );

            // modo público (E4/S4): anuncia SÓ o endereço público como externo — senão o
            // provider record carrega 127.0.0.1/192.168.x e o cliente de fora falha.
            // poc-05 E3: se o "public_ip" for um ONION (não-IPv4), anuncia `/dns/<onion>/tcp`
            // (sem QUIC — onion é TCP puro) — é o endereço que o publicador anônimo descobre
            // e disca por dentro do circuito.
            if let Some(host) = &cfg.public_ip {
                let port = cfg.public_port.unwrap_or(tcp_port);
                let protos: Vec<String> = if host.parse::<std::net::Ipv4Addr>().is_ok() {
                    vec![format!("/ip4/{host}/tcp/{port}"), format!("/ip4/{host}/udp/{port}/quic-v1")]
                } else {
                    vec![format!("/dns/{host}/tcp/{port}")] // onion: só TCP
                };
                for proto in protos {
                    if let Ok(a) = proto.parse::<Multiaddr>() {
                        swarm.add_external_address(a);
                    }
                }
            }
            // poc-05 C2 dual-homed: anuncia TAMBÉM o onion (/dns/<onion>/tcp/<port>) como
            // externo — é o endereço que o publicador ANÔNIMO descobre e disca (o IP público
            // acima é o dos leitores clearnet). O provider record passa a carregar os dois.
            if let Some(onion) = &cfg.onion_host {
                let port = cfg.public_port.unwrap_or(tcp_port);
                if let Ok(a) = format!("/dns/{onion}/tcp/{port}").parse::<Multiaddr>() {
                    swarm.add_external_address(a);
                }
            }

            let _ = ready_tx.send(Ok((pid.clone(), tcp_port)));
            server_loop(swarm, rx, store, public_mode).await;
        });

        let (peer_id, listen_port) = runtime
            .block_on(ready_rx)
            .map_err(|_| FacadeError::Init("ator do servidor morreu".into()))??;

        Ok(ServerNode { peer_id, listen_port, tx, runtime })
    }

    pub fn peer_id(&self) -> String {
        self.peer_id.clone()
    }

    pub fn listen_port(&self) -> u16 {
        self.listen_port
    }

    pub async fn bootstrap(&self, multiaddr_with_peer: String) -> Result<(), FacadeError> {
        let addr: Multiaddr = multiaddr_with_peer
            .parse()
            .map_err(|e| FacadeError::Dial(format!("multiaddr: {e}")))?;
        let (reply, rx) = oneshot::channel();
        self.tx
            .send(ServerCmd::Bootstrap { addr, reply })
            .await
            .map_err(|_| FacadeError::Request("swarm indisponível".into()))?;
        rx.await.map_err(|_| FacadeError::Timeout)?
    }

    pub async fn start_providing(&self, obra_id: String) -> Result<(), FacadeError> {
        let (reply, rx) = oneshot::channel();
        self.tx
            .send(ServerCmd::StartProviding { obra_id, reply })
            .await
            .map_err(|_| FacadeError::Request("swarm indisponível".into()))?;
        rx.await.map_err(|_| FacadeError::Timeout)?
    }
}

fn build_server_swarm(cfg: &ServerConfig) -> Result<libp2p::Swarm<ServerBehaviour>, FacadeError> {
    let mut seed: [u8; 32] = decode_hex(&cfg.identity_seed_hex)
        .map_err(FacadeError::Init)?
        .try_into()
        .map_err(|_| FacadeError::Init("semente deve ter 32 bytes".into()))?;
    let id_keys = identity::Keypair::ed25519_from_bytes(&mut seed)
        .map_err(|e| FacadeError::Init(format!("identidade: {e}")))?;

    let ttl = Duration::from_millis(cfg.ttl_ms);
    let republish = Duration::from_millis(cfg.republish_ms);

    let swarm = SwarmBuilder::with_existing_identity(id_keys)
        .with_tokio()
        .with_tcp(
            tcp::Config::default(),
            noise::Config::new,
            yamux::Config::default,
        )
        .map_err(|e| FacadeError::Init(e.to_string()))?
        .with_quic()
        .with_behaviour(|key| {
            let peer_id = PeerId::from(key.public());
            // expiry/republish INTERNOS ao backend (D2): TTL e intervalo de republicação
            // dos provider records vêm do AnnounceTuning neutro, o mecanismo é o do Kademlia
            let mut kcfg = kad::Config::default();
            kcfg.set_provider_record_ttl(Some(ttl));
            kcfg.set_provider_publication_interval(Some(republish));
            let store = ExpiringStore { inner: MemoryStore::new(peer_id) };
            let mut kad_b = kad::Behaviour::with_config(peer_id, store, kcfg);
            kad_b.set_mode(Some(kad::Mode::Server)); // nó pleno: serve a DHT
            Ok(ServerBehaviour {
                kad: kad_b,
                blocks: request_response::cbor::Behaviour::new(
                    [(StreamProtocol::new(BLOCK_PROTOCOL), ProtocolSupport::Full)],
                    request_response::Config::default().with_request_timeout(REQUEST_TIMEOUT),
                ),
                push: request_response::cbor::Behaviour::new(
                    [(StreamProtocol::new(PUSH_PROTOCOL), ProtocolSupport::Full)],
                    request_response::Config::default().with_request_timeout(REQUEST_TIMEOUT),
                ),
                identify: identify::Behaviour::new(identify::Config::new(
                    "/opentoons/0.0.1".into(),
                    key.public(),
                )),
            })
        })
        .map_err(|e| FacadeError::Init(e.to_string()))?
        .with_swarm_config(|c| c.with_idle_connection_timeout(Duration::from_secs(120)))
        .build();
    Ok(swarm)
}

// Loop do ator servidor: comandos FFI + eventos; serve manifesto/blocos chamando DE VOLTA
// o blockstore Kotlin. Loga CONN/REQUEST no stderr — a prova de conexão do S3/S4.
async fn server_loop(
    mut swarm: libp2p::Swarm<ServerBehaviour>,
    mut rx: mpsc::Receiver<ServerCmd>,
    store: Box<dyn BlockstoreCallback>,
    public_mode: bool,
) {
    loop {
        tokio::select! {
            cmd = rx.recv() => match cmd {
                None => break, // ServerNode dropado → encerra o ator
                Some(ServerCmd::Bootstrap { addr, reply }) => {
                    let res = match extract_peer(&addr) {
                        Some(peer) => {
                            swarm.behaviour_mut().kad.add_address(&peer, addr.clone());
                            let r = swarm.dial(addr).map_err(|e| FacadeError::Dial(e.to_string()));
                            let _ = swarm.behaviour_mut().kad.bootstrap();
                            r
                        }
                        None => Err(FacadeError::Dial("bootstrap sem /p2p/<id>".into())),
                    };
                    let _ = reply.send(res);
                }
                Some(ServerCmd::StartProviding { obra_id, reply }) => {
                    let key = kad::RecordKey::new(&obra_id.into_bytes());
                    let res = swarm.behaviour_mut().kad.start_providing(key)
                        .map(|_| ())
                        .map_err(|e| FacadeError::Request(e.to_string()));
                    let _ = reply.send(res);
                }
            },
            event = swarm.select_next_some() => match event {
                SwarmEvent::NewListenAddr { address, .. } => {
                    if !public_mode {
                        swarm.add_external_address(address);
                    }
                }
                SwarmEvent::ConnectionEstablished { peer_id, .. } => {
                    eprintln!("CONN {peer_id}");
                }
                // mesma ponte identify→kad do client: o full node aprende os endereços de
                // escuta de quem conecta (o publicador que disca o bootstrap, p.ex.)
                SwarmEvent::Behaviour(ServerBehaviourEvent::Identify(identify::Event::Received {
                    peer_id, info, ..
                })) => {
                    for addr in info.listen_addrs.iter().filter(|a| has_transport(a)) {
                        swarm.behaviour_mut().kad.add_address(&peer_id, addr.clone());
                    }
                }
                SwarmEvent::Behaviour(ServerBehaviourEvent::Blocks(request_response::Event::Message {
                    message: request_response::Message::Request { request, channel, .. },
                    peer,
                    ..
                })) => {
                    eprintln!(
                        "REQUEST de {peer}: manifest_for={:?} cids={}",
                        request.manifest_for,
                        request.cids.len(),
                    );
                    // TODA leitura passa pelo blockstore Kotlin AO VIVO (dual-stack/adulteração)
                    let manifest = request
                        .manifest_for
                        .as_ref()
                        .and_then(|o| store.manifest(o.clone()));
                    let blocks: Vec<Vec<u8>> = request
                        .cids
                        .iter()
                        .filter_map(|c| store.block(c.clone()))
                        .collect();
                    let _ = swarm
                        .behaviour_mut()
                        .blocks
                        .send_response(channel, BlocksResponse { manifest, blocks });
                }
                // poc-05 E2: recepção de PUSH. A decisão de aceitar (assinatura da editora,
                // PushPolicy) roda no Kotlin via accept_push — o rust só transporta e devolve
                // o veredito. Rejeição = accepted:false, sem gravar (o Kotlin não grava).
                SwarmEvent::Behaviour(ServerBehaviourEvent::Push(request_response::Event::Message {
                    message: request_response::Message::Request { request, channel, .. },
                    peer,
                    ..
                })) => {
                    let accepted = store.accept_push(request.obra_id.clone(), request.manifest, request.blocks.into_iter().map(|b| b.into_vec()).collect());
                    eprintln!("PUSH de {peer}: obra={} accepted={accepted}", request.obra_id);
                    let _ = swarm
                        .behaviour_mut()
                        .push
                        .send_response(channel, PushResponse { accepted });
                }
                _ => {}
            }
        }
    }
}

// ============================= util =============================

// endereços conhecidos de um peer nos kbuckets do kad (client) — preenchidos pelo
// `discovered()` quando a resposta de GET_PROVIDERS carrega os addrs do provider record.
fn kbucket_addrs(kad: &mut kad::Behaviour<MemoryStore>, peer: &PeerId) -> Vec<Multiaddr> {
    if let Some(mut bucket) = kad.kbucket(*peer) {
        for entry in bucket.iter() {
            if entry.node.key.preimage() == peer {
                return entry.node.value.iter().cloned().collect();
            }
        }
    }
    Vec::new()
}

// escolhe o melhor endereço discável de um provider: público > privado (LAN) > loopback —
// o ranking importa no S4 (cliente em outra rede precisa do addr público, não do 192.168.x).
fn best_addr(addrs: &[Multiaddr]) -> Option<Multiaddr> {
    use libp2p::multiaddr::Protocol::*;
    let rank = |a: &Multiaddr| -> u8 {
        match a.iter().find_map(|p| if let Ip4(ip) = p { Some(ip) } else { None }) {
            Some(ip) if ip.is_loopback() => 2,
            Some(ip) if ip.is_private() || ip.is_link_local() => 1,
            Some(_) => 0,
            None => 1, // dns etc.
        }
    };
    addrs
        .iter()
        .filter(|a| has_transport(a))
        .min_by_key(|a| rank(a))
        .cloned()
}

// Mesmo formato de wire do poc-03: [len u32 BE][bloco]...
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

fn has_transport(addr: &Multiaddr) -> bool {
    use libp2p::multiaddr::Protocol::*;
    addr.iter().any(|p| matches!(p, Ip4(_) | Ip6(_) | Dns(_) | Dns4(_) | Dns6(_)))
}

fn tcp_port_of(addr: &Multiaddr) -> Option<u16> {
    addr.iter().find_map(|p| match p {
        libp2p::multiaddr::Protocol::Tcp(port) => Some(port),
        _ => None,
    })
}

fn decode_hex(s: &str) -> Result<Vec<u8>, String> {
    if s.len() % 2 != 0 {
        return Err("hex de tamanho ímpar".into());
    }
    (0..s.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&s[i..i + 2], 16).map_err(|e| e.to_string()))
        .collect()
}
