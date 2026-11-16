package com.back.coffeeprod.domain.recommendation.ai;

import com.back.coffeeprod.global.common.CommonResponse;
import com.back.coffeeprod.global.security.auth.CustomUserDetails;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/coffee-recommendations/ai")
@RequiredArgsConstructor
public class AiRecommendationController {
    private final AiRecommendationService service;
    public record Request(@NotBlank @Size(max=1000) String preference) {}
    @PostMapping
    public ResponseEntity<CommonResponse<List<AiCatalog.Recommendation>>> recommend(
            @AuthenticationPrincipal CustomUserDetails user,@Valid @RequestBody Request request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(CommonResponse.success(service.recommend(user.getMember().getId(),request.preference())));
    }
}
