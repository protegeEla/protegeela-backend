package com.condertech.protegeela.shared.exception;

public class EmailDeliveryException extends RuntimeException {
    public EmailDeliveryException() {
        super("Não foi possível enviar o código de confirmação. Verifique a configuração de e-mail e tente novamente.");
    }
}
