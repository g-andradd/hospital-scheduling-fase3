## ADDED Requirements

### Requirement: Imagens executáveis das aplicações

Cada um dos três serviços SHALL ter uma imagem de container construída a partir do código do repositório, sem artefato pré-construído no host. Toda imagem:

- SHALL separar a etapa de construção da etapa de execução;
- SHALL executar sobre o ambiente de execução Java 21, sem ferramentas de build nem código-fonte;
- SHALL executar o processo da aplicação com usuário sem privilégio de administrador;
- SHALL declarar verificação de saúde que consulte o endpoint de saúde do próprio serviço.

A resolução de dependências SHALL anteceder a cópia do código-fonte, para que uma mudança só no código reaproveite as dependências já resolvidas.

#### Scenario: Aplicação executa com usuário sem privilégio
- **WHEN** o container de qualquer dos três serviços está em execução
- **THEN** o processo da aplicação roda com um usuário diferente de root

#### Scenario: Saúde do container reflete o endpoint de saúde
- **WHEN** o serviço responde com sucesso no endpoint de saúde
- **THEN** o container atinge o estado `healthy`
- **AND** enquanto o endpoint não responde com sucesso, o container não é considerado `healthy`

#### Scenario: Imagem final contém só o necessário para executar
- **WHEN** a imagem final de qualquer dos três serviços é inspecionada
- **THEN** ela contém o ambiente de execução Java 21 e o artefato executável do serviço
- **AND** não contém Maven, JDK completo nem o código-fonte do repositório

#### Scenario: Dependências resolvidas antes do código-fonte
- **WHEN** a definição de construção de qualquer dos três serviços é verificada
- **THEN** os descritores de build são copiados e as dependências resolvidas antes da cópia do código-fonte
- **AND** a construção reaproveita um cache de dependências entre execuções

### Requirement: Ambiente completo com um comando

Um único comando SHALL subir PostgreSQL, RabbitMQ, os três serviços e o servidor de correio de demonstração, todos até o estado saudável.

- Cada serviço SHALL iniciar só depois de suas dependências estarem saudáveis.
- Cada serviço SHALL usar exclusivamente o próprio database e alcançar banco, broker e correio pelos nomes internos do ambiente.
- Os três serviços SHALL executar com os perfis de demonstração e de container ativos, portanto com os usuários de demonstração e os logs estruturados.
- Configuração obrigatória ausente, como o segredo de assinatura dos tokens, SHALL impedir a subida com mensagem que identifica a variável.

PostgreSQL e RabbitMQ SHALL continuar com os nomes, volumes e provisionamento de databases já existentes.

#### Scenario: Subida completa até o estado saudável
- **WHEN** um desenvolvedor sobe o ambiente completo com o `.env` preenchido a partir do `.env.example`
- **THEN** os containers de PostgreSQL, RabbitMQ, agendamento, notificação, histórico e correio atingem o estado `healthy`
- **AND** a API do agendamento, o endpoint GraphQL do histórico, o disparo de lembretes da notificação e a interface web do correio respondem nas portas documentadas

#### Scenario: Serviço só inicia com as dependências saudáveis
- **WHEN** o ambiente completo é iniciado a partir de volumes vazios
- **THEN** nenhum dos três serviços inicia antes de PostgreSQL e RabbitMQ estarem `healthy`
- **AND** a notificação não inicia antes de o servidor de correio estar `healthy`

#### Scenario: Cada serviço usa o próprio database
- **WHEN** o ambiente completo está em execução
- **THEN** o agendamento usa só `agendamento_db`, a notificação só `notificacao_db` e o histórico só `historico_db`
- **AND** nenhum serviço aponta para o broker ou o banco por endereço de host local

#### Scenario: Perfis de demonstração e de container ativos
- **WHEN** o ambiente completo está em execução
- **THEN** os usuários de demonstração conseguem se autenticar
- **AND** os logs dos três serviços saem como objetos JSON

#### Scenario: Segredo obrigatório ausente impede a subida
- **WHEN** o ambiente completo é iniciado sem o segredo de assinatura dos tokens
- **THEN** a subida é recusada antes de criar os containers das aplicações
- **AND** a mensagem identifica a variável ausente

### Requirement: Notificações por e-mail no ambiente de demonstração

No ambiente completo, a notificação SHALL usar o canal de correio apontado para o servidor de correio de demonstração, e as notificações reativas e os lembretes SHALL chegar à caixa de entrada visível na interface web desse servidor. Nesse ambiente, a saúde da notificação SHALL incluir o servidor de correio, porque ele é a dependência ativa do canal. Fora do ambiente completo, o canal padrão de log e a saúde sem o indicador de correio SHALL permanecer como estão.

#### Scenario: Notificação reativa chega à caixa de entrada
- **WHEN** uma consulta é criada no ambiente completo
- **THEN** a caixa de entrada do servidor de correio recebe uma mensagem com assunto de agendamento destinada ao paciente da consulta

