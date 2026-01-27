package com.zzf.codeagent.core.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzf.codeagent.core.event.EventStream;
import com.zzf.codeagent.core.rag.pipeline.IndexingWorker;
import com.zzf.codeagent.core.rag.search.CodeSearchService;
import com.zzf.codeagent.core.rag.search.HybridCodeSearchService;
import com.zzf.codeagent.core.rag.search.InMemoryCodeSearchService;
import com.zzf.codeagent.core.runtime.RuntimeService;
import com.zzf.codeagent.core.skill.SkillManager;
import com.zzf.codeagent.core.tools.FileSystemToolService;

public class ToolExecutionContext {
    public String traceId;
    public String workspaceRoot;
    public String sessionRoot;
    public ObjectMapper mapper;
    public FileSystemToolService fs;
    public CodeSearchService search; // Elasticsearch
    public HybridCodeSearchService hybridSearch;
    public InMemoryCodeSearchService memory; // Fallback
    public boolean memoryIndexed;
    public IndexingWorker indexingWorker;
    public String kafkaBootstrapServers;
    public EventStream eventStream;
    public String lastQuery;
    public RuntimeService runtimeService;
    public SkillManager skillManager;
    public int sameQueryCount;

    public ToolExecutionContext(String traceId, String workspaceRoot, String sessionRoot, ObjectMapper mapper, 
                              FileSystemToolService fs, CodeSearchService search,
                              HybridCodeSearchService hybridSearch,
                              IndexingWorker indexingWorker, String kafkaBootstrapServers, EventStream eventStream, RuntimeService runtimeService, SkillManager skillManager) {
        this.traceId = traceId;
        this.workspaceRoot = workspaceRoot;
        this.sessionRoot = sessionRoot;
        this.mapper = mapper;
        this.fs = fs;
        this.search = search;
        this.hybridSearch = hybridSearch;
        this.indexingWorker = indexingWorker;
        this.kafkaBootstrapServers = kafkaBootstrapServers;
        this.eventStream = eventStream;
        this.runtimeService = runtimeService;
        this.skillManager = skillManager;
    }
}
