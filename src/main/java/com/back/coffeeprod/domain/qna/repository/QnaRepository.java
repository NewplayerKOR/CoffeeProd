package com.back.coffeeprod.domain.qna.repository;

import com.back.coffeeprod.domain.qna.entity.Qna;
import com.back.coffeeprod.domain.qna.entity.QnaStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface QnaRepository extends JpaRepository<Qna, Long> {

    // 상품별 문의 목록을 조회함
    @Query(
            value = """
                    SELECT q
                    FROM Qna q
                    JOIN FETCH q.member
                    JOIN FETCH q.product
                    LEFT JOIN FETCH q.answerer
                    WHERE q.product.id = :productId
                    """,
            countQuery = """
                    SELECT COUNT(q)
                    FROM Qna q
                    WHERE q.product.id = :productId
                    """
    )
    Page<Qna> findByProductId(
            @Param("productId") Long productId,
            Pageable pageable
    );

    // 관리자 문의 목록을 상태별로 조회함
    @Query(
            value = """
                    SELECT q
                    FROM Qna q
                    JOIN FETCH q.member
                    JOIN FETCH q.product
                    LEFT JOIN FETCH q.answerer
                    WHERE (:status IS NULL OR q.status = :status)
                    """,
            countQuery = """
                    SELECT COUNT(q)
                    FROM Qna q
                    WHERE (:status IS NULL OR q.status = :status)
                    """
    )
    Page<Qna> findAllByStatus(
            @Param("status") QnaStatus status,
            Pageable pageable
    );

    // 문의 연관 데이터를 함께 조회함
    @Query("""
            SELECT q
            FROM Qna q
            JOIN FETCH q.member
            JOIN FETCH q.product
            LEFT JOIN FETCH q.answerer
            WHERE q.id = :qnaId
            """)
    Optional<Qna> findByIdWithDetails(@Param("qnaId") Long qnaId);
}
