package br.com.fiap.hospital.agendamento.infrastructure.messaging;

import br.com.fiap.hospital.agendamento.domain.*;
import br.com.fiap.hospital.agendamento.domain.port.EventPublisherPort;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.repository.*;
import br.com.fiap.hospital.contracts.*;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OutboxEventPublisher implements EventPublisherPort {
    private static final ZoneId ZONA = ZoneId.of("America/Sao_Paulo");
    private final PacienteJpaRepository pacientes;
    private final MedicoJpaRepository medicos;
    private final UsuarioJpaRepository usuarios;
    private final OutboxRepository outbox;
    private final EventoJson json;
    public OutboxEventPublisher(PacienteJpaRepository pacientes,MedicoJpaRepository medicos,
            UsuarioJpaRepository usuarios,OutboxRepository outbox,EventoJson json) {
        this.pacientes=pacientes; this.medicos=medicos; this.usuarios=usuarios; this.outbox=outbox; this.json=json;
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void publicar(EventoDeConsulta evento) {
        var estado=evento.posterior();
        var paciente=pacientes.findById(estado.pacienteId()).orElseThrow();
        var medico=medicos.findById(estado.medicoId()).orElseThrow();
        var registrante=usuarios.findById(estado.registradoPorId()).orElseThrow();
        var payload = new ConsultaPayload(evento.consultaId(),ConsultaPayload.Status.valueOf(estado.status().name()),
                naZona(estado.periodo().inicio()),estado.periodo().duracaoMinutos(),estado.observacoes(),
                estado.motivoCancelamento(),
                new ConsultaPayload.Paciente(paciente.getId(),paciente.getUsuario().getNome(),
                        paciente.getUsuario().getEmail(),paciente.getTelefone()),
                new ConsultaPayload.Medico(medico.getId(),medico.getUsuario().getNome(),medico.getCrm(),medico.getEspecialidade()),
                new ConsultaPayload.Registrante(registrante.getId(),registrante.getNome(),
                        ConsultaPayload.Perfil.valueOf(registrante.getPerfil().name())),
                alteracoes(evento));
        String correlationId=MDC.get("correlationId");
        if(correlationId==null||correlationId.isBlank()) correlationId=UUID.randomUUID().toString();
        var envelope = new EventoEnvelope<>(UUID.randomUUID(),TipoEvento.valueOf("CONSULTA_"+evento.tipo().name()),
                evento.consultaId(),evento.ocorridoEm().toInstant(),1,correlationId,payload);
        outbox.inserir(envelope,json.escrever(envelope));
    }
    private Map<String,Object> alteracoes(EventoDeConsulta evento) {
        if(evento.tipo()!=TipoEventoConsulta.ATUALIZADA) return null;
        var resultado = new LinkedHashMap<String,Object>();
        var antes=evento.anterior(); var depois=evento.posterior();
        if(antes==null) return resultado;
        if(!antes.periodo().inicio().toInstant().equals(depois.periodo().inicio().toInstant()))
            resultado.put("dataHoraAnterior",naZona(antes.periodo().inicio()));
        if(antes.periodo().duracaoMinutos()!=depois.periodo().duracaoMinutos())
            resultado.put("duracaoMinutosAnterior",antes.periodo().duracaoMinutos());
        if(!antes.medicoId().equals(depois.medicoId())) resultado.put("medicoIdAnterior",antes.medicoId());
        if(!Objects.equals(antes.observacoes(),depois.observacoes())) resultado.put("observacoesAnterior",antes.observacoes());
        return resultado;
    }
    private static OffsetDateTime naZona(OffsetDateTime data) {
        return data.toInstant().atZone(ZONA).toOffsetDateTime();
    }
}

