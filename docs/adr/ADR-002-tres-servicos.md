# ADR-002 — Três serviços: agendamento, notificação e histórico

## Contexto

O enunciado da Fase 3 descreve dois serviços obrigatórios — **agendamento** e **notificações**,
integrados por mensageria — e cita o **serviço de histórico com GraphQL** como item opcional.

Isso abre uma decisão real: embutir a consulta de histórico no serviço de agendamento, que já
tem os dados de consulta no seu banco, ou promovê-la a um terceiro serviço com banco próprio,
alimentado por eventos.

A tensão é entre esforço e demonstração. O caminho embutido custa quase nada — um resolver
GraphQL sobre o repositório JPA existente. O caminho separado exige um consumidor, um read
model, projeção de eventos e tratamento de evento fora de ordem, mas é o que transforma
"publiquei uma mensagem" em uma arquitetura orientada a eventos de verdade.

Pesa também o que é avaliado. O enunciado nomeia explicitamente segurança, GraphQL e
comunicação assíncrona. Um histórico embutido entregaria GraphQL, mas com o RabbitMQ servindo a
um único consumidor — o que enfraquece justamente o eixo assíncrono.

## Decisão

Três serviços, com bancos separados:

| Serviço | Porta | Papel |
|---|---|---|
| `agendamento-service` | 8081 | Escrita. REST, autenticação, regras de negócio, publicação de eventos |
| `notificacao-service` | 8082 | Consome eventos. Confirmações e lembrete D-1 |
| `historico-service` | 8083 | Consome os mesmos eventos. Read model e API GraphQL |

O item opcional entra no escopo. O `hospital.consultas` é um topic exchange com **dois**
consumidores independentes recebendo o mesmo fluxo de eventos, cada um materializando o que
precisa: o notificações mantém uma agenda local para o job D-1, o histórico mantém um snapshot
mais a trilha completa de mudanças.

Isso configura um **CQRS-lite**: a escrita vive no agendamento, a leitura rica vive no
histórico, e a consistência entre os dois é eventual, mediada pelo broker.

## Alternativas consideradas

**Dois serviços, com o histórico embutido no agendamento.**
Descartada. Seria mais barata, mas deixaria o RabbitMQ com um consumidor só — um enfileiramento,
não uma integração. Também acoplaria a carga de leitura à base transacional de escrita, que é
exatamente o que o read model existe para evitar. E abriria mão do item opcional do enunciado
por economia de algumas horas.

**Quatro serviços, separando autenticação em um serviço próprio.**
Descartada. Com JWT stateless (ver ADR-005), a validação do token é local a cada serviço — um
serviço de autenticação dedicado só emitiria o token, sem participar de nenhuma outra requisição.
Seria um container a mais para provar um ponto que o filtro compartilhado já prova, num projeto
cujo ambiente de entrega é um `docker compose up` na máquina do avaliador.

**Um único serviço modular.**
Descartada de saída: contraria o enunciado, que pede comunicação assíncrona entre serviços.

## Consequências

**Positivas**
- O broker tem dois consumidores reais, com fanout por tópico — a comunicação assíncrona deixa de ser decorativa.
- O item opcional do enunciado é entregue.
- Read model separado permite modelar o histórico para leitura (`consulta_evento` com a trilha completa) sem distorcer o modelo de escrita.
- Cada serviço tem seu database (decisão D7 do `docs/00-project-charter.md`) — autonomia sem tabela compartilhada.

**Negativas, aceitas**
- Consistência eventual entre escrita e leitura. Uma consulta criada aparece no histórico em milissegundos, não instantaneamente. Aceitável para o domínio.
- Entrega at-least-once obriga idempotência por `eventId` nos dois consumidores — trabalho que não existiria num monolito.
- Três aplicações para subir, monitorar e testar. Mitigado pelo monorepo (ADR-003) e pelo compose único.
- O snapshot completo no payload do evento (ver `docs/03-contrato-de-eventos.md` §4) duplica dados entre bancos. É deliberado: sem ele, o consumidor precisaria chamar o produtor por HTTP, e o desacoplamento evaporaria.

## Status

Aceita. Materializada em `bootstrap-monorepo` (M00), que cria os três módulos de serviço.
