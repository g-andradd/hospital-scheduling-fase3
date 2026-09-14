## ADDED Requirements

### Requirement: Fixture canônica do contrato compartilhada por produtor e consumidores

O contrato SHALL possuir um único exemplar canônico de mensagem, versionado no módulo de contratos e distribuído aos serviços como artefato de testes, sem cópia em nenhum outro módulo. O exemplar SHALL ser um envelope válido segundo este contrato. Produtor e consumidores SHALL exercitá-lo nos próprios testes a partir dessa mesma origem.

O produtor SHALL demonstrar que o envelope que produz para um fato equivalente, recebido do broker real, é compatível com o exemplar:

- mesmo conjunto de campos em todos os níveis, mesmos tipos JSON e mesmos valores;
- a exceção são os campos gerados na produção — identificador do evento, identificador da consulta, instante do fato e correlação;
- esses campos SHALL ser validados quanto ao formato e às relações que o contrato impõe, e não ignorados.

Cada consumidor SHALL receber, pela topologia normativa do broker real, os bytes exatos do exemplar e SHALL produzir o efeito persistido correspondente ao fato que ele descreve.

Divergência entre o exemplar e o produtor, ou entre o exemplar e qualquer consumidor, SHALL fazer a verificação falhar.

#### Scenario: Exemplar canônico é envelope válido do contrato
- **WHEN** o exemplar canônico é lido pelo validador normativo do contrato
- **THEN** ele é aceito como envelope versão 1 de um fato de criação de consulta

#### Scenario: Existe uma única cópia física do exemplar
- **WHEN** o repositório é inspecionado
- **THEN** existe um único arquivo com o conteúdo do exemplar, no módulo de contratos
- **AND** cada serviço resolve exatamente um exemplar no próprio classpath de testes, originado do artefato de testes do módulo de contratos e idêntico byte a byte ao arquivo canônico

#### Scenario: Cópia ou sombreamento do exemplar em um serviço é recusado
- **WHEN** um serviço passa a conter uma cópia própria do exemplar
- **THEN** a verificação de fixture daquele serviço falha apontando a origem duplicada

#### Scenario: Produtor emite envelope compatível com o exemplar
- **WHEN** o produtor registra, a partir dos mesmos dados de paciente, médico, registrante, horário, duração e observações do exemplar, uma consulta cujo evento é publicado no broker real
- **THEN** a mensagem recebida tem o mesmo conjunto de campos, os mesmos tipos e os mesmos valores do exemplar, excetuados apenas os campos gerados
- **AND** os cabeçalhos normativos e a routing key correspondem ao envelope recebido

#### Scenario: Campos gerados são validados, e não ignorados
- **WHEN** o envelope produzido é comparado ao exemplar
- **THEN** o identificador do evento e o da consulta são UUIDs canônicos, o identificador do agregado coincide com o da consulta criada, o instante do fato está em UTC e corresponde ao do fato, e a correlação não é vazia
- **AND** a violação de qualquer dessas relações faz a verificação falhar

#### Scenario: Notificação processa os bytes do exemplar pelo broker real
- **WHEN** os bytes exatos do exemplar são publicados no exchange normativo com a routing key e os cabeçalhos correspondentes
- **THEN** o serviço de notificações materializa a agenda local da consulta descrita, registra uma única notificação de agendamento ao paciente do exemplar e marca o evento como processado

#### Scenario: Histórico processa os bytes do exemplar pelo broker real
- **WHEN** os bytes exatos do exemplar são publicados no exchange normativo com a routing key e os cabeçalhos correspondentes
- **THEN** o serviço de histórico materializa o snapshot da consulta descrita, acrescenta à trilha o fato com o payload do exemplar e marca o evento como processado

#### Scenario: Divergência entre exemplar e produtor ou consumidor é detectada
- **WHEN** um campo do exemplar deixa de corresponder ao que o produtor emite ou ao que um consumidor aceita
- **THEN** a verificação de compatibilidade do produtor ou o consumo do exemplar pelo consumidor falha identificando a divergência
- **AND** uma mensagem do exemplar recusada por um consumidor é reportada como falha, e não como espera sem resultado
