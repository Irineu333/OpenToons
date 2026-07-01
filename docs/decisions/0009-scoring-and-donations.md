# ADR-0009 — Pontuação e doações: revezamento com pontos

**Status:** Aceito

## Contexto

A rede é sustentada por voluntários (scans e replicadores). O incentivo escolhido é
a **doação direta**, guiada por uma **pontuação** que reflita o consumo real do
usuário e premie quem publicou e quem serviu o conteúdo. Restrições que moldaram a
decisão:

1. **Privacidade / centralização:** um "ranking global" exigiria agregar o consumo
   de todos em algum lugar — uma forma de centralização e um risco de privacidade.
2. **Provar serviço de bytes é caro/burlável:** demonstrar de forma *trustless* que
   um nó realmente serviu conteúdo é o problema que fez a Filecoin existir
   (*proof of retrievability*). Sem prova, pontuar "quem serviu" é manipulável.
3. **Comportamento real do doador:** a doação acontece **fora da rede** (Pix,
   Patreon…), então o app **não consegue verificá-la**; o usuário doa para **no
   máximo um destinatário por mês** e não vai se dar o trabalho de **dividir** a
   doação entre várias scans.

## Decisão

**Pontuação 100% local + revezamento de destaque único.** A justiça é **temporal**:
em vez de dividir um valor entre vários destinatários num mês, a doação **reveza**
entre quem o usuário consome, proporcionalmente ao consumo, ao longo dos meses.

### Pontos — moeda única, lastreada no capítulo lido

- 1 capítulo **lido** = **+1 ponto** para quem **publicou** (a chave que assinou o
  manifesto) e **+1 ponto** para quem **serviu** os blocos daquele capítulo;
- a mesma entidade que publicou **e** serviu soma os dois papéis (**2 pontos**) —
  incentiva a scan a manter o próprio nó no ar;
- capítulo baixado mas **nunca lido não pontua ninguém** (consumo real);
- capítulo servido por **vários peers**: o ponto de serviço é rateado pela fração
  de bytes entregue por cada um;
- **dedup**: cada `(obra, capítulo)` conta no máximo **1× por mês** (reler não
  pontua de novo).

### Revezamento — fila única, um destinatário por mês

- cada entidade (chave de scan ou peer replicador) tem um **acumulador local**:
  pontos **desde a última doação a ela** (ou desde sempre, se nunca houve);
- uma vez por mês (mês civil) o app exibe um **card com um único destinatário**: o
  topo do acumulador — scans e replicadores competem na **mesma fila**;
- **"Doei"** zera o acumulador daquela entidade; **"Pular"** mantém acumulando;
- o **valor doado é irrelevante para a mecânica** (R$ 1 e R$ 100 avançam a fila
  igual) — doação é generosidade, não fatura; o *quanto* é 100% do humano;
- a declaração "doei" **não precisa ser verificável**: ela só avança a fila do
  próprio usuário; mentir não corrompe nada além da própria recomendação.

### Explicabilidade > precisão

A recomendação deve ser reconstituível pelo usuário em uma frase — ex.: *"a Scan A
te entregou 42 capítulos e publicou 38 deles (84 pontos) sem receber nada"*.
Qualquer sofisticação (decaimento contínuo, ponderações) que quebre isso custa mais
do que vale.

## Alternativas consideradas

### Rateio proporcional de orçamento — descartada

O usuário definiria um orçamento mensal e o app recomendaria a **divisão**
proporcional ao consumo, descontando doações anteriores.

- **Por que descartada:** irreal na prática — o usuário doa para no máximo uma scan
  por mês e não divide a doação em vários pagamentos; o desconto de "já doado"
  dependia de auto-declaração **verídica**, que o app não tem como verificar,
  tornando a contabilidade frágil; e exigia um conceito artificial de "orçamento".
  O revezamento entrega a mesma justiça ao longo do tempo com um pagamento por mês.

### Pesos publicador × servidor num score único — dissolvida

Somar os dois papéis com uma constante de ponderação exigia comparar unidades
incomensuráveis (capítulos criados × bytes servidos).

- **Por que dissolvida:** a moeda única "capítulo lido" elimina o parâmetro — cada
  papel vale 1 ponto por capítulo lido, e quem exerce os dois soma naturalmente.

### Ranking global agregado — descartada

Agregar o consumo de todos os usuários para um ranking da rede.

- **Por que descartada:** exige agregação central (ou gossip complexo), violando P1,
  e expõe o consumo dos usuários (privacidade). O acumulador local resolve o
  objetivo (guiar a doação) sem esses custos.

### Pagamento automático proporcional a serviço comprovado — descartada

O app pagaria automaticamente com base em prova de bytes servidos.

- **Por que descartada:** exige *proof of retrievability* trustless — caro e
  complexo (território Filecoin) — e, sem isso, é burlável. Como a doação é
  **decidida pelo humano** e a pontuação só **recomenda**, toleramos uma métrica
  aproximada e barata.

## Consequências

- **O marco 1 (leitor offline) precisa emitir o evento de leitura** — é a única
  entrada do sistema: `{obra_id, capítulo, chave_publicador, origem: [(peer,
  fração_bytes)], timestamp}`. No marco 1 `origem` é vazia (não há rede); a
  estrutura já nasce pronta para o marco 2. Sem isso, o marco 5 estreia com
  histórico zero.
- **Metadados de pagamento devem ser assinados** pela chave do destinatário — scan
  **ou replicador** (ex.: no manifesto, atualizáveis via `seq`; ver
  [ADR-0003](./0003-content-model.md)). Sem isso, um nó intermediário poderia
  substituir o endereço de pagamento pelo dele.
- **Resistência a abuso é estrutural:** como o score é local e por consumidor, para
  inflar pontos junto a um usuário é preciso de fato **servi-lo capítulos que ele
  leia** — exatamente o comportamento desejado. O vetor restante é o impostor que
  republica conteúdo copiado sob a própria chave e farma pontos de *publicador* —
  esse é o problema de identidade do [ADR-0008](./0008-identity-trust.md), não
  deste ADR.
- **Privacidade local:** o histórico nunca sai do device; eventos brutos são
  agregados por mês e descartados após o fechamento do período. Uma opção "não
  registrar histórico" desliga a recomendação, e só.
- A UX fina do card (texto, mínimo de pontos para exibir, lembrete do meio de
  pagamento) fica para o marco 5 do [roadmap](../roadmap.md); o evento de leitura,
  para o marco 1.
