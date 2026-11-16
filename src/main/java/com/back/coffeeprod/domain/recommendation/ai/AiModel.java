package com.back.coffeeprod.domain.recommendation.ai;

import java.time.Duration;

// 제공사 응답은 업무 로직과 분리하고 추론 토큰을 포함한 사용량을 전달한다.
public interface AiModel {
    boolean available();
    Result generate(String input, Duration timeout);
    record Usage(int inputTokens, int outputTokens) {}
    record Result(String text, boolean complete, Usage usage) {}
}
