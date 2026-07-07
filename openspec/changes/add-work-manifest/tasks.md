## 1. Manifesto de obra (`work.json`)

- [ ] 1.1 Definir `WorkManifest` (`version`, `obraId`, `chavePublicador:null`, `title`,
      `direction`, `cover: {chapterId, entryName}`) em `commonMain` (kotlinx-serialization) — D2
- [ ] 1.2 Writer/reader do `work.json` (Okio + Json), análogo a `OpzReader`/`OpzWriter`
- [ ] 1.3 `ContentImporter.importWork`: escrever `obras/{obraId}/work.json` **antes** do
      upsert no banco — D6

## 2. `chapterId` interno e `direction` na obra

- [ ] 2.1 Adicionar `chapterId` ao `OpzManifest`; **remover** `direction` do `OpzManifest` — D3/D4
- [ ] 2.2 `OpzWriter.write`: receber/gravar o `chapterId`; parar de gravar `direction` no
      manifesto do capítulo
- [ ] 2.3 Nome do `.opz` passa a ser o **título** do capítulo (sanitizado); ordem = natural
      sort dos nomes — D3
- [ ] 2.4 Progresso casado por `chapterId` interno (não pelo nome/arquivo) — D3

## 3. Capa de obra (`cover.webp`)

- [ ] 3.1 `expect/actual` `CoverEncoder` — gerar thumbnail a partir dos bytes de uma página
      (Android/iOS/JVM); fallback PNG/JPEG se WebP não fechar no alvo — D5
- [ ] 3.2 Gerar `obras/{obraId}/cover.webp` no import da obra — D5
- [ ] 3.3 Regenerar `cover.webp` + atualizar `work.json` a cada `addChapters` — D5
- [ ] 3.4 Garantir que nenhuma página é transcodificada (páginas seguem STORED cru) — D5

## 4. Split estado × dado e grade

- [ ] 4.1 `WorkEntity`: adicionar `directionOverride`; confirmar `layoutOverride` como estado — D6
- [ ] 4.2 Grade (`LibraryScreen`) carrega a capa de `cover.webp`, não de página em `.opz` — D5
- [ ] 4.3 Recriação **destrutiva** do schema (sem migração; precedente Marco 1)

## 5. Reconstrução a partir do disco

- [ ] 5.1 Rescan de `obras/*/work.json` → reconstruir `WorkEntity`/`ChapterEntity` — D6
- [ ] 5.2 Preservar estado pessoal (favorito/progresso/lido) casando por `obraId`/`chapterId` — D6
- [ ] 5.3 Regra "disco vence" em divergência — D6

## 6. Testes e validação

- [ ] 6.1 Roundtrip `work.json` (write→read) e `OpzManifest` com `chapterId` sem `direction`
- [ ] 6.2 Teste de reconstrução: apagar índice, rescan, estado preservado por id
- [ ] 6.3 Teste: `addChapters` regenera capa e mantém `work.json` coerente
- [ ] 6.4 E2E nos três alvos: grade lê `cover.webp`; leitura em regime intocada
- [ ] 6.5 `openspec validate add-work-manifest --strict`
