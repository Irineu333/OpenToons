# ADR-0005 — Cliente mobile: Caminho A (DHT client)

**Status:** Aceito

## Contexto

O mobile deve consumir a rede sem ser nó pleno (P2), sem servir e sem depender de
centralização (P1, P4). A grande dúvida foi **como** o mobile fala com a rede, e o
medo central foi a **dependência eterna do bootstrap**: em um nó pleno, o bootstrap
serve só para a entrada — a DHT logo fornece vizinhos e a carga se auto-balanceia;
um cliente que *não* participe da DHT tenderia a usar sempre os nós de bootstrap,
recriando centralização.

A confusão-chave: "DHT" não é uma coisa só. Há dois papéis:

- **DHT server** — guarda rotas de outros, aceita entrada, roteia consultas alheias → **serve**;
- **DHT client** — só faz consultas (lookups iterativos de saída), disca direto, não guarda nada → **parasita**.

## Decisão

Adotar o **Caminho A**: o mobile é um **DHT client** (modo consulta), **não** um DHT
server. Ele consulta a rede e baixa direto dos detentores, mas não guarda nem roteia
para ninguém.

A lista de nós é mantida fresca pela composição de quatro mecanismos, **nenhum
central**:

```
① BOOTSTRAP         semente a frio (multi-canal)            ← só cold start
② DHT client        lookups iterativos → nós de toda a rede ← auto-balanceia
③ PEX               cada nó te dá mais nós vivos
④ cache persistente lembra nós entre sessões; liveness descarta mortos
```

Assim o **bootstrap volta a ser só bootstrap**, igual a um nó pleno: após a primeira
consulta, o mobile conhece nós espalhados e cacheia-os para os próximos boots.

## Alternativas consideradas

### Caminho B — mobile como cliente HTTP puro contra endpoints das scans — descartada (adiada)

Toda scan exporia uma API HTTP de manifesto/blocos; o mobile seria um cliente HTTP
"burro" discando scans públicas diretamente.

- **Prós:** muito mais simples de entregar; sem stack libp2p no mobile.
- **Por que descartada (por ora):** o mobile HTTP puro **não fala DHT**, então para
  escapar do bootstrap precisaria **reinventar PEX-sobre-HTTP** ("scan, me dá sua
  lista de scans") — uma mini-DHT caseira e capenga. Como centralização-zero é
  inegociável (P1/P4), o Caminho A entrega descoberta auto-balanceada nativamente.
  B permanece tentador por simplicidade e pode ser reconsiderado para acelerar
  entregas, mas não é o alvo.

### Gateway HTTP central — descartada

Um (ou poucos) gateway HTTP servindo o mobile.

- **Por que descartada:** viola P1/P4 — o gateway vira servidor central e vetor de
  censura. Gateways **plurais** (toda scan) seriam aceitáveis, mas isso é justamente
  o Caminho B, com o problema de bootstrap acima.

### Mobile como nó pleno / DHT server — descartada

O mobile participaria plenamente da DHT, servindo conteúdo.

- **Por que descartada:** viola P2; custo proibitivo de bateria, dados e NAT de
  entrada no mobile (ver [ADR-0006](./0006-nat-and-reachability.md)).

## Consequências

- O mobile embarca um stack **libp2p** em modo cliente (transporte + troca de blocos
  + lookups DHT), sem modo servidor. Custo a validar na PoC (marco 0).
- **Custo honesto:** o mobile é intermitente; seu cache "esfria" mais que o de um nó
  pleno, então cai no bootstrap **com mais frequência** que um nó pleno (mas não
  "sempre"). Mitiga-se com cache generoso e PEX agressivo no reconnect.
- O medo de centralização acaba sendo um **argumento a favor de A**: B exigiria
  reconstruir à mão a descoberta que A oferece pronta.
