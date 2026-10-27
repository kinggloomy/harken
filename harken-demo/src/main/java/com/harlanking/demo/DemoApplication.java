package com.harlanking.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * 「加一个依赖就可用」的验收应用。
 *
 * <p>注意这个类里<b>没有</b>任何 Harken 相关的代码：没有 {@code @Import}、
 * 没有 {@code @EnableHarken}、没有手写 Bean。starter 的自动配置会把
 * {@code HarkenService} 放进容器，业务代码只需要在 application.yml 里
 * 写一个 {@code harken.packages}。
 *
 * <p>{@code main} 里唯一多出来的一行是修 Windows 控制台编码：默认代码页是 GBK，
 * 直接把 UTF-8 的中文写进 {@code System.out} 会变成乱码，而演示输出全是中文，
 * 这行不加就等于白做了个演示。
 *
 * @author harlan
 * @since 1.0.0
 */
@SpringBootApplication
public class DemoApplication {

    /**
     * 启动演示应用。
     *
     * @param args 命令行参数；演示结束用 {@code --demo.exit-after=true}
     */
    public static void main(String[] args) {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
        SpringApplication.run(DemoApplication.class, args);
    }
}
