# Proposta: poc-01 — Prova de Conceito (Marco 0)

## Why

O roadmap inteiro do OpenToons assenta sobre premissas técnicas não comprovadas: que existe stack libp2p viável em Kotlin/JVM e Android, que um nó pleno discável por endereço público manual funciona, que o mobile consegue ser DHT client puro (consultar sem servir) a custo aceitável de bateria/dados, e que manifesto assinado + troca de blocos fecham um ciclo E2E. Validar isso **antes** de investir nos marcos 1–5 é o propósito declarado do Marco 0 — código descartável cujo produto é *conhecimento*, não features.

A investigação preliminar mostrou que a stack oficial ([jvm-libp2p](https://github.com/libp2p/jvm-libp2p)) não tem Kad-DHT completo nem bitswap, enquanto o [nabu](https://github.com/Peergos/nabu) (Peergos, produção há anos) cobre ambos — mas com duas incógnitas que só a PoC responde: roda no Android? suporta modo DHT client puro?

## What Changes

- Novo módulo descartável `poc01/` no repositório com os experimentos (fora dos módulos de produto `shared`/`desktopApp`).
- **E1 — Nó pleno discável (JVM):** nó com nabu, endereço público configurado manualmente, entra na DHT e anuncia um bloco (provider record).
- **E2 — DHT client no Android:** app mínimo que compila o stack, resolve provider records **sem servir**, e mede custo de bateria/dados.
- **E3 — Manifesto assinado:** assinar (Ed25519), verificar e detectar rollback via `seq`. Independente de libp2p; criptografia pura.
- **E4 — E2E (critério do Marco 0):** capítulo assinado num "desktop" discável é descoberto e baixado por um "mobile" atrás de NAT, via DHT, com verificação de assinatura.
- **E5 — Rede bootstrap/DHT própria** *(adicionado durante a execução, após o diagnóstico do gap de descoberta na Amino)*: rede DHT da própria OpenToons com bootstrap dedicado e **descoberta fria** — cliente que conhece só o bootstrap e o CID encontra e baixa conteúdo de um terceiro nó.
- **Relatório final** `docs/poc-report.md` com conclusões, medições e decisão de biblioteca — o artefato durável; o código do `poc01/` pode ser apagado depois.

## Capabilities

### New Capabilities

- `poc-validation`: os experimentos de validação do Marco 0 (E1–E4) e o relatório de conclusões — o que cada experimento precisa demonstrar para ser considerado conclusivo (positiva ou negativamente).

### Modified Capabilities

(nenhuma — não existem specs anteriores)

## Impact

- **Código:** novo módulo Gradle `poc01/` (JVM) e alvo Android mínimo para E2/E4; nenhum código de produto (`shared`, `desktopApp`) é alterado.
- **Dependências:** nabu (e transitivamente jvm-libp2p/Netty) apenas no módulo `poc01/`; biblioteca de crypto Ed25519 para E3.
- **Infra:** um nó com porta pública para E1/E4 (port forwarding manual ou VPS barata); uso da DHT pública do IPFS (Amino) onde fizer sentido.
- **Docs:** `docs/poc-report.md` novo; o roadmap (Marco 0) referencia esta change.
- **Risco assumido:** se E2 falhar (nabu não roda no Android), o plano B (bindings go/rust) é registrado no relatório como decisão a tomar — a PoC falhar *é* um resultado válido.
