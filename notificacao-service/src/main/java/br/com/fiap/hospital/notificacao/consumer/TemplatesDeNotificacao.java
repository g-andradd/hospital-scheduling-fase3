package br.com.fiap.hospital.notificacao.consumer;

import br.com.fiap.hospital.contracts.ConsultaPayload;
import br.com.fiap.hospital.contracts.TipoEvento;
import br.com.fiap.hospital.notificacao.sender.NotificationSenderPort.Mensagem;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Os textos em portugues, num lugar so.
 *
 * <p>O mapa decide quais tipos notificam: o que nao esta aqui nao gera mensagem. E mais
 * dificil de quebrar por engano do que um {@code switch} com ramo default — um tipo novo
 * simplesmente nao notifica ate alguem escrever seu texto, em vez de cair num ramo
 * generico e enviar algo errado ao paciente.
 */
@Component
public class TemplatesDeNotificacao {

    private static final DateTimeFormatter DATA_HORA =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.of("pt", "BR"));

    private static final ZoneId FUSO_DA_CONSULTA = ZoneId.of("America/Sao_Paulo");

    private static final Map<TipoEvento, String> ASSUNTOS = Map.of(
            TipoEvento.CONSULTA_CRIADA, "Consulta agendada",
            TipoEvento.CONSULTA_ATUALIZADA, "Consulta alterada",
            TipoEvento.CONSULTA_CANCELADA, "Consulta cancelada");

    /** Tipos que notificam. Confirmacao e realizacao atualizam a agenda e nao avisam. */
    public boolean notifica(TipoEvento tipo) {
        return ASSUNTOS.containsKey(tipo);
    }

    public Mensagem montar(TipoEvento tipo, ConsultaPayload payload) {
        String quando = DATA_HORA.format(payload.dataHora());
        String paciente = payload.paciente().nome();
        String medico = payload.medico().nome();
        String corpo = switch (tipo) {
            case CONSULTA_CRIADA -> "Ola, " + paciente + ". Sua consulta com " + medico
                    + " foi agendada para " + quando + ".";
            case CONSULTA_ATUALIZADA -> "Ola, " + paciente + ". Sua consulta com " + medico
                    + " foi alterada e agora esta marcada para " + quando + ".";
            case CONSULTA_CANCELADA -> "Ola, " + paciente + ". Sua consulta com " + medico
                    + ", marcada para " + quando + ", foi cancelada.";
            default -> throw new IllegalStateException("tipo sem template: " + tipo);
        };
        return new Mensagem(payload.paciente().email(), ASSUNTOS.get(tipo), corpo);
    }

    /**
     * O lembrete D-1, fora do mapa de eventos: lembrete nao e {@link TipoEvento}.
     *
     * <p>O horario e formatado em America/Sao_Paulo. A agenda guarda o instante, e nao o
     * deslocamento, e o agendamento deriva a data da consulta nesse fuso; formatar em UTC
     * diria ao paciente um horario tres horas adiantado.
     */
    public Mensagem lembrete(String pacienteNome, String pacienteEmail, String medicoNome,
                             Instant dataHora) {
        String quando = DATA_HORA.format(dataHora.atZone(FUSO_DA_CONSULTA));
        String corpo = "Ola, " + pacienteNome + ". Lembrete: sua consulta com " + medicoNome
                + " esta marcada para " + quando + ".";
        return new Mensagem(pacienteEmail, "Lembrete de consulta", corpo);
    }
}
