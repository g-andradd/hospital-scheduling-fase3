# mensageria-de-eventos

## Purpose

Garante que fatos confirmados no agendamento sejam disponibilizados aos serviços satélites com contrato estável, recuperação de falhas e correlação preservada. Define as condições de reentrega e de isolamento de mensagens inválidas para impedir perda silenciosa e repetição infinita de falhas.

## ADDED Requirements

### Requirement: Topologia durável de eventos e dead letters

O sistema SHALL declarar os exchanges topic duráveis `hospital.consultas` e `hospital.consultas.dlx`, as filas duráveis `notificacao.consultas` e `historico.consultas`, ambas com `x-dead-letter-exchange: hospital.consultas.dlx`, e as DLQs duráveis `notificacao.consultas.dlq` e `historico.consultas.dlq`.

Cada fila principal SHALL receber o binding `consulta.#` do exchange principal; cada DLQ SHALL receber o binding `consulta.#` da DLX. Rejeições SHALL preservar a routing key original. Nenhuma DLQ SHALL ser ligada automaticamente de volta às filas principais.

#### Scenario: Todos os destinos recebem os eventos
- **WHEN** cada routing key normativa é publicada no exchange principal
- **THEN** notificacao.consultas e historico.consultas recebem uma cópia por publicação
- **AND** exchanges, filas, argumentos de dead-letter e bindings correspondem ao contrato

#### Scenario: Recursos e mensagens sobrevivem ao reinício do broker
- **WHEN** o broker reinicia preservando seu armazenamento após aceitar uma mensagem persistente
- **THEN** a topologia e a mensagem ainda não consumida continuam disponíveis

#### Scenario: Dead letter alcança as duas DLQs
- **WHEN** uma mensagem de qualquer fila principal é rejeitada definitivamente
- **THEN** as duas DLQs recebem a mensagem com a routing key original
- **AND** os metadados de dead-letter identificam a fila de origem

#### Scenario: Destino de dead letter temporariamente indisponível
- **WHEN** a transferência para a DLX fica temporariamente indisponível e depois é restabelecida
- **THEN** a mensagem rejeitada permanece recuperável até sua entrega às DLQs
- **AND** não retorna ao processamento de negócio da fila principal

### Requirement: Envelope e roteamento normativos

Todo evento SHALL conter `eventId` UUID único por fato, `eventType`, `aggregateId` UUID da consulta, `occurredAt` ISO-8601 UTC do fato, `version` inteiro igual a 1, `correlationId` não vazio e objeto `payload`. `aggregateId` SHALL coincidir com `payload.consultaId`.

O sistema SHALL usar exatamente os pares `CONSULTA_CRIADA`/`consulta.criada`, `CONSULTA_ATUALIZADA`/`consulta.atualizada`, `CONSULTA_CONFIRMADA`/`consulta.confirmada`, `CONSULTA_CANCELADA`/`consulta.cancelada` e `CONSULTA_REALIZADA`/`consulta.realizada`.

A mensagem SHALL usar `content-type: application/json` e headers `x-event-id`, `x-event-type`, `x-correlation-id` iguais aos respectivos valores do envelope. Datas SHALL ser textuais ISO-8601, nunca números de timestamp.

#### Scenario: Cinco tipos usam envelope e routing key correspondentes
- **WHEN** é produzido um fato de cada um dos cinco tipos normativos
- **THEN** a mensagem recebida contém o envelope versão 1, a routing key e os headers correspondentes
- **AND** fatos distintos têm eventIds distintos

#### Scenario: Momento do fato não muda com a publicação tardia
- **WHEN** um evento é publicado minutos depois de sua gravação
- **THEN** occurredAt mantém o instante original em UTC e não o momento da publicação

### Requirement: Snapshot completo e alterações anteriores

