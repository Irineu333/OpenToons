## Context

O poc-05 provou o **modo anônimo opcional** (publicador sobre Tor, leitores em
clearnet) e concluiu um "gatilho invertido": anonimato favorece o backend Trama
sobre o rust-libp2p. Este poc testa uma hipótese **mais radical e ainda não
medida**: basear a **rede inteira** em I2P dissolveria NAT e anonimato de uma vez.

Três recursos reais estão disponíveis e definem a topologia: o **shell/JVM** desta
máquina (DEV), uma **VPS**, e um **Android em rede separada** (dados móveis).

```
   RECURSO           PAPEL       roda
   ────────────────────────────────────────────────────────────────
   shell/JVM (DEV)   P           router I2P + nó pleno Trama · publicador
   VPS               R + B       router I2P + nó pleno · replicador + bootstrap
   Android (sep.)    M           router I2P + app · leitor (planos A e B)

   caminho de transferência = 3 redes REALMENTE separadas (sem co-localização):
        P(DEV) ══I2P══ R(VPS) ══I2P══ M(Android, dados móveis)
```

Princípio inegociável (direção do usuário): **não suponha nada, teste tudo, em
rede real com código real.** Extrapolar números do Tor (poc-05) não conta como
dado de I2P.

## Goals / Non-Goals

**Goals:**

- Medir a viabilidade técnica de uma rede **nativamente anônima sobre I2P**, com
  foco no crux não-medido: o **cold-start do caminho de leitura do mobile**.
- Comparar os dois papéis do mobile: **plano A** (consumidor puro, client-router)
  e **plano B** (nó pleno que serve) — este último destravado pelo I2P, que dá
  alcançabilidade de entrada de graça.
- Determinar, por camada, o que o I2P **subsume** (NAT), o que **muda de forma**
  (bootstrap), o que **sobrevive** (Bitswap/CID/manifesto assinado) e o que fica
  **mais caro** (DHT sobre túnel).
- Produzir um relatório com conclusão em quatro partes rastreáveis a testes.

**Non-Goals:**

- **Não** altera arquitetura de produção nem reescreve ADRs — só recomenda.
- **Não** compara Tor × I2P para o modo anônimo do poc-05 (é outra pergunta, menor).
- **Não** valida correlação por adversário global-passivo / timing — limite do
  próprio overlay, declarado e jamais prometido (como o D0 do poc-05).
- Comparação **libp2p-sobre-I2P** fica parqueada como E-fase opcional, só se o
  núcleo passar — não assumida pior.

## Decisions

**D1 — Instrumento = backend Trama sobre SAM v3.** O I2P de referência é Java; o
adapter fala SAM v3 a um router I2P local (session = destination; STREAM CONNECT
para discar, STREAM ACCEPT para servir), análogo direto ao adapter SOCKS do poc-05,
agora bidirecional. O mesmo código cross-compila para Android (fala SAM ao router
local). *Alternativa descartada:* streaming lib I2P Java in-process — mais "nativo",
mas acopla dependência pesada; SAM contra router real espelha o padrão daemon+SOCKS
já validado no poc-05 e roda igual em desktop e Android.

**D2 — Reuso do seam, não redesenho.** `poc06/api`, `poc06/trama`, `poc06/node`
partem dos módulos do poc-05. O transporte I2P entra atrás do mesmo `P2pBackend`;
a `PushPolicy` neutra é reusada. *Por quê:* isolar a variável (transporte I2P) sem
reintroduzir ruído de app.

**D3 — Portão de correção antes de medir.** Nenhum número da campanha conta enquanto
o TCK não está verde num cenário controlado (loopback/host único). Correção primeiro,
latência de rede real depois. *Por quê:* separa "o instrumento existe" de "o
instrumento mede"; herda a task 2.3 do poc-05 (TCK antes do adapter).

**D4 — Réguas aferidas (validar o próprio instrumento de medida).** Cada probe é
conferido contra resposta conhecida (ex.: cronômetro de warmup ~0 em router quente,
>0 em router morto-e-revivido), como o poc-05 validou `audit-exits` "nos dois
sentidos". *Por quê:* "teste tudo" inclui a régua.

**D5 — Limiares fixados a priori.** D0 (definições), perguntas e limiares são
cravados **antes** de qualquer medição (molde da task 1.5 do poc-05). Toda claim
carrega classe de evidência `[executado]` / `[dado-só]` / limite declarado.

**D6 — Conclusão do relatório em quatro partes obrigatórias**, cada uma rastreável
a testes: viabilidade técnica (T3/T4 + T1 + portão); prós/contras (ledger com
classe de evidência por linha); comparação com a arquitetura documentada (ADR a
ADR: confirma/contradiz/obsoleta/reescreve, ligado ao teste que decide); aprendizado
e recomendação (o que o dado virou contra o a priori; qual plano e qual ADR novo).

## Risks / Trade-offs

- **[Uma única VPS obriga B+R co-localizados na descoberta (T2)]** → registrado
  como ameaça à validade *só da topologia de descoberta*; mitigado porque o lookup
  ainda sai DEV→I2P→VPS por túnel real. O caminho de transferência (T1/T3/T4) é
  P·R·M em três redes distintas, limpo. (O poc-05 pagou essa cicatriz tarde; aqui
  é ponto de partida.)
- **[Rig quente esconde o custo real do mobile]** → toda medição de leitura é feita
  a **frio** (router recém-reseedado / app morto pelo Android), nunca em rig quente
  — o número quente é a mentira que esconde o crux.
- **[Extrapolação disfarçada de dado]** → gossip×DHT e throughput são medidos
  **sobre I2P**; o 6× e os KiB/s do Tor (poc-05) não são herdados.
- **[Dependência do reseed I2P / instabilidade de rede]** → registrada como modo de
  falha novo; T0 mede reseed→túnel-pronto real em vez de assumir.
- **[Plano B inviável por intermitência]** → não se afirma; T4 mede bateria/uptime
  e o quanto a intermitência do mobile suja os provider records (frescor).
- **[Android hostil a serviço de fundo persistente]** → o router I2P exige foreground
  service; o custo disso é parte do que T4 mede, não pressuposto.

## Open Questions

- Router I2P a usar (i2pd × I2P Java) e a validação exata da API SAM v3 — resolvido
  no spike de de-riscagem da fase de código, não assumido.
- Valores numéricos dos limiares a priori (cold-start "< X s", throughput, descoberta)
  — a cravar com o usuário antes da primeira medição.
- Se o núcleo passar, incluir ou não a E-fase opcional libp2p-sobre-I2P.
