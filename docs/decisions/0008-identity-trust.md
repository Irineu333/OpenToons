# ADR-0008 — Autenticidade de identidade (chave → publicador)

**Status:** Em aberto

## Contexto

Todo conteúdo é assinado, o que garante **integridade** (ninguém adultera) — mas
**não** garante **autenticidade de identidade**. Se um impostor cria uma chave nova
e se anuncia como "Publicador Famoso", copiando nome e capa, o cliente verifica a
assinatura com sucesso... contra a chave *errada*. O problema: como o cliente sabe
que uma dada chave pública pertence ao publicador "de verdade", **sem uma raiz de
confiança central**?

Este é reconhecidamente o próximo calcanhar de Aquiles do projeto e **ainda não tem
decisão**.

## Opções em avaliação

### TOFU (*Trust On First Use*)

Confiar na chave no primeiro contato e alertar se ela mudar depois.

- **Prós:** simples; sem infraestrutura.
- **Contras:** vulnerável exatamente no primeiro contato (quando o usuário mais
  precisa de garantia); não ajuda a distinguir o impostor do original na estreia.

### Registro assinado no bootstrap (nome → chave)

A lista distribuída mapearia nomes de publicadores conhecidos às suas chaves.

- **Prós:** resolve o caso comum dos publicadores populares.
- **Contras:** aproxima-se de uma raiz central de identidade — quem controla a lista
  controla a identidade (tensão com P1/P4). Deve ficar **separado** do bootstrap de
  rede (ver [ADR-0007](./0007-resilient-bootstrap.md)).

### Teia de confiança (*web of trust*)

Publicadores assinam as chaves uns dos outros; o cliente confia por caminhos de
assinaturas.

- **Prós:** descentralizado; robusto contra impostores isolados.
- **Contras:** mais complexo; exige massa crítica de publicadores participando; UX
  de "níveis de confiança" é delicada.

## Decisão

**Nenhuma ainda.** A ser resolvido antes/junto do marco 4 (polimento / v2 da rede),
quando a rede tiver uso real e dados sobre o comportamento de impostores. Provável
combinação (ex.: TOFU + teia de confiança, com registro assinado opcional como
conveniência), mas sem compromisso.

## Consequências (a considerar quando decidir)

- Impacta diretamente a UX de "seguir um publicador" e os alertas de segurança.
- Interage com o `obra_id = (chave, UUID)` do [ADR-0003](./0003-content-model.md): a
  confiança recai sobre a **chave**.
- Não deve reintroduzir centralização de identidade disfarçada.
