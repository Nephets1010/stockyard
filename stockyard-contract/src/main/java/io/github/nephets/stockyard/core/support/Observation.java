/*
 * Copyright 2026 The stockyard Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.nephets.stockyard.core.support;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 观测路径（打日志 / 采集指标 / 记账）的统一守卫。
 *
 * <p><b>为什么需要它</b>：库存是钱。一次扣减要么成功要么失败，但「这次扣减能不能被记录下来」绝不能反过来 决定「扣减能不能成功」。现实里日志后端会因磁盘满、Appender
 * 配置错误、异步队列打满等原因抛异常；如果 观测代码和业务逻辑写在同一个 try 块里，一条 WARN 打不出去就会把已经算好的库存变更一起回滚掉——这是
 * 最糟的一类失败：业务数据没错，却因为记录失败而被判失败。因此本库约定 <b>观测路径永不抛异常</b>：所有 只负责留痕的代码一律经由 {@link #run(Runnable)} 执行，抛出的
 * {@code RuntimeException} 一律吞掉并计数降级。
 *
 * <p><b>为什么吞掉后只计数、不再补打一条日志</b>：补打的那一句走的正是刚刚失败的那条路径。若日志后端已经
 * 不可用，补打只会再抛一次，甚至递归。所以唯一安全的降级是把「有多少条留痕丢了」记在内存计数器里，由 {@link #suppressedCount()}
 * 暴露给运维与回归测试。留痕失败必须<b>可观测</b>，但不能用「再留一次痕」来观测 ——这也是本类刻意不持有 {@code Logger} 的原因。
 *
 * <p><b>为什么只捕获 {@code RuntimeException} 而不捕获 {@code Error}</b>：{@code OutOfMemoryError}、 {@code
 * StackOverflowError} 意味着 JVM 已不具备继续执行的前提，吞掉它们会把一个应当快速失败的场景变成 难以复现的数据损坏。这类错误按约定直接向上冒泡。
 *
 * <p><b>为什么计数是进程级静态值</b>：观测失败是「基础设施级」事件，与某个具体存储实例无关；用实例级计数
 * 会让「本进程一共丢了多少条日志」这个问题无法回答。代价是多个存储共享同一个计数器，因此回归测试应当 断言增量（{@code after - before}）而不是绝对值。
 */
public final class Observation {

    private static final AtomicLong SUPPRESSED = new AtomicLong();

    private Observation() {}

    /**
     * 执行一段只负责留痕的代码；它抛出的任何 {@code RuntimeException} 都被吞掉并计入 {@link #suppressedCount()}。
     *
     * <p>约定：传入的动作不得包含任何影响业务结果的副作用——它只能写日志、累加计数器或读取只读状态。 业务状态变更必须写在 {@code run}
     * 之外，否则「记录失败」会连带吞掉真正的失败原因。
     */
    public static void run(Runnable observation) {
        try {
            observation.run();
        } catch (RuntimeException ignored) {
            SUPPRESSED.incrementAndGet();
        }
    }

    /** 自进程启动以来被吞掉的观测失败次数；大于 0 说明有留痕丢了，日志链路需要排查。 */
    public static long suppressedCount() {
        return SUPPRESSED.get();
    }

    /** 仅用于回归测试复位全局计数器；生产代码不应调用。 */
    static void resetForTest() {
        SUPPRESSED.set(0L);
    }
}
