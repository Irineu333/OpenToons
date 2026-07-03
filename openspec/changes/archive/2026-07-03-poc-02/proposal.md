# Proposta: poc-02 — Implementação própria da camada de rede (sem framework P2P)

## Why

O poc-01 validou a stack nabu/jvm-libp2p, mas o veredito veio com ressalvas estruturais: quatro bugs upstream diagnosticados (race no `provideBlock`, entrada no-op no router, hash errado no `findProviders`, QUIC instável), workarounds em todas as camadas, upstream sem builds utilizáveis no JitPack desde a v0.8.0 e 12 MB de APK para usar uma fração da funcionalidade. Mais importante: a recomendação final do poc-01 foi **rede própria da OpenToons como caminho principal** — e o valor central do libp2p (interoperabilidade com o ecossistema IPFS/Amino) deixa de existir numa rede própria. A Amino serviu apenas para validar comportamento em escala, papel que simulação cumpre melhor.

A arquitetura da OpenToons descarta por decisão de design quase tudo que o libp2p resolve: NAT traversal (ADR-0006: endereço público manual), negociação dinâmica de protocolos, transportes plugáveis e muxing de streams arbitrários (o tráfego é todo request/response). Este poc-02 testa a hipótese inversa do poc-01: **pagar o custo de construir uma camada de rede própria e enxuta, para colher simplicidade, controle e um caminho KMP limpo**. Como no Marco 0, é código descartável cujo produto é *conhecimento* — dados comparativos para decisões futuras, não features.

## What Changes

- Novo módulo descartável `poc02/` no repositório (fora dos módulos de produto `shared`/`desktopApp`).
- **E1 — Canal seguro (2 variantes comparadas):** handshake mútuo autenticado ligado à identidade Ed25519, implementado como (a) **TLS 1.3** da plataforma com certificado autoassinado embutindo a identidade e (b) **Noise XX** sobre primitivas do BouncyCastle, validado contra os vetores de teste oficiais. Medição comparativa: esforço, LoC, dependências, latência de handshake e reconexão no dispositivo físico, restrições de API Android, rejeição de impostor.
- **E2 — RPC + troca de blocos:** protocolo de frames length-prefixed com request-id sobre o canal do E1 — sem muxer, sem bitswap. Download de manifesto + blocos com verificação Ed25519 (reaproveita o formato de manifesto validado no E3 do poc-01).
- **E3 — Descoberta (2 variantes comparadas):** (a) **membership completo + gossip** (anti-entropia, PEX) e (b) **Kademlia enxuto próprio**, ambos medidos em simulação in-process com n = 10..10.000 nós e em descoberta fria real. Métricas: RTTs por lookup do cliente, tráfego/hora por nó, memória de estado, convergência pós-churn, esforço/LoC. O resultado fixa o mecanismo do Marco 2 e o limiar objetivo de migração futura.
- **E4 — E2E (mesmo critério do Marco 0):** capítulo assinado num nó público é descoberto (descoberta fria) e baixado por um mobile em outra rede, com verificação de assinatura e rejeição de conteúdo adulterado — provando que a stack própria fecha o mesmo critério que o nabu fechou.
- **E5 — Medições comparativas:** mesma sessão de 30 min, mesmo dispositivo (Moto g(30)) e mesmos limiares do poc-01 (< 5% bateria, < 20 MB dados), mais tamanho de APK, LoC e nº de dependências — tabela lado a lado nabu × implementação própria.
- **Relatório final** `docs/poc02-report.md` com as duas matrizes de decisão (canal seguro; descoberta), medições e recomendação de stack para o Marco 2 — o artefato durável; o código de `poc02/` pode ser apagado depois.

## Capabilities

### New Capabilities

- `poc2-validation`: os experimentos comparativos do poc-02 (E1–E5) e o relatório — o que cada experimento precisa demonstrar e medir para ser considerado conclusivo (positiva ou negativamente), incluindo os limiares fixados a priori e o veto de esforço.

### Modified Capabilities

(nenhuma — não existem specs ativas; a spec do poc-01 foi arquivada com o change)

## Impact

- **Código:** novo módulo Gradle `poc02/` (JVM + alvo Android mínimo); nenhum código de produto (`shared`, `desktopApp`) é alterado. `poc01/` permanece intocado como linha de base de comparação.
- **Dependências:** apenas libs comuns e triviais — BouncyCastle (já em uso no E3 do poc-01), ktor-network ou java.nio para sockets, kotlinx.serialization para o formato de wire. **Nenhum framework P2P.** Conscrypt entra somente se o E1a (TLS) exigir para minSdk 26.
- **Infra:** um nó com porta pública (o mesmo port forwarding do poc-01); dispositivo físico Moto g(30) para comparabilidade das medições; **sem uso da Amino** (simulação substitui a validação em escala).
- **Docs:** `docs/poc02-report.md` novo; o roadmap e o `poc01-report.md` ganham referência a esta change.
- **Risco assumido:** implementação própria custar mais do que parece. O veto de esforço (teto de dias por variante do E1, fixado a priori no design) transforma estouro de custo em **resultado válido da PoC** — "ficar com o nabu + workarounds" segue sendo uma conclusão possível. O custo de manutenção perpétua de código de segurança não é mensurável por experimento e será registrado como consequência assumida na decisão final.
