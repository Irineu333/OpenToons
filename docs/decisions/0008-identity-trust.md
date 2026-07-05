# ADR-0008 — Autenticidade de identidade (chave → publicador)

**Status:** Aceito

## Contexto

Todo conteúdo é assinado, o que garante **integridade** (ninguém adultera) — mas
**não** garante **autenticidade de identidade**. Se um impostor cria uma chave nova
e se anuncia como "Publicador Famoso", copiando nome e capa, o cliente verifica a
assinatura com sucesso... contra a chave *errada*. O problema: como o cliente sabe
que uma dada chave pública pertence ao publicador "de verdade", **sem uma raiz de
confiança central**?

Reenquadramento que guiou a decisão: como o conteúdo é livremente replicável por
design, o ganho real do impostor é **desviar doações** (ver
[ADR-0009](./0009-scoring-and-donations.md)) e **sequestrar a continuidade** (o
leitor segue a chave errada e recebe dela os capítulos futuros). O risco se
concentra em dois momentos — a **descoberta** ("qual das duas 'Obra Famosa' é a
original?") e a **doação**. Depois do "seguir", o pinning da chave resolve o resto.

**Critério da decisão:** só entram mecanismos **auditáveis e infalsificáveis** —
cada aresta de confiança deve ser verificável criptograficamente pelo próprio
cliente, sem raiz central e sem depender de alegações de terceiros.

## Decisão

Quatro camadas, todas verificáveis pelo cliente:

1. **TOFU + pinning no "seguir" (base).** Seguir pina a chave; mudança de chave
   posterior gera alerta forte. Cobre tudo após o primeiro contato.

2. **Seguir direto pela chave (caminho autoritativo).** O usuário pode seguir por
   URI/QR obtidos nos **canais próprios do publicador** (site, redes, material
   impresso), pulando a descoberta em-rede. A raiz de confiança é o canal externo
   que o leitor já reconhece. O nome embutido na URI é cortesia de exibição — quem
   manda é a chave. Um follow pode ficar **pendente** até a DHT resolver a chave.

3. **Endosso público de 1 salto.** O manifesto ganha uma lista `recomenda:`
   (pública e assinada — distinta do "seguir" privado do app, que é ato de
   consumo). A UI exibe o selo "recomendado por N publicadores que você segue" e
   **prioriza** esses resultados na descoberta. A aresta é assinada pelo
   endossante: impostor não a forja, e Sybil não cria caminho até o conjunto de
   follows do usuário. **Um salto apenas** — caminhos multi-salto reproduzem a UX
   de níveis de confiança do PGP, descartada.

4. **Verificação por domínio (opcional).** O manifesto declara uma URL; a página
   apontada publica o fingerprint da chave; o vínculo só vale se fechar **nos dois
   sentidos**. Cada cliente verifica **localmente** (sem serviço verificador
   central), cacheia com TTL e re-verifica; uma verificação que *deixa* de fechar
   **rebaixa com alerta** — é o mesmo evento de segurança que mudança de chave no
   TOFU. A UI mostra **o domínio** ("✓ fulana.com.br"), nunca um selo genérico.
   Endereços auto-autenticantes (ex.: `.b32.i2p`, derivados da própria chave de
   destino) contam como verificação forte — cobre o publicador anônimo.

Transversal às camadas: **UI honesta (petnames)**. Nome autodeclarado é sempre
apresentado como alegação ("diz chamar-se…"), nunca como fato; o leitor pode dar
apelidos locais. A UI nunca legitima o impostor por conta própria.

## Alternativas descartadas

- **Registro assinado no bootstrap (nome → chave):** quem controla a lista é uma
  raiz central de identidade — colide com P1/P4. O caso que ele cobriria (o
  publicador famoso) é coberto pelas camadas 2 e 4 sem raiz interna.
- **Teia de confiança multi-salto:** complexidade e UX delicada sem massa crítica
  que a justifique; o endosso de 1 salto captura o valor essencial com arestas
  100% verificáveis.
- **Sinais de continuidade como segurança** (`seq` alto, datas autodeclaradas,
  volume de histórico): **falsificáveis em segundos** — um script fabrica "anos"
  de histórico. O `seq` é anti-rollback *de uma chave dada*, não prova de idade.
  Continuidade só aparece na UI como **memória local honesta** ("você conhece
  esta chave há 8 meses", "você já baixou 3 obras dela") — dados que o próprio
  cliente observou — nunca como fato sobre o publicador.
- **Carimbo externo de tempo (ex.: OpenTimestamps), adiado:** é a única forma
  infalsificável de provar *idade* de uma chave (manifestos encadeados por hash +
  âncora pública de tempo), mas prova idade, não identidade (impostor paciente
  pode envelhecer uma chave), depende de o original ter adotado o carimbo e
  adiciona dependência externa. Reavaliar apenas se o caso "publicador anônimo sem
  domínio e sem endossos" doer na prática.

## Consequências

- O manifesto ([ADR-0003](./0003-content-model.md)) ganha campos a especificar:
  `recomenda:` (chaves endossadas publicamente) e — como porta aberta para rotação
  de chave — `chave_sucessora` (nova chave assinada pela antiga). A sucessão não
  precisa ser implementada já, mas o campo deve ser previsto: com chaves pinadas e
  QRs impressos em canais externos, é impossível adicioná-la retroativamente sem
  dor.
- Especificar o formato da URI/QR de follow: versionado, tipo de chave explícito,
  nome de cortesia, dicas opcionais de conectividade (peers/relay) para follow
  pendente.
- O fluxo de **doação** deve impor barreira extra para chave sem nenhuma
  verificação ("você nunca verificou esta chave e vai enviar dinheiro").
- A UX de "seguir" assume papel de segurança: o momento do follow é o momento do
  pinning.
- **Limitação assumida:** usuário novo (zero follows) diante de um publicador sem
  domínio e sem endossos não tem garantia nenhuma — resta a UI honesta e a
  barreira na doação. Aceito como custo de não reintroduzir centralização.
