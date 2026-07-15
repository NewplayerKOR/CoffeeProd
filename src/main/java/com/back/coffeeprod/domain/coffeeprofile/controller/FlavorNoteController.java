package com.back.coffeeprod.domain.coffeeprofile.controller;

import com.back.coffeeprod.domain.coffeeprofile.dto.FlavorNoteDto;
import com.back.coffeeprod.domain.coffeeprofile.service.FlavorNoteService;
import com.back.coffeeprod.global.common.CommonResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "FlavorNote", description = "커피 향미 노트 조회 API")
@RestController
@RequestMapping("/api/v1/flavor-notes")
@RequiredArgsConstructor
public class FlavorNoteController {

    private final FlavorNoteService flavorNoteService;

    // 향미 노트 목록을 조회함
    @Operation(summary = "향미 노트 목록 조회")
    @GetMapping
    public ResponseEntity<CommonResponse<List<FlavorNoteDto.Response>>>
    getFlavorNotes() {
        return ResponseEntity.ok(CommonResponse.success(
                flavorNoteService.getFlavorNotes()
        ));
    }
}
