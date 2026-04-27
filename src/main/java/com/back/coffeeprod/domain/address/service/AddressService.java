package com.back.coffeeprod.domain.address.service;

import com.back.coffeeprod.domain.address.dto.AddressDto;
import com.back.coffeeprod.domain.address.entity.Address;
import com.back.coffeeprod.domain.address.repository.AddressRepository;
import com.back.coffeeprod.domain.member.entity.Member;
import com.back.coffeeprod.domain.member.service.MemberService;
import com.back.coffeeprod.global.exception.CustomException;
import com.back.coffeeprod.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AddressService {

    private static final int MAX_ADDRESS_COUNT = 5; // 최대 배송지 등록 수

    private final AddressRepository addressRepository;
    private final MemberService memberService;

    // 배송지 목록 조회
    public List<AddressDto.Response> getAddresses(Long memberId) {
        return addressRepository.findByMemberId(memberId)
                .stream()
                .map(AddressDto.Response::new)
                .collect(Collectors.toList());
    }

    // 신규 배송지 등록
    @Transactional
    public AddressDto.Response addAddress(Long memberId, AddressDto.Request request) {
        Member member = memberService.findMemberById(memberId);

        // 최대 배송지 수 초과 검증
        if (addressRepository.countByMemberId(memberId) >= MAX_ADDRESS_COUNT) {
            throw new CustomException(ErrorCode.ADDRESS_LIMIT_EXCEEDED);
        }

        // 첫 번째 배송지 등록 시 자동으로 기본 배송지 설정
        boolean isFirst = addressRepository.countByMemberId(memberId) == 0;

        Address address = Address.builder()
                .member(member)
                .recipient(request.getRecipient())
                .phone(request.getPhone())
                .zipcode(request.getZipcode())
                .addressLine1(request.getAddressLine1())
                .addressLine2(request.getAddressLine2())
                .isDefault(isFirst)
                .build();

        return new AddressDto.Response(addressRepository.save(address));
    }

    // 배송지 수정
    @Transactional
    public AddressDto.Response updateAddress(Long memberId, Long addressId,
                                             AddressDto.Request request) {
        // 본인이 등록한 배송지인지 검증 후 조회
        Address address = findAddressByIdAndMemberId(addressId, memberId);
        address.update(
                request.getRecipient(),
                request.getPhone(),
                request.getZipcode(),
                request.getAddressLine1(),
                request.getAddressLine2()
        );

        return new AddressDto.Response(address);
    }

    // 배송지 삭제
    @Transactional
    public void deleteAddress(Long memberId, Long addressId) {
        Address address = findAddressByIdAndMemberId(addressId, memberId);

        // 기본 배송지 삭제 시 다른 배송지를 자동으로 기본 배송지로 설정
        if (address.isDefault()) {
            List<Address> remaining = addressRepository.findByMemberId(memberId)
                    .stream()
                    .filter(a -> !a.getId().equals(addressId)) // 삭제 대상 제외
                    .collect(Collectors.toList());

            // 남은 배송지 중 첫 번째를 기본 배송지로 설정
            if (!remaining.isEmpty()) {
                remaining.get(0).setDefault();
            }
        }

        addressRepository.delete(address);
    }

    // 기본 배송지 설정
    @Transactional
    public AddressDto.Response setDefaultAddress(Long memberId, Long addressId) {

        // 1. 현재 기본 배송지 해제
        addressRepository.findByMemberIdAndIsDefaultTrue(memberId)
                .ifPresent(Address::unsetDefault);

        // 2. 새 기본 배송지 설정
        Address newDefault = findAddressByIdAndMemberId(addressId, memberId);
        newDefault.setDefault();

        return new AddressDto.Response(newDefault);
    }


    // [내부 공용] 배송지 조회 + 본인 등록여부 검증
    private Address findAddressByIdAndMemberId(Long addressId, Long memberId) {
        Address address = addressRepository.findById(addressId)
                .orElseThrow(() -> new CustomException(ErrorCode.ADDRESS_NOT_FOUND));

        // 다른 회원의 배송지 목록 접근 시도 차단
        if (!address.getMember().getId().equals(memberId)) {
            throw new CustomException(ErrorCode.ADDRESS_ACCESS_DENIED);
        }

        return address;
    }
}
