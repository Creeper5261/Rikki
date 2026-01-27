package com.zzf.codeagent.core.rag.search;

import java.util.Collections;
import java.util.List;

public final class CodeSearchResponse {
    private final List<CodeSearchHit> hits;
    private final String error;

    public CodeSearchResponse(List<CodeSearchHit> hits) {
        this(hits, null);
    }

    public CodeSearchResponse(List<CodeSearchHit> hits, String error) {
        this.hits = hits == null ? Collections.emptyList() : Collections.unmodifiableList(hits);
        this.error = error;
    }

    public List<CodeSearchHit> getHits() {
        return hits;
    }

    public String getError() {
        return error;
    }
}
