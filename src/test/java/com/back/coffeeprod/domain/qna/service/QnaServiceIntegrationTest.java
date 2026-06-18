package com.back.coffeeprod.domain.qna.service;

import com.back.coffeeprod.domain.member.entity.Member;
import com.back.coffeeprod.domain.member.entity.Role;
import com.back.coffeeprod.domain.member.repository.MemberRepository;
import com.back.coffeeprod.domain.order.repository.OrderRepository;
import com.back.coffeeprod.domain.product.entity.Category;
import com.back.coffeeprod.domain.product.entity.Product;
import com.back.coffeeprod.domain.product.entity.RoastLevel;
import com.back.coffeeprod.domain.product.repository.CategoryRepository;
import com.back.coffeeprod.domain.product.repository.ProductRepository;
import com.back.coffeeprod.domain.qna.dto.QnaDto;
import com.back.coffeeprod.domain.qna.entity.QnaStatus;
import com.back.coffeeprod.domain.qna.repository.QnaRepository;
import com.back.coffeeprod.domain.review.repository.ReviewRepository;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
class QnaServiceIntegrationTest {

    private final QnaService qnaService;
    private final QnaRepository qnaRepository;
    private final ReviewRepository reviewRepository;
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final MemberRepository memberRepository;

    @Autowired
    QnaServiceIntegrationTest(
            QnaService qnaService,
            QnaRepository qnaRepository,
            ReviewRepository reviewRepository,
            OrderRepository orderRepository,
            ProductRepository productRepository,
            CategoryRepository categoryRepository,
            MemberRepository memberRepository
    ) {
        this.qnaService = qnaService;
        this.qnaRepository = qnaRepository;
        this.reviewRepository = reviewRepository;
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.memberRepository = memberRepository;
    }

    @BeforeEach
    void setUp() {
        reviewRepository.deleteAll();
        qnaRepository.deleteAll();
        orderRepository.deleteAll();
        productRepository.deleteAll();
        categoryRepository.deleteAll();
        memberRepository.deleteAll();
    }

    @Test
    void create_savesWaitingQna() {
        Member member = saveMember("qna-writer@test.com", "qnaWriter", Role.USER);
        Product product = saveProduct("문의 대상 원두");

        QnaDto.Response response = qnaService.create(
                member.getId(),
                product.getId(),
                questionRequest("분쇄 옵션 문의", "에스프레소 분쇄도 선택이 가능한가요?")
        );

        assertEquals(product.getId(), response.getProductId());
        assertEquals("qnaWriter", response.getNickname());
        assertEquals(QnaStatus.WAITING, response.getStatus());
        assertEquals(1, qnaRepository.count());
    }

    @Test
    void getProductQnas_returnsOnlyRequestedProductsQnas() {
        Member member = saveMember("qna-list@test.com", "qnaList", Role.USER);
        Product firstProduct = saveProduct("첫 번째 원두");
        Product secondProduct = saveProduct("두 번째 원두");
        qnaService.create(member.getId(), firstProduct.getId(), questionRequest("첫 문의", "첫 상품 문의"));
        qnaService.create(member.getId(), secondProduct.getId(), questionRequest("둘째 문의", "둘째 상품 문의"));

        Page<QnaDto.Response> response = qnaService.getProductQnas(
                firstProduct.getId(),
                PageRequest.of(0, 10)
        );

        assertEquals(1, response.getTotalElements());
        assertEquals(firstProduct.getId(), response.getContent().get(0).getProductId());
    }

    @Test
    void update_changesOwnedWaitingQna() {
        Member member = saveMember("qna-update@test.com", "qnaUpdate", Role.USER);
        Product product = saveProduct("수정 문의 원두");
        QnaDto.Response created = qnaService.create(
                member.getId(),
                product.getId(),
                questionRequest("수정 전 제목", "수정 전 내용")
        );

        QnaDto.Response response = qnaService.update(
                member.getId(),
                created.getId(),
                questionRequest("수정 후 제목", "수정 후 내용")
        );

        assertEquals("수정 후 제목", response.getTitle());
        assertEquals("수정 후 내용", response.getQuestion());
    }

    @Test
    void update_rejectsOtherMembersQna() {
        Member owner = saveMember("qna-owner@test.com", "qnaOwner", Role.USER);
        Member other = saveMember("qna-other@test.com", "qnaOther", Role.USER);
        Product product = saveProduct("문의 소유권 원두");
        QnaDto.Response created = qnaService.create(
                owner.getId(),
                product.getId(),
                questionRequest("작성자 문의", "작성자 문의 내용")
        );

        CustomException exception = assertThrows(CustomException.class, () ->
                qnaService.update(
                        other.getId(),
                        created.getId(),
                        questionRequest("타인 수정", "타인 수정 내용")
                )
        );

        assertEquals(ErrorCode.QNA_ACCESS_DENIED, exception.getErrorCode());
    }

