package com.back.coffeeprod.domain.qna.dto;

import com.back.coffeeprod.domain.qna.entity.Qna;
import com.back.coffeeprod.domain.qna.entity.QnaStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

public class QnaDto {

    @Getter
    @NoArgsConstructor
    public static class QuestionRequest {

        @NotBlank(message = "문의 제목은 필수입니다.")
        @Size(max = 200, message = "문의 제목은 200자 이하여야 합니다.")
        private String title;

        @NotBlank(message = "문의 내용은 필수입니다.")
        @Size(max = 2000, message = "문의 내용은 2000자 이하여야 합니다.")
        private String question;
    }

    @Getter
    @NoArgsConstructor
    public static class AnswerRequest {

        @NotBlank(message = "답변 내용은 필수입니다.")
        @Size(max = 2000, message = "답변 내용은 2000자 이하여야 합니다.")
        private String answer;
    }

    @Getter
    public static class Response {

        private final Long id;
        private final Long productId;
        private final String nickname;
        private final String title;
        private final String question;
        private final String answer;
        private final String answererNickname;
        private final QnaStatus status;
        private final LocalDateTime createdAt;
        private final LocalDateTime updatedAt;
        private final LocalDateTime answeredAt;

        public Response(Qna qna) {
            this.id = qna.getId();
            this.productId = qna.getProduct().getId();
            this.nickname = qna.getMember().getNickname();
            this.title = qna.getTitle();
            this.question = qna.getQuestion();
            this.answer = qna.getAnswer();
            this.answererNickname = qna.getAnswerer() == null
                    ? null
                    : qna.getAnswerer().getNickname();
            this.status = qna.getStatus();
            this.createdAt = qna.getCreatedAt();
            this.updatedAt = qna.getUpdatedAt();
            this.answeredAt = qna.getAnsweredAt();
        }
    }
}
