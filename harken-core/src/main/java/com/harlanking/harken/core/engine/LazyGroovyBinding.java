package com.harlanking.harken.core.engine;

import com.harlanking.harken.core.spi.BeanResolver;
import groovy.lang.Binding;
import groovy.lang.MissingPropertyException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 带懒解析的 {@code Binding}。整个「Bean 名直连」功能的落地点。
 *
 * <p>Groovy 把未声明变量名编译成 {@code binding.getVariable("name")}。所以这里
 * 先试显式变量，失败再去问容器，就实现了「脚本里直接写 {@code orderService}」，
 * 不需要 {@code ctx.getBean("orderService")}。
 *
 * <p><b>三个容易写错的地方：</b>
 * <ol>
 *   <li>父类构造函数<b>直接持有传入的 Map 引用</b>，不会复制。传 {@code Map.of()} 进去，
 *       后面 {@code setVariable} 会抛 {@code UnsupportedOperationException}。</li>
 *   <li>解析到的 Bean 要回填到 Binding，否则同一个脚本里用两次就 {@code getBean} 两次
 *       （对 prototype Bean 更是错误语义）。</li>
 *   <li>解析失败必须重新抛原始 {@code MissingPropertyException}，不能换成自己的异常——
 *       否则 Groovy 的报错信息里会丢掉变量名和上下文，用户看不懂。</li>
 * </ol>
 *
 * <p>非 public：这是实现细节，不属于对外 API。想替换解析逻辑请实现 {@link BeanResolver}。
 *
 * @author harlan
 * @since 1.0.0
 */
final class LazyGroovyBinding extends Binding {

    private final BeanResolver resolver;

    /**
     * @param variables 预置变量，会被复制；{@code null} 视为空
     * @param resolver  Bean 解析器，不能为 {@code null}
     */
    LazyGroovyBinding(Map<String, Object> variables, BeanResolver resolver) {
        super(variables == null ? new LinkedHashMap<>() : new LinkedHashMap<>(variables));
        this.resolver = resolver;
    }

    /**
     * 先查显式变量，未命中再向容器懒解析，成功则回填。
     *
     * @param name 变量名
     * @return 变量值或 Bean 实例
     * @throws MissingPropertyException 显式变量与容器里都没有这个名字
     */
    @Override
    public Object getVariable(String name) {
        try {
            return super.getVariable(name);
        } catch (MissingPropertyException notDeclared) {
            if (resolver.contains(name)) {
                Object bean = resolver.get(name);
                if (bean != null) {
                    super.setVariable(name, bean);
                    return bean;
                }
            }
            throw notDeclared;
        }
    }
}
