package br.com.fiap.hospital.agendamento.infrastructure.persistence.repository;

import br.com.fiap.hospital.agendamento.infrastructure.persistence.entity.MedicoEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MedicoJpaRepository extends JpaRepository<MedicoEntity, UUID> {

    java.util.Optional<MedicoEntity> findByUsuarioId(java.util.UUID usuarioId);
}