O payload SHALL conter o snapshot após a mudança, com `consultaId`, `status`, `dataHora`, `duracaoMinutos`, `observacoes`, `motivoCancelamento`, `paciente` (`id`, `nome`, `email`, `telefone`), `medico` (`id`, `nome`, `crm`, `especialidade`) e `registradoPor` (`id`, `nome`, `perfil`). Telefone, observações e motivo SHALL admitir nulo quando não aplicáveis; cancelamento SHALL conter motivo não vazio. Nenhum evento SHALL expor senha, hash ou CPF.

Somente `CONSULTA_ATUALIZADA` SHALL conter `alteracoes`, relacionando apenas campos que mudaram e seus valores anteriores: `dataHoraAnterior`, `duracaoMinutosAnterior`, `medicoIdAnterior`, `observacoesAnterior`, conforme aplicável. Uma atualização aceita sem diferença SHALL produzir `alteracoes` vazio. Mudança apenas do offset textual para o mesmo instante SHALL NOT ser tratada como mudança de horário.

O snapshot SHALL permanecer o do fato, ainda que a consulta ou os cadastros sejam alterados antes da publicação; o consumidor SHALL NOT precisar consultar o produtor para reconstruí-lo.

#### Scenario: Snapshot de criação é autossuficiente
- **WHEN** uma consulta é registrada e seu evento é recebido
- **THEN** todos os campos normativos e os dados de paciente, médico e registrante estão presentes
- **AND** o status é AGENDADA e não há alteracoes, senha, hash ou CPF

#### Scenario: Atualização inclui apenas valores anteriores alterados
- **WHEN** uma consulta tem horário, duração, médico ou observações modificados
- **THEN** o snapshot contém os valores posteriores
- **AND** alteracoes contém exatamente os campos modificados com os valores anteriores, inclusive nulo anterior quando aplicável

#### Scenario: Atualização sem diferença preserva o contrato
- **WHEN** uma atualização aceita mantém todos os valores, inclusive o instante de um horário expresso com outro offset
- **THEN** o evento é CONSULTA_ATUALIZADA com alteracoes vazio

#### Scenario: Mudanças de status têm snapshot posterior
- **WHEN** um fato de confirmação, cancelamento ou realização é produzido
- **THEN** o snapshot traz o status resultante sem alteracoes
- **AND** cancelamento traz o motivo registrado

#### Scenario: Mudança posterior não reescreve o passado
- **WHEN** consulta ou cadastro de pessoa é alterado depois de gravado um evento e antes de publicá-lo
- **THEN** o evento publicado conserva os dados capturados no fato original

### Requirement: Publicação vinculada ao commit da consulta

Cada operação de escrita aceita SHALL persistir a consulta e exatamente uma intenção de publicação durável na mesma transação, ou não persistir nenhuma das duas. Operação recusada SHALL NOT produzir intenção de publicação. O acesso ao broker SHALL ocorrer depois, pelo relay, sem condicionar o commit de negócio à disponibilidade do RabbitMQ.

#### Scenario: Operação aceita confirma consulta e evento juntos
- **WHEN** uma operação de escrita é confirmada
- **THEN** uma leitura em outra transação encontra a consulta e exatamente um evento correspondente persistidos

#### Scenario: Falha depois de salvar consulta desfaz a escrita
- **WHEN** ocorre exceção depois de salvar a consulta e antes de terminar a gravação do outbox
- **THEN** a transação não deixa a alteração da consulta nem evento persistido

#### Scenario: Falha depois de salvar outbox desfaz ambas as escritas
- **WHEN** ocorre exceção após inserir o evento e antes do commit
- **THEN** nem a alteração da consulta nem o evento são confirmados

#### Scenario: Regra de negócio recusada não produz evento
- **WHEN** uma operação é recusada por conflito, transição inválida ou outra regra de negócio
- **THEN** o estado anterior da consulta é preservado e nenhum evento adicional fica persistido

#### Scenario: Broker indisponível não desfaz fato confirmado
- **WHEN** uma consulta válida é registrada enquanto o broker está indisponível
- **THEN** a operação de negócio é confirmada e seu evento permanece pendente para envio posterior

### Requirement: Relay limitado e recuperável

