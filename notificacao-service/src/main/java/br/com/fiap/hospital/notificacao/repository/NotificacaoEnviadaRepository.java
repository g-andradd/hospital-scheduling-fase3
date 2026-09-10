package br.com.fiap.hospital.notificacao.repository;

import br.com.fiap.hospital.notificacao.domain.NotificacaoEnviada;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface NotificacaoEnviadaRepository extends JpaRepository<NotificacaoEnviada, UUID> { }
