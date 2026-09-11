package br.com.fiap.hospital.agendamento.integracao;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.*;

class OutboxReentregaIT extends M05RabbitBase {
    @Test @DisplayName("Queda entre confirmação do broker e commit local")
    
    // Scenario: Queda entre confirmação do broker e commit local
    void ackRealSeguidoDeRollbackReenviaMesmoEnvelope() {
        criar();var e=eventos().getFirst();
        jdbc.execute("""
            CREATE FUNCTION falhar_publicado_m05() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN RAISE EXCEPTION 'falha local depois do ACK'; END $$
            """);
        jdbc.execute("""
            CREATE TRIGGER falhar_publicado_m05 BEFORE UPDATE ON outbox_evento
            FOR EACH ROW WHEN (NEW.publicado_em IS NOT NULL) EXECUTE FUNCTION falhar_publicado_m05()
            """);
        byte[] primeiro;
        try {
            assertThatThrownBy(()->relay.executar()).isInstanceOf(org.springframework.dao.DataAccessException.class);
            primeiro=receber(N).getBody();receber(H);
            publicado(false);tentativas(0);contagens(1,1);
            assertThat(eventos().getFirst()).isEqualTo(e);
        } finally {
            jdbc.execute("DROP TRIGGER falhar_publicado_m05 ON outbox_evento");
            jdbc.execute("DROP FUNCTION falhar_publicado_m05()");
        }
        relay.executar();publicado(true);contagens(1,1);
        assertThat(receber(N).getBody()).isEqualTo(primeiro);
        assertThat(receber(H).getBody()).isEqualTo(primeiro);
    }
}
