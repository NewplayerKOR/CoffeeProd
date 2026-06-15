package com.back.coffeeprod.domain.product.service;

import com.back.coffeeprod.domain.address.repository.AddressRepository;
import com.back.coffeeprod.domain.cart.repository.CartItemRepository;
import com.back.coffeeprod.domain.cart.repository.CartRepository;
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

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379",
        "spring.data.redis.password=test",
        "jwt.secret-key=coffeeprod-test-secret-key-32bytes-minimum",
        "jwt.access-expiration=1800000",
        "jwt.refresh-expiration=1209600000",
        "pg.toss.client-key=test-client-key",
        "pg.toss.secret-key=test-secret-key"
})
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
            CategoryRepository categoryRepository
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
    }

    @BeforeEach
    void setUp() {
        paymentRepository.deleteAll();
        orderRepository.deleteAll();
        cartItemRepository.deleteAll();
        cartRepository.deleteAll();
        addressRepository.deleteAll();
        productRepository.deleteAll();
        categoryRepository.deleteAll();
        memberRepository.deleteAll();
    }

    @Test
    void createCategory_rejectsDuplicateName() {
        categoryService.createCategory(categoryRequest("싱글 오리진"));

        CustomException exception = assertThrows(CustomException.class, () ->
                categoryService.createCategory(categoryRequest("싱글 오리진"))
        );

        assertEquals(ErrorCode.DUPLICATE_CATEGORY_NAME, exception.getErrorCode());
    }

    @Test
    void updateCategory_rejectsNameUsedByAnotherCategory() {
        CategoryDto.Response firstCategory = categoryService.createCategory(categoryRequest("싱글 오리진"));
        categoryService.createCategory(categoryRequest("블렌드"));

        CustomException exception = assertThrows(CustomException.class, () ->
                categoryService.updateCategory(firstCategory.getId(), categoryRequest("블렌드"))
        );

        assertEquals(ErrorCode.DUPLICATE_CATEGORY_NAME, exception.getErrorCode());
    }

    @Test
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
        assertEquals(ProductStatus.ON_SALE, response.getStatus());
        assertEquals(1, productRepository.count());
    }

    @Test
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
                PageRequest.of(0, 10)
        );

        assertEquals(1, response.getTotalElements());
        assertEquals("판매 원두", response.getContent().get(0).getName());
    }

    private CategoryDto.Request categoryRequest(String name) {
        CategoryDto.Request request = new CategoryDto.Request();
        ReflectionTestUtils.setField(request, "name", name);
        return request;
    }

    private ProductDto.Request productRequest(Long categoryId, String name, int price, int stockQuantity) {
        ProductDto.Request request = new ProductDto.Request();
        ReflectionTestUtils.setField(request, "categoryId", categoryId);
        ReflectionTestUtils.setField(request, "name", name);
        ReflectionTestUtils.setField(request, "price", price);
        ReflectionTestUtils.setField(request, "stockQuantity", stockQuantity);
        ReflectionTestUtils.setField(request, "roastLevel", RoastLevel.MEDIUM);
        ReflectionTestUtils.setField(request, "description", "테스트 상품 설명");
        ReflectionTestUtils.setField(request, "image_url", "https://example.com/coffee.jpg");
        return request;
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
