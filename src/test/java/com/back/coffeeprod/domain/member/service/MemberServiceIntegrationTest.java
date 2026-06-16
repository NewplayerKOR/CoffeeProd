package com.back.coffeeprod.domain.member.service;

import com.back.coffeeprod.domain.address.repository.AddressRepository;
import com.back.coffeeprod.domain.cart.repository.CartItemRepository;
import com.back.coffeeprod.domain.cart.repository.CartRepository;
import com.back.coffeeprod.domain.member.dto.MemberDto;
import com.back.coffeeprod.domain.member.entity.Grade;
import com.back.coffeeprod.domain.member.entity.Member;
import com.back.coffeeprod.domain.member.entity.MemberStatus;
import com.back.coffeeprod.domain.member.entity.Role;
import com.back.coffeeprod.domain.member.repository.MemberRepository;
import com.back.coffeeprod.domain.order.repository.OrderRepository;
import com.back.coffeeprod.domain.payment.repository.PaymentRepository;
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
class MemberServiceIntegrationTest {

    private final MemberService memberService;
    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final CartItemRepository cartItemRepository;
    private final CartRepository cartRepository;
    private final AddressRepository addressRepository;
    private final MemberRepository memberRepository;

    @Autowired
    MemberServiceIntegrationTest(
            MemberService memberService,
            PaymentRepository paymentRepository,
            OrderRepository orderRepository,
            CartItemRepository cartItemRepository,
            CartRepository cartRepository,
            AddressRepository addressRepository,
            MemberRepository memberRepository
    ) {
        this.memberService = memberService;
        this.paymentRepository = paymentRepository;
        this.orderRepository = orderRepository;
        this.cartItemRepository = cartItemRepository;
        this.cartRepository = cartRepository;
        this.addressRepository = addressRepository;
        this.memberRepository = memberRepository;
    }

    @BeforeEach
    void setUp() {
        paymentRepository.deleteAll();
        orderRepository.deleteAll();
        cartItemRepository.deleteAll();
        cartRepository.deleteAll();
        addressRepository.deleteAll();
        memberRepository.deleteAll();
    }

    @Test
    void updateMemberGrade_changesGrade() {
        Member member = saveMember("grade@test.com", "gradeUser");

        MemberDto.AdminResponse response = memberService.updateMemberGrade(
                member.getId(),
                gradeUpdateRequest(Grade.GOLD)
        );

        assertEquals(Grade.GOLD, response.getGrade());
    }

    @Test
    void updateMemberStatus_suspendsActiveMember() {
        Member member = saveMember("status@test.com", "statusUser");

        MemberDto.AdminResponse response = memberService.updateMemberStatus(
                member.getId(),
                statusUpdateRequest(MemberStatus.SUSPENDED)
        );

        assertEquals(MemberStatus.SUSPENDED, response.getStatus());
    }

    @Test
    void updateMemberStatus_rejectsWithdrawnAsNextStatus() {
        Member member = saveMember("withdraw-next@test.com", "withdrawNext");

        CustomException exception = assertThrows(CustomException.class, () ->
                memberService.updateMemberStatus(
                        member.getId(),
                        statusUpdateRequest(MemberStatus.WITHDRAWN)
                )
        );

        assertEquals(ErrorCode.INVALID_INPUT_VALUE, exception.getErrorCode());
    }

    @Test
    void updateMemberStatus_rejectsChangingWithdrawnMember() {
        Member member = saveMember("withdraw-current@test.com", "withdrawCurrent");
        member.updateStatus(MemberStatus.WITHDRAWN);
        memberRepository.save(member);

        CustomException exception = assertThrows(CustomException.class, () ->
                memberService.updateMemberStatus(
                        member.getId(),
                        statusUpdateRequest(MemberStatus.ACTIVE)
                )
        );

        assertEquals(ErrorCode.INVALID_INPUT_VALUE, exception.getErrorCode());
    }

    @Test
    void getAllMembers_excludesWithdrawnMembersByDefault() {
        saveMember("active-list@test.com", "activeList");
        Member withdrawnMember = saveMember("withdraw-list@test.com", "withdrawList");
        withdrawnMember.updateStatus(MemberStatus.WITHDRAWN);
        memberRepository.save(withdrawnMember);

        // 관리자 회원 목록은 기본적으로 탈퇴 회원을 제외한다.
        Page<MemberDto.AdminResponse> response = memberService.getAllMembers(false, PageRequest.of(0, 20));

        assertEquals(1, response.getTotalElements());
        assertEquals(MemberStatus.ACTIVE, response.getContent().get(0).getStatus());
    }

    @Test
    void getAllMembers_includesWithdrawnMembersWhenRequested() {
        saveMember("active-all@test.com", "activeAll");
        Member withdrawnMember = saveMember("withdraw-all@test.com", "withdrawAll");
        withdrawnMember.updateStatus(MemberStatus.WITHDRAWN);
        memberRepository.save(withdrawnMember);

        // includeWithdrawn=true면 탈퇴 회원도 관리자 목록에 포함한다.
        Page<MemberDto.AdminResponse> response = memberService.getAllMembers(true, PageRequest.of(0, 20));

        assertEquals(2, response.getTotalElements());
    }

    @Test
    void getMember_returnsAdminMemberDetail() {
        Member member = saveMember("admin-detail@test.com", "adminDetail");

        // 관리자 회원 단건 조회는 회원의 식별 정보와 운영 상태를 반환한다.
        MemberDto.AdminResponse response = memberService.getMember(member.getId());

        assertEquals(member.getId(), response.getId());
        assertEquals("admin-detail@test.com", response.getEmail());
        assertEquals("adminDetail", response.getNickname());
        assertEquals(Role.USER, response.getRole());
        assertEquals(Grade.BRONZE, response.getGrade());
        assertEquals(MemberStatus.ACTIVE, response.getStatus());
    }

    private Member saveMember(String email, String nickname) {
        return memberRepository.save(Member.builder()
                .email(email)
                .password("encoded-password")
                .name("테스트회원")
                .nickname(nickname)
                .role(Role.USER)
                .build());
    }

    private MemberDto.GradeUpdateRequest gradeUpdateRequest(Grade grade) {
        MemberDto.GradeUpdateRequest request = new MemberDto.GradeUpdateRequest();
        ReflectionTestUtils.setField(request, "grade", grade);
        return request;
    }

    private MemberDto.StatusUpdateRequest statusUpdateRequest(MemberStatus status) {
        MemberDto.StatusUpdateRequest request = new MemberDto.StatusUpdateRequest();
        ReflectionTestUtils.setField(request, "status", status);
        return request;
    }
}
