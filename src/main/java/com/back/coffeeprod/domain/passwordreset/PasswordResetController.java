package com.back.coffeeprod.domain.passwordreset;

import com.back.coffeeprod.global.common.CommonResponse;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.data.redis.RedisConnectionFailureException;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth/password-reset")
public class PasswordResetController {
    private final PasswordResetService service;
    public PasswordResetController(PasswordResetService service) { this.service = service; }
    public record Send(@NotBlank @Email @Size(max=100) String email, @Pattern(regexp="[A-Za-z0-9_-]{43}") String transaction) { }
    public record Verify(@NotBlank @Email @Size(max=100) String email, @NotNull @Pattern(regexp="[A-Za-z0-9_-]{43}") String transaction,
                         @NotNull @Pattern(regexp="[0-9]{6}") String code) { }
    public record Reset(@NotBlank @Email @Size(max=100) String email, @NotNull @Pattern(regexp="[A-Za-z0-9_-]{43}") String transaction,
                        @NotNull @Pattern(regexp="[A-Za-z0-9_-]{43}") String grant,
                        @NotBlank @Size(min=8,max=100) String newPassword, @NotBlank @Size(min=8,max=100) String confirmPassword) { }
    @ModelAttribute void noStore(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }
    @PostMapping("/send") public CommonResponse<?> send(@Valid @RequestBody Send body, HttpServletRequest request) {
        String id = service.send(body.email().trim(), body.transaction(), request.getRemoteAddr());
        return CommonResponse.success(Map.of("transaction", id, "message", "복구 가능한 계정이면 가입 이메일로 인증번호를 보냅니다. 소셜 가입 회원은 연결된 소셜 로그인을 이용해 주세요."));
    }
    @PostMapping("/verify") public CommonResponse<?> verify(@Valid @RequestBody Verify body) {
        return CommonResponse.success(Map.of("grant", service.verify(body.email().trim(), body.transaction(), body.code())));
    }
    @PostMapping("/reset") public CommonResponse<?> reset(@Valid @RequestBody Reset body) {
        service.reset(body.email().trim(), body.transaction(), body.grant(), body.newPassword(), body.confirmPassword());
        return CommonResponse.success(Map.of("reset", true));
    }
    @ExceptionHandler(RedisConnectionFailureException.class) public ResponseEntity<?> unavailable() {
        return ResponseEntity.status(503).body(CommonResponse.error(503, "인증 서비스를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요."));
    }
}
