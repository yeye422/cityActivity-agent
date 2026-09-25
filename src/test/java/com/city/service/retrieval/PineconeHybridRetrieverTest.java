package com.city.service.retrieval;

import com.city.enums.SourceMode;
import com.city.model.ActivityItem;
import com.city.model.SlotBundle;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PineconeHybridRetrieverTest {

    @Test
    void shouldUsePineconeForBothLexicalAndDenseChannels() {
        PineconeDocumentClient pinecone = mock(PineconeDocumentClient.class);
        DashScopeEmbeddingClient embeddings = mock(DashScopeEmbeddingClient.class);
        PineconeHybridRetriever retriever = new PineconeHybridRetriever(pinecone, embeddings);

        ActivityItem first = new ActivityItem(
                1L, SourceMode.PUBLIC, null, "陶艺", "安静治愈的陶艺手作",
                SlotBundle.empty(), null, null, null, null, 120, 0.0);
        ActivityItem second = new ActivityItem(
                2L, SourceMode.PUBLIC, null, "篮球", "高强度篮球对抗",
                SlotBundle.empty(), null, null, null, null, 120, 0.0);

        when(pinecone.available()).thenReturn(true);
        when(pinecone.searchText("想安静放空", List.of(1L, 2L), 2))
                .thenReturn(Map.of(1L, 3.2, 2L, 0.4));
        when(embeddings.available()).thenReturn(true);
        when(embeddings.embed(List.of("想安静放空")))
                .thenReturn(List.of(List.of(0.2, 0.8)));
        when(pinecone.searchDense(List.of(0.2, 0.8), List.of(1L, 2L), 2))
                .thenReturn(Map.of(1L, 0.92, 2L, 0.31));

        PineconeHybridRetriever.Result result = retriever.retrieve(
                List.of(first, second), "想安静放空");

        assertEquals(Map.of(1L, 3.2, 2L, 0.4), result.lexicalScores());
        assertEquals(Map.of(1L, 0.92, 2L, 0.31), result.vectorScores());
    }
}
