package br.com.fiap.hospital.historico.infrastructure.persistence.repository;

import br.com.fiap.hospital.historico.infrastructure.persistence.entity.ConsultaHistoricoEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import java.util.Optional;
import java.util.UUID;

public interface ConsultaHistoricoJpaRepository
        extends JpaRepository<ConsultaHistoricoEntity, UUID>,
                JpaSpecificationExecutor<ConsultaHistoricoEntity> {

    /**
     * Trava a linha antes de ler o estado anterior.
     *
     * <p>A correcao precisa do valor antigo para auditar, entao le, modifica e escreve.
     * Sem o lock, duas correcoes concorrentes leriam o mesmo "antes" e a auditoria da
     * perdedora registraria um valor anterior que nunca existiu no snapshot final.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ConsultaHistoricoEntity> findWithLockById(UUID id);
}
