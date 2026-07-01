# Registros de Decisão de Arquitetura (ADRs)

Cada ADR (*Architecture Decision Record*) captura **uma decisão** de projeto: o
contexto, a decisão tomada, as **alternativas consideradas e por que foram
descartadas**, e as consequências. ADRs são imutáveis por convenção — quando uma
decisão muda, cria-se um novo ADR que supersede o anterior.

## Índice

| # | Decisão | Status |
|---|---------|--------|
| [0001](./0001-network-model.md) | Modelo de rede: P2P assimétrico com papéis | Aceito |
| [0002](./0002-three-planes.md) | Separação em três planos (anúncio / catálogo / conteúdo) | Aceito |
| [0003](./0003-content-model.md) | Modelo de conteúdo: manifesto assinado + `obra_id` estável + `seq` | Aceito |
| [0004](./0004-deletion-semantics.md) | Semântica de exclusão: despublicação por escolha | Aceito |
| [0005](./0005-mobile-client.md) | Cliente mobile: Caminho A (DHT client) | Aceito |
| [0006](./0006-nat-and-reachability.md) | NAT e alcançabilidade: scans públicas, relay só fallback | Aceito |
| [0007](./0007-resilient-bootstrap.md) | Bootstrap resiliente multi-canal | Aceito |
| [0008](./0008-identity-trust.md) | Autenticidade de identidade (chave → scan) | **Em aberto** |
| [0009](./0009-scoring-and-donations.md) | Pontuação e doações | **Proposto** |

## Legenda de status

- **Aceito** — decisão firmada; guia a implementação.
- **Proposto** — direção provável, mas ainda sujeita a refinamento.
- **Em aberto** — problema reconhecido, sem decisão; requer exploração futura.
- **Superseded por NNNN** — substituído por um ADR mais novo.
