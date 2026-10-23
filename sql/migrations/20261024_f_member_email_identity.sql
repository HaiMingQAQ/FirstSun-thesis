-- 邮箱验证码注册建立的已验证登录身份。只建空表，不回填历史 member_user.email。
-- member_user.email 仍是未验证且可编辑的联系方式；mobile 已允许 NULL，无需改列。
-- 上线前单独执行本结构迁移，本实现阶段不连接或修改现存数据库。
CREATE TABLE IF NOT EXISTS `ph_member_email_identity`
(
    `id`               bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `normalized_email` varchar(254) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '已验证的规范化邮箱',
    `member_id`        bigint       NOT NULL COMMENT '药店会员编号',
    `creator`          varchar(64)  DEFAULT '' COMMENT '创建者',
    `create_time`      datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`          varchar(64)  DEFAULT '' COMMENT '更新者',
    `update_time`      datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`          bit(1)       NOT NULL DEFAULT b'0' COMMENT '是否删除',
    `tenant_id`        bigint       NOT NULL DEFAULT 0 COMMENT '租户编号',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ph_member_email_identity` (`tenant_id`, `normalized_email`),
    UNIQUE KEY `uk_ph_member_email_member` (`tenant_id`, `member_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '药店会员已验证邮箱身份';
