## 1. Fundação multiplataforma

- [x] 1.1 Adicionar alvos `iosArm64`/`iosSimulatorArm64` ao módulo `shared` (Android e Desktop JVM já existem)
- [x] 1.2 Criar a casca do app leitor para iOS (Compose MP) e confirmar build nos três alvos
      <!-- Casca iOS: `MainViewController` (iosMain) + Swift (`iosApp/iosApp/*.swift`, Info.plist).
           Build validado: Desktop (compileKotlin), Android (assembleDebug), iOS (compileKotlinIosSimulatorArm64).
           Projeto Xcode a criar/rodar pelo usuário — ver `iosApp/README.md` (task 8.2). -->
- [x] 1.3 Ativar os pacotes `data/domain/ui/util/di` em `commonMain`; remover `sqldelight/` esboçado
      <!-- `sqldelight/` não existia (nada a remover); template Greeting/Platform substituído pela app real. -->
- [x] 1.4 Configurar Material 3 e tema claro/escuro no Compose MP

## 2. De-risco (spikes antes de comprometer)

- [x] 2.1 Spike Telephoto: validar sub-sampling em iOS e Desktop (risco nº 1); definir fallback se não cobrir
      <!-- RESULTADO: Telephoto 0.19.0 arrasta `androidx.compose.{foundation,ui}` sem klibs iOS e o CMP 1.11.1
           usa outro baseline — resolução iOS bloqueada (skew androidx↔jetbrains). Adotado o FALLBACK do
           design D7: zoom manual (graphicsLayer+gestos, paginado) + downscale do Coil ao viewport (long strip),
           sem tiling. Telephoto = follow-up quando as versões alinharem. -->
- [x] 2.2 Spike Room KMP: `BundledSQLiteDriver` + KSP2 abrindo/gravando nos três alvos
      <!-- KSP2 (2.3.9) gera o `@ConstructedBy` e o `BundledSQLiteDriver` compila nos 3 alvos (JVM/Android/iOS).
           Abrir/gravar em runtime: E2E manual (task 8.2). -->
- [x] 2.3 Spike Okio `openZip` lendo um `.cbz` real em iOS/Native (list + read sob demanda)
      <!-- Mecanismo (commonMain) validado no host por teste real: `CbzArchiveJvmTest` cria um `.cbz` e faz
           list (ordem natural) + read sob demanda. Mesmo código roda em Native; on-device via 8.2. -->

## 3. Camada Source (content-import)

- [x] 3.1 Definir a interface `Source` (contrato de obra/capítulo/página) extensível para `NetworkSource`
- [x] 3.2 Integrar FileKit: pick filtrado por `cbz/zip` nas três plataformas
      <!-- CBR (RAR) removido do filtro: openZip (D5) só lê ZIP; suportar RAR = follow-up. -->

- [x] 3.3 Implementar copy-in: copiar bytes para `FileKit.filesDir`; app dono do `.cbz` intacto
- [x] 3.4 Descompactação com Okio `openZip`: leitura sob demanda por página
- [x] 3.5 Ordenação natural dos nomes de entrada para a ordem das páginas
- [x] 3.6 Extrair metadados básicos (título, capa) e montar a obra na importação

## 4. Modelo de dados e persistência (Room)

- [x] 4.1 Modelar entidades obra/capítulo/página alinhadas ao `obra_id=(chave_publicador?, uuid)` (ADR-0003)
- [x] 4.2 Campos de layout: `detectedLayout` (capítulo), `layoutOverride` (obra e capítulo), `direction` (obra)
- [x] 4.3 Campos de biblioteca: favorito, progresso por capítulo, marca de lido
- [x] 4.4 DAOs e queries de biblioteca/detalhe/progresso; garantir `chave_publicador` não populada

## 5. Heurística de layout e direção

- [x] 5.1 Calcular `mediana(altura/largura)` no import e derivar `detectedLayout` (threshold ~2.0 + pico >~3)
- [x] 5.2 Resolver layout efetivo: `capítulo.override ?? obra.override ?? detected`
- [x] 5.3 UI de override de layout (obra e capítulo) e de direção (obra); limpar override restaura detecção

