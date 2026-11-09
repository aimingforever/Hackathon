package com.cnh.goodfish.question;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.GetResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Stores an optional topic per board (for example the talk title and abstract). The topic's tokens
 * are down-weighted when comparing questions, so "it's about eBPF" alone does not make two questions
 * duplicates.
 */
@Service
public class BoardService {

    /** ELSER weight a topic token needs before it is treated as a topic word. */
    static final double TOPIC_TOKEN_MIN_WEIGHT = 0.5;

    private final ElasticsearchClient client;
    private final QuestionProperties properties;
    private final Embedder embedder;
    private final Map<String, Map<String, Double>> weightsCache = new ConcurrentHashMap<>();

    BoardService(ElasticsearchClient client, QuestionProperties properties, Embedder embedder) {
        this.client = client;
        this.properties = properties;
        this.embedder = embedder;
    }

    /** Stored board document. */
    record Board(String board, String topic, Map<String, Float> topicTokens) {
    }

    /** API representation of a board. */
    public record BoardView(String board, String topic) {
    }

    public BoardView setTopic(String board, String topic) {
        Board doc = new Board(board, topic.strip(), embedder.embed(topic));
        try {
            client.index(i -> i.index(properties.boardIndex()).id(board).document(doc).refresh(Refresh.WaitFor));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        weightsCache.put(board, weightsFor(doc));
        return new BoardView(board, doc.topic());
    }

    public Optional<BoardView> get(String board) {
        return load(board).map(b -> new BoardView(b.board(), b.topic()));
    }

    /** Token weights for comparing questions on this board; empty when the board has no topic. */
    Map<String, Double> topicWeights(String board) {
        return weightsCache.computeIfAbsent(board, b -> load(b).map(this::weightsFor).orElse(Map.of()));
    }

    private Map<String, Double> weightsFor(Board board) {
        return SparseVectors.topicWeights(board.topicTokens(), TOPIC_TOKEN_MIN_WEIGHT, properties.topicFloor());
    }

    private Optional<Board> load(String board) {
        try {
            GetResponse<Board> response = client.get(g -> g.index(properties.boardIndex()).id(board), Board.class);
            return response.found() ? Optional.ofNullable(response.source()) : Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
