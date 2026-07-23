package com.back.coffeeprod.domain.product.service;

import com.back.coffeeprod.domain.address.repository.AddressRepository;
import com.back.coffeeprod.domain.cart.repository.CartItemRepository;
import com.back.coffeeprod.domain.cart.repository.CartRepository;
import com.back.coffeeprod.domain.coffeeprofile.entity.BeanType;
import com.back.coffeeprod.domain.coffeeprofile.entity.CoffeeProfile;
import com.back.coffeeprod.domain.coffeeprofile.entity.ProcessingMethod;
import com.back.coffeeprod.domain.coffeeprofile.repository.CoffeeProfileRepository;
import com.back.coffeeprod.domain.coffeeprofile.repository.ProcessingMethodRepository;
import com.back.coffeeprod.domain.member.repository.MemberRepository;
import com.back.coffeeprod.domain.order.repository.OrderRepository;
import com.back.coffeeprod.domain.payment.repository.PaymentRepository;
import com.back.coffeeprod.domain.product.dto.CategoryDto;
import com.back.coffeeprod.domain.product.dto.ProductDto;
import com.back.coffeeprod.domain.product.entity.ProductStatus;
import com.back.coffeeprod.domain.product.entity.RoastLevel;
import com.back.coffeeprod.domain.product.repository.CategoryRepository;
import com.back.coffeeprod.domain.product.repository.ProductRepository;
import com.back.coffeeprod.global.exception.CustomException;
import com.back.coffeeprod.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb;MODE=PostgreSQL;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379",
        "spring.data.redis.password=test",
        "jwt.secret-key=coffeeprod-test-secret-key-32bytes-minimum",
        "jwt.access-expiration=1800000",
        "jwt.refresh-expiration=1209600000",
        "pg.toss.client-key=test-client-key",
        "pg.toss.secret-key=test-secret-key"
})
@DisplayName("상품 서비스 통합 테스트")
class ProductServiceIntegrationTest {

    private final ProductService productService;
    private final CategoryService categoryService;
    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final CartItemRepository cartItemRepository;
    private final CartRepository cartRepository;
    private final AddressRepository addressRepository;
    private final MemberRepository memberRepository;
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final CoffeeProfileRepository coffeeProfileRepository;
    private final ProcessingMethodRepository processingMethodRepository;
    private int skuSequence;

    @Autowired
    ProductServiceIntegrationTest(
            ProductService productService,
            CategoryService categoryService,
            PaymentRepository paymentRepository,
            OrderRepository orderRepository,
            CartItemRepository cartItemRepository,
            CartRepository cartRepository,
            AddressRepository addressRepository,
            MemberRepository memberRepository,
            ProductRepository productRepository,
            CategoryRepository categoryRepository,
            CoffeeProfileRepository coffeeProfileRepository,
            ProcessingMethodRepository processingMethodRepository
    ) {
        this.productService = productService;
        this.categoryService = categoryService;
        this.paymentRepository = paymentRepository;
        this.orderRepository = orderRepository;
        this.cartItemRepository = cartItemRepository;
        this.cartRepository = cartRepository;
        this.addressRepository = addressRepository;
        this.memberRepository = memberRepository;
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.coffeeProfileRepository = coffeeProfileRepository;
        this.processingMethodRepository = processingMethodRepository;
    }

    @BeforeEach
    void setUp() {
        skuSequence = 0;
        paymentRepository.deleteAll();
        orderRepository.deleteAll();
        cartItemRepository.deleteAll();
        cartRepository.deleteAll();
        addressRepository.deleteAll();
        productRepository.deleteAll();
        coffeeProfileRepository.deleteAll();
        processingMethodRepository.deleteAll();
        categoryRepository.deleteAll();
        memberRepository.deleteAll();
    }

    @Test
    @DisplayName("중복된 이름의 카테고리를 생성할 수 없다")
    void createCategory_rejectsDuplicateName() {
        categoryService.createCategory(categoryRequest("싱글 오리진"));

        CustomException exception = assertThrows(CustomException.class, () ->
                categoryService.createCategory(categoryRequest("싱글 오리진"))
        );

        assertEquals(ErrorCode.DUPLICATE_CATEGORY_NAME, exception.getErrorCode());
    }

