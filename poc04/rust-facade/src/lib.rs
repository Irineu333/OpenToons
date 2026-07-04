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

/// Fonte de conteúdo do lado Kotlin (Blockstore neutro do `:api`). O servidor chama de
/// volta a JVM a cada request — trait gerado pelo scaffolding UniFFI (callback interface).
pub trait BlockstoreCallback: Send + Sync {
    fn manifest(&self, obra_id: String) -> Option<Vec<u8>>;
    fn block(&self, cid: String) -> Option<Vec<u8>>;
}

pub struct ServerConfig {
    pub identity_seed_hex: String,
    pub listen_port: u16,
    pub public_ip: Option<String>,
    pub public_port: Option<u16>,
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

// ============================= CLIENT (poc-03, mantido) =============================

// relay_client + dcutr existem para hole-punch (capability só-libp2p do E3).
#[derive(NetworkBehaviour)]
struct Behaviour {
    kad: kad::Behaviour<MemoryStore>,
    blocks: request_response::cbor::Behaviour<BlocksRequest, BlocksResponse>,
    identify: identify::Behaviour,
    dcutr: dcutr::Behaviour,
    relay_client: relay::client::Behaviour,
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
}

pub struct Node {
    peer_id: String,
    tx: mpsc::Sender<Cmd>,
    // mantido vivo de propósito: dropar o Node dropa o runtime e encerra o ator do swarm.
    #[allow(dead_code)]
    runtime: tokio::runtime::Runtime,
}

impl Node {
    /// Modo client puro (ADR-0005). SÍNCRONO de propósito (ver facade.udl).
    pub fn new(bootstrap_multiaddr: String) -> Result<Self, FacadeError> {
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()
            .map_err(|e| FacadeError::Init(e.to_string()))?;

        let (tx, rx) = mpsc::channel::<Cmd>(32);
        let (ready_tx, ready_rx) = oneshot::channel::<Result<String, FacadeError>>();

        runtime.spawn(async move {
            match build_client_swarm() {
                Ok(mut swarm) => {
                    let pid = swarm.local_peer_id().to_string();
                    swarm.behaviour_mut().kad.set_mode(Some(kad::Mode::Client));
                    let boot_ok = connect_bootstrap(&mut swarm, &bootstrap_multiaddr).await;
                    let _ = ready_tx.send(boot_ok.map(|_| pid));
                    client_loop(swarm, rx).await;
                }
                Err(e) => {
                    let _ = ready_tx.send(Err(e));
                }
            }
        });

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
            },
            event = swarm.select_next_some() => match event {
                SwarmEvent::Behaviour(BehaviourEvent::Kad(kad::Event::OutboundQueryProgressed {
                    id, result: kad::QueryResult::GetProviders(Ok(ok)), ..
                })) => match ok {
                    kad::GetProvidersOk::FoundProviders { providers, .. } if !providers.is_empty() => {
                        if let Some(reply) = pending_resolve.remove(&id) {
                            let peer = providers.into_iter().next().unwrap();
                            // o `discovered()` do kad roda ANTES deste evento: os endereços
                            // que vieram no provider record já estão nos kbuckets — usa-os
                            // direto; FIND_NODE fica como fallback (2º passo do poc-03).
                            let known = kbucket_addrs(&mut swarm, &peer);
                            if let Some(addr) = best_addr(&known) {
                                let _ = reply.send(Ok(format!("{addr}/p2p/{peer}")));
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
            if let Some(ip) = &cfg.public_ip {
                let port = cfg.public_port.unwrap_or(tcp_port);
                for proto in [format!("/ip4/{ip}/tcp/{port}"), format!("/ip4/{ip}/udp/{port}/quic-v1")] {
                    if let Ok(a) = proto.parse::<Multiaddr>() {
                        swarm.add_external_address(a);
                    }
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
                _ => {}
            }
        }
    }
}

// ============================= util =============================

// endereços conhecidos de um peer nos kbuckets do kad (client) — preenchidos pelo
// `discovered()` quando a resposta de GET_PROVIDERS carrega os addrs do provider record.
fn kbucket_addrs(swarm: &mut libp2p::Swarm<Behaviour>, peer: &PeerId) -> Vec<Multiaddr> {
    if let Some(mut bucket) = swarm.behaviour_mut().kad.kbucket(*peer) {
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
