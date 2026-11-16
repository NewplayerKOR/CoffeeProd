package com.back.coffeeprod.domain.oauth;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Component
public class AuthStore {
    private final StringRedisTemplate redis;
    private static final SecureRandom RANDOM = new SecureRandom();
    public AuthStore(org.springframework.data.redis.connection.RedisConnectionFactory factory) {
        redis = new StringRedisTemplate(factory);
    }
    public static String random() {
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public void create(String id, Map<String,String> data, int seconds) {
        String key = "oauth:tx:" + id;
        redis.execute(new DefaultRedisScript<>("redis.call('HSET',KEYS[1],unpack(ARGV,2)); redis.call('EXPIRE',KEYS[1],ARGV[1]); return 1", Long.class),
                List.of(key), arguments(seconds, data));
    }
    private Object[] arguments(int seconds, Map<String,String> data) {
        List<String> args = new ArrayList<>(); args.add(""+seconds);
        data.forEach((k,v)-> { args.add(k); args.add(v); }); return args.toArray();
    }
    public Map<String,String> read(String id) {
        Map<String,String> result = new HashMap<>();
        if (id == null || !id.matches("[A-Za-z0-9_-]{43}")) return result;
        redis.<String,String>opsForHash().entries("oauth:tx:"+id).forEach(result::put); return result;
    }
    public void put(String id, String field, String value) {
        redis.execute(new DefaultRedisScript<>("if redis.call('EXISTS',KEYS[1])==0 then return 0 end; redis.call('HSET',KEYS[1],ARGV[1],ARGV[2]); return 1",Long.class),List.of("oauth:tx:"+id),field,value);
    }
    public String takeField(String id, String field) {
        return redis.execute(new DefaultRedisScript<>("local v=redis.call('HGET',KEYS[1],ARGV[1]); redis.call('HDEL',KEYS[1],ARGV[1]); return v",String.class),List.of("oauth:tx:"+id),field);
    }
    public boolean transition(String id, String expected, String next) {
        return Long.valueOf(1).equals(redis.execute(new DefaultRedisScript<>("if redis.call('HGET',KEYS[1],'status')~=ARGV[1] then return 0 end; redis.call('HSET',KEYS[1],'status',ARGV[2]); return 1",Long.class),List.of("oauth:tx:"+id),expected,next));
    }
    public void value(String key, String value, int seconds) { redis.opsForValue().set("auth:"+key,value,seconds,TimeUnit.SECONDS); }
    public String consume(String key) { return redis.opsForValue().getAndDelete("auth:"+key); }
    public String eval(String script, List<String> keys, String... args) {
        return redis.execute(new DefaultRedisScript<>(script,String.class),keys,(Object[])args);
    }
}
