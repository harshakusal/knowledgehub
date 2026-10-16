package com.knowledgehub.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgehub.client.LlmClient;
import com.knowledgehub.dto.Citation;
import com.knowledgehub.dto.RagAnswer;
import com.knowledgehub.model.ChatMessage;
import com.knowledgehub.model.RetrievedChunk;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Retrieval-augmented answer with a validated JSON contract. Invalid model output (unparseable JSON, missing
 * fields, citations that do not reference a retrieved chunk) is retried ONCE with a corrective message; if it is
 * still invalid we return the safe fallback {@link RagAnswer#notFound()} (answerFound=false).
 */
@Service
public class RagService {
    private static final Logger log = LoggerFactory.getLogger(RagService.class);
    private final RetrievalService retrieval;
    private final PromptBuilder prompts;
    private final LlmClient llm;
    private final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public RagService(RetrievalService retrieval, PromptBuilder prompts, LlmClient llm) {
        this.retrieval = retrieval;
        this.prompts = prompts;
        this.llm = llm;
    }

    public RagAnswer answer(String userId, String question, List<UUID> documentIds) {
        List<RetrievedChunk> context = retrieval.retrieve(userId, question, documentIds);
        if (context.isEmpty()) {
            return RagAnswer.notFound();
        }
        List<ChatMessage> messages = new ArrayList<>(prompts.jsonPrompt(question, context));
        String raw = llm.complete(messages, true);
        Optional<RagAnswer> parsed = validate(raw, context);
        if (parsed.isPresent()) {
            return parsed.get();
        }
        log.info("LLM returned invalid structured answer; retrying once");
        messages.add(new ChatMessage("assistant", raw));
        messages.add(ChatMessage.user("Your previous reply was invalid. Reply ONLY with the JSON object described in the rules, "
                + "citing only the provided documentId/chunkIndex pairs."));
        return validate(llm.complete(messages, true), context).orElseGet(() -> {
            log.warn("LLM structured answer invalid after retry; returning fallback");
            return RagAnswer.notFound();
        });
    }

    Optional<RagAnswer> validate(String raw, List<RetrievedChunk> context) {
        if (raw == null) {
            return Optional.empty();
        }
        String json = raw.strip().replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        RagAnswer a;
        try {
            a = mapper.readValue(json, RagAnswer.class);
        } catch (Exception e) {
            return Optional.empty();
        }
        if (a.answer() == null || a.answer().isBlank() || a.confidence() == null || a.citations() == null) {
            return Optional.empty();
        }
        Set<String> allowed = context.stream().map(c -> key(c.documentId(), c.chunkIndex())).collect(Collectors.toSet());
        for (Citation c : a.citations()) {
            if (c == null || c.documentId() == null || !allowed.contains(key(c.documentId(), c.chunkIndex()))) {
                return Optional.empty();
            }
        }
        if (a.answerFound() && a.citations().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new RagAnswer(a.answer(), List.copyOf(a.citations()), a.confidence(), a.answerFound()));
    }

    private static String key(UUID id, int idx) {
        return id + "#" + idx;
    }
}
