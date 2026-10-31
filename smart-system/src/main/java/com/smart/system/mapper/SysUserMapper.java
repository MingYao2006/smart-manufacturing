package com.smart.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smart.system.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    @Select("SELECT * FROM sys_user WHERE username = #{username}")
    SysUser selectByUsername(@Param("username") String username);

    /**
     * 查用户的角色编码列表。
     */
    @Select("SELECT r.role_code FROM sys_role r "
            + "INNER JOIN sys_user_role ur ON ur.role_id = r.id "
            + "WHERE ur.user_id = #{userId} AND r.status = 1")
    List<String> selectRoleCodes(@Param("userId") Long userId);
}
