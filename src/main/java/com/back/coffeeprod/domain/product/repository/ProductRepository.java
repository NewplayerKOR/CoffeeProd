package com.back.coffeeprod.domain.product.repository;

import com.back.coffeeprod.domain.coffeeprofile.entity.BeanType;
import com.back.coffeeprod.domain.product.entity.Product;
import com.back.coffeeprod.domain.product.entity.ProductStatus;
import com.back.coffeeprod.domain.product.entity.RoastLevel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    // 상품 목록 조회 시 카테고리와 커피 프로필을 함께 조회함
    @EntityGraph(attributePaths = {"category", "coffeeProfile"})
    @Query("""
            SELECT p
            FROM Product p
            LEFT JOIN p.coffeeProfile coffeeProfile
            LEFT JOIN coffeeProfile.processingMethod processingMethod
            WHERE (:categoryId IS NULL OR p.category.id = :categoryId)
            AND (:coffeeProfileId IS NULL OR coffeeProfile.id = :coffeeProfileId)
            AND (:processingMethodId IS NULL OR processingMethod.id = :processingMethodId)
            AND (:beanType IS NULL OR coffeeProfile.beanType = :beanType)
            AND (:decaf IS NULL OR coffeeProfile.decaf = :decaf)
            AND (:roastLevel IS NULL OR p.roastLevel = :roastLevel)
            AND (:status IS NULL OR p.status = :status)
            AND (:keyword IS NULL OR p.name LIKE %:keyword%)
            """)
    Page<Product> findAllWithFilters(
            @Param("categoryId") Long categoryId,
            @Param("coffeeProfileId") Long coffeeProfileId,
            @Param("processingMethodId") Long processingMethodId,
            @Param("beanType") BeanType beanType,
            @Param("decaf") Boolean decaf,
            @Param("roastLevel") RoastLevel roastLevel,
            @Param("status") ProductStatus status,
            @Param("keyword") String keyword,
            Pageable pageable
    );

    // 상품 상세 응답에 필요한 연관 데이터를 함께 조회함
    @EntityGraph(attributePaths = {
            "category",
            "coffeeProfile",
            "coffeeProfile.processingMethod"
    })
    @Query("""
            SELECT p
            FROM Product p
            WHERE p.id = :productId
            """)
    Optional<Product> findDetailById(@Param("productId") Long productId);

    // 추천 가능한 판매 상품과 프로필 정보를 함께 조회함
    @EntityGraph(attributePaths = {
            "category",
            "coffeeProfile",
            "coffeeProfile.processingMethod"
    })
    @Query("""
            SELECT p
            FROM Product p
            JOIN p.coffeeProfile coffeeProfile
            WHERE p.status = :status
            AND p.stockQuantity > 0
            AND (:decaf IS NULL OR coffeeProfile.decaf = :decaf)
            """)
    List<Product> findAvailableProductsForRecommendation(
            @Param("status") ProductStatus status,
            @Param("decaf") Boolean decaf
    );

    // 특정 카테고리에 연결된 상품 존재 여부를 확인함
    boolean existsByCategoryId(Long categoryId);

    // SKU 중복 여부를 확인함
    boolean existsBySku(String sku);

    // 수정 대상 외 SKU 중복 여부를 확인함
    boolean existsBySkuAndIdNot(String sku, Long productId);

    // 재고를 조건부로 차감함
    @Modifying(flushAutomatically = true)
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