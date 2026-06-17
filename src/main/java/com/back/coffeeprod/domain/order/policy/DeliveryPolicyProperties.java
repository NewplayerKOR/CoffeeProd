package com.back.coffeeprod.domain.order.policy;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.delivery")
public class DeliveryPolicyProperties {

    private int baseFee;
    private int freeThreshold;

    // 상품 금액 기준으로 배송비 계산
    public int calculateDeliveryFee(int productTotalPrice) {
        if (productTotalPrice >= freeThreshold) {
            return 0;
        }

        return baseFee;
    }
}
