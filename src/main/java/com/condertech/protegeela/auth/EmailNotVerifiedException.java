package com.condertech.protegeela.auth;

public class EmailNotVerifiedException extends RuntimeException {
    public EmailNotVerifiedException() {
        super("Confirme seu e-mail com o código enviado antes de entrar.");
    }
}
