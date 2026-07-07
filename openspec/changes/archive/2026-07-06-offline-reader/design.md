## Context

O Marco 1 constrói o **leitor** do OpenToons antes da rede existir. A arquitetura já
está consolidada em `docs/` (ADRs 0001–0009): leitor = **nó leve** que só consome; a
rede virá no Marco 2 como um novo `Source`. A restrição central desta mudança é
**preparar a costura** para que o Marco 2 encaixe sem reescrita — e entregar, já
agora, uma experiência de leitura de primeira linha, **multiplataforma** (Android,
Desktop JVM, iOS) e **100% offline**.

Três formatos (mangá, manhwa, webtoon) reduzem-se a **duas físicas de leitura**:
paginado (páginas discretas, direção RTL/LTR) e long strip (rolagem vertical
contínua). Acertar as duas é o que define "primeira linha".

## Goals / Non-Goals

**Goals:**
- Leitor multiplataforma coeso (Android · Desktop · iOS), separado do app publicador.
- Import local robusto (copy-in) e biblioteca offline com favoritos e progresso.
- Dois renderers sob uma chrome imersiva única; layout por heurística + override.
- Modelo de dados e seam `Source` que o Marco 2 atravessa sem alterar leitura/biblioteca.

**Non-Goals:**
- Qualquer rede (DHT, catálogo, download) — Marco 2.
- Evento de leitura/pontuação — deferido ao Marco 2 (sem publicador atribuível offline).
- App publicador, assinatura/verificação de conteúdo.
- Paridade fina de atalhos de teclado no desktop (básico agora, refino depois).

## Decisions

### D1 — App leitor multiplataforma, separado do publicador (Topologia A)
O leitor é um app KMP/Compose para Android/Desktop/iOS; o publicador segue app
desktop à parte (Marco 2). **Por quê:** preserva P2 (leitor = nó leve; publicador =
nó pleno) sem misturar papéis num binário. **Alternativa descartada:** um app desktop
que lê *e* publica — mistura nó leve com nó pleno, fere a limpeza da arquitetura.

### D2 — Fonte: padrão da comunidade sob abstração `Source`
CBZ/ZIP (o universo Tachiyomi/Mihon), atrás de uma interface `Source`. **Por quê:**
familiar ao usuário e extensível — a rede do Marco 2 vira `NetworkSource`, mais uma
implementação. **CBR (RAR) fica fora de escopo neste marco:** a descompactação é Okio
`openZip` (D5), que só lê ZIP; adicionar RAR exigiria outra lib — follow-up. **Alternativa:**
nascer já no formato content-addressed do poc-07 (manifest+blocks) — adiado; começar
com o padrão da comunidade entrega valor antes.

### D3 — Pick + acesso a arquivo: FileKit
`FileKit.openFilePicker(type = File(["cbz","zip"]))` unifica o seletor nas
plataformas; `bookmarkData()` cobre security-scoped bookmark (iOS) e persistent URI
permission (Android). **Por quê:** elimina o `expect/actual` de acesso a arquivo.

### D4 — Copy-in como estratégia de import
No import, os bytes são copiados para `FileKit.filesDir` (o app vira dono). **Por quê:**
(a) biblioteca offline robusta, imune a mover/apagar a origem; (b) **pré-requisito
técnico** do D5 — `openZip` exige um `Path` seekável, e a URI do picker (content:// /
bookmark) não é seekável, sobretudo no iOS; (c) faz `LocalImportSource` e o futuro
`NetworkSource` convergirem (ambos aterram bytes próprios). Sub-estratégia: **guardar
o `.cbz` intacto** e ler sob demanda (menos disco, import instantâneo, capítulo como
unidade endereçável ≈ bloco/CID). **Alternativa:** referência por bookmark sem copiar
— frágil (origem some) e incompatível com `openZip`; pode virar um 2º modo "linkar
pasta" no futuro, não o padrão.

### D5 — Descompactação: Okio `openZip`
`FileSystem.SYSTEM.openZip(path)` lê o diretório central, indexa offsets e lê cada
página sob demanda; roda em Native/iOS; cobre STORED+DEFLATE (todo CBZ). **Por quê:**
zero lib extra (Okio já é a base de IO, inclusive do Coil 3), leitura lazy →
memória limitada. Ordenação natural dos nomes de entrada define a ordem das páginas.

