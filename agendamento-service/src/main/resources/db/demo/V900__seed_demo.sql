-- Seed do perfil demo.
--
-- Este arquivo so e visto pelo Flyway quando o perfil demo esta ativo, porque so
-- application-demo.yml acrescenta classpath:db/demo a spring.flyway.locations. Fora do
-- perfil, nao ha codigo de seed carregado que possa errar uma condicao.
--
-- A numeracao alta separa o seed das migrations de schema: elas continuam em V1, V2...
-- e nunca colidem com este arquivo.
--
-- As senhas ficam so como hash BCrypt. A senha em claro correspondente esta em
-- docs/02-especificacao-funcional.md secao 5, que e documentacao para a banca testar.

INSERT INTO usuario (id, nome, email, senha_hash, perfil, ativo, criado_em) VALUES
  ('11111111-1111-1111-1111-111111111111', 'Dr. Joao Lima',   'medico@hospital.com',     '$2a$10$JUU8mSXfivdwzpuhR9norOIR5JKK5EcQiWSwiultOGzapLvxFTLVW', 'MEDICO',     true, now()),
  ('22222222-2222-2222-2222-222222222222', 'Ana Enfermeira',  'enfermeiro@hospital.com', '$2a$10$JUU8mSXfivdwzpuhR9norOIR5JKK5EcQiWSwiultOGzapLvxFTLVW', 'ENFERMEIRO', true, now()),
  ('33333333-3333-3333-3333-333333333333', 'Maria Souza',     'paciente@hospital.com',   '$2a$10$JUU8mSXfivdwzpuhR9norOIR5JKK5EcQiWSwiultOGzapLvxFTLVW', 'PACIENTE',   true, now()),
  ('44444444-4444-4444-4444-444444444444', 'Jose Silva',      'paciente2@hospital.com',  '$2a$10$JUU8mSXfivdwzpuhR9norOIR5JKK5EcQiWSwiultOGzapLvxFTLVW', 'PACIENTE',   true, now());

INSERT INTO medico (id, usuario_id, crm, especialidade) VALUES
  ('aaaaaaaa-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'DF-12345', 'Cardiologia');

INSERT INTO paciente (id, usuario_id, cpf, data_nascimento, telefone) VALUES
  ('bbbbbbbb-0000-0000-0000-000000000001', '33333333-3333-3333-3333-333333333333', '52998224725', '1990-05-12', '+5561999990000'),
  ('bbbbbbbb-0000-0000-0000-000000000002', '44444444-4444-4444-4444-444444444444', '11144477735', '1985-03-01', '+5561988880000');
