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
- [x] 3.2 Integrar FileKit: pick filtrado por `cbz/cbr/zip` nas três plataformas
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
- [~] 8.2 E2E manual nos três alvos: importar CBZ paginado (RTL) e webtoon long strip, ler e retomar
      <!-- Desktop: OK (import, grid, leitura, atalhos, barras). Android: OK (import, leitura, barras;
           vários ajustes na seção 9). iOS: app SOBE no simulador (biblioteca/tema/Room/nav verificados via
           screenshot) — ver 9.15; fluxo de import por picker no simulador não dirigido aqui. -->
- [ ] 8.2 E2E manual nos três alvos: importar CBZ paginado (RTL) e webtoon long strip, ler e retomar
      <!-- REQUER USUÁRIO: rodar os apps. Desktop `./gradlew :desktopApp:run`; Android `:androidApp:installDebug`;
           iOS ver `iosApp/README.md` (xcodegen + xcodebuild + simctl). -->
- [ ] 8.3 Confirmar operação 100% offline (sem qualquer chamada de rede)
      <!-- Por construção não há dependência de rede (Coil core sem coil-network; sem ktor/http). Confirmação
           em runtime (modo avião) = REQUER USUÁRIO. -->
- [ ] 8.4 Validar/calibrar o threshold da heurística com corpus real de mangá e webtoon
      <!-- REQUER USUÁRIO: corpus real. Threshold exposto em `LayoutHeuristic.STRIP_THRESHOLD`/`STRIP_PEAK`. -->