    @Test
    @DisplayName("다른 카테고리가 사용 중인 이름으로 수정할 수 없다")
    void updateCategory_rejectsNameUsedByAnotherCategory() {
        CategoryDto.Response firstCategory = categoryService.createCategory(categoryRequest("싱글 오리진"));
        categoryService.createCategory(categoryRequest("블렌드"));

        CustomException exception = assertThrows(CustomException.class, () ->
                categoryService.updateCategory(firstCategory.getId(), categoryRequest("블렌드"))
        );

        assertEquals(ErrorCode.DUPLICATE_CATEGORY_NAME, exception.getErrorCode());
    }

    @Test
    @DisplayName("카테고리 상세 정보를 조회한다")
    void getCategory_returnsCategoryDetail() {
        CategoryDto.Response category = categoryService.createCategory(categoryRequest("싱글 오리진"));

        // 공개 카테고리 단건 조회는 등록된 카테고리 정보를 그대로 반환한다.
        CategoryDto.Response response = categoryService.getCategory(category.getId());

        assertEquals(category.getId(), response.getId());
        assertEquals("싱글 오리진", response.getName());
    }

    @Test
    @DisplayName("상품이 없는 카테고리를 삭제한다")
    void deleteCategory_removesCategoryWithoutProducts() {
        CategoryDto.Response category = categoryService.createCategory(categoryRequest("삭제 가능 카테고리"));

        categoryService.deleteCategory(category.getId());

        CustomException exception = assertThrows(CustomException.class, () ->
                categoryService.getCategory(category.getId())
        );

        assertEquals(ErrorCode.CATEGORY_NOT_FOUND, exception.getErrorCode());
    }

    @Test
    @DisplayName("상품이 사용 중인 카테고리는 삭제할 수 없다")
    void deleteCategory_rejectsCategoryInUse() {
        CategoryDto.Response category = categoryService.createCategory(categoryRequest("상품 연결 카테고리"));
        productService.createProduct(productRequest(category.getId(), "연결 상품", 10_000, 5));

        CustomException exception = assertThrows(CustomException.class, () ->
                categoryService.deleteCategory(category.getId())
        );

        assertEquals(ErrorCode.CATEGORY_IN_USE, exception.getErrorCode());
        assertTrue(categoryRepository.existsById(category.getId()));
    }

    @Test
    @DisplayName("카테고리가 지정된 상품을 저장한다")
    void createProduct_savesProductWithCategory() {
        CategoryDto.Response category = categoryService.createCategory(categoryRequest("디카페인"));

        ProductDto.DetailResponse response = productService.createProduct(productRequest(
                category.getId(),
                "콜롬비아 디카페인",
                18_000,
                20
        ));

        assertEquals("콜롬비아 디카페인", response.getName());
        assertEquals("디카페인", response.getCategoryName());
        assertEquals("TEST-SKU-001", response.getSku());
        assertEquals(200, response.getWeightGrams());
        assertEquals(ProductStatus.ON_SALE, response.getStatus());
        assertEquals(1, productRepository.count());
    }

    @Test
    @DisplayName("관리자는 숨김 상품의 상세 정보를 조회할 수 있다")
    void getAdminProduct_returnsHiddenProductDetail() {
        CategoryDto.Response category = categoryService.createCategory(categoryRequest("관리자 상세"));
        ProductDto.DetailResponse product = productService.createProduct(productRequest(
                category.getId(),
                "숨김 상세 상품",
                14_000,
                8
        ));
        productService.updateProductStatus(product.getId(), statusRequest(ProductStatus.HIDDEN));

        // 관리자는 일반 사용자에게 숨김 처리된 상품도 상세 조회할 수 있다.
        ProductDto.DetailResponse response = productService.getAdminProduct(product.getId());

        assertEquals(product.getId(), response.getId());
        assertEquals(ProductStatus.HIDDEN, response.getStatus());
    }