### D6 — Layout por heurística de aspect-ratio + override 2-eixos
No import: `sinal = mediana(altura/largura)`; `sinal ≥ ~2.0 → long strip`, senão
paginado (threshold a validar com material real; pico > ~3 reforça strip). **Layout**
tem override manual em obra e capítulo; efetivo = `capítulo.override ?? obra.override
?? detected`, com `detected` guardado **separado** do override (limpar override
restaura a detecção). **Direção** (RTL/LTR) é eixo **ortogonal**, sem sinal automático:
manual, nível **obra**, default LTR, aplicável só ao paginado. **Por quê:** aspect-ratio
decide *layout* mas é cego à *direção*; tratá-los como um eixo só seria errado.

### D7 — Render: Coil 3 + Telephoto
Coil 3 (decode/cache, KMP-nativo, base okio) carrega; Telephoto faz zoom e
**sub-sampling** de imagens grandes (tiling anti-OOM). Paginado usa `ZoomableImage`;
long strip usa lista lazy com sub-sampling e sem zoom livre. **Por quê:** solução
recomendada para Compose; Coil+Okio compõem direto com o D5.

### D8 — Persistência: Room KMP
Room em `commonMain` (obras, capítulos, favoritos, progresso), com
`BundledSQLiteDriver` (obrigatório para iOS/Desktop) e KSP2. Descarta o
`shared/src/commonMain/sqldelight/` esboçado. **Por quê:** preferência declarada por
Room; suporte KMP maduro. **Alternativa:** SQLDelight — plano B.

### D9 — UI: simples, padrão do gênero (Material 3)
Três telas (Biblioteca em grid → Detalhe da obra → Leitor imersivo) sobre Material 3
(default do Compose MP). Leitor: chrome única imersiva, tap central alterna overlay
(topo: voltar/título/config; base: seek + capítulo anterior/próximo). Diferenças
paginado vs long strip: tap-zones de página, forma do progresso e zoom. Input básico
mapeia touch e mouse/teclado aos mesmos comandos.

## Risks / Trade-offs

- **Sub-sampling do Telephoto em iOS/Desktop** → **Risco técnico nº 1.** A doc atual
  confirma integração `coil3` KMP, mas não a matriz de plataformas do tiling
  (historicamente dependia do `BitmapRegionDecoder` do Android). **Mitigação:** spike
  de de-risco antes de comprometer; fallback = renderizar sem tiling em Desktop/iOS
  com downscale agressivo, ou lib de sub-sampling alternativa.
- **Threshold da heurística de layout** (zona cinza: webtoon fatiado ~1.6) → **Mitigação:**
  reforço por pico (>~3) e por baixa confiança escolher default com toggle óbvio;
  calibrar com material real.
- **Copy-in duplica storage** → **Mitigação:** guardar o `.cbz` intacto (não extrair);
  oferecer gestão de espaço/remoção na biblioteca.
- **Room KMP + KSP2 + BundledSQLiteDriver** setup não-trivial no iOS/Native →
  **Mitigação:** montar o driver bundled desde o dia 1, não como afterthought.

## Migration Plan

Projeto greenfield do lado do leitor (hoje só o esqueleto KMP `Greeting`). Sem
migração de dados. Ativa os diretórios `data/domain/ui/util/di` já esboçados; adiciona
alvos `iosArm64`/`iosSimulatorArm64` ao `shared`. Rollback = não há release anterior.

## Open Questions

- **Desktop-leitor na rede (Marco 2):** um leitor no desktop é nó leve com perfil de
  NAT/bateria diferente do mobile — continua DHT client puro ou pode se comportar
  melhor na malha? Não decidido aqui; item de design do Marco 2.
- **Threshold exato** da heurística (`~2.0`) a fixar após teste com corpus real de
  mangá e webtoon.
- **Formato de import futuro:** quando o content-addressed (poc-07) entrar, o
  `LocalImportSource` materializa CBZ → manifesto interno, ou coexistem dois modelos?
