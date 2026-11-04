package com.enterprise.iqk.security;

import com.enterprise.iqk.mapper.PermissionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * File: PermissionService.java
 * <p>
 * RBAC 权限解析（docs/architecture-enterprise.md「RBAC + route-level permission
 * checks」）：把角色列表展开为去重后的权限串列表，供签发 JWT / 组装身份时使用。
 */
@Service
@RequiredArgsConstructor
public class PermissionService {
    private final PermissionMapper permissionMapper;

    /** 逐角色查询权限并去重；roles 为空返回空列表；{@code ROLE_} 前缀自动剥离。 */
    public List<String> permissionsForRoles(List<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String role : roles) {
            if (role == null) {
                continue;
            }
            result.addAll(permissionMapper.findByRoleName(role.replace("ROLE_", "")));
        }
        return result.stream().distinct().toList();
    }
}
