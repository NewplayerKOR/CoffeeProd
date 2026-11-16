package com.back.coffeeprod.domain.oauth;

import com.back.coffeeprod.global.exception.*;
import org.springframework.stereotype.Service;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;

@Service
public class EmailVerificationService {
    public enum Purpose { PASSWORD_RESET, OAUTH_SIGNUP_EMAIL }
    private final AuthStore store; private final MailSender sender; private final OAuthSettings settings;
    private static final SecureRandom RANDOM=new SecureRandom();
    public EmailVerificationService(AuthStore store,MailSender sender,OAuthSettings settings) {this.store=store;this.sender=sender;this.settings=settings;}
    String digest(String text) {
        if(settings.getVerificationSecret().length()<32) throw new CustomException(ErrorCode.AUTH_PROVIDER_UNAVAILABLE);
        try {var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(settings.getVerificationSecret().getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.GeneralSecurityException e){throw new IllegalStateException(e);}
    }
    private String binding(Purpose purpose,String email,String transaction) {return digest(purpose+":"+email.trim().toLowerCase(Locale.ROOT)+":"+transaction);}
    public void send(Purpose purpose,String email,String transaction,String ip) {
        String bind=binding(purpose,email,transaction), who=digest(email.trim().toLowerCase(Locale.ROOT)), source=digest(ip), attempt=AuthStore.random();
        long now=System.currentTimeMillis(); var date=LocalDate.now(ZoneOffset.UTC);
        List<String> keys=List.of("auth:mail:pending:"+bind,"auth:mail:cooldown:"+who,"auth:mail:eh:"+who,"auth:mail:ed:"+who+":"+date,
            "auth:mail:ih:"+source,"auth:mail:id:"+source+":"+date,"auth:mail:all:"+date,"auth:mail:month:"+YearMonth.from(date));
        String result=store.eval("""
            if redis.call('EXISTS',KEYS[1])==1 or redis.call('EXISTS',KEYS[2])==1 then return 'LIMIT' end
            local limits={3,5,10,20,80,2400}
            local windows={3600000,86400000,3600000,86400000,86400000,2764800000}
            for i=3,8 do
              redis.call('ZREMRANGEBYSCORE',KEYS[i],'-inf',tonumber(ARGV[1])-windows[i-2])
              if redis.call('ZCARD',KEYS[i])>=limits[i-2] then return 'LIMIT' end
            end
            redis.call('SET',KEYS[1],ARGV[2],'EX',60)
            redis.call('SET',KEYS[2],ARGV[2],'EX',60)
            for i=3,8 do redis.call('ZADD',KEYS[i],ARGV[1],ARGV[2]); redis.call('PEXPIRE',KEYS[i],windows[i-2]) end
            return 'OK'
            """,keys,""+now,attempt);
        if(!"OK".equals(result))throw new CustomException(ErrorCode.AUTH_RATE_LIMIT);
        String code=String.format(Locale.ROOT,"%06d",RANDOM.nextInt(1000000));
        long expiresAt=System.currentTimeMillis()+300000;
        MailSender.Outcome outcome=sender.send(email,code,attempt);
        if(outcome==MailSender.Outcome.SENT) {
            String published=store.eval("""
                if redis.call('GET',KEYS[1])~=ARGV[1] then return 'STALE' end
                local ttl=tonumber(ARGV[3])-tonumber(ARGV[4]); if ttl<=0 then return 'STALE' end
                redis.call('DEL',KEYS[2]); redis.call('HSET',KEYS[2],'digest',ARGV[2],'attempts','0'); redis.call('PEXPIRE',KEYS[2],ttl)
                redis.call('DEL',KEYS[1]); return 'OK'
                """,List.of(keys.get(0),"auth:mail:code:"+bind),attempt,digest(bind+":"+code),""+expiresAt,""+System.currentTimeMillis());
            if(!"OK".equals(published))throw new CustomException(ErrorCode.AUTH_PROVIDER_UNAVAILABLE);
        } else {
            // 결과 불명 발송의 예약은 유지하고 기존 인증번호를 보존한다.
            throw new CustomException(ErrorCode.AUTH_PROVIDER_UNAVAILABLE);
        }
    }
    public String verify(Purpose purpose,String email,String transaction,String code) {
        String bind=binding(purpose,email,transaction), who=digest(email.trim().toLowerCase(Locale.ROOT)), grant=AuthStore.random();
        String result=store.eval("""
            local now=tonumber(ARGV[1]); redis.call('ZREMRANGEBYSCORE',KEYS[2],'-inf',now-900000)
            if redis.call('ZCARD',KEYS[2])>=10 then return 'LIMIT' end
            if redis.call('EXISTS',KEYS[1])==0 then return 'INVALID' end
            if tonumber(redis.call('HGET',KEYS[1],'attempts') or '0')>=5 then return 'LIMIT' end
            if redis.call('HGET',KEYS[1],'digest')~=ARGV[2] then
              redis.call('HINCRBY',KEYS[1],'attempts',1); redis.call('ZADD',KEYS[2],now,ARGV[3]); redis.call('EXPIRE',KEYS[2],900); return 'INVALID'
            end
            redis.call('DEL',KEYS[1]); redis.call('SET',KEYS[3],ARGV[4],'EX',300); return 'OK'
            """,List.of("auth:mail:code:"+bind,"auth:mail:fail:"+who,"auth:emailgrant:"+grant),""+System.currentTimeMillis(),digest(bind+":"+code),AuthStore.random(),bind);
        if("LIMIT".equals(result))throw new CustomException(ErrorCode.AUTH_RATE_LIMIT);
        if(!"OK".equals(result))throw new CustomException(ErrorCode.OAUTH_TRANSACTION_INVALID);
        return grant;
    }
    public void consume(Purpose purpose,String email,String transaction,String grant) {
        String expected=binding(purpose,email,transaction);
        String actual=grant==null?null:store.consume("emailgrant:"+grant);
        if(!expected.equals(actual))throw new CustomException(ErrorCode.OAUTH_CONFIRMATION_REQUIRED);
    }
}
