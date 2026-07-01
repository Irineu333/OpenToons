# OpenToons

> Rede **descentralizada** e **resistente à censura** para compartilhamento de mangás.

O OpenToons é uma rede P2P sem servidor central onde **scans** publicam conteúdo
**assinado** e **leitores** consomem de qualquer nó da malha, com cache offline.
O conteúdo é impossível de falsificar e inviável de remover por terceiros.

## Ideia central

- **P2P assimétrico, sem autoridade:** *scans* (desktop) e *replicadores* (CLI)
  sustentam a rede como nós plenos; *leitores* (mobile) apenas consomem, como nós
  leves — sem precisar servir conteúdo.
- **Tudo assinado:** o cliente sempre verifica a assinatura; impostores não forjam.
- **Três planos:** anúncio (aberto) · catálogo (leve, assinado, replicável) ·
  conteúdo (pesado, réplica opt-in com teto).
- **Sem centralização escondida:** o mobile é um *DHT client* que descobre e baixa
  direto dos detentores; bootstrap é só para a entrada.
- **Incentivo por doação direta**, guiada por pontuação calculada localmente.

## Aplicações

| App | Público | Papel | Função |
|-----|---------|-------|--------|
| **Desktop** | Scans | Nó pleno | Publicar, gerenciar, replicar, servir |
| **Mobile** | Leitores | Nó leve | Ler mangás, cache offline, consumir a rede |
| **CLI** | Voluntários | Nó pleno | Replicar e servir; sustentar a rede |

## Documentação

A documentação técnica vive em [`docs/`](./docs):

- [**Visão geral**](./docs/overview.md) — problema, princípios, modelo de ameaças, papéis, glossário.
- [**Arquitetura**](./docs/architecture.md) — os três planos, identidade, modelo de conteúdo, rede, descoberta, NAT.
- [**Roadmap**](./docs/roadmap.md) — os marcos, do PoC ao sistema de doações.
- [**Decisões (ADRs)**](./docs/decisions/README.md) — decisões de projeto e **alternativas descartadas**.

> ℹ️ Há um tema ainda **em aberto**: autenticidade de identidade
> ([ADR-0008](./docs/decisions/0008-identity-trust.md)).

## Stack

Projeto **Kotlin Multiplatform + Compose Multiplatform** (desktop JVM e Android),
com camada de rede baseada em conceitos de **libp2p/IPFS** (bibliotecas concretas a
validar na Prova de Conceito — marco 0).

- [`/shared`](./shared/src) — código compartilhado entre plataformas.
  - [`commonMain`](./shared/src/commonMain/kotlin) — comum a todos os alvos.
  - `jvmMain` / demais — código específico de plataforma.

## Executando

Use as *run configurations* do widget de execução da sua IDE, ou os comandos:

- App desktop:
  - Hot reload: `./gradlew :desktopApp:hotRun --auto`
  - Execução padrão: `./gradlew :desktopApp:run`

## Estado do projeto

Em fase de **design**. A arquitetura está consolidada na documentação; a
implementação seguirá os marcos do [roadmap](./docs/roadmap.md), começando pela
Prova de Conceito.

---

Saiba mais sobre [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html).
