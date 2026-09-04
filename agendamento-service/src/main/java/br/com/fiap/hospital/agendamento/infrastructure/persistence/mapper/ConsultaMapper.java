package br.com.fiap.hospital.agendamento.infrastructure.persistence.mapper;

import br.com.fiap.hospital.agendamento.domain.Consulta;
import br.com.fiap.hospital.agendamento.domain.PeriodoConsulta;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.entity.ConsultaEntity;
import org.springframework.stereotype.Component;

/**
 * Mapeamento manual entre a consulta persistida e a de dominio.
 *
 * <p>Duas decisoes moram aqui.
 *
 * <p><b>Reidratacao usa {@code reconstituir}, nunca {@code agendar}.</b> A fabrica de
 * agendamento aplica a regra de periodo futuro; usa-la ao carregar faria toda consulta
 * passada estourar na leitura, e o sistema ficaria incapaz de ler o proprio historico.
 * Invariante de transicao pertence a operacao de negocio, nao a leitura: uma consulta
 * gravada e fato consumado.
 *
 * <p><b>{@code paraDominio} devolve objeto desacoplado.</b> O resultado nao e a
 * entidade gerenciada, e sim uma copia solta, invisivel ao {@code EntityManager}.
 * Muta-la nao agenda escrita — que e o que impede uma operacao recusada de ser
 * persistida no flush do commit.
 */
@Component
public class ConsultaMapper {

    public Consulta paraDominio(ConsultaEntity entidade) {
        return Consulta.reconstituir(
                entidade.getId(),
                entidade.getPacienteId(),
                entidade.getMedicoId(),
                entidade.getRegistradoPorId(),
                new PeriodoConsulta(entidade.getDataHora(), entidade.getDuracaoMinutos()),
                entidade.getStatus(),
                entidade.getObservacoes(),
                entidade.getMotivoCancelamento(),
                entidade.getCriadoEm(),
                entidade.getAtualizadoEm());
    }

    /** Cria a entidade de uma consulta que ainda nao existe no banco. */
    public ConsultaEntity novaEntidade(Consulta consulta) {
        ConsultaEntity entidade = new ConsultaEntity(
                consulta.id(), consulta.pacienteId(), consulta.medicoId(), consulta.registradoPorId());
        copiarParaEntidade(consulta, entidade);
        return entidade;
    }

    /** Copia o estado do dominio sobre a entidade gerenciada. */
    public void copiarParaEntidade(Consulta consulta, ConsultaEntity entidade) {
        entidade.copiarDe(
                consulta.medicoId(),
                consulta.periodo().inicio(),
                consulta.periodo().duracaoMinutos(),
                consulta.status(),
                consulta.observacoes(),
                consulta.motivoCancelamento(),
                consulta.criadoEm(),
                consulta.atualizadoEm());
    }
}
