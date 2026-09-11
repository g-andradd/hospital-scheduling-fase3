package br.com.fiap.hospital.notificacao.web;

import br.com.fiap.hospital.notificacao.lembrete.ServicoDeLembretes;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Disparo manual do lembrete D-1, para demonstrar sem esperar a hora cheia.
 *
 * <p>A cadeia compartilhada exige token em {@code /internal/**}; quem decide o perfil e a
 * anotacao deste metodo. A celula de cada perfil vive em docs/02-especificacao-funcional.md
 * secao 3, e os testes a leem de la.
 */
@RestController
public class LembreteController {

    private final ServicoDeLembretes lembretes;

    public LembreteController(ServicoDeLembretes lembretes) {
        this.lembretes = lembretes;
    }

    @PostMapping("/internal/lembretes/executar")
    @PreAuthorize("hasAnyRole('MEDICO','ENFERMEIRO')")
    public ResultadoDaExecucao executar() {
        return new ResultadoDaExecucao(lembretes.executar());
    }
}
