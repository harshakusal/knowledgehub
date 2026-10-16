package com.knowledgehub.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.knowledgehub.dto.Confidence;
import com.knowledgehub.dto.RagAnswer;
import com.knowledgehub.exception.NotFoundException;
import com.knowledgehub.service.ChatStreamService;
import com.knowledgehub.service.DocumentPermissionService;
import com.knowledgehub.service.RagWorker;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import com.knowledgehub.config.AppProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@WebMvcTest(ChatController.class)
@Import(ChatControllerTest.Props.class)
class ChatControllerTest {
    @TestConfiguration
    @EnableConfigurationProperties(AppProperties.class)
    static class Props {
    }

    @Autowired
    MockMvc mvc;
    @MockBean
    ChatStreamService streams;
    @MockBean
    RagWorker rag;
    @MockBean
    DocumentPermissionService permissions;

    @Test
    void streamStartsAsyncSse() throws Exception {
        mvc.perform(post("/api/chat/stream").header("X-User-Id", "alice").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"leave?\",\"documentIds\":[]}"))
                .andExpect(request().asyncStarted());
        verify(streams).start(eq("alice"), eq("leave?"), eq(List.of()), any(), any());
    }

    @Test
    void blankQuestionIs400() throws Exception {
        mvc.perform(post("/api/chat/stream").header("X-User-Id", "alice").contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\" \"}")).andExpect(status().isBadRequest());
        verifyNoInteractions(streams);
    }

    @Test
    void foreignDocumentIs404BeforeStreaming() throws Exception {
        UUID foreign = UUID.randomUUID();
        when(permissions.requireAllReadable(List.of(foreign), "bob")).thenThrow(new NotFoundException("Document not found"));
        mvc.perform(post("/api/chat/stream").header("X-User-Id", "bob").contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"q\",\"documentIds\":[\"" + foreign + "\"]}")).andExpect(status().isNotFound());
        verifyNoInteractions(streams);
    }

    @Test
    void structuredAnswer() throws Exception {
        when(rag.answer(eq("q"), any())).thenReturn(CompletableFuture.completedFuture(
                new RagAnswer("a", List.of(), Confidence.LOW, false)));
        MvcResult r = mvc.perform(post("/api/chat/answer").header("X-User-Id", "alice").contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"q\"}")).andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(r)).andExpect(status().isOk())
                .andExpect(jsonPath("$.confidence").value("low"))
                .andExpect(jsonPath("$.answerFound").value(false));
    }
}
