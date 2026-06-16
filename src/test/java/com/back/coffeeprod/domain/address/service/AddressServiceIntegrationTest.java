package com.back.coffeeprod.domain.address.service;

import com.back.coffeeprod.domain.address.dto.AddressDto;
import com.back.coffeeprod.domain.address.repository.AddressRepository;
import com.back.coffeeprod.domain.cart.repository.CartItemRepository;
import com.back.coffeeprod.domain.cart.repository.CartRepository;
import com.back.coffeeprod.domain.member.entity.Member;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
class AddressServiceIntegrationTest {

    private final AddressService addressService;
    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final CartItemRepository cartItemRepository;
    private final CartRepository cartRepository;
    private final AddressRepository addressRepository;
    private final MemberRepository memberRepository;

    @Autowired
    AddressServiceIntegrationTest(
            AddressService addressService,
            PaymentRepository paymentRepository,
            OrderRepository orderRepository,
            CartItemRepository cartItemRepository,
            CartRepository cartRepository,
            AddressRepository addressRepository,
            MemberRepository memberRepository
    ) {
        this.addressService = addressService;
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
    void getAddress_returnsOnlyRequestedAddress() {
        Member member = saveMember("address-detail@test.com", "addressDetail");
        AddressDto.Response firstAddress = addressService.addAddress(member.getId(), addressRequest(
                "김테스트",
                "010-1111-2222",
                "12345",
                "서울시 강남구",
                "101호"
        ));
        addressService.addAddress(member.getId(), addressRequest(
                "박테스트",
                "010-3333-4444",
                "54321",
                "서울시 마포구",
                "202호"
        ));

        // 배송지 단건 조회는 요청한 addressId에 해당하는 배송지만 반환한다.
        AddressDto.Response response = addressService.getAddress(member.getId(), firstAddress.getId());

        assertEquals(firstAddress.getId(), response.getId());
        assertEquals("김테스트", response.getRecipient());
        assertEquals("010-1111-2222", response.getPhone());
        assertEquals("12345", response.getZipcode());
        assertEquals("서울시 강남구", response.getAddressLine1());
        assertEquals("101호", response.getAddressLine2());
        assertTrue(response.isDefault());
    }

    @Test
    void getAddress_rejectsOtherMembersAddress() {
        Member owner = saveMember("address-owner@test.com", "addressOwner");
        Member otherMember = saveMember("address-other@test.com", "addressOther");
        AddressDto.Response ownerAddress = addressService.addAddress(owner.getId(), addressRequest(
                "소유자",
                "010-5555-6666",
                "11111",
                "부산시 해운대구",
                "303호"
        ));

        CustomException exception = assertThrows(CustomException.class, () ->
                addressService.getAddress(otherMember.getId(), ownerAddress.getId())
        );

        assertEquals(ErrorCode.ADDRESS_ACCESS_DENIED, exception.getErrorCode());
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

    private AddressDto.Request addressRequest(
            String recipient,
            String phone,
            String zipcode,
            String addressLine1,
            String addressLine2
    ) {
        AddressDto.Request request = new AddressDto.Request();
        ReflectionTestUtils.setField(request, "recipient", recipient);
        ReflectionTestUtils.setField(request, "phone", phone);
        ReflectionTestUtils.setField(request, "zipcode", zipcode);
        ReflectionTestUtils.setField(request, "addressLine1", addressLine1);
        ReflectionTestUtils.setField(request, "addressLine2", addressLine2);
        return request;
    }
}
