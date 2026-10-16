package com.knowledgehub.dto;

import java.util.List;

/** Validated, structured answer produced by the RAG pipeline. */
public record RagAnswer(String answer, List<Citation> citations, Confidence confidence, boolean answerFound) {
    public static RagAnswer notFound() {
        return new RagAnswer("I couldn't find a reliable answer to that in your documents.", List.of(), Confidence.LOW, false);
    }
}
