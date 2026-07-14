package com.back.coffeeprod.domain.recommendation.entity;

import com.back.coffeeprod.domain.coffeeprofile.entity.BeanType;
import com.back.coffeeprod.domain.coffeeprofile.entity.ProcessingMethod;
import com.back.coffeeprod.domain.member.entity.Member;
import com.back.coffeeprod.domain.product.entity.RoastLevel;
import com.back.coffeeprod.global.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "member_coffee_preference")
public class MemberCoffeePreference extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "member_coffee_preference_id")
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false, unique = true)
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "processing_method_id")
    private ProcessingMethod processingMethod;

    @Enumerated(EnumType.STRING)
    @Column(name = "bean_type", length = 30)
    private BeanType beanType;

    @Enumerated(EnumType.STRING)
    @Column(name = "roast_level", length = 30)
    private RoastLevel roastLevel;

    @Column(name = "decaf")
    private Boolean decaf;

    @Column(name = "preferred_acidity", columnDefinition = "SMALLINT")
    private Short preferredAcidity;

    @Column(name = "preferred_body", columnDefinition = "SMALLINT")
    private Short preferredBody;

    @Column(name = "preferred_sweetness", columnDefinition = "SMALLINT")
    private Short preferredSweetness;

    @Column(name = "preferred_aroma", columnDefinition = "SMALLINT")
    private Short preferredAroma;

    @Builder
    public MemberCoffeePreference(
            Member member,
            ProcessingMethod processingMethod,
            BeanType beanType,
            RoastLevel roastLevel,
            Boolean decaf,
            Short preferredAcidity,
            Short preferredBody,
            Short preferredSweetness,
            Short preferredAroma
    ) {
        this.member = member;
        this.processingMethod = processingMethod;
        this.beanType = beanType;
        this.roastLevel = roastLevel;
        this.decaf = decaf;
        this.preferredAcidity = preferredAcidity;
        this.preferredBody = preferredBody;
        this.preferredSweetness = preferredSweetness;
        this.preferredAroma = preferredAroma;
    }

    // 회원 커피 취향을 전체 교체함
    public void update(
            ProcessingMethod processingMethod,
            BeanType beanType,
            RoastLevel roastLevel,
            Boolean decaf,
            Short preferredAcidity,
            Short preferredBody,
            Short preferredSweetness,
            Short preferredAroma
    ) {
        this.processingMethod = processingMethod;
        this.beanType = beanType;
        this.roastLevel = roastLevel;
        this.decaf = decaf;
        this.preferredAcidity = preferredAcidity;
        this.preferredBody = preferredBody;
        this.preferredSweetness = preferredSweetness;
        this.preferredAroma = preferredAroma;
    }
}