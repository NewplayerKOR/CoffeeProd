package com.back.coffeeprod.domain.product.repository;

import com.back.coffeeprod.domain.product.entity.Product;
import com.back.coffeeprod.domain.product.entity.ProductStatus;
import com.back.coffeeprod.domain.product.entity.RoastLevel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, Long> {

    // 상품 목록 조회: 카테고리, 로스팅 강도, 상태, 이름 검색 복합필터
    @Query("""
        SELECT p FROM Product p
        JOIN FETCH p.category
        WHERE (:categoryId IS NULL OR p.category.id = :categoryId)
        AND (:roastLevel IS NULL OR p.roastLevel = :roastLevel)
        AND (:status IS NULL OR p.status = :status)
        AND (:keyword IS NULL OR p.name LIKE %:keyword%)
        """)
    Page<Product> findAllWithFilters(
            @Param("categoryId") Long categoryId,
            @Param("roastLevel") RoastLevel roastLevel,
            @Param("status") ProductStatus status,
            @Param("keyword") String keyword,
            Pageable pageable
    );

    // 재고 차감 쿼리
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE Product p
        SET p.stockQuantity = p.stockQuantity - :quantity
        WHERE p.id = :productId
        AND p.stockQuantity >= :quantity
        """)
    int decreaseStockIfEnough(
            @Param("productId") Long productId,
            @Param("quantity") int quantity
    );
}
