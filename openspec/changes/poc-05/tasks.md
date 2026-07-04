# Tasks: poc-05 — modo anônimo (publicador sobre Tor), backend trocável (Trama × rust-libp2p)

> Ordem conforme design: E0 (definição + seam+push + TCK, o instrumento) → E1 (Trama, barato) → E2 (libp2p, o candidato a estourar o veto) → E3 (descoberta via Tor) → E4 (matriz 2×2 + não-vazamento auditado) → E5 (custo/robustez) → relatório. Registrar **esforço real (dias)** ao concluir cada grupo — é dado do relatório (Q8). Restrições transversais: implementação real dos dois lados, sem falseamento; não-vazamento é **auditado por pcap + exits do consenso** (D5), nunca declarado; o app leitor não muda entre backends (D1).

## 1. Setup

- [ ] 1.1 Criar módulos `poc05/api` (seam do poc-04 + `push`), `poc05/trama`, `poc05/libp2p`, `poc05/android` (build variants `trama`/`libp2p` — um backend por variant), `poc05/node` (full nodes de host: publicador P anônimo, replicador R, bootstrap B), fora dos módulos de produto; `poc01/`–`poc04/` intocados como baselines
- [ ] 1.2 Copiar o facade rust do poc-04 para `poc05/rust-facade` (o original fica como baseline); revalidar o toolchain (cargo-ndk + UniFFI + NDK + alvo de host dylib) — registrar atrito
- [ ] 1.3 Subir um daemon Tor local na máquina do publicador P (control port + SOCKS 9050) e configurar um onion service na VPS R (dual-homed: IP público + `.onion`); registrar o `torrc` de cada um
- [ ] 1.4 Montar o rig de auditoria: captura de rede (pcap) na interface de P, coleta de `lsof`/netstat de P, e um script que cruza IPs de conexão de B/R contra o consenso Tor (Onionoo) — o instrumento do critério transversal (D5)
- [ ] 1.5 Registrar no relatório-rascunho a **definição de "anônimo"** (D0), as questões Q1–Q10 e os limiares refixados para Tor (D7) — ANTES de qualquer medição

## 2. E0 — Seam estendido com push + TCK (o instrumento)

- [ ] 2.1 Estender o `poc05/api` com `push(provider, manifest, blocks)` (papel `FullNode`/`Replicator` — decidir conforme o TCK); tipos neutros, `verify` fora do seam, o frame de push sem endereço de origem (D1)
- [ ] 2.2 Definir a config de anonimato como parâmetro de **fábrica de backend** (proxy/onion endpoint + on/off), consumida dentro do adapter; candidata a capability-flag `ANONYMOUS_DIAL` (D2)
- [ ] 2.3 Escrever o TCK do push ANTES dos adapters: push autenticado pelo Noise gravado no `Blockstore`; push com manifesto de chave errada rejeitado **antes** de gravar — mesmos vetores para qualquer backend
- [ ] 2.4 Revisar o seam estendido contra os dois backends: `push` e a config de anonimato sem conceito específico de backend na superfície — responde Q3/Q4 no nível de design

## 3. E1 — Modo anônimo no backend Trama (veto: 5 dias úteis por adapter)

- [ ] 3.1 Dial da Trama através de `Proxy(SOCKS, 9050)` com resolução **remota** (SOCKS5h) ou dial por onion — provar por pcap que nenhum DNS sai localmente (D4)
- [ ] 3.2 Implementar o `push` sobre o canal Noise tunelado; confirmar que o handshake Noise autentica a identidade Ed25519 do par através do túnel (impostor via exit rejeitado antes de dados)
- [ ] 3.3 Rodar o TCK (client + push) do backend Trama em modo anônimo: 100% verde, com a auditoria de não-vazamento ativa

## 4. E2 — Modo anônimo no backend rust-libp2p (veto: 5 dias úteis por adapter)

