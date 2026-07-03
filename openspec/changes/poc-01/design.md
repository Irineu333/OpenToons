# Design: poc-01 — Prova de Conceito (Marco 0)

## Context

O projeto hoje é um esqueleto KMP (`shared` + `desktopApp`) sem código de rede. O Marco 0 do [roadmap](../../../docs/roadmap.md) exige validar as premissas mais arriscadas antes dos marcos de produto. As restrições vêm dos ADRs já aceitos:

- [ADR-0005](../../../docs/decisions/0005-mobile-client.md): o mobile é **DHT client puro** — consulta, não serve.
- [ADR-0006](../../../docs/decisions/0006-nat-and-reachability.md): nó pleno tem **endereço público manual**; nada de hole punching na v1.
- [ADR-0003](../../../docs/decisions/0003-content-model.md): manifesto assinado com `seq` monotônico contra rollback.

Investigação preliminar (jul/2026): jvm-libp2p oficial tem transporte/Noise/gossipsub maduros, mas **Kad-DHT incompleto e sem bitswap**; nabu (Peergos) implementa **Kad-DHT + Bitswap 1.2.0** sobre a mesma família de stack, roda em produção há anos, e declara compatibilidade Android não comprovada.

## Goals / Non-Goals

**Goals:**

- Responder com evidência: (a) nabu roda no Android? (b) existe modo DHT client puro? (c) qual o custo de bateria/dados? (d) o E2E do Marco 0 fecha?
- Produzir `docs/poc-report.md` — o artefato durável — com medições, conclusões e a decisão de biblioteca para o Marco 2.
- Falhar rápido: o caminho crítico (E2, Android) é atacado primeiro em modo *spike*.

**Non-Goals:**

- Qualquer código de produto — nada em `shared`/`desktopApp`; UX, cache offline, catálogo, doações ficam nos marcos 1–5.
- Hole punching / relay (ADR-0006 adia para o marco 4).
- Formato final de manifesto — E3 valida o *mecanismo* (assinatura + `seq`), não o schema definitivo.
- Robustez, testes exaustivos, cobertura — código descartável, com o rigor mínimo para as conclusões serem críveis.

## Decisions

### D1 — nabu como stack primária; jvm-libp2p puro como fallback parcial

O que o Marco 0 precisa (DHT + troca de blocos) só existe pronto, na JVM, no nabu. Usar jvm-libp2p direto exigiria implementar Kad-DHT/bitswap à mão — inviável para uma PoC. Se o nabu falhar **no Android** (E2), o plano B documentado é bindings de go-libp2p (gomobile) ou rust-libp2p (UniFFI) — fora do escopo desta change; a PoC apenas registra a evidência da falha e a recomendação.

### D2 — DHT pública do IPFS (Amino) para descoberta; rede própria só se necessário

Uma "rede" de 2–3 nós tem DHT degenerada e não prova descoberta real. Entrar na Amino dá bootstrap resolvido, condições reais e vizinhança de verdade. Custo: os experimentos dependem de rede externa — aceitável para PoC. Se a Amino se mostrar hostil (churn, latência inviabilizando medição), E4 pode rodar em rede privada com bootstrap próprio, registrando o desvio no relatório.

### D3 — Módulo Gradle `poc01/` isolado no repositório

Repo separado dificultaria referenciar ADRs e manter histórico junto. Um módulo `poc01/` (com submódulos `poc01/node` JVM e `poc01/android` se necessário) mantém as dependências pesadas (nabu/Netty) fora de `shared`/`desktopApp` e é trivial de apagar após o relatório.

### D4 — E3 (manifesto) em Kotlin puro, sem libp2p

Assinatura Ed25519 e verificação de `seq` não dependem de rede. Fazer E3 com uma lib de crypto isolada (ex.: BouncyCastle ou a crypto do próprio nabu) permite progresso paralelo ao caminho crítico e produz conhecimento reutilizável no Marco 2 independentemente da decisão de stack.

### D5 — Medição de bateria/dados com critério definido a priori

Antes de medir, o relatório fixa os limiares (proposta inicial: sessão de leitura simulada de 30 min com lookups periódicos deve consumir < 5% de bateria e < 20 MB de dados além do conteúdo baixado; ajustável com justificativa). Ferramentas: Battery Historian / `dumpsys batterystats` + contadores de tráfego por UID. Medição de PoC, não de laboratório — o objetivo é detectar inviabilidade grosseira, não otimizar.

### D6 — Ordem dos experimentos: E2 primeiro (spike), depois E1 → E4; E3 em paralelo

O risco existencial é o Android. Um spike de compilação/execução do nabu no Android (mesmo sem DHT ainda) na primeira semana decide se o resto do plano vale. E1 (nó discável JVM) é baixo risco e serve de contraparte para E2/E4.

### D7 — Rede bootstrap/DHT própria como caminho principal (adicionada durante a execução)

O diagnóstico do E2/4.1 mostrou que a descoberta na Amino em escala depende de correções upstream no nabu/jvm-libp2p (race no `provideBlock`, dial `/dns/`, QUIC instável em dials paralelos, hash do walk). Em vez de condicionar o Marco 0 a esses fixes, o experimento E5 valida a alternativa: **rede DHT própria da OpenToons** (bootstrap dedicado + nós plenos públicos em malha de dials de saída), onde a descoberta fria funciona ponta a ponta com os workarounds documentados. A Amino vira integração futura opcional.

## Risks / Trade-offs

- [nabu não roda no Android (Netty, APIs JDK, dex)] → spike E2 primeiro; se falhar, relatório recomenda plano B (gomobile/UniFFI) e a change encerra com resultado negativo — que é um resultado válido.
- [nabu não expõe modo DHT client puro] → verificar API/configuração logo no início de E2; se só houver modo servidor, medir o custo de operar com servidor desabilitado/limitado e registrar o gap como requisito para o Marco 2 (upstream ou fork).
- [Amino DHT instável para medição] → repetir medições em horários distintos; fallback para rede privada (D2) com o desvio documentado.
- [Endereço público manual indisponível no ambiente do dev] → VPS barata como nó E1; o custo já está assumido no Impact da proposta.
- [PoC "vaza" para produto (código descartável virando base)] → módulo `poc01/` explicitamente fora dos módulos de produto; o relatório, não o código, é o entregável.

## Open Questions

- O nabu expõe API para atuar como *DHT client* sem aceitar conexões de entrada, ou isso exigirá configuração/patch? (primeira tarefa de E2)
- Licenciamento e tamanho do APK com nabu+Netty embarcados — aceitáveis para o produto futuro? (registrar no relatório)
- Qual versão mínima de Android o stack suporta na prática? (registrar no relatório)
