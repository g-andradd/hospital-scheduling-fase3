package br.com.fiap.hospital.agendamento.contrato;

import br.com.fiap.hospital.agendamento.domain.port.ConsultaRepositoryPort;
import br.com.fiap.hospital.agendamento.fake.ConsultaRepositoryFake;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;

/** O contrato executado contra o fake em memoria. Sem container, roda no surefire. */
@DisplayName("Contrato de ConsultaRepositoryPort — fake em memoria")
class ConsultaRepositoryFakeTest extends ConsultaRepositoryContractTest {

    private ConsultaRepositoryFake fake;
    private UUID paciente;
    private UUID medico;
    private UUID outroPaciente;
    private UUID outroMedico;
    private UUID registrante;

    @BeforeEach
    void preparar() {
        fake = new ConsultaRepositoryFake();
        paciente = UUID.randomUUID();
        medico = UUID.randomUUID();
        outroPaciente = UUID.randomUUID();
        outroMedico = UUID.randomUUID();
        registrante = UUID.randomUUID();
    }

    @Override
    protected ConsultaRepositoryPort repositorio() {
        return fake;
    }

    @Override
    protected UUID pacienteId() {
        return paciente;
    }

    @Override
    protected UUID medicoId() {
        return medico;
    }

    @Override
    protected UUID outroPacienteId() {
        return outroPaciente;
    }

    @Override
    protected UUID outroMedicoId() {
        return outroMedico;
    }

    @Override
    protected UUID registradoPorId() {
        return registrante;
    }
}
