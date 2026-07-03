## ADDED Requirements

### Requirement: E1 — Canal seguro próprio em duas variantes comparadas
A PoC SHALL implementar e comparar duas variantes de canal seguro com autenticação mútua ligada à identidade Ed25519: **E1a — TLS 1.3** da plataforma (certificado autoassinado embutindo a identidade) e **E1b — Noise XX** sobre primitivas do BouncyCastle. Cada variante SHALL respeitar o veto de esforço de 5 dias úteis (design D5); estourar o veto encerra a variante com resultado registrado. A comparação SHALL registrar, por variante: dias de esforço, LoC, dependências adicionadas (e delta de APK), latência de handshake e de reconexão no dispositivo físico, e restrições de API Android encontradas.

#### Scenario: Handshake mútuo autenticado (ambas as variantes)
- **WHEN** um cliente Android (dispositivo físico) conecta a um nó JVM usando a variante, com as identidades Ed25519 esperadas de ambos os lados
- **THEN** o handshake completa, cada lado comprova a posse da chave de identidade do outro e o canal cifra o tráfego subsequente

#### Scenario: Impostor é rejeitado (ambas as variantes)
- **WHEN** um nó apresenta um canal válido mas cuja identidade não corresponde à chave Ed25519 esperada pelo par
- **THEN** a conexão é rejeitada antes de qualquer troca de dados de aplicação

#### Scenario: Noise XX validado contra vetores oficiais
- **WHEN** a implementação E1b executa os vetores de teste oficiais do padrão Noise para o handshake XX (X25519 + ChaCha20-Poly1305 + SHA-256)
- **THEN** todos os vetores passam; falha em qualquer vetor bloqueia o uso da variante no E2/E4

#### Scenario: Métricas comparativas registradas
- **WHEN** as duas variantes concluem (ou estouram o veto de esforço)
- **THEN** o relatório registra a matriz comparativa (esforço, LoC, dependências/APK, latências, restrições Android) e a recomendação de canal para o Marco 2, com racional

#### Scenario: Veto de esforço duplo é resultado válido
- **WHEN** ambas as variantes estouram o veto de 5 dias úteis sem handshake funcional
- **THEN** o relatório registra a evidência e recomenda permanecer com nabu + workarounds; a PoC é considerada completa sem os experimentos dependentes do canal

### Requirement: E2 — RPC por frames e troca de blocos sem muxer/bitswap
A PoC SHALL demonstrar um protocolo de frames length-prefixed com request-id sobre uma única conexão TCP segura (canal escolhido no E1), suportando requisições concorrentes, e o download de um capítulo (manifesto assinado + blocos) com verificação Ed25519 — reutilizando o formato de manifesto validado no E3 do poc-01. O throughput SHALL ser comparado ao do nabu/bitswap no mesmo capítulo de teste do poc-01.

#### Scenario: Requisições concorrentes numa única conexão
- **WHEN** o cliente envia múltiplas requisições de blocos sem aguardar as respostas anteriores
- **THEN** as respostas chegam correlacionadas pelo request-id, independentemente da ordem, sem corromper frames

#### Scenario: Download e verificação de capítulo
- **WHEN** o cliente requisita o manifesto e os blocos de um capítulo publicado no nó JVM
- **THEN** ele baixa manifesto + blocos, verifica a assinatura Ed25519 do manifesto e o hash de cada bloco contra o manifesto, e reconstrói o capítulo íntegro

#### Scenario: Throughput comparado ao nabu
- **WHEN** o mesmo capítulo de 3 blocos do poc-01 é baixado pela stack própria em condições equivalentes
- **THEN** o tempo de download é registrado no relatório lado a lado com o do nabu/bitswap, junto com qualquer efeito de head-of-line blocking observado

### Requirement: E3 — Descoberta em duas variantes comparadas por simulação
A PoC SHALL implementar e comparar duas variantes de descoberta atrás de uma interface comum `resolve(obraId) → [providers]`: **E3a — membership completo + gossip** (anti-entropia) e **E3b — Kademlia enxuto próprio**. Ambas SHALL ser medidas em simulação in-process com n ∈ {10, 100, 1.000, 10.000} nós e churn injetado — a simulação substitui o papel de validação em escala que a Amino cumpriu no poc-01. As métricas por variante e por n SHALL incluir: RTTs por lookup do cliente, tráfego por nó por hora, memória de estado por nó, tempo de convergência pós-churn e esforço/LoC.

