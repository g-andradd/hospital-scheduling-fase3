# Roteiro de demonstração

Apresentação de **6 min 30 s** do sistema em funcionamento, cobrindo o fluxo síncrono REST,
a consulta GraphQL do histórico, a mensageria, a notificação por e-mail e a correlação dos
logs dos três serviços.

O relógio só começa com o ambiente já saudável e as abas abertas. A preparação está na seção
seguinte e **não entra na contagem**: construir as imagens pela primeira vez leva vários
minutos e não é o que a banca assiste.

---

## Pré-demonstração

Fora do relógio. Faça isto antes de começar a apresentar.

| Passo | Comando ou endereço | O que confirmar |
|---|---|---|
| 1 | `make demo` | Termina com `[demo] Ambiente pronto` e imprime `consultaId`, `emailAgendamentoId`, `emailLembreteId` e `lembretesEnviados` |
| 2 | `make ps` | Os seis containers de pé: `hospital-postgres`, `hospital-rabbitmq`, `hospital-mailpit`, `hospital-agendamento`, `hospital-notificacao`, `hospital-historico` |
| 3 | http://localhost:8081/swagger-ui.html | Swagger do agendamento carrega e lista os endpoints |
| 4 | http://localhost:8083/graphiql | GraphiQL do histórico carrega |
| 5 | http://localhost:15672 | RabbitMQ Management aceita `hospital` / `hospital` |
| 6 | http://localhost:8025 | Mailpit abre a caixa de entrada |

Deixe as quatro abas abertas e um terminal livre para os logs. `make demo` é idempotente:
se rodar de novo com o ambiente de pé, ele reaproveita a consulta de demonstração em vez de
criar outra. **Não derrube os volumes antes de apresentar** — reconstruir o ambiente do zero
custa mais tempo do que a apresentação inteira.

Credenciais do profile `demo`, todas com a senha `Senha@123`:
`medico@hospital.com`, `enfermeiro@hospital.com`, `paciente@hospital.com` e
`paciente2@hospital.com`.

---

## Apresentação cronometrada

| # | Duração | Ação | Comando ou URL | O que aparece na tela | Alternativa curta |
|---:|---|---|---|---|---|
| 1 | 1:00 | Autenticar como enfermeiro e criar uma consulta **dentro das próximas 24 horas** — o lembrete D-1 do bloco 6 só alcança consultas nessa janela, e uma data além dela faz aquele bloco devolver `lembretesEnviados: 0` sem e-mail nenhum | http://localhost:8081/swagger-ui.html — `POST /auth/login` e depois `POST /api/v1/consultas` com `dataHora` entre 2 e 3 horas à frente, como o `make demo` já faz | `accessToken`, `expiresIn` e `perfil`; a consulta volta `201` com `id` e `status: AGENDADA` | Se o Swagger demorar, mostre o `consultaId` que o `make demo` já imprimiu — ele nasce dentro da janela — e siga para o bloco 2 |
| 2 | 1:00 | Mostrar o evento atravessando o broker | http://localhost:15672 — aba *Queues*, filas `notificacao.consultas` e `historico.consultas` | Nas colunas de totais, as duas filas com o mesmo número em *publicadas* e *entregues* — **o campo *Ready* fica em 0, porque o consumo é imediato** —, e as DLQs `notificacao.consultas.dlq` e `historico.consultas.dlq` sem nenhuma mensagem | Se a interface do broker não abrir, mostre no log do agendamento a linha do relay publicando o evento |
| 3 | 1:00 | Mostrar a notificação chegando ao paciente | http://localhost:8025 | E-mail com assunto `Consulta agendada` para `paciente@hospital.com` | Se o Mailpit não responder, mostre o registro de envio no log do serviço de notificação |
| 4 | 1:15 | Consultar o histórico projetado, com filtro por período | http://localhost:8083/graphiql — `consultasDoPaciente(pacienteId:, filtro:)` com `TODAS` e depois `FUTURAS` | A mesma consulta aparece no read model; `FUTURAS` devolve só a que ainda não ocorreu | Se o GraphiQL travar, execute a mesma query por `POST http://localhost:8083/graphql` no terminal |
| 5 | 1:15 | Seguir o mesmo `correlationId` nos três serviços | `make logs` e procure o identificador da requisição do bloco 1 nos containers `agendamento`, `notificacao` e `historico` | O mesmo `correlationId` aparece nos logs JSON dos três, ligando request HTTP, publicação e projeção | Se o log ficar poluído, mostre o campo `correlationId` no corpo de erro de uma requisição 401 do Swagger |
| 6 | 1:00 | Disparar o lembrete D-1 e mostrar o segundo e-mail | `POST http://localhost:8082/internal/lembretes/executar` com o token do enfermeiro, depois http://localhost:8025 | Resposta com `lembretesEnviados` numérico e o e-mail `Lembrete de consulta` na caixa de entrada | Se o disparo falhar, mostre o `emailLembreteId` que o `make demo` já havia registrado |

**Total: 6:30**, dentro da janela de 5 a 8 minutos, com folga para uma pergunta no meio.

---

## Encerramento

Feche dizendo o que a demonstração provou, sem repetir o que já apareceu na tela:

- **segurança** — autenticação por JWT e autorização por perfil, com a matriz de
  `docs/02-especificacao-funcional.md` §3 testada célula a célula;
- **mensageria** — publicação transacional por outbox, entrega ao-menos-uma-vez e
  idempotência por `eventId` nos dois consumidores;
- **GraphQL** — histórico materializado por projeção de eventos, com filtro por período;
- **verificabilidade** — `bash scripts/auditoria.sh` percorre os 30 requisitos e o
  `mvn clean verify` aplica os gates de execução, infraestrutura real e cobertura.

Se sobrar tempo, mostre a saída de `bash scripts/auditoria.sh`: uma linha por requisito, com
a evidência versionada de cada um.

---

## Documentos relacionados

- [Relatório técnico](relatorio-tecnico.md)
- [Especificação funcional](02-especificacao-funcional.md)
- [Arquitetura](01-arquitetura.md)
