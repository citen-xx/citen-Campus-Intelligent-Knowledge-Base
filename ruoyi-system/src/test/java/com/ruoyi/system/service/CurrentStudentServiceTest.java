package com.ruoyi.system.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Set;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.Student;
import com.ruoyi.system.mapper.StudentMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class CurrentStudentServiceTest
{
    private final StudentMapper studentMapper = org.mockito.Mockito.mock(StudentMapper.class);
    private final CurrentStudentService service = new CurrentStudentService(studentMapper);

    @AfterEach
    void clearSecurityContext()
    {
        SecurityContextHolder.clearContext();
    }

    @Test
    void resolvesStudentFromCurrentLoginInsteadOfClientIdentity()
    {
        authenticate(101L);
        Student current = student(101L, "A001");
        when(studentMapper.selectStudentByUserId(101L)).thenReturn(current);

        assertEquals("A001", service.requireCurrentStudentId());
        verify(studentMapper).selectStudentByUserId(101L);
    }

    @Test
    void rejectsCallbackIdentityThatAttemptsToSwitchStudent()
    {
        authenticate(101L);
        when(studentMapper.selectStudentByUserId(101L)).thenReturn(student(101L, "A001"));

        ServiceException exception = assertThrows(ServiceException.class,
            () -> service.requireCallbackStudent(101L, "B002"));

        assertEquals(HttpStatus.FORBIDDEN, exception.getCode());
    }

    @Test
    void anonymousAndUnboundUsersAreRejected()
    {
        ServiceException anonymous = assertThrows(ServiceException.class, service::requireCurrentStudentId);
        assertEquals(HttpStatus.UNAUTHORIZED, anonymous.getCode());

        authenticate(303L);
        when(studentMapper.selectStudentByUserId(303L)).thenReturn(null);
        ServiceException unbound = assertThrows(ServiceException.class, service::requireCurrentStudentId);
        assertEquals(HttpStatus.FORBIDDEN, unbound.getCode());
    }

    private void authenticate(Long userId)
    {
        SysUser user = new SysUser(userId);
        user.setUserName("user-" + userId);
        user.setPassword("not-used");
        LoginUser loginUser = new LoginUser(userId, 1L, user, Set.of());
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities()));
    }

    private Student student(Long userId, String studentId)
    {
        Student student = new Student();
        student.setUserId(userId);
        student.setStudentId(studentId);
        return student;
    }
}
