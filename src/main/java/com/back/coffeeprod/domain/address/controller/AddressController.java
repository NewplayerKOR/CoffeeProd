package com.back.coffeeprod.domain.address.controller;

import com.back.coffeeprod.domain.address.dto.AddressDto;
import com.back.coffeeprod.domain.address.service.AddressService;
import com.back.coffeeprod.global.common.CommonResponse;
import com.back.coffeeprod.global.security.auth.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Address", description = "배송지 관리 API")
@SecurityRequirement(name = "jwtAuth")
@RestController
@RequestMapping("/api/v1/members/me/addresses")
@RequiredArgsConstructor
public class AddressController {

    private final AddressService addressService;

    // 배송지 목록 조회
    @Operation(summary = "배송지 목록 조회", description = "로그인한 회원의 전체 배송지 목록을 반환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패")
    })
    @GetMapping
    public ResponseEntity<CommonResponse<List<AddressDto.Response>>> getAddress(
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        Long memberId = userDetails.getMember().getId();
        return ResponseEntity.ok(CommonResponse.success(addressService.getAddresses(memberId)));
    }

    // 신규 배송지 등록
    @Operation(summary = "배송지 등록", description = "배송지를 등록합니다. 최대 5개까지 가능합니다")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "등록 성공"),
            @ApiResponse(responseCode = "400", description = "배송지 최대 개수 초과")
    })
    @PostMapping
    public ResponseEntity<CommonResponse<AddressDto.Response>> addAddress(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody AddressDto.Request request) {

        Long memberId = userDetails.getMember().getId();
        AddressDto.Response response = addressService.addAddress(memberId, request);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(CommonResponse.success(response));
    }

    // 배송지 수정
    @Operation(summary = "배송지 수정", description = "배송지를 수정합니다. 본인이 등록한 배송지만 수정 가능합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패"),
            @ApiResponse(responseCode = "403", description = "본인 배송지 아님"),
            @ApiResponse(responseCode = "404", description = "배송지 없음")
    })
    @PutMapping("/{addressId}")
    public ResponseEntity<CommonResponse<AddressDto.Response>> updateAddress(
            @AuthenticationPrincipal CustomUserDetails userDetails,

            @Parameter(description = "수정할 배송지 ID", required = true)
            @PathVariable("addressId") Long addressId,

            @Valid @RequestBody AddressDto.Request request) {

        Long memberId = userDetails.getMember().getId();
        return ResponseEntity.ok(CommonResponse.success(
                addressService.updateAddress(memberId, addressId, request)));
    }

    // 배송지 삭제
    @Operation(summary = "배송지 삭제", description = "배송지를 삭제합니다. 기본 배송지 삭제 시 남은 배송지 중 첫 번째가 자동으로 기본 배송지가 됩니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "삭제 성공"),
            @ApiResponse(responseCode = "403", description = "본인 배송지 아님"),
            @ApiResponse(responseCode = "404", description = "배송지 없음")
    })
    @DeleteMapping("/{addressId}")
    public ResponseEntity<CommonResponse<Void>> deleteAddress(
            @AuthenticationPrincipal CustomUserDetails userDetails,

            @Parameter(description = "삭제할 배송지 ID", required = true)
            @PathVariable("addressId") Long addressId) {

        Long memberId = userDetails.getMember().getId();
        addressService.deleteAddress(memberId, addressId);
        return ResponseEntity.ok(CommonResponse.success(200, "배송지가 삭제되었습니다."));
    }

    // 기본 배송지 설정
    @Operation(summary = "기본 배송지 설정", description = "특정 배송지를 기본 배송지로 설정합니다. 기존 기본 배송지는 자동으로 해제됩니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "설정 성공"),
            @ApiResponse(responseCode = "403", description = "본인 배송지 아님"),
            @ApiResponse(responseCode = "404", description = "배송지 없음")
    })
    @PatchMapping("/{addressId}/default")
    public ResponseEntity<CommonResponse<AddressDto.Response>> setDefaultAddress(
            @AuthenticationPrincipal CustomUserDetails userDetails,

            @Parameter(description = "설정할 기본 배송지 ID", required = true)
            @PathVariable("addressId") Long addressId) {

        Long memberId = userDetails.getMember().getId();
        return ResponseEntity.ok(CommonResponse.success(
                addressService.setDefaultAddress(memberId, addressId)));
    }
}