    @Test
    void delete_removesOwnedWaitingQna() {
        Member member = saveMember("qna-delete@test.com", "qnaDelete", Role.USER);
        Product product = saveProduct("삭제 문의 원두");
        QnaDto.Response created = qnaService.create(
                member.getId(),
                product.getId(),
                questionRequest("삭제할 문의", "삭제할 문의 내용")
        );

        qnaService.delete(member.getId(), created.getId());

        assertFalse(qnaRepository.existsById(created.getId()));
    }

    @Test
    void answer_marksQnaAnsweredWithAdminInfo() {
        Member member = saveMember("qna-answer@test.com", "qnaAnswer", Role.USER);
        Member admin = saveMember("qna-admin@test.com", "qnaAdmin", Role.ADMIN);
        Product product = saveProduct("답변 문의 원두");
        QnaDto.Response created = qnaService.create(
                member.getId(),
                product.getId(),
                questionRequest("배송 문의", "언제 출고되나요?")
        );

        QnaDto.Response response = qnaService.answer(
                admin.getId(),
                created.getId(),
                answerRequest("평일 오후 2시 이전 주문은 당일 출고됩니다.")
        );

        assertEquals(QnaStatus.ANSWERED, response.getStatus());
        assertEquals("qnaAdmin", response.getAnswererNickname());
        assertEquals("평일 오후 2시 이전 주문은 당일 출고됩니다.", response.getAnswer());
        assertNotNull(response.getAnsweredAt());
    }

    @Test
    void answeredQna_rejectsUpdateDeleteAndSecondAnswer() {
        Member member = saveMember("qna-locked@test.com", "qnaLocked", Role.USER);
        Member admin = saveMember("qna-lock-admin@test.com", "qnaLockAdmin", Role.ADMIN);
        Product product = saveProduct("답변 완료 원두");
        QnaDto.Response created = qnaService.create(
                member.getId(),
                product.getId(),
                questionRequest("보관 문의", "개봉 후 보관 방법이 궁금합니다.")
        );
        qnaService.answer(admin.getId(), created.getId(), answerRequest("밀폐 후 서늘한 곳에 보관해 주세요."));

        CustomException updateException = assertThrows(CustomException.class, () ->
                qnaService.update(
                        member.getId(),
                        created.getId(),
                        questionRequest("수정 시도", "수정 시도 내용")
                )
        );
        CustomException deleteException = assertThrows(CustomException.class, () ->
                qnaService.delete(member.getId(), created.getId())
        );
        CustomException answerException = assertThrows(CustomException.class, () ->
                qnaService.answer(admin.getId(), created.getId(), answerRequest("두 번째 답변"))
        );

        assertEquals(ErrorCode.QNA_ALREADY_ANSWERED, updateException.getErrorCode());
        assertEquals(ErrorCode.QNA_ALREADY_ANSWERED, deleteException.getErrorCode());
        assertEquals(ErrorCode.QNA_ALREADY_ANSWERED, answerException.getErrorCode());
    }

    private Member saveMember(String email, String nickname, Role role) {
        return memberRepository.save(Member.builder()
                .email(email)
                .password("encoded-password")
                .name("테스트회원")
                .nickname(nickname)
                .role(role)
                .build());
    }

    private Product saveProduct(String name) {
        Category category = categoryRepository.save(Category.builder()
                .name(name + " 카테고리")
                .build());

        return productRepository.save(Product.builder()
                .category(category)
                .name(name)
                .price(15_000)
                .stockQuantity(20)
                .roastLevel(RoastLevel.MEDIUM)
                .description("테스트 상품")
                .imageUrl("https://example.com/coffee.jpg")
                .build());
    }

    private QnaDto.QuestionRequest questionRequest(String title, String question) {
        QnaDto.QuestionRequest request = new QnaDto.QuestionRequest();
        ReflectionTestUtils.setField(request, "title", title);
        ReflectionTestUtils.setField(request, "question", question);
        return request;
    }

    private QnaDto.AnswerRequest answerRequest(String answer) {
        QnaDto.AnswerRequest request = new QnaDto.AnswerRequest();
        ReflectionTestUtils.setField(request, "answer", answer);
        return request;
    }
}
