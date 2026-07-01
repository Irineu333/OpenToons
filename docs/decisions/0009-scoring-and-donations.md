# ADR-0009 — Pontuação e doações

**Status:** Proposto

## Contexto

A rede é sustentada por voluntários (scans e replicadores). O incentivo escolhido é
a **doação direta**, guiada por uma **pontuação** que reflita o consumo real do
usuário e premie quem publicou e quem serviu o conteúdo. Há duas armadilhas a evitar:

1. **Privacidade / centralização:** um "ranking global" exigiria agregar o consumo
   de todos em algum lugar — uma forma de centralização e um risco de privacidade.
2. **Provar serviço de bytes é caro/burlável:** demonstrar de forma *trustless* que
   um nó realmente serviu conteúdo é o problema que fez a Filecoin existir
   (*proof of retrievability*). Sem prova, pontuar "quem serviu" é manipulável.

## Decisão (proposta)

- **Pontuação 100% local**, calculada no device a partir do **consumo real** do
  próprio usuário; premia **quem publicou** e **quem serviu** o conteúdo (pesos a
  definir).
- **Ranking local**: "as scans/CLIs que *eu* mais consumi" — não um ranking global.
- **Doação direta** à scan/replicador, pelo meio que cada um configurar.
- O app **recomenda uma doação mais justa** considerando a pontuação e as doações
  anteriores.
- **Sem pagamento automático** — o humano decide.

## Alternativas consideradas

### Ranking global agregado — descartada

Agregar o consumo de todos os usuários para um ranking da rede.

- **Por que descartada:** exige agregação central (ou gossip complexo), violando P1,
  e expõe o consumo dos usuários (privacidade). O ranking local resolve o objetivo
  (guiar a doação) sem esses custos.

### Pagamento automático proporcional a serviço comprovado — descartada

O app pagaria automaticamente com base em prova de bytes servidos.

- **Por que descartada:** exige *proof of retrievability* trustless — caro e
  complexo (território Filecoin) — e, sem isso, é burlável (um nó "serve para si
  mesmo" e infla a pontuação). Como a doação é **decidida pelo humano** e a
  pontuação só **recomenda**, toleramos uma métrica "boa o suficiente" e barata.

## Consequências / questões em aberto

- **Pesos** entre publicador e servidor: a definir.
- Como atribuir crédito de "serviço" de forma barata e razoável (ex.: contabilizar
  de quem o próprio cliente baixou), aceitando que é aproximado.
- UX da aba de doação, do ranking local e da "recomendação justa".
- Alvo do marco 5 do [roadmap](../roadmap.md); depende pouco do núcleo da rede.
