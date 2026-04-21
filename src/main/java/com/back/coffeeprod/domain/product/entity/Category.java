package com.back.coffeeprod.domain.product.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "category")
public class Category {

    // 카테고리 id
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "category_id")
    private Long id;

    // 카테고리 이름
    @Column(nullable = false, length = 50)
    private String name;

    // Builder
    @Builder
    public Category(String name) {
        this.name = name;
    }

    // 카테고리 이름 수정
    public void updateName(String name) {
        this.name = name;
    }
}
