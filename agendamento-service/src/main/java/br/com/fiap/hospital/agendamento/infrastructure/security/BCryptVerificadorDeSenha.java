package br.com.fiap.hospital.agendamento.infrastructure.security;

import br.com.fiap.hospital.agendamento.domain.port.VerificadorDeSenhaPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Adaptador BCrypt da porta de verificacao de senha. */
@Component
public class BCryptVerificadorDeSenha implements VerificadorDeSenhaPort {

    /**
     * Hash descartavel, usado apenas para gastar o mesmo tempo quando nao ha usuario.
     * E um BCrypt valido de uma senha aleatoria: o algoritmo roda por inteiro.
     */
    private static final String HASH_DE_REFERENCIA =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final PasswordEncoder encoder;

    public BCryptVerificadorDeSenha(PasswordEncoder encoder) {
        this.encoder = encoder;
    }

    @Override
    public boolean confere(String senhaEmClaro, String hash) {
        if (senhaEmClaro == null || hash == null) {
            return false;
        }
        return encoder.matches(senhaEmClaro, hash);
    }

    @Override
    public void consumirTempoDeVerificacao() {
        // O resultado e descartado de proposito: o que importa e o tempo gasto.
        encoder.matches("senha-que-nao-confere", HASH_DE_REFERENCIA);
    }
}
