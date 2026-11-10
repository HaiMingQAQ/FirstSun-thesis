-- 修正文件存储域：历史库里 infra_file_config.domain 仍是已废弃的 127.0.0.1:58090，
-- 每次上传头像都会生成不可访问的地址（4193 顾客端表现为头像空白/默认图标）。
-- 幂等：只替换仍含旧端口的行，可重复执行。
-- 执行后需重启 backend，FileClient 配置在进程内缓存，改库不会自动生效。
UPDATE infra_file_config
SET config = REPLACE(config, '127.0.0.1:58090', '127.0.0.1:28080')
WHERE config LIKE '%127.0.0.1:58090%';

UPDATE infra_file
SET url = REPLACE(url, '127.0.0.1:58090', '127.0.0.1:28080')
WHERE url LIKE '%127.0.0.1:58090%';

UPDATE member_user
SET avatar = REPLACE(avatar, '127.0.0.1:58090', '127.0.0.1:28080')
WHERE avatar LIKE '%127.0.0.1:58090%';
