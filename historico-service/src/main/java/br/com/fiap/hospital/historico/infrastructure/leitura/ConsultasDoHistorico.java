package br.com.fiap.hospital.historico.infrastructure.leitura;

import br.com.fiap.hospital.historico.infrastructure.graphql.FiltroConsulta;
import br.com.fiap.hospital.historico.infrastructure.graphql.PeriodoFiltro;
import br.com.fiap.hospital.historico.infrastructure.persistence.entity.ConsultaHistoricoEntity;
import br.com.fiap.hospital.historico.infrastructure.persistence.repository.ConsultaHistoricoJpaRepository;
import jakarta.persistence.criteria.Predicate;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Leitura filtrada do snapshot.
 *
 * <p>Todo predicado vai para o SQL. Recortar em memoria daria o mesmo resultado hoje e o
 * resultado errado no dia em que existir limite ou paginacao — o corte aconteceria depois
 * do corte do banco, sobre uma amostra que ja perdeu linhas.
 *
 * <p>A ordenacao e {@code data_hora, id}. O segundo campo nao e criterio de relevancia:
 * sem ele, dois registros no mesmo instante sairiam em ordem indefinida, e um teste de
 * ordenacao passaria ou falharia conforme o plano escolhido pelo PostgreSQL.
 */
@Component
public class ConsultasDoHistorico {

    private static final Sort ORDEM = Sort.by(Sort.Order.asc("dataHora"), Sort.Order.asc("id"));

    private final ConsultaHistoricoJpaRepository repositorio;
    private final Clock clock;

    ConsultasDoHistorico(ConsultaHistoricoJpaRepository repositorio, Clock clock) {
        this.repositorio = repositorio;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ConsultaHistoricoEntity> doPaciente(UUID pacienteId, FiltroConsulta filtro) {
        return repositorio.findAll(porDimensao("pacienteId", pacienteId).and(recorte(filtro)), ORDEM);
    }

    @Transactional(readOnly = true)
    public List<ConsultaHistoricoEntity> doMedico(UUID medicoId, FiltroConsulta filtro) {
        return repositorio.findAll(porDimensao("medicoId", medicoId).and(recorte(filtro)), ORDEM);
    }

    @Transactional(readOnly = true)
    public Optional<ConsultaHistoricoEntity> porId(UUID id) {
        return repositorio.findById(id);
    }

    private Specification<ConsultaHistoricoEntity> porDimensao(String campo, UUID valor) {
        return (raiz, consulta, cb) -> cb.equal(raiz.get(campo), valor);
    }

    /** Periodo, intervalo e status entram por conjuncao; lista de status vazia nao restringe. */
    private Specification<ConsultaHistoricoEntity> recorte(FiltroConsulta filtro) {
        FiltroConsulta efetivo = filtro == null ? FiltroConsulta.VAZIO : filtro;
        return (raiz, consulta, cb) -> {
            List<Predicate> predicados = new ArrayList<>();
            OffsetDateTime agora = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);

            PeriodoFiltro periodo = efetivo.periodo();
            if (periodo == PeriodoFiltro.FUTURAS) {
                predicados.add(cb.greaterThanOrEqualTo(raiz.get("dataHora"), agora));
            } else if (periodo == PeriodoFiltro.PASSADAS) {
                predicados.add(cb.lessThan(raiz.get("dataHora"), agora));
            }
            if (efetivo.de() != null) {
                predicados.add(cb.greaterThanOrEqualTo(raiz.get("dataHora"), efetivo.de()));
            }
            if (efetivo.ate() != null) {
                predicados.add(cb.lessThan(raiz.get("dataHora"), efetivo.ate()));
            }
            if (!efetivo.status().isEmpty()) {
                predicados.add(raiz.get("status").in(efetivo.status()));
            }
            return cb.and(predicados.toArray(Predicate[]::new));
        };
    }
}
