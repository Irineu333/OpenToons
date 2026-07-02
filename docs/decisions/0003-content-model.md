# ADR-0003 — Modelo de conteúdo: manifesto assinado + `obra_id` estável + `seq`

**Status:** Aceito

## Contexto

Obras mudam com o tempo (novos capítulos, correções). O endereçamento por conteúdo
(CID) é **imutável**: o mesmo endereço sempre aponta para os mesmos bytes. Precisamos
de um ponteiro mutável e confiável para "o estado atual de um publicador". Além
disso:

- cada obra precisa de um **identificador estável** para permitir favoritar/seguir;
- o cliente **só precisa da última versão**, não do histórico;
- o conteúdo precisa ser **impossível de falsificar**.

## Decisão

**Identidade:**

- cada publicador é um par de chaves; a chave pública **é** sua identidade;
- `obra_id = (chave_do_publicador, UUID)` — identificador **estável**, cunhado uma
  vez pelo publicador e assinado; sobrevive a novos capítulos. Favoritar/seguir
  aponta para ele;
- o conteúdo é endereçado por **CID** (imutável, muda a cada alteração de bytes).

**Estado do publicador = manifesto assinado do estado atual:**

```
seq: 42                    ← sequência monotônica (anti-rollback)
obras:
  - id: uuid-...
    meta: { título, capa → CID, tags }
    capítulos: [ { n: 1 → CID }, ... ]    ← apenas os VIVOS
assinatura: sig(chave_do_publicador)
```

- **Anti-falsificação:** o manifesto é assinado; impostor não produz manifesto
  válido para chave alheia.
- **Anti-rollback:** o cliente memoriza o maior `seq` visto por publicador e rejeita
  manifestos com `seq` menor (impede servir versão antiga assinada como se fosse a atual).

## Alternativas consideradas

### IPNS para o ponteiro mutável — descartada

Usar IPNS (nomes mutáveis do IPFS) para apontar sempre ao manifesto mais recente.

- **Por que descartada:** IPNS é notoriamente **lento** para propagar e resolver.
  Um manifesto assinado próprio dá controle total sobre formato, `seq` e
  distribuição via o plano de anúncio.

### Log append-only completo replicado pelo cliente (estilo SSB/hypercore) — descartada

O cliente reproduziria todo o histórico de eventos do publicador.

- **Por que descartada:** o cliente **só precisa da última versão**. Forçá-lo a
  reproduzir histórico é custo desnecessário de banda/armazenamento no mobile.
  Mantemos o manifesto como *estado atual materializado*. (Nós plenos/replicadores
  podem manter histórico se quiserem, mas não é requisito do cliente.)

### `obra_id` derivado do CID — descartada

Usar o próprio CID como identidade da obra.

- **Por que descartada:** o CID **muda a cada capítulo** (imutável por definição).
  Favoritar/seguir precisa de um identificador **estável**, independente do
  conteúdo — daí o UUID assinado.

## Consequências

- A mesma obra publicada pelo Publicador A e pelo Publicador B são entidades
  **diferentes**: identidade é **por publicador**. Agrupar a mesma obra de
  publicadores distintos na UI fica para depois (problema de apresentação).
- O cliente precisa **persistir o maior `seq` por publicador** para a proteção de
  rollback.
- O manifesto é a base do plano de catálogo ([ADR-0002](./0002-three-planes.md)) e
  da semântica de exclusão ([ADR-0004](./0004-deletion-semantics.md)).
