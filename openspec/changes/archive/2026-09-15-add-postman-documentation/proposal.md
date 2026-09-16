## Why

A entrega precisa oferecer à banca um caminho reproduzível para validar REST, GraphQL, autenticação, autorização e erros sem montar requisições ou copiar tokens manualmente. A documentação arquitetural também precisa chegar ao estado final de leitura, com diagramas navegáveis e as sete decisões já tomadas consolidadas e rastreáveis aos changes que as originaram.

## What Changes

- Adicionar uma collection Postman v2.1 e um environment local versionados, organizados nas cinco pastas obrigatórias do M13.
- Fazer os quatro logins do profile `demo` salvarem seus próprios tokens e encadear os requests pelo Runner, sem edição manual, com dados dinâmicos e asserções `pm.test()` em toda requisição.
- Cobrir o fluxo principal de agendamento e histórico GraphQL, além de cenários explícitos de 401, 403, 422, 409, 404 e 400.
- Verificar a collection em dois níveis: estrutura e rastreabilidade no `quality-gates`, e execução real completa contra o ambiente Docker Compose pelo Newman oficial.
- Consolidar o README como porta de entrada da entrega, preservando e complementando os diagramas Mermaid, a execução local e o catálogo das APIs.
- Uniformizar os sete ADRs em `Contexto / Decisão / Alternativas / Consequências / Status`, criar `docs/adr/README.md` e ligar cada decisão ao archive OpenSpec de origem.

Esta change fecha o requisito do enunciado de fornecer collections para teste e consolida a documentação de arquitetura, endpoints, configuração e execução. É transversal às seis capabilities, não altera RF/RNF já implementado e tem como release alvo `1.0.0`.

### O que não muda

- Nenhum endpoint, schema GraphQL, regra de negócio, matriz de autorização, contrato AMQP, migration ou código de produção.
- O Compose e o `make demo` do M12 não ganham novo comportamento.
- O relatório técnico, o roteiro de apresentação, o script de auditoria e o fechamento da release continuam exclusivos do M14.

## Capabilities

### New Capabilities

- Nenhuma.

### Modified Capabilities

- `operacao-do-ambiente`: acrescenta o pacote Postman executável e a documentação consolidada como artefatos operacionais verificáveis da entrega.

## Impact

- Novos arquivos versionados sob `postman/` para a collection e o environment local.
- Novas verificações puramente técnicas em `quality-gates`, sem dependência dos módulos de código.
- Atualizações em `README.md`, nos sete arquivos de `docs/adr/` e em um novo índice `docs/adr/README.md`.
- Execução de aceite sobre o Compose do M12 com o runner em container `postman/newman:6.1.3-alpine`; sem dependência Node/npm no reactor Maven.
