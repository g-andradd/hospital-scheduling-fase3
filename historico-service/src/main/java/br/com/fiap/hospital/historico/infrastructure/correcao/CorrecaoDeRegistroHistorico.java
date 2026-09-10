package br.com.fiap.hospital.historico.infrastructure.correcao;

import br.com.fiap.hospital.contracts.ConsultaPayload;
import br.com.fiap.hospital.historico.infrastructure.graphql.CorrigirRegistroHistoricoInput;
import br.com.fiap.hospital.historico.infrastructure.graphql.ExcecoesDoHistorico.CorrecaoInvalida;
import br.com.fiap.hospital.historico.infrastructure.graphql.ExcecoesDoHistorico.RegistroNaoEncontrado;
import br.com.fiap.hospital.historico.infrastructure.persistence.entity.ConsultaEventoEntity;
import br.com.fiap.hospital.historico.infrastructure.persistence.entity.ConsultaHistoricoEntity;
import br.com.fiap.hospital.historico.infrastructure.persistence.repository.ConsultaEventoJpaRepository;
import br.com.fiap.hospital.historico.infrastructure.persistence.repository.ConsultaHistoricoJpaRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Correcao manual do read model, atomica com sua auditoria.
 *
 * <p>A sequencia e fixa: travar a linha, validar, capturar o estado anterior, aplicar,
 * avancar {@code atualizado_em} pelo {@code Clock} e gravar o fato na trilha. Tudo na
 * mesma transacao — se a auditoria falhar, a correcao tambem nao vale. Auditoria opcional
 * seria pior que auditoria nenhuma: registro alterado sem rastro parece dado original.
 *
 * <p>Avancar {@code atualizado_em} para o instante da correcao e o que faz a regra de
 * monotonicidade do M08 continuar valendo sem excecao: evento antigo nao desfaz a
 * correcao, evento posterior legitimamente a sobrescreve.
 *
 * <p>Nao grava {@code evento_processado} — a marca pertence ao protocolo AMQP, e criar uma
 * aqui faria um eventId inexistente parecer processado — e nao publica evento, o que
 * inverteria a direcao do fluxo definida na ADR-002.
 */
@Service
public class CorrecaoDeRegistroHistorico {

    /** Tipo local da trilha. Nao entra em TipoEvento, routing key nem topologia. */
    public static final String TIPO_CORRECAO_MANUAL = "CORRECAO_MANUAL";

    private final ConsultaHistoricoJpaRepository snapshots;
    private final ConsultaEventoJpaRepository trilha;
    private final ObjectMapper mapper;
    private final Clock clock;

