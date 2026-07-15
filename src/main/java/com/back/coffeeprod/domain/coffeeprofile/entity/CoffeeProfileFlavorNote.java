package com.back.coffeeprod.domain.coffeeprofile.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "coffee_profile_flavor_note")
public class CoffeeProfileFlavorNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "coffee_profile_flavor_note_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "coffee_profile_id", nullable = false)
    private CoffeeProfile coffeeProfile;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "flavor_note_id", nullable = false)
    private FlavorNote flavorNote;

    @Column(name = "display_order", nullable = false, columnDefinition = "SMALLINT")
    private short displayOrder;

    @Column(nullable = false, columnDefinition = "SMALLINT")
    private short intensity;

    private CoffeeProfileFlavorNote(
            CoffeeProfile coffeeProfile,
            FlavorNote flavorNote,
            short displayOrder,
            short intensity
    ) {
        this.coffeeProfile = coffeeProfile;
        this.flavorNote = flavorNote;
        this.displayOrder = displayOrder;
        this.intensity = intensity;
    }

    // 프로필 향미 연결 정보를 생성
    public static CoffeeProfileFlavorNote of(
            CoffeeProfile coffeeProfile,
            FlavorNote flavorNote,
            short displayOrder,
            short intensity
    ) {
        return new CoffeeProfileFlavorNote(
                coffeeProfile,
                flavorNote,
                displayOrder,
                intensity
        );
    }
}