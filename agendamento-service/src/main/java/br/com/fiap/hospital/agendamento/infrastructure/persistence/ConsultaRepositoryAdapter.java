package br.com.fiap.hospital.agendamento.infrastructure.persistence;

import br.com.fiap.hospital.agendamento.domain.Consulta;
import br.com.fiap.hospital.agendamento.domain.FiltroDeConsultas;
import br.com.fiap.hospital.agendamento.domain.Pagina;
import br.com.fiap.hospital.agendamento.domain.PeriodoConsulta;
import br.com.fiap.hospital.agendamento.domain.exception.AlteracaoConcorrenteException;
import br.com.fiap.hospital.agendamento.domain.port.ConsultaRepositoryPort;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.entity.ConsultaEntity;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.mapper.ConsultaMapper;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.repository.ConsultaJpaRepository;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.repository.ConsultaSpecifications;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.DataAccessException;
import java.sql.SQLException;
import org.postgresql.util.PSQLException;
import br.com.fiap.hospital.agendamento.domain.exception.ConflitoDeAgendaException;
import org.springframework.stereotype.Repository;

/**
 * Adaptador de persistencia de consultas.
 *
 * <p>Ponto central: {@code buscarPorId} devolve um objeto de dominio <b>desacoplado</b>
 * da entidade gerenciada. O caso de uso muta esse objeto solto, o que nao agenda
 * escrita nenhuma. A entidade gerenciada so e tocada dentro de {@code salvar} — logo,
 * uma operacao recusada nunca chega a produzir escrita no flush do commit.
 */
@Repository
public class ConsultaRepositoryAdapter implements ConsultaRepositoryPort {

    private final ConsultaJpaRepository repositorio;
    private final ConsultaMapper mapper;

    public ConsultaRepositoryAdapter(ConsultaJpaRepository repositorio, ConsultaMapper mapper) {
        this.repositorio = repositorio;
        this.mapper = mapper;
    }

    @Override
    public Consulta salvar(Consulta consulta) {
        try {
            ConsultaEntity entidade = repositorio.findById(consulta.id())
                    .map(existente -> {
                        mapper.copiarParaEntidade(consulta, existente);
                        return existente;
                    })
                    .orElseGet(() -> mapper.novaEntidade(consulta));

            return mapper.paraDominio(repositorio.saveAndFlush(entidade));
        } catch (OptimisticLockingFailureException e) {
            throw new AlteracaoConcorrenteException(consulta.id());
        } catch (DataAccessException e) {
            for (Throwable causa=e; causa!=null; causa=causa.getCause()) {
                if (causa instanceof SQLException sql &&
                        ("40P01".equals(sql.getSQLState()) || "40001".equals(sql.getSQLState())))
                    throw new AlteracaoConcorrenteException(consulta.id());
                if (causa instanceof PSQLException violacao && "23P01".equals(violacao.getSQLState())
                        && violacao.getServerErrorMessage()!=null) {
                    if ("ex_consulta_medico_periodo".equals(violacao.getServerErrorMessage().getConstraint()))
                        throw ConflitoDeAgendaException.doMedico(consulta.periodo().toString());
                    if ("ex_consulta_paciente_periodo".equals(violacao.getServerErrorMessage().getConstraint()))
                        throw ConflitoDeAgendaException.doPaciente(consulta.periodo().toString());
                }
            }
            throw e;
        
        }
    }

    @Override
    public Optional<Consulta> buscarPorId(UUID id) {
        return repositorio.findById(id).map(mapper::paraDominio);
    }

    @Override
    public List<Consulta> buscarAtivasDoMedicoNoPeriodo(UUID medicoId, PeriodoConsulta periodo) {
        return repositorio
                .buscarAtivasDoMedicoNoPeriodo(medicoId, periodo.inicio(), periodo.fim())
                .stream()
                .map(mapper::paraDominio)
                .toList();
    }

    @Override
    public List<Consulta> buscarAtivasDoPacienteNoPeriodo(UUID pacienteId, PeriodoConsulta periodo) {
        return repositorio
                .buscarAtivasDoPacienteNoPeriodo(pacienteId, periodo.inicio(), periodo.fim())
                .stream()
                .map(mapper::paraDominio)
                .toList();
    }

    @Override
    public Pagina<Consulta> listar(FiltroDeConsultas filtro) {
        // A ordenacao e explicita porque paginacao sem ordem estavel repete e omite
        // elementos entre paginas consecutivas.
        var pagina = repositorio.findAll(
                ConsultaSpecifications.de(filtro),
                PageRequest.of(filtro.pagina(), filtro.tamanho(),
                        Sort.by(Sort.Direction.ASC, "dataHora", "id")));

        return new Pagina<>(
                pagina.getContent().stream().map(mapper::paraDominio).toList(),
                filtro.pagina(),
                filtro.tamanho(),
                pagina.getTotalElements());
    }
}
