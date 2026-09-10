package br.com.fiap.hospital.historico.infrastructure.persistence.repository;

import br.com.fiap.hospital.historico.infrastructure.persistence.entity.EventoProcessadoEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface EventoProcessadoJpaRepository extends JpaRepository<EventoProcessadoEntity, UUID> { }