    @Test
    @DisplayName("관리자는 판매 상태와 관계없이 전체 상품을 조회할 수 있다")
    void getAdminProducts_returnsProductsRegardlessOfStatus() {
        CategoryDto.Response category = categoryService.createCategory(categoryRequest("관리자 목록"));
        productService.createProduct(productRequest(category.getId(), "판매 상품", 10_000, 10));

        ProductDto.DetailResponse soldOutProduct = productService.createProduct(productRequest(
                category.getId(),
                "품절 상품",
                11_000,
                0
        ));
        productService.updateProductStatus(soldOutProduct.getId(), statusRequest(ProductStatus.SOLD_OUT));

        ProductDto.DetailResponse hiddenProduct = productService.createProduct(productRequest(
                category.getId(),
                "숨김 상품",
                12_000,
                5
        ));
        productService.updateProductStatus(hiddenProduct.getId(), statusRequest(ProductStatus.HIDDEN));

        // status 필터를 비우면 관리자는 ON_SALE, SOLD_OUT, HIDDEN 상품을 모두 조회한다.
        Page<ProductDto.SummaryResponse> response = productService.getAdminProducts(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                PageRequest.of(0, 10)
        );

        assertEquals(3, response.getTotalElements());
    }

    @Test
    @DisplayName("상품 재고를 추가하면 재고 수량이 증가한다")
    void addStock_increasesProductStockQuantity() {
        CategoryDto.Response category = categoryService.createCategory(categoryRequest("블렌드"));
        ProductDto.DetailResponse product = productService.createProduct(productRequest(
                category.getId(),
                "하우스 블렌드",
                15_000,
                3
        ));

        ProductDto.DetailResponse response = productService.addStock(product.getId(), stockRequest(7));

        assertEquals(10, response.getStockQuantity());
    }

    @Test
    @DisplayName("상품 삭제 시 물리 삭제하지 않고 숨김 상태로 변경한다")
    void deleteProduct_hidesProductInsteadOfPhysicalDelete() {
        CategoryDto.Response category = categoryService.createCategory(categoryRequest("삭제 상품"));
        ProductDto.DetailResponse product = productService.createProduct(productRequest(
                category.getId(),
                "삭제 요청 상품",
                16_000,
                10
        ));

        productService.deleteProduct(product.getId());

        // 상품 삭제는 주문 이력 보호를 위해 물리 삭제가 아니라 HIDDEN 상태 변경으로 처리한다.
        ProductDto.DetailResponse adminResponse = productService.getAdminProduct(product.getId());
        assertEquals(ProductStatus.HIDDEN, adminResponse.getStatus());

        CustomException exception = assertThrows(CustomException.class, () ->
                productService.getProduct(product.getId())
        );
        assertEquals(ErrorCode.PRODUCT_NOT_FOUND, exception.getErrorCode());
    }

    @Test
    @DisplayName("공개 상품 상세 조회에서 숨김 상품을 노출하지 않는다")
    void getProduct_hidesHiddenProductFromPublicDetail() {
        CategoryDto.Response category = categoryService.createCategory(categoryRequest("시즌 한정"));
        ProductDto.DetailResponse product = productService.createProduct(productRequest(
                category.getId(),
                "겨울 한정 블렌드",
                20_000,
                10
        ));
        productService.updateProductStatus(product.getId(), statusRequest(ProductStatus.HIDDEN));

        CustomException exception = assertThrows(CustomException.class, () ->
                productService.getProduct(product.getId())
        );

        assertEquals(ErrorCode.PRODUCT_NOT_FOUND, exception.getErrorCode());
    }

    @Test
    @DisplayName("공개 상품 목록에는 판매 중인 상품만 조회한다")
    void getProducts_returnsOnlyOnSaleProducts() {
        CategoryDto.Response category = categoryService.createCategory(categoryRequest("원두"));
        productService.createProduct(productRequest(category.getId(), "판매 원두", 10_000, 10));
        ProductDto.DetailResponse hiddenProduct = productService.createProduct(productRequest(
                category.getId(),
                "숨김 원두",
                12_000,
                10
        ));
        productService.updateProductStatus(hiddenProduct.getId(), statusRequest(ProductStatus.HIDDEN));

        // 공개 상품 목록 조회는 ON_SALE 상품만 반환한다.
        Page<ProductDto.SummaryResponse> response = productService.getProducts(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                PageRequest.of(0, 10)
        );

        assertEquals(1, response.getTotalElements());
        assertEquals("판매 원두", response.getContent().get(0).getName());
    }

