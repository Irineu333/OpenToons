//! poc-05 E2 (D6) — o Transport SOCKS custom que "não vem pronto" no rust-libp2p.
//!
//! Disca TODA conexão por um proxy SOCKS5 (daemon Tor local) com resolução REMOTA
//! (SOCKS5h): o alvo por domínio/`.onion` é resolvido DENTRO do circuito, nunca localmente
//! (D4 — 0 DNS local). NÃO escuta: `listen_on` sempre recusa — o publicador anônimo é só
//! saída (ADR-0005). Como este é o ÚNICO transporte do swarm anônimo, nenhum dial direto é
//! sequer possível — a contenção de identify/mDNS/DNS/relay é ESTRUTURAL, não configurada.

use std::io;
use std::net::SocketAddr;
use std::pin::Pin;
use std::task::{Context, Poll};

use futures::future::{BoxFuture, FutureExt};
use libp2p::core::multiaddr::{Multiaddr, Protocol};
use libp2p::core::transport::{DialOpts, ListenerId, TransportError, TransportEvent};
use libp2p::core::Transport;
use tokio::net::TcpStream;
use tokio_socks::tcp::Socks5Stream;
use tokio_util::compat::{Compat, TokioAsyncReadCompatExt};

pub struct SocksTransport {
    proxy: SocketAddr,
}

impl SocksTransport {
    pub fn new(proxy: SocketAddr) -> Self {
        Self { proxy }
    }
}

/// (host, porta) discável de um multiaddr: `/ip4|ip6/../tcp/..` ou `/dns[4|6]/<host>/tcp/..`
/// (o `.onion` viaja como `/dns/<hostname>.onion/tcp/<port>`). `/p2p/<id>` é ignorado.
fn target_of(addr: &Multiaddr) -> Option<(String, u16)> {
    let mut host: Option<String> = None;
    let mut port: Option<u16> = None;
    for p in addr.iter() {
        match p {
            Protocol::Ip4(ip) => host = Some(ip.to_string()),
            Protocol::Ip6(ip) => host = Some(ip.to_string()),
            Protocol::Dns(h) | Protocol::Dns4(h) | Protocol::Dns6(h) => host = Some(h.to_string()),
            Protocol::Tcp(p) => port = Some(p),
            _ => {}
        }
    }
    Some((host?, port?))
}

impl Transport for SocksTransport {
    type Output = Compat<Socks5Stream<TcpStream>>;
    type Error = io::Error;
    type ListenerUpgrade = futures::future::Pending<Result<Self::Output, io::Error>>;
    type Dial = BoxFuture<'static, Result<Self::Output, io::Error>>;

    fn listen_on(
        &mut self,
        _id: ListenerId,
        addr: Multiaddr,
    ) -> Result<(), TransportError<io::Error>> {
        // publicador anônimo NUNCA escuta (só saída)
        Err(TransportError::MultiaddrNotSupported(addr))
    }

    fn remove_listener(&mut self, _id: ListenerId) -> bool {
        false
    }

    fn dial(
        &mut self,
        addr: Multiaddr,
        _opts: DialOpts,
    ) -> Result<Self::Dial, TransportError<io::Error>> {
        let (host, port) = match target_of(&addr) {
            Some(t) => t,
            None => return Err(TransportError::MultiaddrNotSupported(addr)),
        };
        let proxy = self.proxy;
        Ok(async move {
            // resolução REMOTA: alvo por domínio/.onion resolvido dentro do circuito (SOCKS5h)
            let stream = Socks5Stream::connect(proxy, (host.as_str(), port))
                .await
                .map_err(|e| io::Error::new(io::ErrorKind::Other, e))?;
            eprintln!("SOCKS→{host}:{port} OK (circuito Tor)"); // prova de dial tunelado
            Ok(stream.compat())
        }
        .boxed())
    }

    fn poll(
        self: Pin<&mut Self>,
        _cx: &mut Context<'_>,
    ) -> Poll<TransportEvent<Self::ListenerUpgrade, io::Error>> {
        // sem listener → nunca há eventos de transporte
        Poll::Pending
    }
}
