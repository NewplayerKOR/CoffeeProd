package com.back.coffeeprod.domain.oauth;

public interface MailSender {
    enum Outcome { SENT, REJECTED, UNKNOWN }
    Outcome send(String email,String code,String idempotencyKey);
}
