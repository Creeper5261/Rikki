package com.zzf.codeagent.core.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

public class IntentClassifierTest {

    private OpenAiChatModel model;
    private ObjectMapper mapper;
    private IntentClassifier classifier;

    @BeforeEach
    public void setup() {
        model = Mockito.mock(OpenAiChatModel.class);
        mapper = new ObjectMapper();
        classifier = new IntentClassifier(model, mapper);
    }

    @Test
    public void testRuleBasedSearch() {
        assertEquals(IntentClassifier.Intent.SEARCH, classifier.classify("find User class"));
        assertEquals(IntentClassifier.Intent.SEARCH, classifier.classify("search for login method"));
        assertEquals(IntentClassifier.Intent.SEARCH, classifier.classify("grep pattern 'TODO'"));
    }

    @Test
    public void testRuleBasedExplain() {
        assertEquals(IntentClassifier.Intent.EXPLAIN, classifier.classify("explain how auth works"));
        assertEquals(IntentClassifier.Intent.EXPLAIN, classifier.classify("what is JsonReActAgent"));
    }

    @Test
    public void testLlmFallbackComplex() {
        // Mock LLM response for complex query
        when(model.chat(anyString())).thenReturn("{\"intent\": \"COMPLEX_TASK\"}");
        
        assertEquals(IntentClassifier.Intent.COMPLEX_TASK, classifier.classify("refactor this class and add tests"));
    }
    
    @Test
    public void testLlmFallbackCodeGen() {
        // Mock LLM response for code gen
        when(model.chat(anyString())).thenReturn("{\"intent\": \"CODE_GEN\"}");
        
        assertEquals(IntentClassifier.Intent.CODE_GEN, classifier.classify("generate a new Controller"));
    }
}
