package com.back.coffeeprod.domain.coffeeprofile.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "processing_method")
public class ProcessingMethod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "processing_method_id")
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Builder
    public ProcessingMethod(String code, String name, String description) {
        this.code = code;
        this.name = name;
        this.description = description;
    }

    {
        this.code = code;
        this.name = name;
        this.description = description;
    }

    // 가공 방식 표시 정보를 수정함
    public void update(String name, String description) {
        this.name = name;
        this.description = description;
    }
}
