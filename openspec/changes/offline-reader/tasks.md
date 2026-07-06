## 1. Fundação multiplataforma

- [ ] 1.1 Adicionar alvos `iosArm64`/`iosSimulatorArm64` ao módulo `shared` (Android e Desktop JVM já existem)
- [ ] 1.2 Criar a casca do app leitor para iOS (Compose MP) e confirmar build nos três alvos
- [ ] 1.3 Ativar os pacotes `data/domain/ui/util/di` em `commonMain`; remover `sqldelight/` esboçado
- [ ] 1.4 Configurar Material 3 e tema claro/escuro no Compose MP

## 2. De-risco (spikes antes de comprometer)

- [ ] 2.1 Spike Telephoto: validar sub-sampling em iOS e Desktop (risco nº 1); definir fallback se não cobrir
- [ ] 2.2 Spike Room KMP: `BundledSQLiteDriver` + KSP2 abrindo/gravando nos três alvos
- [ ] 2.3 Spike Okio `openZip` lendo um `.cbz` real em iOS/Native (list + read sob demanda)

## 3. Camada Source (content-import)

- [ ] 3.1 Definir a interface `Source` (contrato de obra/capítulo/página) extensível para `NetworkSource`
- [ ] 3.2 Integrar FileKit: pick filtrado por `cbz/cbr/zip` nas três plataformas
- [ ] 3.3 Implementar copy-in: copiar bytes para `FileKit.filesDir`; app dono do `.cbz` intacto
- [ ] 3.4 Descompactação com Okio `openZip`: leitura sob demanda por página
- [ ] 3.5 Ordenação natural dos nomes de entrada para a ordem das páginas
- [ ] 3.6 Extrair metadados básicos (título, capa) e montar a obra na importação

## 4. Modelo de dados e persistência (Room)

- [ ] 4.1 Modelar entidades obra/capítulo/página alinhadas ao `obra_id=(chave_publicador?, uuid)` (ADR-0003)
- [ ] 4.2 Campos de layout: `detectedLayout` (capítulo), `layoutOverride` (obra e capítulo), `direction` (obra)
- [ ] 4.3 Campos de biblioteca: favorito, progresso por capítulo, marca de lido
- [ ] 4.4 DAOs e queries de biblioteca/detalhe/progresso; garantir `chave_publicador` não populada

## 5. Heurística de layout e direção

- [ ] 5.1 Calcular `mediana(altura/largura)` no import e derivar `detectedLayout` (threshold ~2.0 + pico >~3)
- [ ] 5.2 Resolver layout efetivo: `capítulo.override ?? obra.override ?? detected`
- [ ] 5.3 UI de override de layout (obra e capítulo) e de direção (obra); limpar override restaura detecção

## 6. Renderers de leitura (reading-experience)

- [ ] 6.1 Renderer paginado: `ZoomableImage` (Telephoto), avanço discreto, direção RTL/LTR, zoom/double-tap
- [ ] 6.2 Renderer long strip: lista lazy vertical com sub-sampling, sem zoom livre
- [ ] 6.3 Chrome imersiva única: tap central alterna overlay (topo + barra de progresso/navegação)
- [ ] 6.4 Progresso por modo: página (paginado) e fração de rolagem (long strip); persistir e retomar
- [ ] 6.5 Carregamento sob demanda página→Coil 3 a partir do `openZip`; memória limitada
- [ ] 6.6 Camada fina de input: mapear touch e mouse/teclado básico aos mesmos comandos

## 7. Telas de biblioteca (offline-library)

- [ ] 7.1 Tela Biblioteca em grid de capas (home)
- [ ] 7.2 Favoritar/desfavoritar com persistência
- [ ] 7.3 Tela Detalhe da obra: capa, metadados, lista de capítulos, continuar leitura
- [ ] 7.4 Navegação Biblioteca → Detalhe → Leitor

## 8. Verificação

- [ ] 8.1 Cobrir cenários das specs (import copy-in, ordenação, override/precedência, direção, progresso, offline)
- [ ] 8.2 E2E manual nos três alvos: importar CBZ paginado (RTL) e webtoon long strip, ler e retomar
- [ ] 8.3 Confirmar operação 100% offline (sem qualquer chamada de rede)
- [ ] 8.4 Validar/calibrar o threshold da heurística com corpus real de mangá e webtoon
