package com.back.coffeeprod.domain.review.dto;

import com.back.coffeeprod.domain.review.entity.Review;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

public class ReviewDto {

    @Getter
    @NoArgsConstructor
    public static class Request {
        @Min(1)
        @Max(5)
        private int rating;

        @NotBlank
        @Size(max = 1000)
        private String content;
    }

    @Getter
    public static class Response {
        private final Long id;
        private final String nickname;
        private final int rating;
        private final String content;
        private final LocalDateTime createdAt;

        public Response(Review review) {
            this.id = review.getId();
            this.nickname = review.getMember().getNickname();
            this.rating = review.getRating();
            this.content = review.getContent();
            this.createdAt = review.getCreatedAt();
        }
    }
}
