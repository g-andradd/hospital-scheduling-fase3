package br.com.fiap.hospital.agendamento;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Prova que o contexto do Spring sobe sem nenhuma infraestrutura externa.
 * Banco e broker entram nos changes seguintes, em testes *IT com Testcontainers.
 */
@SpringBootTest
class AgendamentoApplicationTest {

    @Test
    @DisplayName("o contexto da aplicacao carrega")
    void contextLoads() {
    }
}
