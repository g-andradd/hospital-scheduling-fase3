package br.com.fiap.hospital.agendamento.fake;

import br.com.fiap.hospital.agendamento.domain.Consulta;
import br.com.fiap.hospital.agendamento.domain.FiltroDeConsultas;
import br.com.fiap.hospital.agendamento.domain.PeriodoConsulta;
import br.com.fiap.hospital.agendamento.domain.port.ConsultaRepositoryPort;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Repositorio de consultas em memoria.
 *
 * <p>E um fake, nao um mock: a filtragem por periodo e por status ativo acontece de
 * verdade sobre o que foi gravado. Um mock responderia o que o teste mandasse responder
 * e nunca poderia contradizer o caso de uso — que e justamente o que se quer de um
 * teste de regra de negocio.
 *
 * <p>O adaptador do M02 fara esta mesma filtragem em SQL. O contrato e o mesmo; muda
 * apenas onde ela roda.
 */
public class ConsultaRepositoryFake implements ConsultaRepositoryPort {

    private final Map<UUID, Consulta> armazenadas = new LinkedHashMap<>();

    @Override
    public Consulta salvar(Consulta consulta) {
        armazenadas.put(consulta.id(), consulta);
        return consulta;
    }

    @Override
    public Optional<Consulta> buscarPorId(UUID id) {
        return Optional.ofNullable(armazenadas.get(id));
    }

    @Override
    public List<Consulta> buscarAtivasDoMedicoNoPeriodo(UUID medicoId, PeriodoConsulta periodo) {
        return ativasQueSobrepoem(periodo).stream()
                .filter(c -> c.medicoId().equals(medicoId))
                .toList();
    }

    @Override
    public List<Consulta> buscarAtivasDoPacienteNoPeriodo(UUID pacienteId, PeriodoConsulta periodo) {
        return ativasQueSobrepoem(periodo).stream()
                .filter(c -> c.pacienteId().equals(pacienteId))
                .toList();
    }

    @Override
    public List<Consulta> listar(FiltroDeConsultas filtro) {
        return armazenadas.values().stream().filter(filtro::aceita).toList();
    }

    private List<Consulta> ativasQueSobrepoem(PeriodoConsulta periodo) {
        List<Consulta> encontradas = new ArrayList<>();
        for (Consulta consulta : armazenadas.values()) {
            if (consulta.ativa() && consulta.periodo().sobrepoe(periodo)) {
                encontradas.add(consulta);
            }
        }
        return encontradas;
    }

    /** Insere direto, sem passar pelas regras do caso de uso. Para montar cenarios. */
    public ConsultaRepositoryFake com(Consulta... consultas) {
        for (Consulta consulta : consultas) {
            armazenadas.put(consulta.id(), consulta);
        }
        return this;
    }

    public int quantidade() {
        return armazenadas.size();
    }

    public List<Consulta> todas() {
        return List.copyOf(armazenadas.values());
    }
}
