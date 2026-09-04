package br.com.fiap.hospital.agendamento.infrastructure.persistence.repository;

import br.com.fiap.hospital.agendamento.infrastructure.persistence.entity.ConsultaEntity;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConsultaJpaRepository
        extends JpaRepository<ConsultaEntity, UUID>, JpaSpecificationExecutor<ConsultaEntity> {

    /**
     * Sobreposicao de intervalo resolvida no banco.
     *
     * <p>Traducao literal de {@code PeriodoConsulta.sobrepoe}: o periodo e semiaberto,
     * {@code [inicio, fim)}. As duas comparacoes sao ESTRITAS, e e so isso que faz uma
     * consulta que comeca exatamente quando outra termina nao ser conflito — quando
     * {@code data_hora + duracao = :inicio}, a segunda condicao vira
     * {@code :inicio > :inicio}, falsa. Trocar por {@code <=} transformaria toda
     * consulta encaixada em conflito.
     *
     * <p>O filtro de status ativo esta na propria query: consulta cancelada ou
     * realizada nao ocupa agenda e nao pode ser transferida para memoria so para ser
     * descartada depois.
     */
    @Query(value = """
            SELECT * FROM consulta c
             WHERE c.medico_id = :medicoId
               AND c.status IN ('AGENDADA', 'CONFIRMADA')
               AND c.data_hora < :fim
               AND c.data_hora + (c.duracao_minutos * INTERVAL '1 minute') > :inicio
            """, nativeQuery = true)
    List<ConsultaEntity> buscarAtivasDoMedicoNoPeriodo(
            @Param("medicoId") UUID medicoId,
            @Param("inicio") OffsetDateTime inicio,
            @Param("fim") OffsetDateTime fim);

    /** Mesma regra de sobreposicao, aplicada a agenda do paciente. */
    @Query(value = """
            SELECT * FROM consulta c
             WHERE c.paciente_id = :pacienteId
               AND c.status IN ('AGENDADA', 'CONFIRMADA')
               AND c.data_hora < :fim
               AND c.data_hora + (c.duracao_minutos * INTERVAL '1 minute') > :inicio
            """, nativeQuery = true)
    List<ConsultaEntity> buscarAtivasDoPacienteNoPeriodo(
            @Param("pacienteId") UUID pacienteId,
            @Param("inicio") OffsetDateTime inicio,
            @Param("fim") OffsetDateTime fim);
}
