package br.com.fiap.hospital.historico.infrastructure.persistence.repository;

import br.com.fiap.hospital.historico.infrastructure.persistence.entity.ConsultaHistoricoEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface ConsultaHistoricoJpaRepository extends JpaRepository<ConsultaHistoricoEntity, UUID> { }
