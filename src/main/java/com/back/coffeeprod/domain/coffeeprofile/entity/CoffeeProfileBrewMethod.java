package com.back.coffeeprod.domain.coffeeprofile.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "coffee_profile_brew_method")
public class CoffeeProfileBrewMethod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "coffee_profile_brew_method_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "coffee_profile_id", nullable = false)
    private CoffeeProfile coffeeProfile;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "brew_method_id", nullable = false)
    private BrewMethod brewMethod;

    @Column(name = "display_order", nullable = false, columnDefinition = "SMALLINT")
    private short displayOrder;

    @Column(name = "recommendation_note", length = 500)
    private String recommendationNote;

    private CoffeeProfileBrewMethod(
            CoffeeProfile coffeeProfile,
            BrewMethod brewMethod,
            short displayOrder,
            String recommendationNote
    ) {
        this.coffeeProfile = coffeeProfile;
        this.brewMethod = brewMethod;
        this.displayOrder = displayOrder;
        this.recommendationNote = recommendationNote;
    }

    // 프로필 추출법 연결 정보를 생성함
    public static CoffeeProfileBrewMethod of(
            CoffeeProfile coffeeProfile,
            BrewMethod brewMethod,
            short displayOrder,
            String recommendationNote
    ) {
        return new CoffeeProfileBrewMethod(
                coffeeProfile,
                brewMethod,
                displayOrder,
                recommendationNote
        );
    }
}
