# ADR-0007 — Bootstrap resiliente multi-canal

**Status:** Aceito

## Contexto

Mesmo com todo o resto descentralizado, o **cold start** depende de conhecer ao
menos um nó vivo. Se *todos* os nós de bootstrap forem bloqueados, um nó novo (pleno
ou mobile) não consegue entrar na rede. A lista de bootstrap é, portanto, um
potencial ponto único de censura se for distribuída por um só canal.

## Decisão

O bootstrap é servido por **multiplicidade de canais e mecanismos**, de modo que
nenhum canal isolado seja ponto único de falha:

```
- lista de nós ASSINADA, distribuída por N canais (git, IPNS, pastebin, telegram, ...)
- DNS seed (domínio → IPs de bootstrap), fácil de rotacionar
- mDNS na LAN (descobre nós plenos na rede local, zero internet)
- peers "fallback" hardcoded no app, atualizados a cada release
- último cache de peers do próprio usuário (se já entrou antes, tem sementes)
```

A lista distribuída é **assinada** para que, mesmo obtida por um canal não confiável,
sua integridade seja verificável.

## Alternativas consideradas

### Lista de bootstrap por um único canal (ex.: só um domínio/servidor) — descartada

- **Por que descartada:** ponto único de censura. Bloquear o canal derruba o cold
  start de toda a rede — exatamente o que o projeto combate (P1).

### Bootstrap como também raiz de confiança de identidade — descartada (separado)

Usar a mesma lista de bootstrap para mapear nome → chave das scans.

- **Por que descartada aqui:** mistura dois papéis (entrada na rede × autenticidade
  de identidade) e concentra poder — quem controla a lista controlaria a identidade.
  Bootstrap fica restrito a **entrada na rede**; a autenticidade de identidade é
  tratada à parte (ver [ADR-0008](./0008-identity-trust.md)).

## Consequências

- Ferramentas/processo para publicar e assinar a lista em múltiplos canais.
- O app precisa tentar canais em cascata e persistir o cache de peers.
- mDNS habilita cenários offline/LAN interessantes (ex.: compartilhamento local).