    @Test
    @DisplayName("중복된 SKU의 상품을 생성할 수 없다")
    void createProduct_rejectsDuplicateSku() {
        CategoryDto.Response category = categoryService.createCategory(categoryRequest("SKU 검증"));
        productService.createProduct(productRequest(
                category.getId(),
                null,
                "COLOMBIA-200G",
                200,
                "콜롬비아 200g",
                15_000,
                10
        ));

        CustomException exception = assertThrows(CustomException.class, () ->
                productService.createProduct(productRequest(
                        category.getId(),
                        null,
                        "COLOMBIA-200G",
                        500,
                        "콜롬비아 500g",
                        30_000,
                        10
                ))
        );

        assertEquals(ErrorCode.DUPLICATE_PRODUCT_SKU, exception.getErrorCode());
    }

    @Test
    @DisplayName("상품 수정 시 기존 SKU를 유지하고 중량을 변경한다")
    void updateProduct_keepsOwnSkuAndUpdatesWeight() {
        CategoryDto.Response category = categoryService.createCategory(categoryRequest("SKU 수정"));
        ProductDto.DetailResponse created = productService.createProduct(productRequest(
                category.getId(),
                null,
                "ETHIOPIA-200G",
                200,
                "에티오피아 200g",
                18_000,
                10
        ));

        ProductDto.DetailResponse response = productService.updateProduct(
                created.getId(),
                productRequest(
                        category.getId(),
                        null,
                        "ETHIOPIA-200G",
                        500,
                        "에티오피아 500g",
                        38_000,
                        10
                )
        );

        assertEquals("ETHIOPIA-200G", response.getSku());
        assertEquals(500, response.getWeightGrams());
        assertEquals("에티오피아 500g", response.getName());
    }

    @Test
    @DisplayName("커피 프로필 식별자로 상품 목록을 필터링한다")
    void getProducts_filtersByCoffeeProfileId() {
        CategoryDto.Response category = categoryService.createCategory(categoryRequest("프로필 필터"));
        CoffeeProfile coffeeProfile = saveCoffeeProfile("예가체프 프로필");

        ProductDto.DetailResponse linkedProduct = productService.createProduct(productRequest(
                category.getId(),
                coffeeProfile.getId(),
                "YIRGACHEFFE-200G",
                200,
                "예가체프 200g",
                19_000,
                10
        ));
        productService.createProduct(productRequest(
                category.getId(),
                null,
                "HOUSE-BLEND-200G",
                200,
                "하우스 블렌드 200g",
                15_000,
                10
        ));

        Page<ProductDto.SummaryResponse> response = productService.getProducts(
                null,
                coffeeProfile.getId(),
                null,
                null,
                null,
                null,
                null,
                PageRequest.of(0, 10)
        );

        assertEquals(1, response.getTotalElements());
        assertEquals(linkedProduct.getId(), response.getContent().get(0).getId());
        assertEquals("예가체프 프로필", response.getContent().get(0).getCoffeeProfileName());
    }

    @Test
    @DisplayName("가공 방식과 원두 유형 및 디카페인 여부로 상품을 필터링한다")
    void getProducts_filtersByProcessingMethodBeanTypeAndDecaf() {
        CategoryDto.Response category = categoryService.createCategory(categoryRequest("복합 필터"));
        ProcessingMethod washed = processingMethodRepository.save(ProcessingMethod.builder()
                .code("WASHED")
                .name("Washed")
                .description("수세식")
                .build());
        ProcessingMethod natural = processingMethodRepository.save(ProcessingMethod.builder()
                .code("NATURAL")
                .name("Natural")
                .description("건식")
                .build());
        CoffeeProfile matchingProfile = saveCoffeeProfile(
                "필터 일치 프로필", washed, BeanType.SINGLE_ORIGIN, false
        );
        CoffeeProfile differentMethod = saveCoffeeProfile(
                "가공 방식 불일치", natural, BeanType.SINGLE_ORIGIN, false
        );
        CoffeeProfile differentBeanType = saveCoffeeProfile(
                "원두 유형 불일치", washed, BeanType.BLEND, false
        );
        CoffeeProfile differentDecaf = saveCoffeeProfile(
                "디카페인 불일치", washed, BeanType.SINGLE_ORIGIN, true
        );
        ProductDto.DetailResponse matchingProduct = productService.createProduct(productRequest(
                category.getId(), matchingProfile.getId(), "FILTER-MATCH", 200, "필터 일치 상품", 18_000, 10
        ));
        productService.createProduct(productRequest(
                category.getId(), differentMethod.getId(), "FILTER-METHOD", 200, "가공 방식 불일치 상품", 18_000, 10
        ));
        productService.createProduct(productRequest(
                category.getId(), differentBeanType.getId(), "FILTER-BEAN", 200, "원두 유형 불일치 상품", 18_000, 10
        ));
        productService.createProduct(productRequest(
                category.getId(), differentDecaf.getId(), "FILTER-DECAF", 200, "디카페인 불일치 상품", 18_000, 10
        ));

        Page<ProductDto.SummaryResponse> response = productService.getProducts(
                null,
                null,
                washed.getId(),
                BeanType.SINGLE_ORIGIN,
                false,
                null,
                null,
                PageRequest.of(0, 10)
        );

        assertEquals(1, response.getTotalElements());
        assertEquals(matchingProduct.getId(), response.getContent().get(0).getId());
        assertEquals("필터 일치 프로필", response.getContent().get(0).getCoffeeProfileName());
    }

