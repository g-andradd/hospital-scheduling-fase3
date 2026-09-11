# autenticacao-e-autorizacao

## Purpose

Estabelece quem é o usuário que faz cada requisição e o que o perfil dele permite. Cobre a autenticação por e-mail e senha, o token que carrega a identidade entre requisições e serviços, a matriz de permissões por perfil e a regra de propriedade que impede um paciente de alcançar recursos de outro.

## Requirements

### Requirement: Autenticação por e-mail e senha

O sistema SHALL autenticar um usuário a partir de e-mail e senha, devolvendo um token de acesso, o prazo de validade dele e o perfil do usuário.

O token SHALL carregar a identidade do usuário, o e-mail, o perfil e, quando aplicável, o identificador de paciente ou de médico, além dos instantes de emissão e de expiração.

Senhas SHALL ser armazenadas apenas como hash. Nenhuma resposta, log ou mensagem de erro SHALL conter a senha ou o hash dela.

#### Scenario: Autenticação bem-sucedida
- **WHEN** são enviadas credenciais corretas de um usuário ativo
- **THEN** a resposta apresenta um token de acesso, o prazo de validade e o perfil
- **AND** o corpo não contém a senha nem o hash dela

#### Scenario: Token carrega a identidade do usuário
- **WHEN** um usuário se autentica
- **THEN** o token emitido identifica o usuário, o e-mail e o perfil
- **AND** para um paciente, também o identificador de paciente

#### Scenario: Senha incorreta é recusada
- **WHEN** é enviada uma senha incorreta para um e-mail existente
- **THEN** a autenticação é recusada por credencial inválida

#### Scenario: E-mail inexistente é recusado da mesma forma
- **WHEN** é enviada uma credencial cujo e-mail não existe
- **THEN** a autenticação é recusada com resposta indistinguível da de senha incorreta
- **AND** a resposta não permite concluir se o e-mail existe

#### Scenario: Usuário inativo é recusado
- **WHEN** são enviadas credenciais corretas de um usuário inativo
- **THEN** a autenticação é recusada

#### Scenario: A recusa não vaza pelo tempo de resposta
- **WHEN** são comparadas muitas tentativas com e-mail inexistente e com senha incorreta
- **THEN** os tempos de resposta não permitem distinguir os dois casos

### Requirement: Acesso sem credencial válida

Requisição a recurso protegido sem token, ou com token que o sistema não aceita, SHALL ser recusada como não autenticada, no formato Problem Detail.

Um token SHALL ser recusado quando estiver expirado, quando a assinatura não conferir, quando estiver malformado ou quando lhe faltar informação necessária para identificar o usuário.

#### Scenario: Requisição sem token
- **WHEN** um recurso protegido é acessado sem credencial
- **THEN** a resposta é `401` no formato Problem Detail

#### Scenario: Token expirado
- **WHEN** um recurso protegido é acessado com token cujo prazo de validade passou
- **THEN** a resposta é `401` no formato Problem Detail

#### Scenario: Assinatura adulterada
- **WHEN** um recurso protegido é acessado com token cuja assinatura não confere
- **THEN** a resposta é `401` no formato Problem Detail

#### Scenario: Token malformado
- **WHEN** um recurso protegido é acessado com credencial que não é um token válido
- **THEN** a resposta é `401` no formato Problem Detail
- **AND** a resposta não expõe detalhe interno de implementação

#### Scenario: Token sem informação de identidade
- **WHEN** um recurso protegido é acessado com token assinado corretamente mas sem o perfil do usuário
- **THEN** a resposta é `401` no formato Problem Detail

#### Scenario: Recursos públicos permanecem acessíveis
- **WHEN** a autenticação, a verificação de saúde ou a especificação da API são acessadas sem credencial
- **THEN** o acesso é permitido

#### Scenario: Nenhuma sessão é criada
- **WHEN** requisições autenticadas são feitas em sequência
- **THEN** cada uma é autenticada apenas pelo token apresentado
- **AND** o servidor não mantém estado de sessão entre elas

### Requirement: Autorização por perfil no agendamento

O sistema SHALL aplicar a cada operação do agendamento a permissão do perfil do usuário autenticado. Perfil sem permissão para a operação SHALL receber recusa por acesso negado, no formato Problem Detail.

Existem três perfis: `MEDICO`, `ENFERMEIRO` e `PACIENTE`.

#### Scenario: MEDICO registra consulta
- **WHEN** um usuário com perfil `MEDICO` registra uma consulta
- **THEN** a operação é permitida

#### Scenario: ENFERMEIRO registra consulta
- **WHEN** um usuário com perfil `ENFERMEIRO` registra uma consulta
- **THEN** a operação é permitida

#### Scenario: PACIENTE não registra consulta
- **WHEN** um usuário com perfil `PACIENTE` tenta registrar uma consulta
- **THEN** a resposta é `403` no formato Problem Detail

#### Scenario: MEDICO altera consulta
- **WHEN** um usuário com perfil `MEDICO` altera uma consulta
- **THEN** a operação é permitida

#### Scenario: ENFERMEIRO altera consulta
- **WHEN** um usuário com perfil `ENFERMEIRO` altera uma consulta
- **THEN** a operação é permitida

#### Scenario: PACIENTE não altera consulta
- **WHEN** um usuário com perfil `PACIENTE` tenta alterar uma consulta
- **THEN** a resposta é `403` no formato Problem Detail

#### Scenario: MEDICO cancela consulta
- **WHEN** um usuário com perfil `MEDICO` cancela uma consulta
- **THEN** a operação é permitida

