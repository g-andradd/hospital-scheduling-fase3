package br.com.fiap.hospital.notificacao.repository;

import br.com.fiap.hospital.notificacao.domain.EventoProcessado;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface EventoProcessadoRepository extends JpaRepository<EventoProcessado, UUID> { }