O relay SHALL selecionar somente eventos pendentes, em lotes de até cinquenta, sem duas instâncias processarem simultaneamente a mesma linha bloqueada. O intervalo entre o término de uma execução agendada e a próxima SHALL ser de 1 segundo. Registros já publicados SHALL NOT ser selecionados novamente em execução normal.

Um evento SHALL ser marcado publicado somente após confirmação positiva do broker, sem retorno por falta de rota. Falha de publicação, retorno, confirmação negativa ou timeout SHALL mantê-lo pendente e incrementar seu contador uma vez por tentativa falha registrada. Não SHALL existir teto que descarte o evento ou o marque publicado sem confirmação; a contagem SHALL NOT transbordar ao ultrapassar o limite de inteiro de 32 bits. Eventos com muitas falhas SHALL NOT impedir a seleção de novos eventos aptos. Recuperação de falha SHALL reutilizar o envelope persistido.

#### Scenario: Pendente confirmado passa a publicado
- **WHEN** o broker confirma positivamente um envio sem retorno
- **THEN** o evento recebe instante de publicação e deixa de ser selecionado nas próximas varreduras

#### Scenario: Falha mantém pendente e incrementa tentativas
- **WHEN** o envio falha por indisponibilidade, retorno sem rota, NACK ou timeout
- **THEN** o evento permanece pendente e a tentativa falha é contabilizada
- **AND** a próxima execução pode reenviá-lo com o mesmo envelope

#### Scenario: Falha repetida não descarta nem bloqueia eventos novos
- **WHEN** cinquenta eventos acumulam falhas repetidas e surge um novo evento apto
- **THEN** os antigos permanecem pendentes e elegíveis para retentativa e o novo pode ser publicado

#### Scenario: Contador não transborda em inteiro de 32 bits
- **WHEN** mais uma falha ocorre para um evento com 2147483647 tentativas
- **THEN** o contador cresce para 2147483648 e o evento permanece pendente

#### Scenario: Lote e concorrência do relay respeitam o limite
- **WHEN** existem mais de cinquenta pendências e duas instâncias executam o relay simultaneamente
- **THEN** cada lote seleciona no máximo cinquenta linhas e pula as já bloqueadas pela outra instância
- **AND** uma linha não é enviada simultaneamente pelas duas instâncias

### Requirement: Reentrega com identidade estável

A entrega SHALL ser at-least-once: um evento já aceito pelo broker pode ser reenviado se a confirmação local da publicação não for persistida. Toda reentrega SHALL conservar `eventId`, `occurredAt`, `correlationId` e snapshot originais. O produtor SHALL disponibilizar esse eventId estável como chave de idempotência para os consumidores.

Consequência para M06/M08: a idempotência dos consumidores é obrigatória, com efeito e registro do eventId na mesma transação. Sua implementação e comprovação pertencem àqueles marcos; este Requirement verifica a identidade estável fornecida pelo produtor.

#### Scenario: Queda entre confirmação do broker e commit local
- **WHEN** o broker aceita o evento, mas a transação local da publicação é desfeita antes de confirmar publicado_em
- **THEN** a execução seguinte reenvia o evento com a mesma identidade e conteúdo
- **AND** a consulta de negócio continua confirmada, sem criar nova linha de outbox para a reentrega

### Requirement: Correlação e offset sobrevivem à publicação tardia

O correlationId do request SHALL ser persistido junto ao evento e propagado para o header AMQP mesmo após o término do request. Fato sem request SHALL receber correlação no momento da gravação, reutilizada nas retentativas.

`payload.dataHora` e `alteracoes.dataHoraAnterior`, quando presente, SHALL representar o instante correspondente com o offset da zona `America/Sao_Paulo` nessa data. Não SHALL depender da zona da máquina, da sessão do banco ou do offset atual do relógio. A publicação tardia SHALL conservar a representação já gravada.

#### Scenario: Correlação HTTP reaparece no header após o fim do request
- **WHEN** um evento originado por HTTP é publicado com o contexto do request já encerrado
- **THEN** x-correlation-id e correlationId do envelope são iguais ao id original da requisição

