package com.back.coffeeprod.domain.recommendation.ai;

import com.back.coffeeprod.global.exception.CustomException;
import com.back.coffeeprod.global.exception.ErrorCode;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

@Component
public class AiQuota {
    private final StringRedisTemplate redis;
    private final AiSettings settings;
    public AiQuota(RedisConnectionFactory factory, AiSettings settings) {
        this.redis = new StringRedisTemplate(factory); this.settings = settings;
    }
    public record Reservation(String month, String key, int inputRate, int outputRate) {}
    // UTC 경계는 Redis 서버 시각으로 정한다. 예약·호출 횟수·월 합계를 한 연산에서 갱신한다.
    private static final String RESERVE = """
        local t=redis.call('TIME'); local now=tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000)
        local day=math.floor(now/86400000)
        if day~=tonumber(ARGV[1]) then return 'CLOCK' end
        redis.call('ZREMRANGEBYSCORE',KEYS[1],'-inf',now-60000)
        if redis.call('ZCARD',KEYS[1])>=2 then return 'LIMIT' end
        if tonumber(redis.call('GET',KEYS[2]) or '0')>=20 or tonumber(redis.call('GET',KEYS[3]) or '0')>=100 then return 'LIMIT' end
        local used=tonumber(redis.call('GET',KEYS[4]) or '0')
        if used+tonumber(ARGV[2])>tonumber(ARGV[3]) then return 'BUDGET' end
        if redis.call('EXISTS',KEYS[5])==1 then return 'LIMIT' end
        redis.call('ZADD',KEYS[1],now,ARGV[4]); redis.call('PEXPIRE',KEYS[1],61000)
        redis.call('INCR',KEYS[2]); redis.call('EXPIRE',KEYS[2],172800)
        redis.call('INCR',KEYS[3]); redis.call('EXPIRE',KEYS[3],172800)
        redis.call('INCRBY',KEYS[4],ARGV[2]); redis.call('EXPIREAT',KEYS[4],ARGV[5])
        redis.call('HSET',KEYS[5],'state','RESERVED','cost',ARGV[2]); redis.call('EXPIREAT',KEYS[5],ARGV[5])
        return 'OK'
        """;
    public Reservation reserve(long memberId) {
        try {
            if(!settings.valid())throw new CustomException(ErrorCode.AI_UNAVAILABLE);
            // UTC 자정 사이 요청은 실패 처리하고 재시도하지 않는다.
            var now=Instant.ofEpochSecond(redis.execute((org.springframework.data.redis.core.RedisCallback<Long>) c -> c.serverCommands().time(java.util.concurrent.TimeUnit.SECONDS)));
            var date=now.atZone(ZoneOffset.UTC).toLocalDate();
            // 가격 인상 경계를 통과할 수 있는 요청은 새 단가로 보수적으로 예약한다.
            var pricingDate=now.plusSeconds(15).atZone(ZoneOffset.UTC).toLocalDate();
            int inputRate=settings.inputRate(pricingDate), outputRate=settings.outputRate(pricingDate);
            String month="ai:{quota}:month:"+date.withDayOfMonth(1);
            String id=UUID.randomUUID().toString(), key="ai:{quota}:request:"+id;
            var keys=List.of("ai:{quota}:minute:"+memberId,"ai:{quota}:member:"+memberId+":"+date,"ai:{quota}:day:"+date,month,key);
            long expires=date.withDayOfMonth(1).plusMonths(1).plusDays(2).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
            String result=redis.execute(new DefaultRedisScript<>(RESERVE,String.class),keys,
                    ""+date.toEpochDay(),""+(6000L*inputRate+2048L*outputRate),""+settings.budgetUnits(),id,""+expires);
            if ("LIMIT".equals(result)) throw new CustomException(ErrorCode.AI_RATE_LIMIT);
            if ("BUDGET".equals(result)) throw new CustomException(ErrorCode.AI_BUDGET_LIMIT);
            if (!"OK".equals(result)) throw new CustomException(ErrorCode.AI_UNAVAILABLE);
            return new Reservation(month,key,inputRate,outputRate);
        } catch (CustomException e) { throw e; }
        catch (RuntimeException e) { throw new CustomException(ErrorCode.AI_UNAVAILABLE); }
    }
    public void settle(Reservation reservation, AiModel.Usage usage) {
        // 불완전하거나 상한 밖의 사용량은 환급 근거로 사용하지 않는다.
        if (usage==null || usage.inputTokens()<0 || usage.inputTokens()>6000 || usage.outputTokens()<0 || usage.outputTokens()>2048) return;
        long actual=usage.inputTokens()*(long)reservation.inputRate()+usage.outputTokens()*(long)reservation.outputRate();
        try {
            String result=redis.execute(new DefaultRedisScript<>("""
                if redis.call('HGET',KEYS[2],'state')~='RESERVED' then return 'INVALID' end
                local reserved=tonumber(redis.call('HGET',KEYS[2],'cost'))
                local total=tonumber(redis.call('GET',KEYS[1]) or '-1')
                if total<reserved or tonumber(ARGV[1])>reserved then return 'INVALID' end
                redis.call('DECRBY',KEYS[1],reserved-tonumber(ARGV[1]))
                redis.call('HSET',KEYS[2],'state','SETTLED','cost',ARGV[1]); return 'OK'
                """,String.class),List.of(reservation.month(),reservation.key()),""+actual);
            if (!"OK".equals(result)) throw new CustomException(ErrorCode.AI_UNAVAILABLE);
        } catch (RuntimeException e) { throw new CustomException(ErrorCode.AI_UNAVAILABLE); }
    }
}
