package com.back.coffeeprod.domain.oauth;

import com.back.coffeeprod.global.common.CommonResponse;
import com.back.coffeeprod.global.exception.*;
import com.back.coffeeprod.global.security.auth.CustomUserDetails;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.RedisConnectionFailureException;
import java.util.*;

@RestController
@RequestMapping("/api/v1/auth/oauth")
public class OAuthController {
    private final OAuthFlow flow;private final OAuthSettings settings;private final AuthStore store;
    private final OAuthAccountService accounts;private final EmailVerificationService verification;
    public OAuthController(OAuthFlow flow,OAuthSettings settings,AuthStore store,OAuthAccountService accounts,EmailVerificationService verification) {
        this.flow=flow;this.settings=settings;this.store=store;this.accounts=accounts;this.verification=verification;
    }
    public record Start(@NotNull Intent purpose,Boolean reauthenticate,String currentPassword,String confirmation) { }
    public enum Intent { LOGIN, LINK, WITHDRAW }
    public record Signup(@NotBlank @Email @Size(max=100) String email,@NotBlank @Size(max=50) String name,@NotBlank @Size(max=50) String nickname,String grant) { }
    public record SendEmail(@NotBlank @Email @Size(max=100) String email) { }
    public record VerifyEmail(@NotBlank @Email @Size(max=100) String email,@Pattern(regexp="[0-9]{6}") @NotNull String code) { }
    public record Consent(@AssertTrue boolean confirmed) { }
    private Long memberId(CustomUserDetails user){return user==null?null:user.getMember().getId();}
    @ModelAttribute
    public void guard(HttpServletRequest request,HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        if(!"GET".equals(request.getMethod()))flow.checkOrigin(request);
    }
    @GetMapping("/providers")
    public CommonResponse<?> providers(@AuthenticationPrincipal CustomUserDetails user) {
        return CommonResponse.success(Map.of("enabled",List.of("google","kakao").stream().filter(settings::enabled).toList(),
            "connected",user==null?List.of():accounts.providers(memberId(user))));
    }
    @PostMapping("/{provider}/start")
    public CommonResponse<?> start(@PathVariable String provider,@Valid @RequestBody Start body,@AuthenticationPrincipal CustomUserDetails user,HttpServletRequest request,HttpServletResponse response) {
        if(!settings.enabled(provider))throw new CustomException(ErrorCode.AUTH_PROVIDER_UNAVAILABLE);
        Long target=null;String purpose=body.purpose().name();
        if(body.purpose()!=Intent.LOGIN) {
            target=memberId(user);accounts.active(target);
            if(body.purpose()==Intent.LINK && !Boolean.TRUE.equals(body.reauthenticate()))accounts.confirmLink(target,body.currentPassword(),body.confirmation());
            if(Boolean.TRUE.equals(body.reauthenticate()) || body.purpose()==Intent.WITHDRAW) {
                if(!accounts.providers(target).contains(provider))throw new CustomException(ErrorCode.OAUTH_CONFIRMATION_REQUIRED);
                if(body.purpose()==Intent.LINK)purpose="PROVE_LINK";
            }
        } else if(Boolean.TRUE.equals(body.reauthenticate()))throw new CustomException(ErrorCode.INVALID_INPUT_VALUE);
        String url=flow.begin(response,provider,purpose,target);
        return CommonResponse.success(Map.of("authorizationUrl",url));
    }
    @PostMapping("/exchange")
    public CommonResponse<?> exchange(@AuthenticationPrincipal CustomUserDetails user,HttpServletRequest r) {
        return CommonResponse.success(accounts.exchange(flow.transaction(r),flow.ready(r),memberId(user)));
    }
    @PostMapping("/signup")
    public CommonResponse<?> signup(@Valid @RequestBody Signup body,HttpServletRequest r) {
        return CommonResponse.success(accounts.signup(flow.transaction(r),flow.ready(r),body.email().trim(),body.name().trim(),body.nickname().trim(),body.grant()));
    }
    private String signupTransaction(HttpServletRequest r) {
        var tx=flow.ready(r);accounts.requireSignupCandidate(tx);
        return flow.transaction(r);
    }
    @PostMapping("/email/send")
    public CommonResponse<?> send(@Valid @RequestBody SendEmail body,HttpServletRequest r) {
        verification.send(EmailVerificationService.Purpose.OAUTH_SIGNUP_EMAIL,body.email().trim(),signupTransaction(r),r.getRemoteAddr());
        return CommonResponse.success(Map.of("sent",true));
    }
    @PostMapping("/email/verify")
    public CommonResponse<?> verify(@Valid @RequestBody VerifyEmail body,HttpServletRequest r) {
        String grant=verification.verify(EmailVerificationService.Purpose.OAUTH_SIGNUP_EMAIL,body.email().trim(),signupTransaction(r),body.code());
        return CommonResponse.success(Map.of("grant",grant));
    }
    @PostMapping("/link")
    public CommonResponse<?> link(@Valid @RequestBody Consent body,@AuthenticationPrincipal CustomUserDetails user,HttpServletRequest r) {
        accounts.link(flow.transaction(r),flow.ready(r),memberId(user));return CommonResponse.success(Map.of("linked",true));
    }
    @PostMapping("/withdraw")
    public CommonResponse<?> withdraw(@Valid @RequestBody Consent body,@AuthenticationPrincipal CustomUserDetails user,HttpServletRequest r) {
        accounts.withdraw(flow.transaction(r),flow.ready(r),memberId(user));return CommonResponse.success(Map.of("withdrawn",true));
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<?> conflict(){return ResponseEntity.status(409).body(CommonResponse.error(409,ErrorCode.OAUTH_CONFLICT.getMessage()));}
    @ExceptionHandler(RedisConnectionFailureException.class)
    public ResponseEntity<?> unavailable(){return ResponseEntity.status(503).body(CommonResponse.error(503,ErrorCode.AUTH_PROVIDER_UNAVAILABLE.getMessage()));}
}
