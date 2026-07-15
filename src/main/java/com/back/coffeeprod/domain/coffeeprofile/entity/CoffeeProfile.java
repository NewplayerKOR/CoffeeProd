package com.back.coffeeprod.domain.coffeeprofile.entity;

import com.back.coffeeprod.domain.product.entity.RoastLevel;
import com.back.coffeeprod.global.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.BatchSize;

import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
@Table(name = "coffee_profile")
public class CoffeeProfile extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "coffee_profile_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "processing_method_id")
    private ProcessingMethod processingMethod;

    @Column(name = "profile_name", nullable = false, length = 150)
    private String profileName;

    @Enumerated(EnumType.STRING)
    @Column(name = "bean_type", nullable = false, length = 30)
    private BeanType beanType;

    @Column(name = "origin_country_code", length = 2)
    private String originCountryCode;

    @Column(name = "origin_region", length = 100)
    private String originRegion;

    @Column(name = "farm_or_cooperative", length = 150)
    private String farmOrCooperative;

    @Column(length = 150)
    private String producer;

    @Column(name = "altitude_min")
    private Integer altitudeMin;

    @Column(name = "altitude_max")
    private Integer altitudeMax;

    @Enumerated(EnumType.STRING)
    @Column(name = "roast_level", nullable = false, length = 30)
    private RoastLevel roastLevel;

    @Column(nullable = false)
    private boolean decaf;

    @Column(name = "decaf_method", length = 50)
    private String decafMethod;

    @Column(nullable = false, columnDefinition = "SMALLINT")
    private short acidity;

    @Column(nullable = false, columnDefinition = "SMALLINT")
    private short body;

    @Column(nullable = false, columnDefinition = "SMALLINT")
    private short sweetness;

    @Column(nullable = false, columnDefinition = "SMALLINT")
    private short aroma;

    @Column(columnDefinition = "TEXT")
    private String summary;

    // 프로필별 향미 노트를 우선순위로 관리함
    @OneToMany(
            mappedBy = "coffeeProfile",
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    @OrderBy("displayOrder ASC")
    @BatchSize(size = 50)
    private List<CoffeeProfileFlavorNote> flavorNotes = new ArrayList<>();

    // 프로필별 추천 추출법을 우선순위로 관리함
    @OneToMany(
            mappedBy = "coffeeProfile",
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    @OrderBy("displayOrder ASC")
    @BatchSize(size = 50)
    private List<CoffeeProfileBrewMethod> brewMethods = new ArrayList<>();

    @Builder
    public CoffeeProfile(
            ProcessingMethod processingMethod,
            String profileName,
            BeanType beanType,
            String originCountryCode,
            String originRegion,
            String farmOrCooperative,
            String producer,
            Integer altitudeMin,
            Integer altitudeMax,
            RoastLevel roastLevel,
            boolean decaf,
            String decafMethod,
            short acidity,
            short body,
            short sweetness,
            short aroma,
            String summary
    ) {
        this.processingMethod = processingMethod;
        this.profileName = profileName;
        this.beanType = beanType;
        this.originCountryCode = originCountryCode;
        this.originRegion = originRegion;
        this.farmOrCooperative = farmOrCooperative;
        this.producer = producer;
        this.altitudeMin = altitudeMin;
        this.altitudeMax = altitudeMax;
        this.roastLevel = roastLevel;
        this.decaf = decaf;
        this.decafMethod = decafMethod;
        this.acidity = acidity;
        this.body = body;
        this.sweetness = sweetness;
        this.aroma = aroma;
        this.summary = summary;
    }

    // 커피 프로필 정보를 수정함
    public void update(
            ProcessingMethod processingMethod,
            String profileName,
            BeanType beanType,
            String originCountryCode,
            String originRegion,
            String farmOrCooperative,
            String producer,
            Integer altitudeMin,
            Integer altitudeMax,
            RoastLevel roastLevel,
            boolean decaf,
            String decafMethod,
            short acidity,
            short body,
            short sweetness,
            short aroma,
            String summary
    ) {
        this.processingMethod = processingMethod;
        this.profileName = profileName;
        this.beanType = beanType;
        this.originCountryCode = originCountryCode;
        this.originRegion = originRegion;
        this.farmOrCooperative = farmOrCooperative;
        this.producer = producer;
        this.altitudeMin = altitudeMin;
        this.altitudeMax = altitudeMax;
        this.roastLevel = roastLevel;
        this.decaf = decaf;
        this.decafMethod = decafMethod;
        this.acidity = acidity;
        this.body = body;
        this.sweetness = sweetness;
        this.aroma = aroma;
        this.summary = summary;
    }

    // 향미 노트 연결 정보를 전체 교체함
    public void replaceFlavorNotes(
            List<CoffeeProfileFlavorNote> flavorNotes
    ) {
        this.flavorNotes.clear();
        this.flavorNotes.addAll(flavorNotes);
    }

    // 추천 추출법 연결 정보를 전체 교체함
    public void replaceBrewMethods(
            List<CoffeeProfileBrewMethod> brewMethods
    ) {
        this.brewMethods.clear();
        this.brewMethods.addAll(brewMethods);
    }
}