## 6. Renderers de leitura (reading-experience)

- [x] 6.1 Renderer paginado: `ZoomableImage` (Telephoto), avanço discreto, direção RTL/LTR, zoom/double-tap
      <!-- Zoom manual (graphicsLayer+detectTransformGestures) no lugar do Telephoto — ver 2.1. -->
- [x] 6.2 Renderer long strip: lista lazy vertical com sub-sampling, sem zoom livre
      <!-- Sub-sampling = downscale do Coil ao viewport (fillWidth), fallback sem tiling — ver 2.1. -->
- [x] 6.3 Chrome imersiva única: tap central alterna overlay (topo + barra de progresso/navegação)
- [x] 6.4 Progresso por modo: página (paginado) e fração de rolagem (long strip); persistir e retomar
- [x] 6.5 Carregamento sob demanda página→Coil 3 a partir do `openZip`; memória limitada
- [x] 6.6 Camada fina de input: mapear touch e mouse/teclado básico aos mesmos comandos

## 7. Telas de biblioteca (offline-library)

- [x] 7.1 Tela Biblioteca em grid de capas (home)
- [x] 7.2 Favoritar/desfavoritar com persistência
- [x] 7.3 Tela Detalhe da obra: capa, metadados, lista de capítulos, continuar leitura
- [x] 7.4 Navegação Biblioteca → Detalhe → Leitor

## 8. Verificação

- [x] 8.1 Cobrir cenários das specs (import copy-in, ordenação, override/precedência, direção, progresso, offline)
      <!-- Testes automatizados (commonTest/jvmTest, 9 casos): ordenação natural, heurística de layout,
           precedência/limpar override, e roundtrip real de `openZip` (list+read). Import copy-in, direção,
           progresso e offline ponta-a-ponta dependem de FileKit/Room/arquivos → E2E manual (8.2). -->
- [x] 8.2 E2E manual nos três alvos: importar CBZ paginado (RTL) e webtoon long strip, ler e retomar
      <!-- Desktop: OK (import, grid, leitura, atalhos, barras). Android: OK (import, leitura, barras;
           vários ajustes na seção 9). iOS: OK (testado pelo usuário) — app sobe, importa, lê e retoma;
           casca via xcodegen (ver 9.15). Fluxo completo dos três alvos verificado. -->
- [x] 8.3 Confirmar operação 100% offline (sem qualquer chamada de rede)
      <!-- Por construção não há dependência de rede (Coil core sem coil-network; sem ktor/http) e
           confirmado em runtime pelo usuário. -->
- [x] 8.4 Validar/calibrar o threshold da heurística com corpus real de mangá e webtoon
      <!-- Validado pelo usuário com corpus real: detecção funcionou (paginado vs long strip corretos).
           Threshold `LayoutHeuristic.STRIP_THRESHOLD`/`STRIP_PEAK` mantido; override manual cobre a zona cinza. -->

## 9. Ajustes pós-E2E (não previstos no plano original)

Correções surgidas do E2E manual (task 8.2) em Desktop e Android — registradas para
rastreabilidade; não estavam nas specs/tasks originais.

- [x] 9.1 Tema consistente entre telas: `MaterialTheme` não pinta fundo, então a biblioteca
      aparecia clara enquanto detalhe/leitor ficavam escuros. `AppTheme` passa a envolver o
      conteúdo num `Surface` de `background`. (E2E desktop)
- [x] 9.2 Contraste na tela de detalhe: cabeçalho (capa + metadados) em painel elevado
      `surfaceContainerHigh` e capa em `Surface` com sombra. (E2E desktop)
- [x] 9.3 Animação da chrome do leitor: slide vertical (topo/base) + fade no lugar do fade
      padrão do `AnimatedVisibility`. (E2E desktop)
