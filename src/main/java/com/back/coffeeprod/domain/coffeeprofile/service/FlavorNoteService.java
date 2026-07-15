package com.back.coffeeprod.domain.coffeeprofile.service;

import com.back.coffeeprod.domain.coffeeprofile.dto.FlavorNoteDto;
import com.back.coffeeprod.domain.coffeeprofile.entity.FlavorNote;
import com.back.coffeeprod.domain.coffeeprofile.repository.FlavorNoteRepository;
import com.back.coffeeprod.global.exception.CustomException;
import com.back.coffeeprod.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FlavorNoteService {

    private final FlavorNoteRepository flavorNoteRepository;

    // 향미 노트 목록을 조회함
    public List<FlavorNoteDto.Response> getFlavorNotes() {
        return flavorNoteRepository.findAll(Sort.by(Sort.Direction.ASC, "name"))
                .stream()
                .map(FlavorNoteDto.Response::new)
                .toList();
    }

    // 향미 노트를 등록함
    @Transactional
    public FlavorNoteDto.Response createFlavorNote(
            FlavorNoteDto.CreateRequest request
    ) {
        if (flavorNoteRepository.existsByCode(request.getCode())) {
            throw new CustomException(ErrorCode.DUPLICATE_FLAVOR_NOTE_CODE);
        }

        FlavorNote flavorNote = FlavorNote.builder()
                .code(request.getCode())
                .name(request.getName())
                .description(request.getDescription())
                .build();

        return new FlavorNoteDto.Response(
                flavorNoteRepository.save(flavorNote)
        );
    }

    // 향미 노트를 수정함
    @Transactional
    public FlavorNoteDto.Response updateFlavorNote(
            Long flavorNoteId,
            FlavorNoteDto.UpdateRequest request
    ) {
        FlavorNote flavorNote = findFlavorNoteById(flavorNoteId);
        flavorNote.update(request.getName(), request.getDescription());

        return new FlavorNoteDto.Response(flavorNote);
    }

    // 내부 공용 조회
    public FlavorNote findFlavorNoteById(Long flavorNoteId) {
        return flavorNoteRepository.findById(flavorNoteId)
                .orElseThrow(() -> new CustomException(
                        ErrorCode.FLAVOR_NOTE_NOT_FOUND
                ));
    }
}
