package com.back.coffeeprod.dev.auth;

import com.back.coffeeprod.global.security.jwt.JwtUtil;
import io.jsonwebtoken.*;
import org.mockito.MockedStatic;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Observes real parsing from construction onward; never substitutes verified claims. */
public final class JwtParserProbe implements AutoCloseable {
    private final MockedStatic<Jwts> factory;
    public final AtomicInteger builds = new AtomicInteger();
    public final AtomicInteger parses = new AtomicInteger();
    public int initializationBuilds;

    public JwtParserProbe() { this(null); }
    public JwtParserProbe(Clock clock) {
        factory = mockStatic(Jwts.class, CALLS_REAL_METHODS);
        factory.when(Jwts::parser).thenAnswer(invocation -> {
            var builder = spy((JwtParserBuilder) invocation.callRealMethod());
            if (clock != null) builder.clock(clock);
            doAnswer(call -> {
                builds.incrementAndGet();
                var parser = spy((JwtParser) call.callRealMethod());
                doAnswer(parse -> { parses.incrementAndGet(); return parse.callRealMethod(); })
                        .when(parser).parseSignedClaims(anyString());
                return parser;
            }).when(builder).build();
            return builder;
        });
    }

    public JwtUtil initialize(Supplier<JwtUtil> create) {
        JwtUtil jwt = create.get(); initializationBuilds = builds.get(); resetRequest(); return jwt;
    }
    public void resetRequest() { builds.set(0); parses.set(0); }
    @Override public void close() { factory.close(); }
}
