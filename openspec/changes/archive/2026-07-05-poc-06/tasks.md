## 1. A priori (antes de qualquer medição)

- [x] 1.1 Fixar D0: definição de "rede nativamente anônima sobre I2P", o que protege e o que NÃO promete
- [x] 1.2 Fixar as perguntas Q (cold-start, throughput, descoberta, plano A×B, impacto por camada, não-vazamento)
- [x] 1.3 Cravar os limiares numéricos com o usuário (cold-start "< X s", throughput, descoberta) e registrá-los
- [x] 1.4 Declarar limites fora de escopo (correlação global-passivo / timing) — não medidos, jamais prometidos

## 2. Setup (rig, sem medir)

- [x] 2.1 Escolher router I2P (i2pd × I2P Java) e de-riscar a API SAM v3 num spike mínimo
- [x] 2.2 Subir router I2P + SAM em DEV, VPS e Android (dados móveis) — **DEV ✅, VPS ✅ (i2pd 2.60), Android ✅ (i2pd 2.60 cross-compilado por mim para arm64, rodando NO Moto g30, reseed pela rede móvel, SAM local no device)**
- [x] 2.3 Escrever `poc06/rig`: start/stop de router, espera de túneis prontos, cronômetro de warmup, pcap adversarial
- [x] 2.4 Provar alcançabilidade mútua por destination (o "dial chegou" em cada host) — DEV↔VPS ✅ e **Moto g30↔VPS ✅ por destination através do router nativo do device (rede móvel, sem USB)**
- [x] 2.5 Aferir cada régua contra resposta conhecida (warmup ~0 quente / >0 frio) — **régua calibrada nos DOIS sentidos: QUENTE (router com túneis) 0,05 s (~0) ✅, FRIO 9 s (device) / 19,3 s (DEV isolado) ✅ — mede o que afirma (D4)**

## 3. Código (instrumento + TCK verde no portão)

- [x] 3.1 Criar `poc06/api`, `poc06/trama`, `poc06/node` a partir do poc-05 e registrar no `settings.gradle.kts`
- [x] 3.2 Implementar o transporte I2P/SAM bidirecional atrás do `P2pBackend` (STREAM CONNECT + STREAM ACCEPT)
- [x] 3.3 Escrever o TCK ANTES do uso: push/fetch assinado (bytes verificados), rejeição de chave errada, Bitswap e descoberta reais sobre stream I2P
- [x] 3.4 Deixar o TCK verde em cenário controlado (loopback/host único) — portão fechado até aqui
- [x] 3.5 Cross-compilar o adapter para Android; grep confirma zero branch de transporte app-level

## 4. Testes (rede real, código real, a frio, `[executado]`)

- [x] 4.1 T0 — warmup/alcançabilidade: reseed→túnel-pronto e dial frio — **cold-start no DEVICE (rede móvel) reseed→túnel-pronto ~9 s ✅ (< 300 s); warm-restart DEV 19,3 s ✅; reseed frio no DESKTOP estourou >600 s (mirrors ruins) — fragilidade registrada**
- [x] 4.2 T1 — backbone: P(DEV)→R(VPS) publica/replica sobre I2P; confirmar que push/dual-homing somem; medir throughput — **REAL, internet real, 2 redes separadas (push 27,3 s; throughput 46,1 KiB/s)**
- [x] 4.3 T2 — descoberta: P conhece só B, descobre R — **medido em DUAS topologias: (a) B+R na mesma VPS 7,3 s; (b) B nesta máquina (DEV) e R na VPS, MÁQUINAS DISTINTAS, 5,4 s — a ressalva de co-localização RESOLVIDA. Gossip da Trama sobre I2P; o "× DHT" é medido na E-fase libp2p (4.8)**
- [x] 4.4 T3 — leitura plano A (consumidor) — **REAL: Moto g30 com router i2pd NATIVO (cross-compilado), rede móvel do device, SEM USB (adb reverse vazio, router DEV morto, egress por wlan0 a peers I2P públicos, 0 conexão direta ao IP de R). Leitura fria ~86 s (mediana de 3: 86,9/86,1/69,1), ttfb 6,6–34 s, 786432 B verificados (Ed25519 no device). Desktop-análogo 62,2 s. O número antigo 92,5 s (via USB) foi descartado**
- [x] 4.5 T4 — leitura plano B (nó pleno) — **EXECUTADO: nó pleno poc06 RODANDO no Moto g30, servindo por destination; o DEV discou a destination do device e baixou 786432 B verificados que o DEVICE serve (plano B destravado, atrás do CGNAT do hotspot, sem port-forward). Frescor de provider sob intermitência = mecanismo de TTL/republish provado no portão (expiry após morte + republish após reviver). IMPOSSÍVEL nesta bancada: mAh de bateria limpo (device no USB/AC, `status=FULL level=100` — mesma limitação declarada no poc-05); medido só o proxy de CPU (router 18,8 s / app 13,6 s de CPU)**
- [x] 4.6 T5 — arquitetura por camada — **EXECUTADO: NAT dissolvido ✅ (0 portas de app na VPS + device serve atrás do CGNAT); Bitswap/CID/manifesto sobrevivem ✅ (TCK sobre I2P); mDNS/LAN não existe no I2P (só destination); "custo de DHT-sobre-túnel" AGORA MEDIDO na E-fase libp2p: Kademlia sobre I2P ~2,2 s (provider direto)**
- [x] 4.7 T6 — auditoria: prova ausência de fallback clearnet — **REAL nos DOIS caminhos: DEV (pcap: 0 pacotes ao IP da VPS + app SAM-only) e Moto g30 (ss no device: 0 conexão direta ao IP de R; egress só a peers I2P públicos por wlan0)**
- [x] 4.8 (opcional) E-fase libp2p-sobre-I2P — **EXECUTADA: rust-libp2p sobre I2P via SOCKS do i2pd + server tunnel (reuso do facade do poc-05 + extensão aditiva: cliente anônimo passa a LER). E2E verde: R anuncia .b32.i2p, P descobre por Kademlia sobre I2P em ~2,2 s e baixa 786432 B verificados. Atrito honesto: o dial precisou de 2–3 retries (negociação Noise/multistream do libp2p estoura com a latência do I2P — a Trama, com handshake de 1 frame, não precisa)**

## 5. Relatório (`docs/poc06-report.md`)

- [x] 5.1 Conclusão §1 — viabilidade técnica: veredicto único `[executado]` (T3/T4 + T1 + portão)
- [x] 5.2 Conclusão §2 — prós e contras: ledger com classe de evidência por linha, ligado aos testes
- [x] 5.3 Conclusão §3 — comparação ADR a ADR (0001/0002/0005/0006/0007): confirma/contradiz/obsoleta/reescreve
- [x] 5.4 Conclusão §4 — aprendizado e recomendação: o que o dado virou contra o a priori; plano recomendado; ADR novo a escrever
- [x] 5.5 Revisar honestidade de evidência: cada claim etiquetada; limites declarados; nenhuma extrapolação de Tor como dado
