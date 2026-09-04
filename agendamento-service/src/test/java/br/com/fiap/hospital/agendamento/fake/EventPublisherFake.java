package br.com.fiap.hospital.agendamento.fake;

import br.com.fiap.hospital.agendamento.domain.EventoDeConsulta;
import br.com.fiap.hospital.agendamento.domain.TipoEventoConsulta;
import br.com.fiap.hospital.agendamento.domain.port.EventPublisherPort;
import java.util.ArrayList;
import java.util.List;

/** Publicador que apenas guarda o que foi publicado, para o teste inspecionar. */
public class EventPublisherFake implements EventPublisherPort {

    private final List<EventoDeConsulta> publicados = new ArrayList<>();

    @Override
    public void publicar(EventoDeConsulta evento) {
        publicados.add(evento);
    }

    public List<EventoDeConsulta> publicados() {
        return List.copyOf(publicados);
    }

    public int quantidade() {
        return publicados.size();
    }

    public boolean nadaPublicado() {
        return publicados.isEmpty();
    }

    public List<TipoEventoConsulta> tipos() {
        return publicados.stream().map(EventoDeConsulta::tipo).toList();
    }
}
