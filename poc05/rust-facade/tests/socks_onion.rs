// Teste de isolamento: tokio-socks conecta ao onion:4100 pelo SOCKS local? (E2 diag)
use std::time::Instant;
use tokio_socks::tcp::Socks5Stream;

#[tokio::test]
async fn socks_conecta_no_onion() {
    let onion = match std::env::var("ONION") { Ok(v) => v, Err(_) => { eprintln!("sem ONION, pulando"); return; } };
    let t0 = Instant::now();
    match Socks5Stream::connect("127.0.0.1:9050", (onion.as_str(), 4100u16)).await {
        Ok(_) => eprintln!("SOCKS→onion:4100 OK em {:?}", t0.elapsed()),
        Err(e) => { eprintln!("SOCKS→onion:4100 FALHOU em {:?}: {e}", t0.elapsed()); panic!("{e}"); }
    }
}