    CorrecaoDeRegistroHistorico(ConsultaHistoricoJpaRepository snapshots,
                                ConsultaEventoJpaRepository trilha,
                                ObjectMapper mapper, Clock clock) {
        this.snapshots = snapshots;
        this.trilha = trilha;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public ConsultaHistoricoEntity aplicar(CorrigirRegistroHistoricoInput input, UUID medicoAutor) {
        exigirJustificativa(input.justificativa());

        ConsultaHistoricoEntity registro = snapshots.findWithLockById(input.consultaId())
                .orElseThrow(() -> new RegistroNaoEncontrado(
                        "Consulta nao encontrada no historico."));

        Map<String, Object> alteracoes = calcularAlteracoes(input, registro);
        if (alteracoes.isEmpty()) {
            throw new CorrecaoInvalida(
                    "A correcao precisa alterar ao menos um campo corrigivel.");
        }

        Instant instanteDaCorrecao = clock.instant();
        registro.corrigir(
                (String) valorFinal(input, CorrigirRegistroHistoricoInput.PACIENTE_NOME,
                        registro.getPacienteNome()),
                (String) valorFinal(input, CorrigirRegistroHistoricoInput.MEDICO_NOME,
                        registro.getMedicoNome()),
                (String) valorFinal(input, CorrigirRegistroHistoricoInput.ESPECIALIDADE,
                        registro.getEspecialidade()),
                (OffsetDateTime) valorFinal(input, CorrigirRegistroHistoricoInput.DATA_HORA,
                        registro.getDataHora()),
                (String) valorFinal(input, CorrigirRegistroHistoricoInput.STATUS,
                        registro.getStatus()),
                (String) valorFinal(input, CorrigirRegistroHistoricoInput.OBSERVACOES,
                        registro.getObservacoes()),
                instanteDaCorrecao);
        snapshots.saveAndFlush(registro);

        trilha.saveAndFlush(new ConsultaEventoEntity(
                UUID.randomUUID(), registro.getId(), TIPO_CORRECAO_MANUAL, instanteDaCorrecao,
                auditoria(medicoAutor, input.justificativa(), alteracoes)));
        return registro;
    }

    private void exigirJustificativa(String justificativa) {
        if (justificativa == null || justificativa.isBlank()) {
            throw new CorrecaoInvalida("A correcao exige justificativa.");
        }
    }

    /**
     * O que muda de fato, com os valores antes e depois.
     *
     * <p>Campo informado com o valor que ja estava la nao entra: gravar auditoria de
     * nao-mudanca encheria a trilha de ruido indistinguivel de correcao real.
     */
    private Map<String, Object> calcularAlteracoes(
            CorrigirRegistroHistoricoInput input, ConsultaHistoricoEntity registro) {
        Map<String, Object> alteracoes = new LinkedHashMap<>();
        registrarSeMudou(alteracoes, input, CorrigirRegistroHistoricoInput.PACIENTE_NOME,
                registro.getPacienteNome(), input.pacienteNome());
        registrarSeMudou(alteracoes, input, CorrigirRegistroHistoricoInput.MEDICO_NOME,
                registro.getMedicoNome(), input.medicoNome());
        registrarSeMudou(alteracoes, input, CorrigirRegistroHistoricoInput.ESPECIALIDADE,
                registro.getEspecialidade(), input.especialidade());
        registrarSeMudou(alteracoes, input, CorrigirRegistroHistoricoInput.DATA_HORA,
                registro.getDataHora(), input.dataHora());
        registrarSeMudou(alteracoes, input, CorrigirRegistroHistoricoInput.STATUS,
                registro.getStatus(), statusValidado(input));
        registrarSeMudou(alteracoes, input, CorrigirRegistroHistoricoInput.OBSERVACOES,
                registro.getObservacoes(), input.observacoes());
        return alteracoes;
    }

    private void registrarSeMudou(Map<String, Object> alteracoes,
                                  CorrigirRegistroHistoricoInput input, String campo,
                                  Object anterior, Object novo) {
        if (!input.informou(campo)) {
            return;
        }
        exigirNaoNuloExcetoObservacoes(campo, novo);
        if (Objects.equals(anterior, novo)) {
            return;
        }
        Map<String, Object> antesEDepois = new LinkedHashMap<>();
        antesEDepois.put("antes", anterior == null ? null : anterior.toString());
        antesEDepois.put("depois", novo == null ? null : novo.toString());
        alteracoes.put(campo, antesEDepois);
    }

    /**
     * Nulo explicito so faz sentido onde apagar e uma correcao legitima.
     *
     * <p>Em observacoes, limpar registro clinico e uma correcao possivel. Nos demais
     * campos, nulo apagaria uma dimensao que o snapshot declara obrigatoria.
     */
    private void exigirNaoNuloExcetoObservacoes(String campo, Object valor) {
        if (valor == null && !CorrigirRegistroHistoricoInput.OBSERVACOES.equals(campo)) {
            throw new CorrecaoInvalida("O campo " + campo + " nao aceita valor nulo.");
        }
    }

    /**
     * O status precisa existir no contrato, mas a maquina de transicoes do agendamento nao
     * se aplica: corrigir um registro que ficou com status errado e exatamente o caso de
     * uso, e recusa-lo por transicao invalida tornaria a correcao inutil onde ela importa.
     */
    private String statusValidado(CorrigirRegistroHistoricoInput input) {
        String status = input.status();
        if (!input.informou(CorrigirRegistroHistoricoInput.STATUS) || status == null) {
            return status;
        }
        boolean conhecido = Arrays.stream(ConsultaPayload.Status.values())
                .anyMatch(valor -> valor.name().equals(status));
        if (!conhecido) {
            throw new CorrecaoInvalida("Status desconhecido: " + status);
        }
        return status;
    }

    private Object valorFinal(CorrigirRegistroHistoricoInput input, String campo, Object atual) {
        if (!input.informou(campo)) {
            return atual;
        }
        return switch (campo) {
            case CorrigirRegistroHistoricoInput.PACIENTE_NOME -> input.pacienteNome();
            case CorrigirRegistroHistoricoInput.MEDICO_NOME -> input.medicoNome();
            case CorrigirRegistroHistoricoInput.ESPECIALIDADE -> input.especialidade();
            case CorrigirRegistroHistoricoInput.DATA_HORA -> input.dataHora();
            case CorrigirRegistroHistoricoInput.STATUS -> input.status();
            case CorrigirRegistroHistoricoInput.OBSERVACOES -> input.observacoes();
            default -> atual;
        };
    }

    private String auditoria(UUID medicoAutor, String justificativa, Map<String, Object> alteracoes) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tipo", TIPO_CORRECAO_MANUAL);
        payload.put("medicoAutorId", medicoAutor.toString());
        payload.put("justificativa", justificativa);
        payload.put("camposCorrigidos", alteracoes.keySet());
        payload.put("alteracoes", alteracoes);
        try {
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Falha ao serializar a auditoria da correcao", e);
        }
    }
}
