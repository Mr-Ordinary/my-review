package com.review.vt.web;

import com.review.vt.service.FollowUpService;
import com.review.vt.web.Dtos.FollowUpDto;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 追问接口：按题隔离的聊天记录。
 * 只有在交卷批改后的题上才能提问（追问上下文包含批改与参考答案）。
 */
@RestController
public class FollowUpController {

    public record AskFollowUpRequest(String content) {}

    private final FollowUpService followUps;

    public FollowUpController(FollowUpService followUps) {
        this.followUps = followUps;
    }

    @GetMapping("/api/questions/{id}/followups")
    public List<FollowUpDto> list(@PathVariable String id) {
        return followUps.list(id);
    }

    /** 提一个问题，返回更新后的完整聊天记录 */
    @PostMapping("/api/questions/{id}/followups")
    public List<FollowUpDto> ask(@PathVariable String id,
                                 @RequestBody(required = false) AskFollowUpRequest req) {
        return followUps.ask(id, req == null ? null : req.content());
    }

    /** 清空这道题的聊天记录 */
    @DeleteMapping("/api/questions/{id}/followups")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear(@PathVariable String id) {
        followUps.clear(id);
    }
}
