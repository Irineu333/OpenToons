# Tasks: poc-01 — Prova de Conceito (Marco 0)

Ordem guiada pelo design D6: spike Android (caminho crítico) primeiro; E3 pode andar em paralelo a qualquer momento.

## 1. Setup

- [ ] 1.1 Criar módulo Gradle `poc/node` (JVM) com dependência do nabu e verificar que compila
- [ ] 1.2 Criar esqueleto de `docs/poc-report.md` com os limiares de bateria/dados fixados a priori (design D5) e as questões abertas do design a responder

## 2. Spike E2 — nabu no Android (caminho crítico)

- [ ] 2.1 Investigar na API/código do nabu se existe modo DHT client puro (sem aceitar entrada, sem servir DHT) e registrar a resposta no relatório
- [ ] 2.2 Criar módulo `poc/android` mínimo embarcando o nabu e tentar compilar (dex, minSdk, APIs JDK, Netty)
- [ ] 2.3 Rodar em dispositivo real: inicializar o stack e conectar a pelo menos um nó da rede
- [ ] 2.4 Registrar veredito do spike no relatório; se falha conclusiva, documentar evidência + plano B (gomobile/UniFFI) e pular para o grupo 6

## 3. E1 — Nó pleno discável (JVM)

- [ ] 3.1 Nó `poc/node` conecta ao bootstrap da Amino e participa da DHT como servidor
- [ ] 3.2 Configurar endereço público manual (port forwarding ou VPS) e anunciar provider record de um bloco de teste
- [ ] 3.3 Validar dialabilidade: segundo nó em outra rede disca o endereço público e baixa o bloco anunciado

## 4. E2 — DHT client no Android (completo)

- [ ] 4.1 App resolve o provider record do CID anunciado por E1 via DHT, sem servir nem aceitar entrada
- [ ] 4.2 Executar sessão simulada de 30 min com lookups periódicos, medindo bateria (dumpsys/Battery Historian) e dados por UID
- [ ] 4.3 Registrar medições no relatório e comparar aos limiares definidos em 1.2

## 5. E3 — Manifesto assinado (paralelo, sem libp2p)

- [ ] 5.1 Implementar em `poc/` assinatura e verificação Ed25519 de um manifesto de teste
- [ ] 5.2 Demonstrar rejeição de manifesto adulterado (qualquer byte alterado)
- [ ] 5.3 Demonstrar detecção de rollback via `seq` monotônico (manifesto autêntico com `seq` antigo é rejeitado)

## 6. E4 — E2E do Marco 0

- [ ] 6.1 No nó E1, publicar um "capítulo" de teste: blocos + manifesto assinado apontando os CIDs
- [ ] 6.2 No app E2, atrás de NAT: descobrir via DHT, baixar direto do nó E1, verificar assinatura e reconstruir o capítulo
- [ ] 6.3 Demonstrar rejeição quando o conteúdo não corresponde ao manifesto (bloco corrompido/assinatura inválida)

## 7. Relatório e encerramento

- [ ] 7.1 Completar `docs/poc-report.md`: resultado por experimento, medições, versões usadas, respostas às questões abertas (modo client, minSdk, tamanho do APK, licenças) e recomendação de stack para o Marco 2
- [ ] 7.2 Atualizar referências (roadmap Marco 0 → relatório) e marcar o código de `poc/` como descartável no README do módulo
