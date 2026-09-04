package com.ruoyi.web.controller.system;

import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.framework.security.service.AiCallbackTokenService;
import com.ruoyi.system.domain.Student;
import com.ruoyi.system.service.CurrentStudentService;
import com.ruoyi.system.service.StudentBusinessToolService;
import io.jsonwebtoken.Claims;

/** Private callback surface used by the Python agent. */
@RestController
@RequestMapping("/internal/ai/tool")
public class AiToolCallbackController
{
    private final AiCallbackTokenService tokenService;
    private final CurrentStudentService currentStudentService;
    private final StudentBusinessToolService studentBusinessToolService;

    public AiToolCallbackController(AiCallbackTokenService tokenService,
        CurrentStudentService currentStudentService, StudentBusinessToolService studentBusinessToolService)
    {
        this.tokenService = tokenService;
        this.currentStudentService = currentStudentService;
        this.studentBusinessToolService = studentBusinessToolService;
    }

    @PostMapping("/student-score")
    public AjaxResult studentScore(@RequestBody(required = false) Map<String, Object> body,
        @RequestHeader(value = AiCallbackTokenService.TOKEN_HEADER, required = false) String callbackToken,
        @RequestHeader(value = AiCallbackTokenService.CONVERSATION_HEADER, required = false) String conversationId)
    {
        Claims claims = verify(callbackToken, conversationId);
        String subject = body == null || !(body.get("subject") instanceof String value) ? null : value;
        String tokenStudentId = tokenService.studentId(claims);
        Student student = currentStudentService.requireCallbackStudent(tokenService.userId(claims), tokenStudentId);
        return toAjaxResult(studentBusinessToolService.queryScore(student.getStudentId(), subject));
    }

    @PostMapping("/card-balance")
    public AjaxResult cardBalance(@RequestBody(required = false) Map<String, Object> ignoredBody,
        @RequestHeader(value = AiCallbackTokenService.TOKEN_HEADER, required = false) String callbackToken,
        @RequestHeader(value = AiCallbackTokenService.CONVERSATION_HEADER, required = false) String conversationId)
    {
        Claims claims = verify(callbackToken, conversationId);
        String tokenStudentId = tokenService.studentId(claims);
        Student student = currentStudentService.requireCallbackStudent(tokenService.userId(claims), tokenStudentId);
        return toAjaxResult(studentBusinessToolService.queryBalance(student.getStudentId()));
    }

    private Claims verify(String callbackToken, String conversationId)
    {
        // Verify both callback headers. The request body is never an identity source.
        try
        {
            return tokenService.verify(callbackToken, conversationId);
        }
        catch (ServiceException ex)
        {
            throw ex;
        }
        catch (Exception ex)
        {
            throw new ServiceException("AI callback token is invalid or expired", HttpStatus.UNAUTHORIZED);
        }
    }

    private AjaxResult toAjaxResult(Map<String, Object> toolResult)
    {
        String status = String.valueOf(toolResult.getOrDefault("status", "ERROR"));
        String message = String.valueOf(toolResult.getOrDefault("message", "个人数据查询失败"));
        AjaxResult result = AjaxResult.success(message);
        result.put("status", status);
        if (toolResult.get("data") instanceof Map<?, ?> data && !data.isEmpty())
        {
            result.put("data", data);
        }
        return result;
    }
}
