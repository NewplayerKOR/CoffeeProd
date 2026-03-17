package com.back.coffeeprod.global.common.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;

@Getter
@MappedSuperclass // 이 클래스 상속시 필드들을 칼럼으로 인식
@EntityListeners(AuditingEntityListener.class) // Auditing 기능 포함
public abstract class BaseTimeEntity {

    @CreatedDate // Entity 생성시 자동으로 현재 시간 저장
    @Column(updatable = false, name = "created_at")
    private LocalDateTime createdAt;

    @LastModifiedDate // Entity 수정시 자동 업데이트
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
