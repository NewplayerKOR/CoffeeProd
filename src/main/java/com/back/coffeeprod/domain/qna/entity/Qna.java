package com.back.coffeeprod.domain.qna.entity;

import com.back.coffeeprod.domain.member.entity.Member;
import com.back.coffeeprod.domain.product.entity.Product;
import com.back.coffeeprod.global.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "product_qna")
public class Qna extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "qna_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 2000)
    private String question;

    @Column(length = 2000)
    private String answer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "answerer_id")
    private Member answerer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private QnaStatus status;

    @Column(name = "answered_at")
    private LocalDateTime answeredAt;

    public Qna(
            Member member,
            Product product,
            String title,
            String question
    ) {
        this.member = member;
        this.product = product;
        this.title = title;
        this.question = question;
        this.status = QnaStatus.WAITING;
    }

    // 문의 내용을 수정함
    public void updateQuestion(String title, String question) {
        this.title = title;
        this.question = question;
    }

    // 관리자가 답변을 등록함
    public void answer(Member answerer, String answer) {
        this.answerer = answerer;
        this.answer = answer;
        this.status = QnaStatus.ANSWERED;
        this.answeredAt = LocalDateTime.now();
    }

    // 답변 완료 여부를 반환함
    public boolean isAnswered() {
        return this.status == QnaStatus.ANSWERED;
    }
}
