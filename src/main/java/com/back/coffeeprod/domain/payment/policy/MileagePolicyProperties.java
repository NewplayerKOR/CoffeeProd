package com.back.coffeeprod.domain.payment.policy;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.mileage")
public class MileagePolicyProperties {

    private int rewardRatePercent;

    // 배송비를 제외한 상품 실결제금액 기준으로 적립 마일리지를 계산한다.
    public int calculateRewardMileage(int productTotalPrice, int usedMileage) {
        int rewardBasePrice = Math.max(0, productTotalPrice - usedMileage);
        return rewardBasePrice * rewardRatePercent / 100;
    }
}
