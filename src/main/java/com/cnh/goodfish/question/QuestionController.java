package com.cnh.goodfish.question;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for boards and their questions.
 *
 * <p>A board is any stream of questions: a talk's live Q&amp;A, "topics for next year", feature requests.
 */
@RestController
@RequestMapping("/api")
class QuestionController {

    static final String BOARD_PATTERN = "[a-z0-9][a-z0-9-]{0,39}";

    private final QuestionService questions;
    private final BoardService boards;

    QuestionController(QuestionService questions, BoardService boards) {
        this.questions = questions;
        this.boards = boards;
    }

    record SubmitRequest(@NotBlank @Size(min = 3, max = 300) String text) {
    }

    record TopicRequest(@NotBlank @Size(max = 1000) String topic) {
    }

    @PutMapping("/boards/{board}")
    BoardService.BoardView setTopic(
            @PathVariable @Pattern(regexp = BOARD_PATTERN) String board,
            @Valid @RequestBody TopicRequest request) {
        return boards.setTopic(board, request.topic());
    }

    @GetMapping("/boards/{board}")
    ResponseEntity<BoardService.BoardView> getBoard(@PathVariable @Pattern(regexp = BOARD_PATTERN) String board) {
        return ResponseEntity.of(boards.get(board));
    }

    @PostMapping("/boards/{board}/questions")
    @ResponseStatus(HttpStatus.CREATED)
    QuestionService.SubmitResult submit(
            @PathVariable @Pattern(regexp = BOARD_PATTERN) String board,
            @Valid @RequestBody SubmitRequest request) {
        return questions.submit(board, request.text());
    }

    @GetMapping("/boards/{board}/questions")
    List<QuestionView> top(
            @PathVariable @Pattern(regexp = BOARD_PATTERN) String board,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return questions.top(board, limit);
    }

    @PostMapping("/questions/{id}/upvote")
    QuestionView upvote(@PathVariable String id) {
        return questions.upvote(id);
    }
}
