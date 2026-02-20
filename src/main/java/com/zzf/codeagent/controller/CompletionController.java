package com.zzf.codeagent.controller;

import com.zzf.codeagent.completion.CompletionRequest;
import com.zzf.codeagent.completion.CompletionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class CompletionController {

    private final CompletionService completionService;

    @PostMapping("/complete")
    public SseEmitter complete(@RequestBody CompletionRequest request) {
        return completionService.complete(request);
    }
}
