# Tasks: poc-02 — Implementação própria da camada de rede

> Ordem conforme design D7: E1 primeiro (risco existencial, com veto de esforço), E3-simulação em paralelo; E2 → E4 → E5 na sequência. Registrar **esforço real (dias)** ao concluir cada grupo — é dado do relatório.

## 1. Setup

- [ ] 1.1 Criar módulo Gradle `poc02/` (JVM) e alvo Android mínimo `poc02/android` (minSdk 26), fora dos módulos de produto; deps permitidas: BouncyCastle, ktor-network/java.nio, kotlinx.serialization (D1)
- [ ] 1.2 Portar/referenciar o `Manifest.kt` + testes do poc-01 (formato validado, D8) e utilitários de identidade Ed25519
- [ ] 1.3 Esqueleto de transporte TCP com frames length-prefixed (sem segurança ainda) + teste de loopback JVM↔JVM — base comum para E1a/E1b
- [ ] 1.4 Registrar no relatório-rascunho os limiares a priori (D5) antes de qualquer medição

## 2. E1a — Canal seguro: TLS 1.3 (veto: 5 dias úteis)

- [ ] 2.1 Responder a questão aberta: certificado Ed25519 direto no JSSE/Android funciona, ou é preciso cert ECDSA + extensão com identidade assinada (esquema libp2p-tls)? Prototipar a geração do certificado (bcpkix)
- [ ] 2.2 TrustManager customizado que valida a identidade embutida (ignora cadeia CA) + handshake mútuo JVM↔JVM
- [ ] 2.3 Handshake no Android físico (Moto g(30), API 31): validar TLS 1.3 vs fallback; decidir/medir necessidade de Conscrypt para minSdk 26–28 e registrar o custo em MB
- [ ] 2.4 Teste de impostor: canal válido com identidade errada é rejeitado antes de dados de aplicação
- [ ] 2.5 Medir latência de handshake e reconexão (session resumption) no dispositivo; registrar esforço real, LoC, deps e restrições Android

## 3. E1b — Canal seguro: Noise XX (veto: 5 dias úteis)

- [ ] 3.1 Implementar o handshake Noise XX (X25519 + ChaCha20-Poly1305 + SHA-256/HKDF sobre BouncyCastle) com máquina de estados e transporte pós-handshake (nonces, limite de 65535 B por mensagem)
- [ ] 3.2 Validar contra os vetores de teste oficiais do Noise — bloqueante: falha em vetor impede uso no E2/E4
- [ ] 3.3 Ligar identidade: chave estática Noise assinada pela identidade Ed25519 (esquema libp2p-noise); teste de impostor
- [ ] 3.4 Handshake JVM↔Android físico; medir latência de handshake e reconexão (sem resumption)
- [ ] 3.5 Registrar esforço real, LoC, deps e riscos residuais

## 4. Decisão do canal (fecha E1)

- [ ] 4.1 Preencher a matriz comparativa do design D2 com os dados medidos e recomendar o canal do Marco 2 (ou registrar veto duplo → recomendação "nabu + workarounds" e encerrar a PoC pelo cenário de falha conclusiva)

## 5. E3 — Descoberta em simulação (paralelo a E1; transporte in-process)

- [ ] 5.1 Definir a interface comum `resolve(obraId) → [providers]` e o harness de simulação in-process (n nós, churn injetável, contadores de RTT/tráfego/memória)
- [ ] 5.2 E3a: implementar membership completo + gossip (anti-entropia com digests, PEX, anúncios de provider com expiry); decidir anúncio por obra vs por manifesto e registrar
- [ ] 5.3 E3b: implementar Kademlia enxuto (k-buckets, lookup iterativo, provider records com expiry/republish) — reusando as lições dos 3 bugs do E5 do poc-01
- [ ] 5.4 Rodar ambos com n ∈ {10, 100, 1.000, 10.000} + churn: medir RTTs/lookup do cliente, tráfego/hora/nó, memória de estado, convergência pós-churn
- [ ] 5.5 Verificar que o cliente nunca armazena/roteia/aceita entrada em ambas as variantes (ADR-0005)
- [ ] 5.6 Preencher a matriz do design D3, recomendar a descoberta do Marco 2 e fixar o limiar objetivo de migração p/ DHT (curva de escala); registrar esforço/LoC por variante

## 6. E2 — RPC + blocos sobre o canal vencedor

- [ ] 6.1 Protocolo de frames com request-id sobre o canal do E1: requisições concorrentes numa conexão, respostas correlacionadas fora de ordem; escolher kotlinx.serialization protobuf vs cbor e registrar racional
- [ ] 6.2 Operações get-manifest / get-block com verificação (assinatura do manifesto + hash dos blocos) e reconstrução do capítulo de teste do poc-01
- [ ] 6.3 Medir throughput do capítulo de 3 blocos vs números do nabu/bitswap; observar/registrar head-of-line blocking com downloads concorrentes (mitigar só se aparecer, D4)

## 7. E4 — E2E do Marco 0 com a stack própria

- [ ] 7.1 Nó pleno JVM com endereço público manual (mesmo port forwarding do poc-01) publicando o capítulo de teste; bootstrap com a descoberta escolhida no E3
- [ ] 7.2 App Android no dispositivo físico, em outra rede (hotspot/dados móveis): descoberta fria (só bootstrap + obraId) → disca o publicador nunca informado → baixa → verifica assinatura → reconstrói capítulo
- [ ] 7.3 Rejeição de adulterado: bloco com hash divergente e manifesto com assinatura inválida são rejeitados e reportados

## 8. E5 — Medições comparativas

- [ ] 8.1 Sessão de 30 min no Moto g(30), mesmo roteiro do poc-01 (lookups periódicos, `batterystats` + tráfego por UID): bateria < 5%, dados < 20 MB, lado a lado com 0,03% / 1,09 MB do nabu
- [ ] 8.2 Medir handshake (< 1 s) e reconexão (< 500 ms) em rede real; lookup frio ≤ 3 RTTs se gossip
- [ ] 8.3 Empacotar o app com a camada completa: delta de APK (≤ 2 MB vs 12 MB do nabu), LoC da camada de rede, nº de dependências

## 9. Relatório e fechamento

- [ ] 9.1 Escrever `docs/poc02-report.md`: resultados por experimento, as duas matrizes de decisão preenchidas, esforço real por experimento, respostas às questões abertas do design, limiares vs medições, e recomendação fundamentada (implementação própria × nabu + workarounds) — incluindo manutenção perpétua de código de segurança como consequência assumida
- [ ] 9.2 Atualizar `docs/roadmap.md` e `docs/poc-report.md` com referência ao poc-02; registrar o gatilho de migração gossip→DHT no roadmap se aplicável
