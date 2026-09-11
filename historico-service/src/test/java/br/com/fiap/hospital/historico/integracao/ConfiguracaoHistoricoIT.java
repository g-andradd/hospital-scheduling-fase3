package br.com.fiap.hospital.historico.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;

@DisplayName("Configuração de consumo do histórico")
class ConfiguracaoHistoricoIT extends HistoricoITBase {
    @Autowired RabbitProperties propriedades;

    @Test @DisplayName("retry e rejeição seguem o contrato compartilhado")
    void retryERejeicaoSeguemOContrato() {
        var retry = propriedades.getListener().getSimple().getRetry();
        assertThat(propriedades.getListener().getSimple().getDefaultRequeueRejected()).isFalse();
        assertThat(retry.isEnabled()).isTrue();
        assertThat(retry.getMaxAttempts()).isEqualTo(3);
        assertThat(retry.getInitialInterval().toMillis()).isEqualTo(1_000);
        assertThat(retry.getMultiplier()).isEqualTo(2.0);
        assertThat(retry.getMaxInterval().toMillis()).isEqualTo(10_000);
    }
}