#### Scenario: Eventos sucessivos não compartilham correlação por acidente
- **WHEN** o mesmo worker publica dois eventos com correlações diferentes e depois retenta um deles
- **THEN** cada envio e seu log usam a correlação persistida do respectivo evento

#### Scenario: Fato sem request recebe correlação estável
- **WHEN** um fato é gravado sem contexto HTTP e sofre retentativa
- **THEN** a correlação gerada na gravação está presente e é idêntica em todos os envios

#### Scenario: Offset deriva da zona no instante do agendamento
- **WHEN** horários com offsets de entrada diferentes são persistidos e publicados a partir de sessões ou máquinas em zonas diferentes
- **THEN** o payload mantém cada instante com o offset de America/Sao_Paulo correspondente àquela data
- **AND** a mesma regra vale para dataHoraAnterior e para datas com regras históricas distintas

### Requirement: Retry limitado e rejeição segura de entradas inválidas

O consumo SHALL usar acknowledge automático, retry habilitado com três tentativas totais, intervalo inicial de 1000ms, multiplicador 2.0 e máximo de 10000ms. `spring.rabbitmq.listener.simple.default-requeue-rejected` SHALL ser `false`. Ao esgotar tentativas, a mensagem SHALL ser rejeitada para a DLX, sem ACK de sucesso, descarte silencioso ou requeue infinito.

A política SHALL alcançar JSON malformado, campo obrigatório ausente/nulo, UUID/enum/data/tipo inválido, versão desconhecida, headers obrigatórios ausentes ou divergentes e falha repetida durante o processamento. Validação SHALL ocorrer antes de efeitos de negócio e rejeitar coerções de tipos incompatíveis, datas sem offset e conteúdo de tipo não autorizado. Os bytes da mensagem rejeitada SHALL ser preservados nas DLQs; o consumidor SHALL continuar apto a processar mensagens válidas seguintes.

#### Scenario: Configuração efetiva exige rejeição sem requeue
- **WHEN** a factory de consumo é criada com a configuração entregue
- **THEN** usa acknowledge automático e default-requeue-rejected false
- **AND** sua única política de retry tem três tentativas totais, intervalo inicial 1000ms, multiplicador 2.0 e teto 10000ms

#### Scenario: Payload malformado chega à DLQ após três tentativas
- **WHEN** uma mensagem com JSON ou estrutura de payload malformados é entregue
- **THEN** há três tentativas de conversão/processamento, nenhum efeito e rejeição definitiva para as DLQs
- **AND** os bytes originais são preservados e não há repetição infinita

#### Scenario: Campo obrigatório ausente ou inválido é isolado
- **WHEN** o envelope ou seu payload contém campo obrigatório ausente, nulo ou com UUID, enum, data, duração ou tipo inválido
- **THEN** a entrada falha de forma controlada antes de qualquer efeito e vai às DLQs após três tentativas

#### Scenario: Versão desconhecida não alcança o processamento
- **WHEN** o envelope contém uma versão diferente de 1
- **THEN** nenhum efeito é executado e a mensagem segue às DLQs após três tentativas

#### Scenario: Metadados incompatíveis não alteram a desserialização
- **WHEN** a mensagem contém content-type inválido, headers normativos ausentes/divergentes ou sugestão de tipo não autorizado
- **THEN** a mensagem não instancia tipos arbitrários nem produz efeito e é isolada nas DLQs após três tentativas

#### Scenario: Falha transitória recupera dentro do limite
- **WHEN** o processamento falha duas vezes e sucede na terceira tentativa
- **THEN** a mensagem é reconhecida como processada e não é enviada à DLQ

#### Scenario: Falha persistente não derruba o consumidor
- **WHEN** o processamento de uma mensagem falha nas três tentativas e uma mensagem válida chega em seguida
- **THEN** a primeira vai às DLQs e a segunda é processada pelo consumidor que permaneceu ativo
