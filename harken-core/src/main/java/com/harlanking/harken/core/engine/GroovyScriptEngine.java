package com.harlanking.harken.core.engine;

import com.harlanking.harken.core.HarkenException;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.MultipleCompilationErrorsException;
import org.codehaus.groovy.control.customizers.ASTTransformationCustomizer;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.syntax.SyntaxException;
import groovy.transform.ThreadInterrupt;
import groovy.lang.GroovyClassLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Groovy 实现：编译 + 编译缓存 + 输出捕获。
 *
 * <p><b>编译缓存为什么必须有：</b> {@code parseClass} 每次都 {@code defineClass} 一个
 * 新类，永不卸载。跑一万次不同脚本 = 一万个类占着 Metaspace，最终
 * {@code OutOfMemoryError: Metaspace}。按源码 hash 缓存后，同源码复用同一个 Class。
 *
 * <p><b>@ThreadInterrupt 的作用：</b> AST 转换会在每个循环体和方法调用处插入中断检查。
 * 配合 {@code ScriptExecutor} 的 {@code interrupt()}，纯计算死循环（{@code while(true){}}）
 * 能被定时打断。阻塞调用（IO、synchronized 死锁、native）不响应中断，只能丢弃线程。
 *
 * <p>本类是线程安全的：内部缓存由 {@code Collections.synchronizedMap} 保护，
 * 每次执行都新建脚本实例，不共享可变状态。
 *
 * @author harlan
 * @since 1.0.0
 */
public final class GroovyScriptEngine implements ScriptEngine {

    private static final Logger log = LoggerFactory.getLogger(GroovyScriptEngine.class);

    private static final Pattern LOCATION = Pattern.compile("line (\\d+), column (\\d+)");

    private final GroovyClassLoader classLoader;
    private final Map<String, Class<?>> cache;
    private final int cacheCapacity;

    /**
     * @param parent        父 ClassLoader，必须能看见业务类，否则脚本里 {@code import} 会失败。
     *                      一般传 {@code Thread.currentThread().getContextClassLoader()}
     * @param cacheCapacity 编译缓存容量，小于 16 时按 16 处理
     */
    public GroovyScriptEngine(ClassLoader parent, int cacheCapacity) {
        this.cacheCapacity = Math.max(16, cacheCapacity);

        CompilerConfiguration config = new CompilerConfiguration();
        config.setSourceEncoding(StandardCharsets.UTF_8.name());
        config.addCompilationCustomizers(new ASTTransformationCustomizer(ThreadInterrupt.class));
        // 脚本继承 HarkenScriptBase 而不是 groovy.lang.Script，才能捕获 println 输出
        config.setScriptBaseClass(HarkenScriptBase.class.getName());

        // 父加载器必须是应用 CL：脚本要能 import 业务类
        this.classLoader = new GroovyClassLoader(parent, config);

        // accessOrder=true 的 LinkedHashMap 就是现成的 LRU，不用引 Caffeine
        this.cache = Collections.synchronizedMap(
                new LinkedHashMap<>(64, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, Class<?>> eldest) {
                        return size() > GroovyScriptEngine.this.cacheCapacity;
                    }
                });
    }

    /** {@inheritDoc} */
    @Override
    public void check(String script) {
        compile(script);
    }

    /** {@inheritDoc} */
    @Override
    public Object run(ExecutionContext context, String script, PrintWriter out) {
        Class<?> clazz = compile(script);
        try {
            // 编译期指定了 scriptBaseClass，所以产物一定是 HarkenScriptBase 的子类
            HarkenScriptBase instance = (HarkenScriptBase) clazz.getDeclaredConstructor().newInstance();
            instance.setBinding(new LazyGroovyBinding(context.variables(), context.beanResolver()));
            instance.setHarkenOut(out);
            return instance.run();
        } catch (NoSuchMethodException | InstantiationException | IllegalAccessException
                 | java.lang.reflect.InvocationTargetException e) {
            throw new HarkenException("脚本实例化失败: " + e.getMessage(), e);
        }
    }

    private Class<?> compile(String script) {
        String key = com.harlanking.harken.core.Digests.sha256(script);
        Class<?> cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        Class<?> clazz;
        try {
            clazz = classLoader.parseClass(script, "HarkenScript_" + key.substring(0, 12));
        } catch (MultipleCompilationErrorsException e) {
            throw toCompilationFailure(e);
        }
        cache.put(key, clazz);
        log.debug("compiled script {} (cache size={})", key.substring(0, 12), cache.size());
        return clazz;
    }

    /**
     * 从 Groovy 的编译错误里抠行列号。优先走 ErrorCollector，拿不到再退回正则——
     * Groovy 小版本之间 collector 的 API 有变动，正则兜底更稳。
     */
    private HarkenException.Compilation toCompilationFailure(MultipleCompilationErrorsException e) {
        try {
            if (!e.getErrorCollector().getErrors().isEmpty()
                    && e.getErrorCollector().getErrors().get(0) instanceof SyntaxErrorMessage sem) {
                SyntaxException cause = sem.getCause();
                return new HarkenException.Compilation(
                        cause.getOriginalMessage(), cause.getLine(), cause.getStartColumn(), e);
            }
        } catch (RuntimeException ignored) {
            // 落到正则分支
        }
        Matcher m = LOCATION.matcher(String.valueOf(e.getMessage()));
        if (m.find()) {
            return new HarkenException.Compilation(e.getMessage(),
                    Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), e);
        }
        return new HarkenException.Compilation(e.getMessage(), -1, -1, e);
    }
}