#### Scenario: Saúde da notificação inclui o servidor de correio
- **WHEN** o servidor de correio fica indisponível com o ambiente completo em execução
- **THEN** o container da notificação deixa de estar `healthy`
- **AND** volta a `healthy` quando o servidor de correio é restabelecido

#### Scenario: Fora do ambiente completo o canal e a saúde não mudam
- **WHEN** a notificação sobe fora do ambiente completo, sem configurar canal nem saúde de correio
- **THEN** o canal utilizado é o de log
- **AND** o endpoint de saúde não depende de servidor de correio

### Requirement: Demonstração executável do zero

Um único comando de demonstração SHALL, a partir de volumes vazios:
- preparar o `.env` a partir do `.env.example` quando ele não existir;
- subir o ambiente completo até o estado saudável;
- comprovar o fluxo pela interface pública dos serviços, sem escrita direta em banco.

O fluxo comprovado SHALL incluir:
- a autenticação de um usuário de demonstração;
- a criação de uma consulta cujo horário esteja estritamente depois do instante da criação e no máximo 24 horas depois dele;
- o e-mail de agendamento na caixa de entrada;
- a consulta legível no histórico;
- um disparo do lembrete D-1 com o e-mail de lembrete na caixa de entrada.

Ao final, o comando SHALL exibir endereços e credenciais de demonstração.

Consulta de demonstração é a que atende simultaneamente a:
- paciente e médico de demonstração;
- marcador de observações de demonstração;
- status agendado ou confirmado;
- horário na janela das próximas 24 horas.

Consultas que não atendem a todos esses critérios SHALL ser ignoradas, inclusive as do mesmo paciente. A reexecução com o ambiente já em pé SHALL reaproveitar a consulta de demonstração existente e SHALL NOT criar outra; mais de uma consulta de demonstração na janela SHALL encerrar o comando com código diferente de zero. O comando SHALL recusar a execução, antes de alterar o ambiente, quando faltar pré-requisito do host. Qualquer verificação que falhe SHALL encerrar com código diferente de zero, indicando a etapa.

#### Scenario: Demonstração do zero termina com sucesso
- **WHEN** um desenvolvedor remove o ambiente com seus volumes e executa o comando de demonstração
- **THEN** o comando termina com código zero
- **AND** a caixa de entrada contém o e-mail de agendamento e o e-mail de lembrete do paciente da consulta criada
- **AND** a consulta criada é legível no histórico
- **AND** a saída exibe os endereços dos serviços e as credenciais de demonstração

#### Scenario: Consulta de demonstração fica na janela do lembrete
- **WHEN** o comando de demonstração cria a consulta
- **THEN** o horário dela está estritamente depois do instante da criação e no máximo 24 horas depois dele
- **AND** o disparo do lembrete logo em seguida a considera elegível

#### Scenario: Reexecução não duplica a consulta
- **WHEN** o comando de demonstração é executado de novo com o ambiente já em pé e a consulta de demonstração ainda na janela
- **THEN** o comando termina com código zero, reaproveitando a consulta com o mesmo identificador
- **AND** a quantidade de consultas que atendem a todos os critérios de demonstração continua sendo uma
- **AND** uma consulta do mesmo paciente sem o marcador de demonstração não é reaproveitada nem contada

#### Scenario: Pré-requisito ausente é recusado antes de alterar o ambiente
- **WHEN** o comando de demonstração é executado sem um dos pré-requisitos do host
- **THEN** ele termina com código diferente de zero, identificando o pré-requisito
- **AND** nenhum container, volume ou arquivo `.env` é criado por essa execução

#### Scenario: Verificação que falha interrompe a demonstração
- **WHEN** uma etapa da comprovação do fluxo não é satisfeita no prazo
- **THEN** o comando termina com código diferente de zero, indicando a etapa e a última resposta recebida

### Requirement: Configuração do ambiente verificada estruturalmente

O build SHALL verificar a configuração do ambiente completo sem subir containers e SHALL reprovar desvio das garantias de imagem, de dependência saudável, de isolamento de database, de perfis e do canal de correio. A verificação SHALL identificar o arquivo e a regra violada.

#### Scenario: Configuração real atende
- **WHEN** a verificação estrutural é executada sobre os arquivos reais do repositório
- **THEN** nenhuma violação é apontada

#### Scenario: Desvio de imagem ou de ambiente é recusado
- **WHEN** a configuração passa a executar uma aplicação como root, a omitir a verificação de saúde, a iniciar um serviço sem esperar dependência saudável, a compartilhar database entre serviços, a omitir um perfil obrigatório, a apontar a notificação para outro canal ou a embutir o segredo de tokens
- **THEN** a verificação estrutural falha identificando o arquivo e a regra violada
