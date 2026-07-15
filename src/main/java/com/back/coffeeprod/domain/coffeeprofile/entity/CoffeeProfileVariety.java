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

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "coffee_profile_variety")
public class CoffeeProfileVariety {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "coffee_profile_variety_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "coffee_profile_id", nullable = false)
    private CoffeeProfile coffeeProfile;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "coffee_variety_id", nullable = false)
    private CoffeeVariety coffeeVariety;

    @Column(name = "display_order", nullable = false, columnDefinition = "SMALLINT")
    private short displayOrder;

    private CoffeeProfileVariety(
            CoffeeProfile coffeeProfile,
            CoffeeVariety coffeeVariety,
            short displayOrder
    ) {
        this.coffeeProfile = coffeeProfile;
        this.coffeeVariety = coffeeVariety;
        this.displayOrder = displayOrder;
    }

    // 프로필과 품종 연결 정보를 생성함
    public static CoffeeProfileVariety of(
            CoffeeProfile coffeeProfile,
            CoffeeVariety coffeeVariety,
            short displayOrder
    ) {
        return new CoffeeProfileVariety(
                coffeeProfile,
                coffeeVariety,
                displayOrder
        );
    }
}
