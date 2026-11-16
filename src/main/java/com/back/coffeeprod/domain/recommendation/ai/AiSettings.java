package com.back.coffeeprod.domain.recommendation.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;

@Component
public record AiSettings(
        @Value("${AI_PROVIDER:gemini}") String provider,
        @Value("${AI_MODEL:gemini-4-hydrogen}") String model,
        @Value("${GEMINI_API_KEY:}") String apiKey,
        @Value("${AI_MONTHLY_BUDGET_USD:8}") BigDecimal budget) {
    // 허용 모델은 단가와 함께 관리한다. 설정만으로 고가 모델을 활성화하지 않는다.
    public boolean valid() {
        return provider.equals("gemini") && java.util.Set.of("gemini-4-hydrogen", "gemini-3.8-flash").contains(model)
                && !apiKey.isBlank() && budget.stripTrailingZeros().scale()<=9 && budget.signum() > 0 && budget.compareTo(new BigDecimal("8")) <= 0;
    }
    @Override public String toString() { return "AiSettings[provider="+provider+", model="+model+", budget="+budget+"]"; }
    private boolean discountedFlash(java.time.LocalDate date) { return model.equals("gemini-3.8-flash") && date.isBefore(java.time.LocalDate.of(2027,1,1)); }
    public int inputRate(java.time.LocalDate date) { return discountedFlash(date) ? 750 : 1500; }
    public int outputRate(java.time.LocalDate date) { return discountedFlash(date) ? 3750 : 7500; }
    public int inputRate() { return inputRate(java.time.LocalDate.now(java.time.ZoneOffset.UTC)); }
    public int outputRate() { return outputRate(java.time.LocalDate.now(java.time.ZoneOffset.UTC)); }
    public long budgetUnits() { return budget.movePointRight(9).longValueExact(); }
    // 1단위=$0.000000001. Hydrogen 입력 토큰 1,500단위, 추론 포함 출력 토큰 7,500단위.
    public static final long MAX_COST = 6000L * 1500 + 2048L * 7500;
}