#### Scenario: ENFERMEIRO cancela consulta
- **WHEN** um usuário com perfil `ENFERMEIRO` cancela uma consulta
- **THEN** a operação é permitida

#### Scenario: PACIENTE não cancela consulta
- **WHEN** um usuário com perfil `PACIENTE` tenta cancelar uma consulta
- **THEN** a resposta é `403` no formato Problem Detail

#### Scenario: MEDICO confirma qualquer consulta
- **WHEN** um usuário com perfil `MEDICO` confirma uma consulta de qualquer paciente
- **THEN** a operação é permitida

#### Scenario: ENFERMEIRO confirma qualquer consulta
- **WHEN** um usuário com perfil `ENFERMEIRO` confirma uma consulta de qualquer paciente
- **THEN** a operação é permitida

#### Scenario: PACIENTE confirma a própria consulta
- **WHEN** um usuário com perfil `PACIENTE` confirma uma consulta de que é o paciente
- **THEN** a operação é permitida

#### Scenario: MEDICO recupera qualquer consulta
- **WHEN** um usuário com perfil `MEDICO` recupera uma consulta de qualquer paciente
- **THEN** a operação é permitida

#### Scenario: ENFERMEIRO recupera qualquer consulta
- **WHEN** um usuário com perfil `ENFERMEIRO` recupera uma consulta de qualquer paciente
- **THEN** a operação é permitida

#### Scenario: PACIENTE recupera a própria consulta
- **WHEN** um usuário com perfil `PACIENTE` recupera uma consulta de que é o paciente
- **THEN** a operação é permitida

#### Scenario: MEDICO lista consultas
- **WHEN** um usuário com perfil `MEDICO` lista consultas
- **THEN** a operação é permitida e nenhum recorte por identidade é imposto

#### Scenario: ENFERMEIRO lista consultas
- **WHEN** um usuário com perfil `ENFERMEIRO` lista consultas
- **THEN** a operação é permitida e nenhum recorte por identidade é imposto

#### Scenario: PACIENTE lista consultas
- **WHEN** um usuário com perfil `PACIENTE` lista consultas
- **THEN** a operação é permitida com recorte às próprias consultas

#### Scenario: Autenticação é pública para os três perfis
- **WHEN** a autenticação é acessada sem credencial
- **THEN** o acesso é permitido, qualquer que seja o perfil que venha a se autenticar

### Requirement: Regra de propriedade do paciente

Um usuário com perfil `PACIENTE` SHALL alcançar apenas recursos de que ele próprio é o paciente.

A regra SHALL ser aplicada de forma que uma operação nova sobre consultas não possa ficar sem ela por omissão.

#### Scenario: Paciente não recupera consulta de terceiro
- **WHEN** um paciente recupera uma consulta de que outro paciente é o titular
- **THEN** a resposta é `403` no formato Problem Detail

#### Scenario: Paciente não confirma consulta de terceiro
- **WHEN** um paciente confirma uma consulta de que outro paciente é o titular
- **THEN** a resposta é `403` no formato Problem Detail

#### Scenario: Filtro de paciente é forçado à própria identidade
- **WHEN** um paciente lista consultas informando o identificador de outro paciente
- **THEN** a resposta apresenta apenas as consultas do próprio solicitante
- **AND** a resposta não revela se o outro paciente existe

#### Scenario: Listagem sem filtro também é recortada
- **WHEN** um paciente lista consultas sem informar filtro de paciente
- **THEN** a resposta apresenta apenas as próprias consultas

#### Scenario: Operação nova sem a regra de propriedade é recusada
- **WHEN** uma operação sobre consultas é exposta sem declarar a permissão exigida
- **THEN** o acesso a ela é negado por padrão, e não liberado

### Requirement: Erros de autenticação e autorização em Problem Detail

Recusa por falta de autenticação e recusa por falta de permissão SHALL usar o mesmo formato Problem Detail das demais respostas de erro, com `type`, `title`, `status`, `detail`, `instance`, `correlationId` e `timestamp`.

Nenhuma dessas respostas SHALL expor stack trace, nome de classe interna ou qualquer indício sobre a existência de credenciais.

#### Scenario: Recusa por falta de autenticação
- **WHEN** uma requisição é recusada por não estar autenticada
- **THEN** o corpo segue o formato Problem Detail, com `correlationId` e `timestamp`
- **AND** o `type` identifica a categoria de não autenticado

#### Scenario: Recusa por falta de permissão
- **WHEN** uma requisição autenticada é recusada por falta de permissão
- **THEN** o corpo segue o formato Problem Detail, com `correlationId` e `timestamp`
- **AND** o `type` é distinto do usado para falta de autenticação

#### Scenario: Nenhuma recusa expõe detalhe interno
- **WHEN** qualquer recusa de autenticação ou de autorização é produzida
- **THEN** o corpo não contém stack trace, nome de classe nem pacote

### Requirement: Dados de demonstração restritos

Os usuários de demonstração SHALL existir apenas quando o ambiente estiver explicitamente configurado para demonstração, e SHALL NOT existir em qualquer outra configuração.

A restrição SHALL ser verificável por execução, e não apenas por convenção de nomes.

#### Scenario: Ambiente de demonstração tem os usuários
- **WHEN** o ambiente é provisionado na configuração de demonstração
- **THEN** os usuários de demonstração existem e conseguem se autenticar

#### Scenario: Ambiente padrão não tem os usuários
- **WHEN** o ambiente é provisionado sem a configuração de demonstração
- **THEN** nenhum usuário de demonstração existe
- **AND** a autenticação com aquelas credenciais é recusada
