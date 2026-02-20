package com.zzf.rikki.completion;

import lombok.Data;

@Data
public class CompletionRequest {
    private String prefix;
    private String suffix;
    private String language;
    private String filePath;
    private boolean stream = true;
}
