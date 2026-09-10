package br.com.fiap.hospital.notificacao.repository;

import br.com.fiap.hospital.notificacao.domain.AgendaLocal;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface AgendaLocalRepository extends JpaRepository<AgendaLocal, UUID> { }
