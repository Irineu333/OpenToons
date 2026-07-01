# ADR-0003 — Modelo de conteúdo: manifesto assinado + `obra_id` estável + `seq`

**Status:** Aceito

## Contexto

Mangás mudam com o tempo (novos capítulos, correções). O endereçamento por conteúdo
(CID) é **imutável**: o mesmo endereço sempre aponta para os mesmos bytes. Precisamos
de um ponteiro mutável e confiável para "o estado atual de uma scan". Além disso:

- cada obra precisa de um **identificador estável** para permitir favoritar/seguir;
- o cliente **só precisa da última versão**, não do histórico;
- o conteúdo precisa ser **impossível de falsificar**.

## Decisão

**Identidade:**

- cada scan é um par de chaves; a chave pública **é** sua identidade;
- `obra_id = (chave_da_scan, UUID)` — identificador **estável**, cunhado uma vez
  pela scan e assinado; sobrevive a novos capítulos. Favoritar/seguir aponta para
  ele;
- o conteúdo é endereçado por **CID** (imutável, muda a cada alteração de bytes).

**Estado da scan = manifesto assinado do estado atual:**

```
seq: 42                    ← sequência monotônica (anti-rollback)
obras:
  - id: uuid-...
    meta: { título, capa → CID, tags }
    capítulos: [ { n: 1 → CID }, ... ]    ← apenas os VIVOS
assinatura: sig(chave_da_scan)
```

- **Anti-falsificação:** o manifesto é assinado; impostor não produz manifesto
  válido para chave alheia.
- **Anti-rollback:** o cliente memoriza o maior `seq` visto por scan e rejeita
  manifestos com `seq` menor (impede servir versão antiga assinada como se fosse a atual).

## Alternativas consideradas

### IPNS para o ponteiro mutável — descartada

Usar IPNS (nomes mutáveis do IPFS) para apontar sempre ao manifesto mais recente.

- **Por que descartada:** IPNS é notoriamente **lento** para propagar e resolver.
  Um manifesto assinado próprio dá controle total sobre formato, `seq` e
  distribuição via o plano de anúncio.

### Log append-only completo replicado pelo cliente (estilo SSB/hypercore) — descartada

O cliente reproduziria todo o histórico de eventos da scan.

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

- "Berserk da Scan A" ≠ "Berserk da Scan B": identidade é **por scan**. Agrupar a
  mesma obra de scans distintas na UI fica para depois (problema de apresentação).
- O cliente precisa **persistir o maior `seq` por scan** para a proteção de rollback.
- O manifesto é a base do plano de catálogo ([ADR-0002](./0002-three-planes.md)) e
  da semântica de exclusão ([ADR-0004](./0004-deletion-semantics.md)).
