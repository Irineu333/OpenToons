## ADDED Requirements

### Requirement: Selecionar e remover capítulos

O sistema SHALL permitir selecionar capítulos na tela de detalhe da obra por gesto de
**pressionar-e-segurar** (entrando em modo de seleção múltipla) e removê-los. A remoção
SHALL apagar os arquivos `.opz` correspondentes e o progresso associado, e SHALL ser
confirmada pelo usuário por ser irreversível.

#### Scenario: Entrar em modo de seleção
- **WHEN** o usuário pressiona e segura um capítulo na lista
- **THEN** o sistema SHALL entrar em modo de seleção, permitindo marcar vários capítulos

#### Scenario: Remover capítulos selecionados
- **WHEN** o usuário confirma a remoção dos capítulos selecionados
- **THEN** o sistema SHALL apagar seus `.opz` do storage e o progresso, e atualizar a lista

#### Scenario: Remover todos os capítulos
- **WHEN** o usuário remove o último capítulo restante de uma obra
- **THEN** o sistema SHALL tratar a obra como vazia de forma consistente (remover a obra ou
  mantê-la sem capítulos, conforme confirmação do usuário)

## MODIFIED Requirements

### Requirement: Remover obra da biblioteca

O sistema SHALL permitir remover uma obra da biblioteca. A remoção SHALL apagar também a
pasta própria da obra (`obras/{obra}/` com todos os `.opz` dos capítulos) e o progresso
associado, liberando espaço, e SHALL ser confirmada pelo usuário por ser irreversível.

#### Scenario: Remover obra
- **WHEN** o usuário confirma a remoção de uma obra
- **THEN** o sistema SHALL removê-la da biblioteca e apagar seus arquivos próprios e o progresso

#### Scenario: Espaço liberado
- **WHEN** uma obra é removida
- **THEN** todos os `.opz` da pasta da obra SHALL ser apagados do storage do app
