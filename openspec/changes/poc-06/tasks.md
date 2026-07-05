## 1. A priori (antes de qualquer medição)

- [ ] 1.1 Fixar D0: definição de "rede nativamente anônima sobre I2P", o que protege e o que NÃO promete
- [ ] 1.2 Fixar as perguntas Q (cold-start, throughput, descoberta, plano A×B, impacto por camada, não-vazamento)
- [ ] 1.3 Cravar os limiares numéricos com o usuário (cold-start "< X s", throughput, descoberta) e registrá-los
- [ ] 1.4 Declarar limites fora de escopo (correlação global-passivo / timing) — não medidos, jamais prometidos

## 2. Setup (rig, sem medir)

- [ ] 2.1 Escolher router I2P (i2pd × I2P Java) e de-riscar a API SAM v3 num spike mínimo
- [ ] 2.2 Subir router I2P + SAM em DEV, VPS e Android (dados móveis)
- [ ] 2.3 Escrever `poc06/rig`: start/stop de router, espera de túneis prontos, cronômetro de warmup, pcap adversarial
- [ ] 2.4 Provar alcançabilidade mútua por destination (o "dial chegou" em cada host)
- [ ] 2.5 Aferir cada régua contra resposta conhecida (warmup ~0 quente / >0 frio)

## 3. Código (instrumento + TCK verde no portão)

- [ ] 3.1 Criar `poc06/api`, `poc06/trama`, `poc06/node` a partir do poc-05 e registrar no `settings.gradle.kts`
- [ ] 3.2 Implementar o transporte I2P/SAM bidirecional atrás do `P2pBackend` (STREAM CONNECT + STREAM ACCEPT)
- [ ] 3.3 Escrever o TCK ANTES do uso: push/fetch assinado (bytes verificados), rejeição de chave errada, Bitswap e descoberta reais sobre stream I2P
- [ ] 3.4 Deixar o TCK verde em cenário controlado (loopback/host único) — portão fechado até aqui
- [ ] 3.5 Cross-compilar o adapter para Android; grep confirma zero branch de transporte app-level

## 4. Testes (rede real, código real, a frio, `[executado]`)

- [ ] 4.1 T0 — warmup/alcançabilidade: reseed→túnel-pronto e dial frio DEV↔VPS
- [ ] 4.2 T1 — backbone: P(DEV)→R(VPS) publica/replica sobre I2P; confirmar que push/dual-homing somem; medir throughput
- [ ] 4.3 T2 — descoberta: P conhece só B, descobre R; gossip × DHT medidos SOBRE I2P (registrar a ressalva B+R na mesma VPS)
- [ ] 4.4 T3 — leitura plano A (consumidor): Moto g30 a frio abre capítulo; warmup, tempo-até-primeiro-byte, throughput
- [ ] 4.5 T4 — leitura plano B (nó pleno): mesma leitura + custo de bateria/uptime + frescor de provider sob intermitência
- [ ] 4.6 T5 — arquitetura por camada exercida em código: NAT dissolvido (sem port-forward), mDNS/LAN falha, Bitswap/CID/manifesto sobrevivem, custo de DHT-sobre-túnel
- [ ] 4.7 T6 — auditoria: pcap no caminho de leitura prova ausência de fallback clearnet
- [ ] 4.8 (opcional) E-fase libp2p-sobre-I2P — só se o núcleo passar

## 5. Relatório (`docs/poc06-report.md`)

- [ ] 5.1 Conclusão §1 — viabilidade técnica: veredicto único `[executado]` (T3/T4 + T1 + portão)
- [ ] 5.2 Conclusão §2 — prós e contras: ledger com classe de evidência por linha, ligado aos testes
- [ ] 5.3 Conclusão §3 — comparação ADR a ADR (0001/0002/0005/0006/0007): confirma/contradiz/obsoleta/reescreve
- [ ] 5.4 Conclusão §4 — aprendizado e recomendação: o que o dado virou contra o a priori; plano recomendado; ADR novo a escrever
- [ ] 5.5 Revisar honestidade de evidência: cada claim etiquetada; limites declarados; nenhuma extrapolação de Tor como dado
