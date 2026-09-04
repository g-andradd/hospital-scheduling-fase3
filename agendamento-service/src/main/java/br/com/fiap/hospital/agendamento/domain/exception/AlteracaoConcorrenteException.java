package br.com.fiap.hospital.agendamento.domain.exception;

import java.util.UUID;

/**
 * Falha transitória de concorrência: lock otimista ou transação recusada pelo banco
 * por deadlock/serialização. Não afirma que o horário está ocupado nem que outra
 * transação confirmou uma alteração.
 *
 * <p>Mapeada para 409 com type de alteração concorrente. A operação é desfeita
 * integralmente; o cliente deve reler e tentar novamente. Não há retry automático.
 */
public class AlteracaoConcorrenteException extends RuntimeException {

    public AlteracaoConcorrenteException(UUID consultaId) {
        super("A consulta " + consultaId + " nao pode ser gravada devido a concorrencia. Recarregue e tente novamente");
    }
}
