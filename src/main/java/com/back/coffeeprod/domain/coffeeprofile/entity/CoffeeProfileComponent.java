package com.back.coffeeprod.domain.coffeeprofile.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "coffee_profile_component")
public class CoffeeProfileComponent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "coffee_profile_component_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "coffee_profile_id", nullable = false)
    private CoffeeProfile coffeeProfile;

    @Column(name = "origin_country_code", nullable = false, length = 2)
    private String originCountryCode;

    @Column(name = "origin_region", length = 100)
    private String originRegion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "processing_method_id")
    private ProcessingMethod processingMethod;

    @Column(name = "component_ratio", precision = 5, scale = 2)
    private BigDecimal componentRatio;

    @Column(name = "display_order", nullable = false, columnDefinition = "SMALLINT")
    private short displayOrder;

    private CoffeeProfileComponent(
            CoffeeProfile coffeeProfile,
            String originCountryCode,
            String originRegion,
            ProcessingMethod processingMethod,
            BigDecimal componentRatio,
            short displayOrder
    ) {
        this.coffeeProfile = coffeeProfile;
        this.originCountryCode = originCountryCode;
        this.originRegion = originRegion;
        this.processingMethod = processingMethod;
        this.componentRatio = componentRatio;
        this.displayOrder = displayOrder;
    }

    // 블렌드 구성요소를 생성함
    public static CoffeeProfileComponent of(
            CoffeeProfile coffeeProfile,
            String originCountryCode,
            String originRegion,
            ProcessingMethod processingMethod,
            BigDecimal componentRatio,
            short displayOrder
    ) {
        return new CoffeeProfileComponent(
                coffeeProfile,
                originCountryCode,
                originRegion,
                processingMethod,
                componentRatio,
                displayOrder
        );
    }
}