#### Scenario: Descoberta fria na simulação (ambas as variantes)
- **WHEN** um cliente simulado conhece apenas o nó de bootstrap e um obraId publicado por um terceiro nó
- **THEN** ele resolve os providers corretos em todas as escalas de n testadas

#### Scenario: Convergência pós-churn medida
- **WHEN** uma fração dos nós plenos simulados sai e entra da rede (churn injetado)
- **THEN** o tempo até a descoberta voltar a resolver corretamente é medido e registrado por variante e por n

#### Scenario: Curva de escala e limiar de migração registrados
- **WHEN** as medições de todas as escalas concluem
- **THEN** o relatório registra a matriz comparativa e, se o gossip for o recomendado, o valor de n (ou volume de registros) a partir do qual a migração para DHT se justifica — o gatilho documentado para o roadmap

#### Scenario: Cliente nunca participa da malha
- **WHEN** o cliente executa lookups em qualquer variante
- **THEN** ele não armazena registros de terceiros, não roteia e não aceita conexões de entrada, conforme ADR-0005

### Requirement: E4 — Ciclo E2E do Marco 0 com a stack própria
A PoC SHALL demonstrar, com a stack própria (canal do E1 + RPC do E2 + descoberta escolhida do E3), o mesmo critério de conclusão do Marco 0 fechado pelo nabu no poc-01: um capítulo assinado publicado num nó com endereço público manual é descoberto por **descoberta fria** e baixado por um mobile em outra rede, atrás de NAT, com verificação de assinatura.

#### Scenario: Descoberta fria, download e verificação ponta a ponta pelo caminho público real
- **WHEN** o app no dispositivo físico, em outra rede e conhecendo somente o endereço do bootstrap e o obraId, busca o capítulo de teste
- **THEN** ele descobre o nó publicador (nunca informado), disca seu endereço público, baixa manifesto + blocos, verifica a assinatura e reconstrói o capítulo íntegro

#### Scenario: Conteúdo adulterado é rejeitado
- **WHEN** um bloco baixado não corresponde ao hash do manifesto, ou o manifesto falha na verificação de assinatura
- **THEN** o app rejeita o capítulo e reporta a falha de verificação

### Requirement: E5 — Medições comparativas com o poc-01
A PoC SHALL repetir a sessão de medição do poc-01 (30 minutos, lookups periódicos, mesmo dispositivo Moto g(30), mesmas ferramentas) com a stack própria, e SHALL registrar as métricas contra os limiares fixados a priori no design D5, lado a lado com os números publicados do nabu.

#### Scenario: Sessão de 30 minutos medida nas mesmas condições
- **WHEN** a sessão simulada de leitura com lookups periódicos é executada no mesmo dispositivo e roteiro do poc-01
- **THEN** bateria (< 5%) e dados além do conteúdo (< 20 MB) são medidos e comparados aos limiares e aos valores do nabu (≈ 0,03% / 1,09 MB)

#### Scenario: Latências de conexão medidas
- **WHEN** o app realiza a primeira conexão e uma reconexão a um nó em rede real
- **THEN** handshake (< 1 s) e reconexão (< 500 ms) são medidos contra os limiares do design D5

#### Scenario: Pegada da stack registrada
- **WHEN** o app de teste é empacotado com a camada de rede própria completa
- **THEN** o delta de APK (limiar ≤ 2 MB, vs 12 MB do nabu), LoC da camada de rede e nº de dependências são registrados no relatório

### Requirement: Relatório de conclusões do poc-02
A PoC SHALL produzir `docs/poc02-report.md` registrando, para cada experimento, o resultado (positivo ou negativo), as medições, as matrizes de decisão preenchidas (canal seguro; descoberta) e a recomendação de stack de rede para o Marco 2 — incluindo o custo de manutenção perpétua de código de segurança como consequência assumida, não mensurada. Um resultado negativo documentado satisfaz este requisito.

#### Scenario: Relatório publicado ao final
- **WHEN** os experimentos E1–E5 terminam (com sucesso, falha conclusiva ou veto de esforço)
- **THEN** `docs/poc02-report.md` existe com resultados por experimento, as duas matrizes de decisão preenchidas com dados medidos, esforço real registrado por experimento, respostas às questões abertas do design e recomendação fundamentada (implementação própria × nabu + workarounds)

#### Scenario: Falha conclusiva encerra a PoC com conhecimento
- **WHEN** um experimento do caminho crítico falha de forma conclusiva (ex.: veto de esforço duplo no E1)
- **THEN** o relatório registra a evidência e a recomendação alternativa, e a PoC é considerada completa sem os experimentos dependentes
