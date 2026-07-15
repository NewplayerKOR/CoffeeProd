package com.back.coffeeprod.domain.coffeeprofile.controller;

import com.back.coffeeprod.domain.coffeeprofile.dto.FlavorNoteDto;
import com.back.coffeeprod.domain.coffeeprofile.service.FlavorNoteService;
import com.back.coffeeprod.global.common.CommonResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Admin-FlavorNote", description = "관리자 향미 노트 관리 API")
@SecurityRequirement(name = "jwtAuth")
@PreAuthorize("hasRole('ADMIN')")
@RestController
@RequestMapping("/api/v1/admin/flavor-notes")
@RequiredArgsConstructor
public class AdminFlavorNoteController {

    private final FlavorNoteService flavorNoteService;

    // 관리자가 향미 노트 목록을 조회함
    @Operation(summary = "관리자 향미 노트 목록 조회")
    @GetMapping
    public ResponseEntity<CommonResponse<List<FlavorNoteDto.Response>>>
    getFlavorNotes() {
        return ResponseEntity.ok(CommonResponse.success(
                flavorNoteService.getFlavorNotes()
        ));
    }

    // 관리자가 향미 노트를 등록함
    @Operation(summary = "향미 노트 등록")
    @PostMapping
    public ResponseEntity<CommonResponse<FlavorNoteDto.Response>>
    createFlavorNote(
            @Valid @RequestBody FlavorNoteDto.CreateRequest request
    ) {
        FlavorNoteDto.Response response =
                flavorNoteService.createFlavorNote(request);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(CommonResponse.success(response));
    }

    // 관리자가 향미 노트를 수정함
    @Operation(summary = "향미 노트 수정")
    @PutMapping("/{flavorNoteId}")
    public ResponseEntity<CommonResponse<FlavorNoteDto.Response>>
    updateFlavorNote(
            @PathVariable Long flavorNoteId,
            @Valid @RequestBody FlavorNoteDto.UpdateRequest request
    ) {
        FlavorNoteDto.Response response =
                flavorNoteService.updateFlavorNote(flavorNoteId, request);

        return ResponseEntity.ok(CommonResponse.success(response));
    }
}
