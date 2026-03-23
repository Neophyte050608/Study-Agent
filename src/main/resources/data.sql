-- 初始菜单配置
INSERT INTO `t_menu_config` (`menu_code`, `title`, `description`, `path`, `icon`, `position`, `sort_order`, `is_beta`) 
VALUES 
('DASHBOARD', '面试控制台', '开始一场新的模拟面试', '/', 'dashboard', 'SIDEBAR', 1, 0),
('NOTES', '知识库管理', '同步与搜索你的个人笔记', '/notes', 'description', 'SIDEBAR', 2, 0),
('CODING', '算法刷题', '针对薄弱点进行专项算法训练', '/coding', 'code', 'SIDEBAR', 3, 0),
('PROFILE', '能力画像', '查看你的技术掌握度曲线', '/profile', 'analytics', 'SIDEBAR', 4, 0),
('MONITORING', '系统监控', '模型路由、熔断状态与调用统计', '/monitoring', 'monitoring', 'SIDEBAR', 5, 0)
ON DUPLICATE KEY UPDATE 
title=VALUES(title), 
description=VALUES(description), 
path=VALUES(path), 
icon=VALUES(icon), 
position=VALUES(position), 
sort_order=VALUES(sort_order), 
is_beta=VALUES(is_beta);
