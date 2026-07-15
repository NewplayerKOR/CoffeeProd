package com.back.coffeeprod.domain.coffeeprofile.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.BatchSize;

@Entity
@Getter
@BatchSize(size = 50)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "coffee_variety")
public class CoffeeVariety {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "coffee_variety_id")
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Builder
    public CoffeeVariety(String code, String name, String description) {
        this.code = code;
        this.name = name;
        this.description = description;
    }

    // 커피 품종 표시 정보를 수정함
    public void update(String name, String description) {
        this.name = name;
        this.description = description;
    }
}
