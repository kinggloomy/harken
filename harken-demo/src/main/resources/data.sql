-- 5 笔订单，其中 SO-1001 已是 PAID，其余 NEW。
-- 演示里 SO-1002 会被改两次：一次不提交（回滚），一次提交。
insert into demo_order (order_no, customer, amount, status, created_at) values
  ('SO-1001', '周大福', 12800.00, 'PAID', timestamp '2026-10-01 09:15:00'),
  ('SO-1002', '老凤祥', 32600.00, 'NEW',  timestamp '2026-10-02 11:20:00'),
  ('SO-1003', '六福珠宝', 8900.50, 'NEW',  timestamp '2026-10-03 14:05:00'),
  ('SO-1004', '周生生', 15800.00, 'NEW',   timestamp '2026-10-04 16:40:00'),
  ('SO-1005', '潮宏基', 4300.00,  'NEW',   timestamp '2026-10-05 10:00:00');

insert into supply_order (order_no, status) values
  ('PO-2001', 'OPEN'),
  ('PO-2002', 'OPEN');
