package br.com.fiap.hospital.agendamento.infrastructure.persistence.repository;

import br.com.fiap.hospital.agendamento.infrastructure.persistence.entity.PacienteEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PacienteJpaRepository extends JpaRepository<PacienteEntity, UUID> {}
