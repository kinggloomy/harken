package com.harlanking.harken.core.impl;

import com.harlanking.harken.core.spi.AuditRecord;
import com.harlanking.harken.core.spi.AuditStore;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

/**
 * 内存环形缓冲审计：只保留最近 N 条，供 UI 与「刚才谁跑了什么」这类追问使用。
 *
 * <h2>它解决什么</h2>
 *
 * <p>{@link Slf4jAuditStore} 把记录写进日志系统，但日志系统不是给应用反查的 ——
 * 想让控制台里有个「最近执行」面板，就得有个能被 {@code query} 的后端。
 * {@code JdbcAuditStore} 也能查，但它要求建表、要求业务库有写权限；
 * 只想「看看最近十条」的应用不该为这个需求建一张表。
 *
 * <h2>它不解决什么（必须说清楚）</h2>
 *
 * <ul>
 *   <li><b>重启即失</b>。这不是持久化，是给眼睛看的一扇窗。</li>
 *   <li><b>多实例各自一份</b>。进程内内存，不做跨节点汇总。</li>
 *   <li><b>不能当合规证据</b>。合规要落盘：用 {@link Slf4jAuditStore} 或
 *       {@code JdbcAuditStore}，或者用 {@link CompositeAuditStore} 三个一起挂。</li>
 * </ul>
 *
 * <h2>容量与内存</h2>
 *
 * <p>默认 {@value #DEFAULT_CAPACITY} 条。每条记录的脚本原文已由
 * {@code HarkenService} 按 {@code maxScriptLength} 截断，这里再截到
 * {@value #DEFAULT_MAX_SCRIPT_BYTES} 字符做预览 —— 否则 200 条 100KB 的脚本
 * 就是 20MB 常驻内存，为了一个"看看最近跑了什么"的面板不值当。
 *
 * <p>线程安全：{@link #save} 会被多个执行线程并发调用，全部操作在同一把锁内。
 *
 * @author harlan
 * @since 1.0.0
 */
public final class MemoryAuditStore implements AuditStore {

    /** 默认保留条数。 */
    public static final int DEFAULT_CAPACITY = 200;

    /** 内存副本里脚本原文的字符上限（超出部分截断，避免大脚本常驻内存）。 */
    public static final int DEFAULT_MAX_SCRIPT_BYTES = 4_096;

    private final int capacity;
    private final int maxScriptChars;
    private final Deque<AuditRecord> records;
    private long totalRecorded;

    /**
     * 用默认容量创建。
     */
    public MemoryAuditStore() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * 指定容量创建。
     *
     * @param capacity 保留条数，最小 1
     */
    public MemoryAuditStore(int capacity) {
        this(capacity, DEFAULT_MAX_SCRIPT_BYTES);
    }

    /**
     * 指定容量与脚本预览长度创建。
     *
     * @param capacity       保留条数，最小 1
     * @param maxScriptChars 每条记录保留的脚本字符数，{@code <= 0} 表示不截断
     */
    public MemoryAuditStore(int capacity, int maxScriptChars) {
        this.capacity = Math.max(1, capacity);
        this.maxScriptChars = maxScriptChars;
        this.records = new ArrayDeque<>(this.capacity);
    }

    /**
     * 记一条。满了就覆盖最旧的一条。
     *
     * <p>不抛异常、不阻塞：这个方法处在「审计不能拖垮主流程」的位置上，
     * 而内存操作唯一的失败可能是 OOM —— 那已经不是审计层能处理的问题。
     *
     * @param record 审计记录
     */
    @Override
    public synchronized void save(AuditRecord record) {
        if (record == null) {
            return;
        }
        if (records.size() >= capacity) {
            records.pollFirst();
        }
        records.addLast(trimScript(record));
        totalRecorded++;
    }

    /**
     * 查询最近的记录，按时间倒序（最新在前）。
     *
     * @param limit 最多返回条数，{@code <= 0} 返回空列表
     * @return 不可变列表，永不为 {@code null}
     */
    @Override
    public synchronized List<AuditRecord> query(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        int n = Math.min(limit, records.size());
        List<AuditRecord> out = new ArrayList<>(n);
        Iterator<AuditRecord> it = records.descendingIterator();
        while (it.hasNext() && out.size() < n) {
            out.add(it.next());
        }
        return List.copyOf(out);
    }

    /**
     * @return 恒为 {@code true}
     */
    @Override
    public boolean supportsQuery() {
        return true;
    }

    /**
     * @return 固定标识，不随容量变化（配置变了不该让日志里的名字跟着变）
     */
    @Override
    public String name() {
        return "MemoryAuditStore";
    }

    /**
     * 当前保留条数。
     *
     * @return 0 到容量之间
     */
    public synchronized int size() {
        return records.size();
    }

    /**
     * 累计写入过多少条（包括已被覆盖的）。
     *
     * <p>用途：判断「环形缓冲是否已经转过一圈」，转过就说明有记录被丢了，
     * UI 应该提示「更早的记录已滚出缓冲」。
     *
     * @return 累计条数
     */
    public synchronized long totalRecorded() {
        return totalRecorded;
    }

    /**
     * @return 容量
     */
    public int capacity() {
        return capacity;
    }

    /**
     * 清空缓冲。只清内容，不清累计计数 —— 那个数字代表「历史上有多少次执行」。
     */
    public synchronized void clear() {
        records.clear();
    }

    /**
     * 截断脚本原文，只留一段预览。
     *
     * <p>按 char 截断而不是字节：这里的目的只是「别让 100KB 的脚本常驻内存」，
     * 不需要精确到字节，也不能为了精确而切断代理对。
     *
     * @param r 原始记录
     * @return 脚本被截断后的记录；未超长时原样返回
     */
    private AuditRecord trimScript(AuditRecord r) {
        String script = r.script();
        if (maxScriptChars <= 0 || script == null || script.length() <= maxScriptChars) {
            return r;
        }
        int end = maxScriptChars;
        if (Character.isHighSurrogate(script.charAt(end - 1))) {
            end--;
        }
        String cut = script.substring(0, end) + "\n...(内存副本已截断，共 " + script.length() + " 字符)";
        return AuditRecord.builder()
                .traceId(r.traceId())
                .at(r.at())
                .channel(r.channel())
                .operator(r.operator())
                .clientIp(r.clientIp())
                .scriptDigest(r.scriptDigest())
                .script(cut)
                .commitRequested(r.commitRequested())
                .rolledBack(r.rolledBack())
                .ok(r.ok())
                .errorType(r.errorType())
                .errorMsg(r.errorMsg())
                .resultDigest(r.resultDigest())
                .durationMs(r.durationMs())
                .affectedRows(r.affectedRows())
                .build();
    }
}