- [x] 9.4 OOM no import (Android): copy-in em streaming via `PlatformFile.copyTo` no lugar de
      `readBytes()+write()`, que carregava o `.cbz` inteiro em memória e estourava o heap.
      (E2E Android — `OutOfMemoryError` em `kotlinx.io`)
- [x] 9.5 Botão "Importar" da top bar → `ExtendedFloatingActionButton` (Scaffold), padrão do
      gênero. (E2E Android)
- [x] 9.6 Cache do `FileSystem` aberto por arquivo no `CbzArchive`: NÃO NECESSÁRIO. O E2E do
      usuário no long strip não acusou travadas (a reabertura do `.cbz` a cada página lida não
      pesou na rolagem). Otimização dispensada neste marco; reabrir se um corpus maior regredir.
- [x] 9.7 Swipe não passava página (paginado): o `detectTransformGestures` (pan/zoom) consumia
      o arrasto horizontal em escala 1 e bloqueava o pager. Passa a capturar pan/zoom só quando
      já ampliado (double-tap p/ ampliar). (E2E Android; 2ª rodada)
- [x] 9.8 Padding horizontal errado (detalhe/leitor): `safeContentPadding()` inclui as zonas de
      gesto de navegação (insets laterais). Trocado por `safeDrawingPadding()` (barras do leitor)
      e `Scaffold` no detalhe. (E2E Android; 2ª rodada)
- [x] 9.9 "Continuar leitura" → `ExtendedFloatingActionButton` na tela de detalhe. (E2E Android)
- [x] 9.10 Favoritar → botão de ícone simplificado (só a estrela, com cor primária quando ativo)
      no lugar do TextButton com rótulo. (E2E Android)
- [x] 9.11 Excluir obra: faltava a ação. `deleteWork` passa a apagar também os `.cbz` próprios
      do disco (copy-in) e o progresso, além das linhas; ação "Excluir" na tela de detalhe com
      diálogo de confirmação, voltando à biblioteca. (E2E — gestão de espaço, D-risco copy-in)
- [x] 9.12 Badge do layout detectado movido para dentro do chip (slot `trailingIcon`), no lugar
      da legenda/`BadgedBox` externo. (E2E Android)
- [x] 9.13 Favoritar e excluir como IconButtons consistentes; ícones (coração/lixeira) definidos
      como `ImageVector` a partir dos path data do Material (`AppIcons`), sem depender do
      `material-icons-core` (descontinuado). (E2E Android)
- [x] 9.14 Import de CBZ com pastas de capítulos: antes o arquivo virava um único capítulo.
      Agora cada diretório com imagens vira um capítulo (`CbzArchive.chapters` agrupa por
      pasta-pai; `entryDir` no capítulo; layout detectado por capítulo). CBZ plano continua um
      capítulo. Schema DB v2 (recriação destrutiva). (E2E — CBZ real da internet)
- [x] 9.15 App iOS sobe no simulador: projeto Xcode gerado por xcodegen (`iosApp/project.yml`),
      framework `Shared` via `embedAndSignAppleFrameworkForXcode`; biblioteca/tema/Room/navegação
      renderizam no iPhone 16 (iOS 18.5). Achados de toolchain: Compose MP 1.11.1 referencia
      `UIViewLayoutRegion` (ausente no SDK 18.5 do Xcode 16.4) → workaround `-Wl,-U,...` +
      deployment target 18.0 + `CADisableMinimumFrameDurationOnPhone`. Ver `iosApp/README.md`.
      Fix definitivo = Xcode mais novo (aí remove-se o workaround).
- [x] 9.16 Rolagem vertical bugada no long strip (desktop): as imagens só tinham `fillMaxWidth`,
      sem altura reservada. Até o Coil carregar, o item ficava com altura ~0, a `LazyColumn`
      compunha/disparava dezenas de loads de uma vez e a posição de rolagem "pulava" quando as
      alturas surgiam (visível no desktop — roda de mouse precisa, sem fling). Agora cada item
      reserva ~1 tela (`fillParentMaxHeight`) até carregar e fixa o `aspectRatio` real capturado
      no `onSuccess` do `AsyncImage`. (E2E desktop)
