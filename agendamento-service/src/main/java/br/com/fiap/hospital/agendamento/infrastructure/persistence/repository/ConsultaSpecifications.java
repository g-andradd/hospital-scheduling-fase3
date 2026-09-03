package br.com.fiap.hospital.agendamento.infrastructure.persistence.repository;

import br.com.fiap.hospital.agendamento.domain.FiltroDeConsultas;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.entity.ConsultaEntity;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/**
 * Traduz o filtro de dominio para criterios de banco.
 *
 * <p>Cada criterio informado vira predicado SQL; ausentes nao entram na clausula. O
 * filtro nunca e aplicado em memoria sobre o resultado — trazer a tabela inteira para
 * descartar linhas em Java e o mesmo erro que a query de conflito existe para evitar.
 */
public final class ConsultaSpecifications {

    private ConsultaSpecifications() {}

    public static Specification<ConsultaEntity> de(FiltroDeConsultas filtro) {
        return (raiz, consulta, cb) -> {
            List<Predicate> predicados = new ArrayList<>();

            if (filtro.pacienteId() != null) {
                predicados.add(cb.equal(raiz.get("pacienteId"), filtro.pacienteId()));
            }
            if (filtro.medicoId() != null) {
                predicados.add(cb.equal(raiz.get("medicoId"), filtro.medicoId()));
            }
            if (!filtro.status().isEmpty()) {
                predicados.add(raiz.get("status").in(filtro.status()));
            }
            if (filtro.de() != null) {
                predicados.add(cb.greaterThanOrEqualTo(raiz.get("dataHora"), filtro.de()));
            }
            if (filtro.ate() != null) {
                predicados.add(cb.lessThanOrEqualTo(raiz.get("dataHora"), filtro.ate()));
            }
            return cb.and(predicados.toArray(Predicate[]::new));
        };
    }
}
