package com.back.coffeeprod.domain.passwordreset;

import com.back.coffeeprod.domain.member.entity.*;
import com.back.coffeeprod.domain.member.repository.MemberRepository;
import com.back.coffeeprod.domain.oauth.*;
import com.back.coffeeprod.global.exception.*;
import com.back.coffeeprod.global.security.service.RefreshTokenService;
import jakarta.annotation.PreDestroy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.concurrent.*;
import static com.back.coffeeprod.domain.oauth.EmailVerificationService.Purpose.PASSWORD_RESET;

@Service
public class PasswordResetService {
    private final AuthStore store;
    private final EmailVerificationService verification;
    private final MemberRepository members;
    private final PasswordEncoder passwords;
    private final RefreshTokenService refresh;
    // 응답 시간으로 계정 존재 여부를 추측하지 못하도록 계정 조회·발송을 제한된 큐에서 수행한다.
    private final ThreadPoolExecutor delivery = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(80), Thread.ofPlatform().name("password-reset-mail-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());

    public PasswordResetService(AuthStore store, EmailVerificationService verification, MemberRepository members,
                                PasswordEncoder passwords, RefreshTokenService refresh) {
        this.store = store; this.verification = verification; this.members = members;
        this.passwords = passwords; this.refresh = refresh;
    }
    @PreDestroy void stop() { delivery.shutdownNow(); }
    private CustomException invalid() { return new CustomException(ErrorCode.PASSWORD_RESET_INVALID); }
    private boolean eligible(Member member) { return member.getStatus() == MemberStatus.ACTIVE && member.getPassword() != null; }
    private Map<String,String> transaction(String id, String email) {
        var tx = store.read(id);
        if (!"PASSWORD_RESET".equals(tx.get("purpose")) || !email.equals(tx.get("email"))) throw invalid();
        return tx;
    }
    public String send(String email, String existing, String ip) {
        String id = existing;
        if (id == null || id.isBlank()) {
            id = AuthStore.random();
            store.create(id, Map.of("purpose", "PASSWORD_RESET", "email", email, "status", "OPEN"), 600);
        } else if (!"OPEN".equals(transaction(id, email).get("status"))) throw invalid();
        final String target = id;
        try { delivery.execute(() -> deliver(email, target, ip)); }
        catch (RejectedExecutionException e) { throw new CustomException(ErrorCode.AUTH_PROVIDER_UNAVAILABLE); }
        return id;
    }
    private void deliver(String email, String id, String ip) {
        try {
            var tx = transaction(id, email);
            if (!"OPEN".equals(tx.get("status"))) return;
            var member = members.findByEmail(email).filter(this::eligible);
            // 재발송은 최초 회원·인증 버전을 유지한다. 복구 후 남은 모든 거래는 무효다.
            if (!tx.containsKey("member")) {
                store.put(id, "member", member.map(m -> m.getId().toString()).orElse(""));
                store.put(id, "version", member.map(m -> "" + m.getTokenVersion()).orElse(""));
            }
            verification.send(PASSWORD_RESET, email, id, ip, member.isPresent());
        } catch (RuntimeException ignored) {
            // 가입 여부·발송 결과·제한은 초기 응답에 노출하지 않는다. 인증 성공으로 대체하지 않는다.
        }
    }
    public String verify(String email, String id, String code) {
        var tx = transaction(id, email);
        if (!"OPEN".equals(tx.get("status"))) throw invalid();
        String grant;
        try { grant = verification.verify(PASSWORD_RESET, email, id, code); }
        catch (CustomException e) {
            if (e.getErrorCode() == ErrorCode.AUTH_RATE_LIMIT) throw e;
            throw invalid();
        }
        if (!store.transition(id, "OPEN", "VERIFIED")) throw invalid();
        return grant;
    }
    @Transactional
    public void reset(String email, String id, String grant, String password, String confirm) {
        if (!password.equals(confirm) || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
            throw new CustomException(ErrorCode.INVALID_INPUT_VALUE);
        var tx = transaction(id, email);
        if (!"VERIFIED".equals(tx.get("status")) || tx.getOrDefault("member", "").isBlank()) throw invalid();
        Member member = members.findLockedById(Long.valueOf(tx.get("member"))).orElseThrow(this::invalid);
        if (!eligible(member) || !email.equals(member.getEmail()) || !("" + member.getTokenVersion()).equals(tx.get("version"))) throw invalid();
        try { verification.consume(PASSWORD_RESET, email, id, grant); }
        catch (CustomException e) { throw invalid(); }
        if (!store.transition(id, "VERIFIED", "DONE")) throw invalid();
        // Redis 장애 시 DB 비밀번호도 변경하지 않는다. 버전은 DB 커밋과 함께 증가한다.
        refresh.delete(member.getId());
        member.resetPassword(passwords.encode(password));
    }
}
