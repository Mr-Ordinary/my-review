package com.review.vt.web.ex;

import com.review.vt.web.ex.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;
import com.review.vt.web.ex.LlmException;

/** 统一错误返回：{"error":"消息"}。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    /** 未登录 / 凭证错误统一 401（过滤器直接写的 401 也走同款格式） */
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<Map<String, String>> unauthorized(UnauthorizedException e) {
        log.warn("未授权：{}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", e.getMessage()));
    }

    /** Redis 不可用 → 503：前端可区分「重新登录」(401) 与「稍后再试」(503)。不打堆栈——外部依赖故障，非代码 bug */
    @ExceptionHandler(AuthUnavailableException.class)
    public ResponseEntity<Map<String, String>> authUnavailable(AuthUnavailableException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", e.getMessage()));
    }

    /**
     * 路径不存在（含已下线的接口 / 浏览器探测的静态资源）要给 404，
     * 不能被下面的兜底当成服务器错误。**不打堆栈**——这是预期内的 404，不是故障。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, String>> noRoute(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "接口不存在：" + e.getResourcePath()));
    }

    /** 上传类接口收到非 multipart 请求 / 缺字段时给 400，而不是 500 */
    @ExceptionHandler({MultipartException.class, MissingServletRequestPartException.class})
    public ResponseEntity<Map<String, String>> badUpload(Exception e) {
        log.warn("上传请求格式不对：{}", e.getMessage());
        String m = e.getMessage() == null ? "上传内容格式不对" : e.getMessage();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", m));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        log.warn("请求参数不合法：{}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(LlmException.class)
    public ResponseEntity<Map<String, String>> llm(LlmException e) {
        e.printStackTrace();
        log.warn("LLM 调用失败：{}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.getMessage()));
    }

    /**
     * 客户端中断连接（切页/刷新时头像字节流还在传、浏览器取消请求、SSE 断流）：
     * 响应写到一半连接被掐断不是服务端错误，静默处理，不打堆栈刷屏。
     * IOException 全收——ClientAbortException 是其子类，SSE 异步 dispatch 上抛的普通 IOException
     * （生产日志里的「確立された接続が…中止」）也走这里，不能漏进兜底。
     */
    @ExceptionHandler({AsyncRequestNotUsableException.class, java.io.IOException.class})
    public void clientAborted(Exception e) {
        // 连接已断，什么都写不回去；只留一行 debug 便于排查
        log.debug("客户端中断连接：{}", e.getMessage());
    }

    /**
     * 兜底 500。但若响应已提交 / 是 SSE 流（Content-Type: text/event-stream），
     * 什么都写不回去——写 JSON Map 反而触发「No converter ... text/event-stream」二次异常刷屏，
     * 这种场景只记日志返回 null。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> other(Exception e, HttpServletRequest request, HttpServletResponse response) {
        if (isUnwritable(request, response)) {
            log.debug("响应已提交/SSE 流上抛异常，无法写错误体，仅记日志：{}", e.toString());
            return null;
        }
        log.error("未处理异常：{} {}", request.getMethod(), request.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "服务器内部错误：" + e.getMessage()));
    }

    /** 响应已提交或 SSE 流：错误体写不进去 */
    private static boolean isUnwritable(HttpServletRequest request, HttpServletResponse response) {
        if (response.isCommitted()) return true;
        String ct = response.getContentType();
        if (ct != null && ct.contains("text/event-stream")) return true;
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains("text/event-stream");
    }
}
