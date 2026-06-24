package com.back.coffeeprod.global.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

@Getter
@MappedSuperclass // 이 클래스 상속시 필드들을 칼럼으로 인식
@EntityListeners(AuditingEntityListener.class) // Auditing 기능 포함
public abstract class BaseTimeEntity {

    @CreatedDate // Entity 생성시 자동으로 현재 시간 저장
    @Column(
            name = "created_at",
            updatable = false,
            columnDefinition = "TIMESTAMPTZ"
    )
    private Instant createdAt;

    @LastModifiedDate // Entity 수정시 자동 업데이트
    @Column(
            name = "updated_at",
            columnDefinition = "TIMESTAMPTZ"
    )
    private Instant updatedAt;
}
