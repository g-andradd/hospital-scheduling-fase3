package br.com.fiap.hospital.historico.infrastructure.graphql;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Recorte da leitura, como o cliente o informa.
 *
 * @param periodo recorte relativo ao instante atual; ausente equivale a TODAS
 * @param status valores aceitos; vazia ou ausente nao restringe
 * @param de limite inferior inclusivo
 * @param ate limite superior exclusivo
 */
public record FiltroConsulta(
        PeriodoFiltro periodo, List<String> status, OffsetDateTime de, OffsetDateTime ate) {

    public static final FiltroConsulta VAZIO = new FiltroConsulta(null, null, null, null);

    public FiltroConsulta {
        status = status == null ? List.of() : List.copyOf(status);
    }

    static FiltroConsulta ouVazio(FiltroConsulta filtro) {
        return filtro == null ? VAZIO : filtro;
    }
}
