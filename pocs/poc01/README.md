# poc01/ — código DESCARTÁVEL do Marco 0

Este módulo contém os experimentos da Prova de Conceito
([openspec/changes/archive/2026-07-03-poc-01](../../openspec/changes/archive/2026-07-03-poc-01/proposal.md)).

**O entregável do Marco 0 é o relatório — [docs/poc01-report.md](../../docs/pocs/poc01-report.md) —
não este código.** Nada aqui é base para os módulos de produto (`shared`, `desktopApp`);
este diretório pode (e deve) ser apagado após o relatório concluído, junto com as
entradas `:pocs:poc01:*` em `settings.gradle.kts` e o repositório JitPack/Consensys usados
só por ele.

## Submódulos

- `poc01/node` — nó pleno JVM (nabu): experimentos E1/E4 e o E3 (manifesto assinado,
  `Manifest.kt` + testes).
- `poc01/android` — app mínimo Android: spike E2 (nabu no Android, DHT client puro,
  `ClientModeKademlia.kt`).

## Rodando

```bash
# E1 — nó pleno (porta swarm, duração em segundos)
./gradlew :pocs:poc01:node:run --args="4001 60"

# E3 — testes do manifesto assinado
./gradlew :pocs:poc01:node:test

# E2 — app Android (instalar em device/emulador e observar `adb logcat -s OpenToonsPoC`)
./gradlew :pocs:poc01:android:assembleDebug
```
