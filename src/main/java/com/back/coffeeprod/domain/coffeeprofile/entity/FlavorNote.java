package com.back.coffeeprod.domain.coffeeprofile.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.BatchSize;

@Entity
@Getter
@BatchSize(size = 50)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "flavor_note")
public class FlavorNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "flavor_note_id")
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Builder
    public FlavorNote(String code, String name, String description) {
        this.code = code;
        this.name = name;
        this.description = description;
    }

    // 향미 노트 표시 정보를 수정
    public void update(String name, String description) {
        this.name = name;
        this.description = description;
    }
    
}
