package br.com.fiap.hospital.arquitetura.sintetico.a5.infrastructure.web;

import br.com.fiap.hospital.arquitetura.sintetico.a5.infrastructure.transacao.QualquerUseCaseTransacional;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ControllerConforme {
    private QualquerUseCaseTransacional casoDeUso;
}
