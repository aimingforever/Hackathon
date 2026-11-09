package com.cnh.goodfish.question;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = QuestionController.class, properties = "goodfish.admin-token=test-admin-token")
class QuestionControllerTests {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    QuestionService service;

    @MockitoBean
    BoardService boards;

    private static final QuestionView GROUP = new QuestionView(
            "g1", "keynote", "Does eBPF add much overhead?",
            List.of("Does eBPF add much overhead?", "Is eBPF slow?"), 2, 1, 3, 1L, 2L);

    @Test
    void submitReturnsMergedGroup() throws Exception {
        given(service.submit("keynote", "Is eBPF slow?"))
                .willReturn(new QuestionService.SubmitResult(GROUP, true, 0.56));

        mvc.perform(post("/api/boards/keynote/questions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Is eBPF slow?\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.merged").value(true))
                .andExpect(jsonPath("$.group.asks").value(2))
                .andExpect(jsonPath("$.similarity").value(0.56));
    }

    @Test
    void submitRejectsBlankText() throws Exception {
        mvc.perform(post("/api/boards/keynote/questions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"  \"}"))
                .andExpect(status().isBadRequest());
        verify(service, never()).submit(anyString(), anyString());
    }

    @Test
    void submitRejectsInvalidBoardName() throws Exception {
        mvc.perform(post("/api/boards/Bad_Board!/questions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Is eBPF slow?\"}"))
                .andExpect(status().isBadRequest());
        verify(service, never()).submit(anyString(), anyString());
    }

    @Test
    void topReturnsRankedGroups() throws Exception {
        given(service.top("keynote", 20)).willReturn(List.of(GROUP));

        mvc.perform(get("/api/boards/keynote/questions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("g1"))
                .andExpect(jsonPath("$[0].popularity").value(3));
    }

    @Test
    void setTopicStoresBoardTopic() throws Exception {
        given(boards.setTopic("keynote", "eBPF in production"))
                .willReturn(new BoardService.BoardView("keynote", "eBPF in production"));

        mvc.perform(put("/api/boards/keynote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topic\":\"eBPF in production\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topic").value("eBPF in production"));
    }

    @Test
    void unknownBoardReturns404() throws Exception {
        given(boards.get("nope")).willReturn(java.util.Optional.empty());

        mvc.perform(get("/api/boards/nope")).andExpect(status().isNotFound());
    }

    @Test
    void anyoneCanResetADemoBoard() throws Exception {
        given(service.clear("demo-ebpf-talk")).willReturn(9L);

        mvc.perform(delete("/api/boards/demo-ebpf-talk/questions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(9));
    }

    @Test
    void clearingARealBoardNeedsTheAdminToken() throws Exception {
        given(service.clear("keynote")).willReturn(3L);

        mvc.perform(delete("/api/boards/keynote/questions"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/boards/keynote/questions").header("X-Admin-Token", "wrong"))
                .andExpect(status().isForbidden());
        verify(service, never()).clear("keynote");

        mvc.perform(delete("/api/boards/keynote/questions").header("X-Admin-Token", "test-admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(3));
    }

    @Test
    void upvoteUnknownGroupReturns404() throws Exception {
        given(service.upvote("missing")).willThrow(new QuestionNotFoundException("missing"));

        mvc.perform(post("/api/questions/missing/upvote"))
                .andExpect(status().isNotFound());
    }
}
