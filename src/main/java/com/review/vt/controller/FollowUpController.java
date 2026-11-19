package com.review.vt.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.review.vt.pojo.param.*;

import com.review.vt.service.FollowUpService;
import com.review.vt.pojo.dto.FollowUpDto;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 追问接口：按题隔离的聊天记录。
 * 只有在交卷批改后的题上才能提问（追问上下文包含批改与参考答案）。
 */
@Tag(name = "追问", description = "按题隔离的 AI 答疑")
@RestController
public class FollowUpController {

    private final FollowUpService followUpService;

    public FollowUpController(FollowUpService followUpService) {
        this.followUpService = followUpService;
    }


    @Operation(summary = "追问记录", description = "按题隔离的聊天历史")
    @GetMapping("/api/questions/{id}/followups")
    public List<FollowUpDto> list(@Parameter(description = "资源 ID（见路径）") @PathVariable Long id) {
        return followUpService.list(id);
    }

    /** 提一个问题，返回更新后的完整聊天记录 */
    @Operation(summary = "向 AI 提问", description = "批改后的题才能追问")
    @PostMapping("/api/questions/{id}/followups")
    public List<FollowUpDto> ask(@Parameter(description = "资源 ID（见路径）") @PathVariable Long id,
                                 @RequestBody(required = false) AskFollowUpParam req) {
        return followUpService.ask(id, req == null ? null : req.content());
    }

    /** 清空这道题的聊天记录 */
    @Operation(summary = "清空追问", description = "只清聊天记录，不动题目与作答")
    @PostMapping("/api/questions/{id}/followups/clear")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear(@Parameter(description = "资源 ID（见路径）") @PathVariable Long id) {
        followUpService.clear(id);
    }
}
