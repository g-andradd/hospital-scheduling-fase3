package br.com.fiap.hospital.arquitetura.sintetico.a5.infrastructure.web;

import br.com.fiap.hospital.arquitetura.sintetico.a5.infrastructure.dados.RepositorioSintetico;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ControllerComRepositorio {
    private RepositorioSintetico repositorio;
}
