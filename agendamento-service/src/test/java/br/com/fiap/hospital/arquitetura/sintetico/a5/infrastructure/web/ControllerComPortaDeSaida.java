package br.com.fiap.hospital.arquitetura.sintetico.a5.infrastructure.web;

import br.com.fiap.hospital.arquitetura.sintetico.a5.domain.port.PortaDeSaidaSintetica;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ControllerComPortaDeSaida {
    private PortaDeSaidaSintetica porta;
}
