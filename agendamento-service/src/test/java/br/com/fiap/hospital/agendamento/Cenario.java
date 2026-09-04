package br.com.fiap.hospital.agendamento;

import br.com.fiap.hospital.agendamento.domain.Consulta;
import br.com.fiap.hospital.agendamento.domain.Cpf;
import br.com.fiap.hospital.agendamento.domain.Crm;
import br.com.fiap.hospital.agendamento.domain.Email;
import br.com.fiap.hospital.agendamento.domain.Medico;
import br.com.fiap.hospital.agendamento.domain.Paciente;
import br.com.fiap.hospital.agendamento.domain.PerfilUsuario;
import br.com.fiap.hospital.agendamento.domain.PeriodoConsulta;
import br.com.fiap.hospital.agendamento.domain.SolicitanteAutenticado;
import br.com.fiap.hospital.agendamento.domain.StatusConsulta;
import br.com.fiap.hospital.agendamento.domain.Usuario;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Construtores de cenario para os testes.
 *
 * <p>O relogio e sempre fixo. Nenhum teste deste modulo chama {@code now()} sem
 * {@link Clock}: sem isso, os cenarios de borda — "registro no instante corrente e
 * recusado", "periodos adjacentes nao sao conflito" — passariam ou falhariam conforme
 * a hora em que a suite roda.
 */
public final class Cenario {

    /** Instante de referencia de todos os testes: 2026-09-02, 12:00, horario de Brasilia. */
    public static final OffsetDateTime AGORA =
            OffsetDateTime.of(2026, 9, 2, 12, 0, 0, 0, ZoneOffset.ofHours(-3));

    public static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");

    private Cenario() {}

    /** Relogio parado em {@link #AGORA}. */
    public static Clock relogioFixo() {
        return Clock.fixed(AGORA.toInstant(), FUSO);
    }

    /** Relogio parado no instante informado. */
    public static Clock relogioEm(OffsetDateTime instante) {
        return Clock.fixed(instante.toInstant(), FUSO);
    }

    public static OffsetDateTime daquiA(long horas) {
        return AGORA.plusHours(horas);
    }

    public static OffsetDateTime haQuanto(long horas) {
        return AGORA.minusHours(horas);
    }

    public static Usuario usuario(PerfilUsuario perfil, String nome, String email) {
        return new Usuario(UUID.randomUUID(), nome, new Email(email), "$2a$10$hash", perfil, true);
    }

    public static Paciente paciente() {
        return paciente("Maria Souza", "paciente@hospital.com", "529.982.247-25");
    }

    public static Paciente paciente(String nome, String email, String cpf) {
        return new Paciente(
                UUID.randomUUID(),
                usuario(PerfilUsuario.PACIENTE, nome, email),
                new Cpf(cpf),
                LocalDate.of(1990, 5, 12),
                "+5561999990000");
    }

    public static Medico medico() {
        return medico("Dr. Joao Lima", "medico@hospital.com", "DF-12345");
    }

    public static Medico medico(String nome, String email, String crm) {
        return new Medico(
                UUID.randomUUID(),
                usuario(PerfilUsuario.MEDICO, nome, email),
                new Crm(crm),
                "Cardiologia");
    }

    /**
     * Enfermeiro que registra as consultas.
     *
     * <p>Devolve um usuario, e nao apenas um id, porque o registrador e chave
     * estrangeira e o caso de uso o valida: um id solto, nunca gravado, seria recusado.
     */
    public static Usuario enfermeiro() {
        return usuario(PerfilUsuario.ENFERMEIRO, "Ana Enfermeira", "enfermeiro@hospital.com");
    }

    /** Id avulso, para cenarios que nao passam pelo caso de uso de agendamento. */
    public static UUID enfermeiroId() {
        return enfermeiro().id();
    }

    /**
     * Solicitante sem recorte por identidade.
     *
     * <p>Os testes anteriores ao M04 nao tinham nocao de quem pedia, e um perfil sem
     * recorte preserva exatamente o que eles verificavam. A regra de propriedade tem
     * testes proprios.
     */
    public static SolicitanteAutenticado solicitanteMedico() {
        return new SolicitanteAutenticado(UUID.randomUUID(), PerfilUsuario.MEDICO, null);
    }

    public static SolicitanteAutenticado solicitanteEnfermeiro() {
        return new SolicitanteAutenticado(UUID.randomUUID(), PerfilUsuario.ENFERMEIRO, null);
    }

    /** Solicitante paciente, titular do paciente informado. */
    public static SolicitanteAutenticado solicitantePaciente(Paciente paciente) {
        return new SolicitanteAutenticado(
                paciente.usuario().id(), PerfilUsuario.PACIENTE, paciente.id());
    }

    public static PeriodoConsulta periodo(OffsetDateTime inicio) {
        return new PeriodoConsulta(inicio, Consulta.DURACAO_PADRAO_MINUTOS);
    }

    /** Consulta ja existente, montada direto no status desejado. */
    public static Consulta consultaExistente(
            Paciente paciente, Medico medico, OffsetDateTime inicio, StatusConsulta status) {
        return Consulta.reconstituir(
                UUID.randomUUID(),
                paciente.id(),
                medico.id(),
                enfermeiroId(),
                periodo(inicio),
                status,
                null,
                status == StatusConsulta.CANCELADA ? "motivo anterior" : null,
                AGORA,
                AGORA);
    }

    public static Consulta consultaAgendada(
            Paciente paciente, Medico medico, OffsetDateTime inicio) {
        return consultaExistente(paciente, medico, inicio, StatusConsulta.AGENDADA);
    }
}
