package com.llmgateway.completion;

import com.llmgateway.auth.AuthenticatedKey;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChatCompletionController {

    private final ChatCompletionService completionService;

    public ChatCompletionController(ChatCompletionService completionService) {
        this.completionService = completionService;
    }

    @PostMapping("/v1/chat/completions")
    public ChatCompletionResponse complete(@RequestAttribute(AuthenticatedKey.REQUEST_ATTRIBUTE) AuthenticatedKey caller,
                                           @RequestBody ChatCompletionRequest request) {
        return completionService.complete(caller, request);
    }
}