    private CategoryDto.Request categoryRequest(String name) {
        CategoryDto.Request request = new CategoryDto.Request();
        ReflectionTestUtils.setField(request, "name", name);
        return request;
    }

    private ProductDto.Request productRequest(Long categoryId, String name, int price, int stockQuantity) {
        return productRequest(
                categoryId,
                null,
                String.format("TEST-SKU-%03d", ++skuSequence),
                200,
                name,
                price,
                stockQuantity
        );
    }

    private ProductDto.Request productRequest(
            Long categoryId,
            Long coffeeProfileId,
            String sku,
            int weightGrams,
            String name,
            int price,
            int stockQuantity
    ) {
        ProductDto.Request request = new ProductDto.Request();
        ReflectionTestUtils.setField(request, "categoryId", categoryId);
        ReflectionTestUtils.setField(request, "coffeeProfileId", coffeeProfileId);
        ReflectionTestUtils.setField(request, "sku", sku);
        ReflectionTestUtils.setField(request, "weightGrams", weightGrams);
        ReflectionTestUtils.setField(request, "name", name);
        ReflectionTestUtils.setField(request, "price", price);
        ReflectionTestUtils.setField(request, "stockQuantity", stockQuantity);
        ReflectionTestUtils.setField(request, "roastLevel", RoastLevel.MEDIUM);
        ReflectionTestUtils.setField(request, "description", "테스트 상품 설명");
        ReflectionTestUtils.setField(request, "image_url", "https://example.com/coffee.jpg");
        return request;
    }

    private CoffeeProfile saveCoffeeProfile(String profileName) {
        ProcessingMethod processingMethod = processingMethodRepository.save(ProcessingMethod.builder()
                .code("WASHED")
                .name("Washed")
                .description("수세식")
                .build());

        return saveCoffeeProfile(
                profileName,
                processingMethod,
                BeanType.SINGLE_ORIGIN,
                false
        );
    }

    private CoffeeProfile saveCoffeeProfile(
            String profileName,
            ProcessingMethod processingMethod,
            BeanType beanType,
            boolean decaf
    ) {

        return coffeeProfileRepository.save(CoffeeProfile.builder()
                .processingMethod(processingMethod)
                .profileName(profileName)
                .beanType(beanType)
                .originCountryCode(beanType == BeanType.SINGLE_ORIGIN ? "ET" : null)
                .originRegion(beanType == BeanType.SINGLE_ORIGIN ? "Yirgacheffe" : null)
                .altitudeMin(1_800)
                .altitudeMax(2_100)
                .roastLevel(RoastLevel.LIGHT)
                .decaf(decaf)
                .decafMethod(decaf ? "SWISS_WATER" : null)
                .acidity((short) 5)
                .body((short) 3)
                .sweetness((short) 4)
                .aroma((short) 5)
                .summary("테스트 커피 프로필")
                .build());
    }

    private ProductDto.StatusRequest statusRequest(ProductStatus status) {
        ProductDto.StatusRequest request = new ProductDto.StatusRequest();
        ReflectionTestUtils.setField(request, "status", status);
        return request;
    }

    private ProductDto.StockRequest stockRequest(int quantity) {
        ProductDto.StockRequest request = new ProductDto.StockRequest();
        ReflectionTestUtils.setField(request, "quantity", quantity);
        return request;
    }
}
