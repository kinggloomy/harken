-- 演示表。用 demo_ 前缀是为了避开 order 这个 SQL 保留字，
-- 也顺便提醒：真实的表名会被 Harken 的审计记录引用。
drop table if exists demo_order;

create table demo_order (
    id         bigint auto_increment primary key,
    order_no   varchar(32)   not null unique,
    customer   varchar(64)   not null,
    amount     decimal(12, 2) not null,
    status     varchar(16)   not null,
    created_at timestamp     not null
);

drop table if exists supply_order;

-- 「关键表」。真实系统里这类表是订单主表、资金流水、库存台账 ——
-- 从控制台直接改它们属于事故高发动作，所以演示用黑名单把它锁住。
-- 注意：黑名单只拦写，读它是允许的（排查问题必须能读）。
create table supply_order (
    id       bigint auto_increment primary key,
    order_no varchar(32) not null unique,
    status   varchar(16) not null
);
