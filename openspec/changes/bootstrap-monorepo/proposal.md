# Bootstrap do monorepo

## Por que

O repositório hoje tem apenas documentação. Nada compila, nada sobe. Antes de escrever a primeira regra de negócio, o projeto precisa de um esqueleto que já imponha as decisões estruturais — cinco módulos Maven, versionamento centralizado, e a infraestrutura de apoio (Postgres e RabbitMQ) subindo por um comando.

Fazer isso primeiro, e em separado, evita o erro clássico de descobrir no meio do M04 que o módulo compartilhado não estava sendo herdado direito, ou que o Flyway aponta para o database errado.

## O que muda

- POM pai na raiz, com `dependencyManagement`, plugins e propriedades comuns
- Cinco módulos que compilam vazios: `shared-contracts`, `shared-security`, `agendamento-service`, `notificacao-service`, `historico-service`
- `docker-compose.yml` com Postgres 16 (três databases) e RabbitMQ 3.13 com plugin de management
- `.env.example`, `.editorconfig`
- README com a seção de execução da infraestrutura

## O que NÃO muda

Nenhuma entidade, controller, listener, migration de negócio ou regra. Este change entrega estrutura, não comportamento.

## Impacto

- Capability afetada: `operacao-do-ambiente`
- Módulos: todos (criação)
- Branch: `feature/m00-bootstrap-monorepo`
- Release alvo: `0.1.0`
