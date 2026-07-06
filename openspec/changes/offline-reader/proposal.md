## Why

O [roadmap](../../../docs/roadmap.md) coloca o **Marco 1 — Leitor multiplataforma
completo (offline)** como o primeiro estágio a entregar valor real: um leitor de
obras de primeira linha que funciona **sozinho, sem rede**, amadurecendo a UX antes
de acoplar a complexidade P2P do Marco 2. Todo o esforço até aqui (poc-01…poc-07) foi
na **rede**; nada foi construído do lado que o usuário toca — **ler**. Esta mudança
constrói esse leitor, com uma costura deliberada: a rede do Marco 2 deve entrar como
"mais um `Source`", sem reescrever nada do que se decide aqui.

## What Changes

- **App leitor multiplataforma** (Android · Desktop JVM · iOS) em Compose
  Multiplatform, **separado** do futuro app publicador (decisão de topologia:
  leitor = nó leve; publicador = nó pleno, app à parte — preserva P2).
- **Importação local de obras** por CBZ/CBR/ZIP via **FileKit** (picker comum a todas
  as plataformas), com estratégia **copy-in**: os bytes são copiados para storage
  próprio do app no import, tornando o app dono do conteúdo (biblioteca offline
  robusta, imune a mover/apagar a origem).
- **Descompactação** dos capítulos via **Okio `openZip`** (leitura de ZIP embutida,
  roda em Native/iOS; STORED+DEFLATE cobrem todo CBZ), com leitura **página-a-página
  sob demanda** — memória limitada.
- **Modelo de dados obra/capítulo/página** alinhado ao futuro `obra_id` e manifesto
  (ADR-0003); o campo `chave_publicador` fica **preparado mas não populado** — o
  evento de leitura foi **deferido ao Marco 2** (offline não há publicador
  atribuível; ver mudança no roadmap e ADR-0009).
- **Experiência de leitura** com dois renderers sobre uma chrome imersiva única:
  **paginado** (RTL/LTR por obra, zoom via Telephoto) e **long strip** (scroll
  vertical, sub-sampling anti-OOM). O **layout** é decidido no import por **heurística
  de aspect-ratio**, com **override manual** (nível obra e capítulo); a **direção**
  é manual, nível obra, default LTR.
- **Render** com **Coil 3** (decode/cache, KMP-nativo) + **Telephoto** (zoom +
  sub-sampling de imagens grandes).
- **Biblioteca offline** (grid de capas, favoritos, progresso de leitura) persistida
  com **Room KMP** (+ `BundledSQLiteDriver`, obrigatório para iOS/Desktop).
- **Input multiplataforma básico**: touch (tap-zones, pinch) no mobile e mouse/teclado
  no desktop mapeados aos mesmos comandos; **atalhos finos ficam para depois**.

## Capabilities

### New Capabilities

- `content-import`: importar obras locais (CBZ/CBR/ZIP) — pick multiplataforma
  (FileKit), copy-in para storage próprio, descompactação sob demanda (Okio), modelo
  obra/capítulo/página e o seam `Source` que o `NetworkSource` do Marco 2 atravessa.
- `reading-experience`: a superfície de leitura — renderers paginado e long strip,
  heurística de layout + override (obra/capítulo), direção RTL/LTR por obra, chrome
  imersiva com toggle, progresso, e input multiplataforma básico (touch + mouse/teclado).
- `offline-library`: biblioteca e navegação offline — grid de capas, tela de detalhe
  da obra com lista de capítulos, favoritos, continuar leitura e progresso persistido
  (Room), sem qualquer dependência de rede.

### Modified Capabilities

<!-- Nenhuma: as specs existentes (kmp-p2p-spi, ios-native-campaign, poc07-report)
     são artefatos de POC de rede; o Marco 1 não altera requisitos delas. -->

## Impact

- **Novos módulos/alvos KMP**: alvos `iosArm64`/`iosSimulatorArm64` no `shared`
  (Desktop JVM e Android já existem); casca de app iOS no leitor. Ativa os diretórios
  já esboçados em `shared/src/commonMain/kotlin/.../{data,domain,ui,util,di}` (hoje
  vazios) e **descarta** `shared/src/commonMain/sqldelight/` (Room escolhido no lugar
  de SQLDelight).
- **Dependências externas novas**: Coil 3, Telephoto (zoom/sub-sampling), Room KMP +
  KSP2 + `BundledSQLiteDriver`, FileKit, Okio.
- **Riscos a de-riscar antes de comprometer**:
  - **Spike Telephoto** — confirmar que o sub-sampling multiplataforma cobre
    iOS/Desktop hoje (historicamente dependia do `BitmapRegionDecoder` do Android).
    **Risco técnico nº 1.**
  - **Desktop-leitor na rede** (item aberto do Marco 2): um leitor no desktop é nó
    leve com perfil de NAT/bateria diferente do mobile; não decidido aqui.
- **Fora de escopo (Marco 2+)**: qualquer rede (DHT client, catálogo, download),
  o evento de leitura/pontuação, o app publicador, e assinaturas/verificação de
  conteúdo (não há publicador offline).
- **Referências cruzadas**: ADR-0003 (obra_id/manifesto), ADR-0005 (nó leve),
  ADR-0009 (evento de leitura deferido), roadmap Marco 1.
