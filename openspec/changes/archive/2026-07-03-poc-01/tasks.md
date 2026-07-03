# Tasks: poc-01 — Prova de Conceito (Marco 0)

Ordem guiada pelo design D6: spike Android (caminho crítico) primeiro; E3 pode andar em paralelo a qualquer momento.

## 1. Setup

- [x] 1.1 Criar módulo Gradle `poc01/node` (JVM) com dependência do nabu e verificar que compila
- [x] 1.2 Criar esqueleto de `docs/poc01-report.md` com os limiares de bateria/dados fixados a priori (design D5) e as questões abertas do design a responder

## 2. Spike E2 — nabu no Android (caminho crítico)

- [x] 2.1 Investigar na API/código do nabu se existe modo DHT client puro (sem aceitar entrada, sem servir DHT) e registrar a resposta no relatório
- [x] 2.2 Criar módulo `poc01/android` mínimo embarcando o nabu e tentar compilar (dex, minSdk, APIs JDK, Netty)
- [x] 2.3 Rodar em dispositivo real: inicializar o stack e conectar a pelo menos um nó da rede
- [x] 2.4 Registrar veredito do spike no relatório; se falha conclusiva, documentar evidência + plano B (gomobile/UniFFI) e pular para o grupo 6

## 3. E1 — Nó pleno discável (JVM)

- [x] 3.1 Nó `poc01/node` conecta ao bootstrap da Amino e participa da DHT como servidor
- [x] 3.2 Configurar endereço público manual (port forwarding ou VPS) e anunciar provider record de um bloco de teste
- [x] 3.3 Validar dialabilidade: segundo nó em outra rede disca o endereço público e baixa o bloco anunciado

## 4. E2 — DHT client no Android (completo)

- [x] 4.1 App resolve o provider record do CID anunciado por E1 via DHT, sem servir nem aceitar entrada *(via consulta DHT ao E1 público; resolução na Amino em escala bloqueada pelo nabu v0.8.0 TCP-only — gap documentado no relatório)*
- [x] 4.2 Executar sessão simulada de 30 min com lookups periódicos, medindo bateria (dumpsys/Battery Historian) e dados por UID
- [x] 4.3 Registrar medições no relatório e comparar aos limiares definidos em 1.2

## 5. E3 — Manifesto assinado (paralelo, sem libp2p)

- [x] 5.1 Implementar em `poc01/` assinatura e verificação Ed25519 de um manifesto de teste
- [x] 5.2 Demonstrar rejeição de manifesto adulterado (qualquer byte alterado)
- [x] 5.3 Demonstrar detecção de rollback via `seq` monotônico (manifesto autêntico com `seq` antigo é rejeitado)

## 6. E4 — E2E do Marco 0

- [x] 6.1 No nó E1, publicar um "capítulo" de teste: blocos + manifesto assinado apontando os CIDs
- [x] 6.2 No app E2, atrás de NAT: descobrir via DHT, baixar direto do nó E1, verificar assinatura e reconstruir o capítulo *(rede privada, fallback do design D2 — desvio documentado no relatório)*
- [x] 6.3 Demonstrar rejeição quando o conteúdo não corresponde ao manifesto (bloco corrompido/assinatura inválida)

## 7. Relatório e encerramento

- [x] 7.1 Completar `docs/poc01-report.md`: resultado por experimento, medições, versões usadas, respostas às questões abertas (modo client, minSdk, tamanho do APK, licenças) e recomendação de stack para o Marco 2
- [x] 7.2 Atualizar referências (roadmap Marco 0 → relatório) e marcar o código de `poc01/` como descartável no README do módulo

## 8. E5 — Rede bootstrap/DHT própria (adicionado após o diagnóstico do gap Amino; relatório atualizado ao final)

- [x] 8.1 Suporte a bootstrap próprio nos nós da PoC (modo bootstrap dedicado + join por multiaddr)
- [x] 8.2 Formar rede de 4 nós (bootstrap + publicador + 2 servidores DHT) com endereços públicos na faixa encaminhada 4000-4999
- [x] 8.3 Descoberta fria no app: conhecendo só o bootstrap e o CID, resolver o provider record, baixar do publicador e verificar o capítulo
- [x] 8.4 Registrar o veredito do E5 no relatório
