package com.condertech.protegeela.auth;

public class AuthRateLimitException extends RuntimeException {
    public AuthRateLimitException() { super("Muitas tentativas. Aguarde 15 minutos antes de tentar novamente."); }
}