- [ ] 4.1 Estender o facade rust com transporte Tor (SOCKS/onion) para TCP; **desligar QUIC** (UDP não trafega no Tor); registrar o esforço do Transport customizado — candidato a estourar o veto (D6)
- [ ] 4.2 Conter/desligar tudo que disca sozinho no swarm (identify anunciando endereços, mDNS, multiaddrs `/dns/`); provar a contenção por pcap
- [ ] 4.3 Implementar o `push` como protocolo request-response no facade; expor via UniFFI/host dylib
- [ ] 4.4 Rodar o TCK (client + push) do backend rust-libp2p em modo anônimo com auditoria de não-vazamento; se o veto estourar ou o pcap vazar sem contenção viável, encerrar a célula libp2p com evidência (D6) — resultado válido

## 5. E3 — Descoberta do replicador através do Tor (cenário 2)

- [ ] 5.1 Provar que o publicador, conhecendo só o bootstrap B, descobre o replicador R (nunca informado) por dentro do túnel — no backend Trama (PEX/RESOLVE, 1 circuito)
- [ ] 5.2 Idem no backend rust-libp2p (walk de Kademlia, dials a múltiplos peers = múltiplos circuitos); medir a latência de lookup frio de cada mecanismo contra o limiar de < 10 s (Q2, Q7)

## 6. E4 — Matriz E2E 2×2 real + critério transversal de não-vazamento

- [ ] 6.1 Subir a topologia: P sobre Tor (0 sockets de escuta, só saída), R na clearnet com IP público, B na clearnet, M no Moto g(30) em dados móveis; comprovar alcançabilidade externa de R/B por check-host.net
- [ ] 6.2 **C1 (Trama e libp2p)** — push P→R com IP de R conhecido → app M descobre e baixa de R pela clearnet → verifica assinatura + hashes → rejeições (bloco corrompido; chave errada)
- [ ] 6.3 **C2 (Trama e libp2p)** — push P→R com R descoberto via Tor (P conhece só B) → app M baixa de R → verifica + rejeições
- [ ] 6.4 Auditar o não-vazamento em cada célula (D5): (1) pcap = 0 pacotes fora do daemon Tor; (2) 0 listen não-loopback em P; (3) IPs vistos por B/R ⊆ exits do consenso (ou onion sem IP de origem); (4) dump do wire do push sem endereço de P — célula que funcione mas vaze é ❌
- [ ] 6.5 Confirmar zero branch por backend no app leitor (grep) e que M lê sempre da clearnet (nenhuma dependência de Tor no mobile)

## 7. E5 — Custo da abstração, robustez de circuito, latências

- [ ] 7.1 Inventário de pontos de vazamento novos do seam (meta ≤ 1 além dos 3 do poc-04) por auditoria de grep — capability-flag documentada, zero branch de app (Q4)
- [ ] 7.2 Medir regressão de peso por backend e LoC de cola por adapter; registrar o esforço real por adapter contra o veto (Q8)
- [ ] 7.3 Robustez de circuito: matar um circuito Tor no meio do push de 768 KiB e provar retoma sem intervenção, com verificação ao final (Q9)
- [ ] 7.4 Registrar as latências degradadas (handshake, requisição quente, push, lookup frio) contra os limiares refixados para Tor (Q6); excesso vira dado honesto sobre a UX

## 8. E6 — Relatório e veredito

- [ ] 8.1 Escrever `docs/poc05-report.md` respondendo Q1–Q10, cada claim etiquetada (executado / dado-só / só-design)
- [ ] 8.2 Declarar o veredito: modo anônimo viável e abaixo do seam / viável mas vaza / inviável num backend — e se é **gatilho invertido** contra migrar da Trama ao rust-libp2p (Q7/Q10)
- [ ] 8.3 Registrar as ameaças à validade (P na mesma máquina do rig; exit policies; timing com poucos nós) e as recomendações para o Marco 4 (ADR do Tor como alcançabilidade+privacidade; superfície `push`; seam aceitar endereços não-IP)
- [ ] 8.4 Referenciar a change no roadmap, nos ADR-0001/0006 e nos relatórios anteriores; registrar esforço real total (dias)
