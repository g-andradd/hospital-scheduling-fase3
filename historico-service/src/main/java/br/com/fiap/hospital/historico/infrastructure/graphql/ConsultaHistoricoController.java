package br.com.fiap.hospital.historico.infrastructure.graphql;

import br.com.fiap.hospital.historico.infrastructure.correcao.CorrecaoDeRegistroHistorico;
import br.com.fiap.hospital.historico.infrastructure.leitura.ConsultasDoHistorico;
import br.com.fiap.hospital.historico.infrastructure.persistence.entity.ConsultaHistoricoEntity;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

/**
 * As cinco operacoes do historico, cada uma com decisao de autorizacao explicita.
 *
 * <p>A cadeia de filtros exige token em {@code /graphql}, mas nao distingue perfil. Um
 * metodo novo sem {@code @PreAuthorize} ficaria aberto a <b>qualquer usuario autenticado</b>
 * — paciente alcancando o que era de medico, sem nada falhar. Por isso existe
 * {@code CoberturaDeAutorizacaoGraphqlTest}: a protecao nao pode depender de alguem
 * lembrar.
 */
@Controller
public class ConsultaHistoricoController {

    private final ConsultasDoHistorico consultas;
    private final CorrecaoDeRegistroHistorico correcao;
    private final AutorizacaoDoHistorico autorizacao;

    ConsultaHistoricoController(ConsultasDoHistorico consultas,
                                CorrecaoDeRegistroHistorico correcao,
                                AutorizacaoDoHistorico autorizacao) {
        this.consultas = consultas;
        this.correcao = correcao;
        this.autorizacao = autorizacao;
    }

    @QueryMapping
    @PreAuthorize("hasAnyRole('MEDICO', 'ENFERMEIRO', 'PACIENTE')")
    public List<ConsultaHistoricoEntity> consultasDoPaciente(
            @Argument UUID pacienteId, @Argument FiltroConsulta filtro) {
        autorizacao.exigirAcessoAoPaciente(pacienteId);
        return consultas.doPaciente(pacienteId, filtro);
    }

    /**
     * Exclusiva do paciente, por decisao do Product Owner registrada em D3.
     *
     * <p>Nao recebe identificador: o {@code pacienteId} sai do token. Um argumento aqui
     * seria um segundo caminho de leitura com regra propria para o mesmo dado.
     */
    @QueryMapping
    @PreAuthorize("hasRole('PACIENTE')")
    public List<ConsultaHistoricoEntity> minhasConsultas(@Argument FiltroConsulta filtro) {
        return consultas.doPaciente(autorizacao.pacienteDoToken(), filtro);
    }

    @QueryMapping
    @PreAuthorize("hasAnyRole('MEDICO', 'ENFERMEIRO')")
    public List<ConsultaHistoricoEntity> consultasDoMedico(
            @Argument UUID medicoId, @Argument FiltroConsulta filtro) {
        return consultas.doMedico(medicoId, filtro);
    }

    @QueryMapping
    @PreAuthorize("hasAnyRole('MEDICO', 'ENFERMEIRO', 'PACIENTE')")
    public ConsultaHistoricoEntity consulta(@Argument UUID id) {
        ConsultaHistoricoEntity registro = consultas.porId(id)
                .orElseThrow(() -> new ExcecoesDoHistorico.RegistroNaoEncontrado(
                        "Consulta nao encontrada no historico."));
        autorizacao.exigirAcessoAoPaciente(registro.getPacienteId());
        return registro;
    }

    /**
     * O argumento chega como mapa bruto de proposito.
     *
     * <p>Ligar direto num record perderia a diferenca entre campo ausente e campo nulo — e
     * essa diferenca e o contrato: ausente nao corrige, nulo em {@code observacoes} limpa.
     * O schema continua sendo quem recusa campo desconhecido, antes deste metodo rodar.
     */
    @MutationMapping
    @PreAuthorize("hasRole('MEDICO')")
    public ConsultaHistoricoEntity corrigirRegistroHistorico(
            @Argument("input") Map<String, Object> input) {
        return correcao.aplicar(
                CorrigirRegistroHistoricoInput.de(input), autorizacao.medicoDoToken());
    }
}
