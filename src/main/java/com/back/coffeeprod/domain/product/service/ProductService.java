package com.back.coffeeprod.domain.product.service;

import com.back.coffeeprod.domain.product.dto.ProductDto;
import com.back.coffeeprod.domain.product.entity.Category;
import com.back.coffeeprod.domain.product.entity.Product;
import com.back.coffeeprod.domain.product.entity.ProductStatus;
import com.back.coffeeprod.domain.product.entity.RoastLevel;
import com.back.coffeeprod.domain.product.repository.ProductRepository;
import com.back.coffeeprod.global.exception.CustomException;
import com.back.coffeeprod.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductService {

    private final ProductRepository productRepository;
    private final CategoryService categoryService;  // Repository대신 Service 사용(동일한 예외처리 로직 중복 작성 방지)

    // [공개] 상품 목록 조회 (검색 / 필터 / 정렬 통합)
    public Page<ProductDto.SummaryResponse> getProducts(
            Long categoryId,
            RoastLevel roastLevel,
            String keyword,
            Pageable pageable) {

        // 일반 사용자는 ON_SALE 상품만 조회 가능
        return productRepository
                .findAllWithFilters(categoryId, roastLevel, ProductStatus.ON_SALE, keyword, pageable)
                .map(ProductDto.SummaryResponse::new);
    }

    // [공개] 상품 상세 조회
    public ProductDto.DetailResponse getProduct(Long productId) {
        Product product = findProductById(productId);

        // HIDDEN 상태 상품은 일반 사용자에게 404 반환
        if (product.getStatus() == ProductStatus.HIDDEN) {
            throw new CustomException(ErrorCode.PRODUCT_NOT_FOUND);
        }

        return new ProductDto.DetailResponse(product);
    }

    // [관리자] 상품 목록 조회
    public Page<ProductDto.SummaryResponse> getAdminProducts(
            Long categoryId,
            RoastLevel roastLevel,
            ProductStatus status,
            String keyword,
            Pageable pageable) {

        // 관리자는 ON_SALE, SOLD_OUT, HIDDEN 상품을 모두 조회 가능
        return productRepository
                .findAllWithFilters(categoryId, roastLevel, status, keyword, pageable)
                .map(ProductDto.SummaryResponse::new);
    }

    // [관리자] 상품 상세 조회
    public ProductDto.DetailResponse getAdminProduct(Long productId) {
        Product product = findProductById(productId);
        return new ProductDto.DetailResponse(product);
    }

    // [관리자] 상품 등록
    @Transactional
    public ProductDto.DetailResponse createProduct(ProductDto.Request request) {
        Category category = categoryService.findCategoryById(request.getCategoryId());

        Product product = Product.builder()
                .category(category)
                .name(request.getName())
                .price(request.getPrice())
                .stockQuantity(request.getStockQuantity())
                .roastLevel(request.getRoastLevel())
                .description(request.getDescription())
                .imageUrl(request.getImage_url())
                .build();

        return new ProductDto.DetailResponse(productRepository.save(product));
    }

    // [관리자] 상품 전체 수정
    @Transactional
    public ProductDto.DetailResponse updateProduct(Long productId, ProductDto.Request request) {
        Product product = findProductById(productId);
        Category category = categoryService.findCategoryById(request.getCategoryId());

        product.update(
                category,
                request.getName(),
                request.getPrice(),
                request.getStockQuantity(),
                request.getRoastLevel(),
                request.getDescription(),
                request.getImage_url()
        );

        return new ProductDto.DetailResponse(product); // Dirty Checking으로 자동 UPDATE
    }

    // [관리자] 상품 상태 변경
    @Transactional
    public ProductDto.DetailResponse updateProductStatus(Long productId, ProductDto.StatusRequest request) {
        Product product = findProductById(productId);
        product.updateStatus(request.getStatus());
        return new ProductDto.DetailResponse(product);
    }

    // [관리자] 재고 수동 추가 (입고)
    @Transactional
    public ProductDto.DetailResponse addStock(Long productId, ProductDto.StockRequest request) {
        Product product = findProductById(productId);
        product.addStock(request.getQuantity());
        return new ProductDto.DetailResponse(product);
    }

    // [관리자] 상품 삭제 처리
    @Transactional
    public void deleteProduct(Long productId) {
        Product product = findProductById(productId);

        // 주문 이력 보존을 위해 실제 삭제 대신 숨김 상태로 변경
        product.updateStatus(ProductStatus.HIDDEN);
    }


    // [내부 공용] ID로 상품 조회 (없으면 예외)
    public Product findProductById(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new CustomException(ErrorCode.PRODUCT_NOT_FOUND));
    }
}
