package br.com.fiap.hospital.historico.infrastructure.persistence.repository;

import br.com.fiap.hospital.historico.infrastructure.persistence.entity.ConsultaEventoEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface ConsultaEventoJpaRepository extends JpaRepository<ConsultaEventoEntity, UUID> { }
