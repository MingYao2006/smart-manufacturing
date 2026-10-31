package com.smart.common.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.smart.common.context.UserContextHolder;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

/**
 * MyBatis-Plus 配置。
 *
 * <p>包含三件事：
 * <ol>
 *   <li>分页插件（必须显式指定 DbType.MYSQL，否则 count 语句会出错）</li>
 *   <li>防全表更新删除插件（避免线上误操作 UPDATE 全表）</li>
 *   <li>审计字段自动填充（create_time / update_time / create_by / update_by）</li>
 * </ol>
 */
@Configuration
@ConditionalOnClass(MybatisPlusInterceptor.class)
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        // 单页上限 500，防止 pageSize=999999 拖垮库
        pagination.setMaxLimit(500L);
        pagination.setOverflow(false);
        interceptor.addInnerInterceptor(pagination);

        // 拦截没有 where 条件的 update / delete
        interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());
        return interceptor;
    }

    @Bean
    public MetaObjectHandler metaObjectHandler() {
        return new AuditMetaObjectHandler();
    }

    /**
     * 审计字段自动填充。
     */
    static class AuditMetaObjectHandler implements MetaObjectHandler {

        @Override
        public void insertFill(MetaObject metaObject) {
            LocalDateTime now = LocalDateTime.now();
            strictInsertFill(metaObject, "createTime", LocalDateTime.class, now);
            strictInsertFill(metaObject, "updateTime", LocalDateTime.class, now);
            Long userId = UserContextHolder.currentUserId();
            strictInsertFill(metaObject, "createBy", Long.class, userId);
            strictInsertFill(metaObject, "updateBy", Long.class, userId);
            strictInsertFill(metaObject, "createBy", String.class, UserContextHolder.currentUserName());
        }

        @Override
        public void updateFill(MetaObject metaObject) {
            strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
            strictUpdateFill(metaObject, "updateBy", Long.class, UserContextHolder.currentUserId());
        }
    }
}
