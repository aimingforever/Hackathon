package com.cnh.goodfish.question;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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

    /** Boards with this prefix are throwaway demo boards that anyone may reset. */
    static final String DEMO_BOARD_PREFIX = "demo-";

    private final QuestionService questions;
    private final BoardService boards;
    private final BoardInsightsService insights;
    private final String adminToken;

    QuestionController(QuestionService questions, BoardService boards, BoardInsightsService insights,
                       @Value("${goodfish.admin-token:}") String adminToken) {
        this.questions = questions;
        this.boards = boards;
        this.insights = insights;
        this.adminToken = adminToken;
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

    /** Hybrid (keyword + semantic, RRF) search over a board's questions. */
    @GetMapping("/boards/{board}/search")
    List<BoardInsightsService.SearchHit> search(
            @PathVariable @Pattern(regexp = BOARD_PATTERN) String board,
            @RequestParam @NotBlank @Size(max = 200) String q,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit) {
        return insights.search(board, q, limit);
    }

    /** Board statistics from Elasticsearch aggregations. */
    @GetMapping("/boards/{board}/stats")
    BoardInsightsService.BoardStats stats(@PathVariable @Pattern(regexp = BOARD_PATTERN) String board) {
        return insights.stats(board);
    }

    /**
     * Clears a board. Demo boards ("demo-*") can be reset by anyone; any other board requires the
     * X-Admin-Token header to match the configured admin token, so attendees cannot wipe a live board.
     */
    @DeleteMapping("/boards/{board}/questions")
    ResponseEntity<Map<String, Long>> clear(
            @PathVariable @Pattern(regexp = BOARD_PATTERN) String board,
            @RequestHeader(name = "X-Admin-Token", required = false) String token) {
        if (!board.startsWith(DEMO_BOARD_PREFIX) && !isAdmin(token)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(Map.of("deleted", questions.clear(board)));
    }

    private boolean isAdmin(String token) {
        return !adminToken.isBlank() && token != null && MessageDigest.isEqual(
                adminToken.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping("/questions/{id}/upvote")
    QuestionView upvote(@PathVariable String id) {
        return questions.upvote(id);
    }
}
