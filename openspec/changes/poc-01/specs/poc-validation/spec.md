## ADDED Requirements

### Requirement: E1 — Nó pleno discável na JVM
A PoC SHALL demonstrar um nó JVM (nabu) com endereço público configurado manualmente que entra na DHT e anuncia conteúdo, conforme ADR-0006.

#### Scenario: Nó entra na DHT e anuncia um bloco
- **WHEN** o nó E1 é iniciado com endereço público configurado manualmente (port forwarding ou VPS)
- **THEN** ele conecta ao bootstrap, participa da DHT como servidor e publica um provider record para o CID de um bloco de teste

#### Scenario: Nó é discável de fora
- **WHEN** um segundo nó, em outra rede, disca o endereço público do nó E1
- **THEN** a conexão de entrada é aceita e o bloco anunciado é servido

### Requirement: E2 — DHT client no Android
A PoC SHALL demonstrar o stack (nabu) rodando em um app Android que resolve provider records via DHT **sem servir conteúdo nem aceitar conexões de entrada**, conforme ADR-0005, e SHALL medir o custo de bateria e dados contra limiares definidos antes da medição. A DHT em questão é a rede alcançável pelo app (nós da PoC / rede própria — design D7); a resolução na DHT pública global (Amino) em escala está **fora do escopo** desta PoC e delegada ao Marco 2, condicionada aos fixes upstream documentados no relatório.

#### Scenario: Stack compila e executa no Android
- **WHEN** o app Android mínimo com nabu embarcado é instalado e iniciado em um dispositivo real
- **THEN** o stack inicializa sem crash e conecta a pelo menos um nó da rede

#### Scenario: Resolução de provider record sem servir
- **WHEN** o app consulta a DHT da rede pelo CID anunciado pelo nó E1
- **THEN** ele obtém o provider record apontando para o nó E1, sem aceitar conexões de entrada nem armazenar/rotear registros para terceiros

#### Scenario: Limitação Amino registrada
- **WHEN** a resolução do mesmo CID é tentada contra a DHT pública global (Amino)
- **THEN** o resultado (positivo ou negativo) e o diagnóstico são registrados no relatório; um resultado negativo com causa identificada e plano para o Marco 2 satisfaz este cenário

#### Scenario: Custo de bateria e dados medido
- **WHEN** uma sessão simulada de 30 minutos com lookups periódicos é executada com medição ativa
- **THEN** o consumo de bateria e de dados é registrado no relatório e comparado aos limiares fixados a priori (design D5)

### Requirement: E3 — Manifesto assinado com proteção contra rollback
A PoC SHALL demonstrar assinatura e verificação de um manifesto (Ed25519) e a detecção de rollback via `seq` monotônico, conforme ADR-0003, sem dependência de libp2p.

#### Scenario: Assinatura válida é aceita
- **WHEN** um manifesto é assinado com a chave do publicador e verificado com a chave pública correspondente
- **THEN** a verificação passa

#### Scenario: Manifesto adulterado é rejeitado
- **WHEN** qualquer byte do manifesto assinado é alterado antes da verificação
- **THEN** a verificação falha

#### Scenario: Rollback é detectado
- **WHEN** um manifesto autêntico com `seq` menor que o último `seq` conhecido é apresentado
- **THEN** o verificador o rejeita como rollback

### Requirement: E4 — Ciclo E2E do Marco 0
A PoC SHALL demonstrar o critério de conclusão do Marco 0: um capítulo assinado por um nó "desktop" discável é descoberto e baixado por um "mobile" atrás de NAT, via DHT, com verificação de assinatura.

#### Scenario: Descoberta, download e verificação ponta a ponta
- **WHEN** o nó E1 publica um capítulo de teste (blocos + manifesto assinado) e o app E2, atrás de NAT e sem configuração de rede especial, busca esse capítulo
- **THEN** o app descobre o detentor via DHT, baixa os blocos diretamente do nó E1, verifica a assinatura do manifesto e reconstrói o capítulo íntegro

#### Scenario: Capítulo com assinatura inválida é rejeitado
- **WHEN** o conteúdo baixado não corresponde ao manifesto assinado (bloco corrompido ou assinatura inválida)
- **THEN** o app rejeita o capítulo e reporta a falha de verificação

### Requirement: E5 — Rede bootstrap/DHT própria
A PoC SHALL demonstrar a viabilidade de uma rede DHT própria da OpenToons: nós com bootstrap dedicado (sem a Amino) e **descoberta fria** — um cliente que conhece apenas o nó de bootstrap e o CID resolve o provider record de conteúdo publicado por um terceiro nó e o baixa.

#### Scenario: Rede própria se forma com bootstrap dedicado
- **WHEN** nós DHT (bootstrap, publicador e servidores) são iniciados apontando apenas para o bootstrap próprio, com endereços públicos manuais
- **THEN** todos entram na mesma DHT e o publicador anuncia provider records do capítulo de teste

#### Scenario: Descoberta fria pelo cliente
- **WHEN** o app, em outra rede e conhecendo somente o endereço do bootstrap e o CID do manifesto, consulta a DHT
- **THEN** ele encontra o provider record apontando o nó publicador (que nunca lhe foi informado), disca o endereço público do publicador, baixa o capítulo e verifica a assinatura

### Requirement: Relatório de conclusões
A PoC SHALL produzir `docs/poc-report.md` registrando, para cada experimento, o resultado (positivo ou negativo), as medições, as versões usadas e a recomendação de stack para o Marco 2. Um resultado negativo documentado satisfaz este requisito.

#### Scenario: Relatório publicado ao final
- **WHEN** os experimentos E1–E4 terminam (com sucesso ou falha conclusiva)
- **THEN** `docs/poc-report.md` existe com resultados por experimento, medições de E2, respostas às questões abertas do design e recomendação de biblioteca

#### Scenario: Falha conclusiva encerra a PoC com conhecimento
- **WHEN** um experimento do caminho crítico falha de forma conclusiva (ex.: nabu não roda no Android)
- **THEN** o relatório registra a evidência, o plano B recomendado, e a PoC é considerada completa sem os experimentos dependentes
