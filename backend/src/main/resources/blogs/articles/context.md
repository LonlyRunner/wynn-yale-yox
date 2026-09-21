你在 Controller 中能正常读取当前用户，提交到线程池后却突然变成“未登录”；另一位用户偶尔还会读到上一个请求留下的租户编号。代码在单线程测试里没有问题，到了并发环境才随机失败。原因通常不是线程池把数据弄丢了，而是登录信息、Trace ID 等上下文保存在原线程的 `ThreadLocal` 中，任务切换线程后没有自动复制，线程复用后又没有及时清理。本文会解释上下文跟线程的关系，写一个可直接运行的传播包装器，并给出验证串号和内存泄漏的方法。

## 1. 从一个实际问题开始

Web 请求在线程 A 中完成鉴权，随后把任务交给线程池中的线程 B。`ThreadLocal` 的数据按线程保存，B 从未写入当前用户，因此读取为空。如果 B 上一次处理其他用户时写入数据却没有清理，还可能读到旧值。

这解释了两个看似矛盾的现象：上下文既可能“丢失”，也可能“串号”。

## 2. 先建立整体认识

```text
请求线程写入上下文
→ 提交任务时捕获必要字段
→ 工作线程执行前恢复
→ 业务代码读取
→ finally 中清理
```

可以把上下文想成贴在线程工牌上的便签。任务换了工作人员，便签不会自动过去；工作人员处理完又必须撕掉便签。类比的局限是，真实上下文可能包含可变对象，直接共享引用还会产生并发修改问题。

## 3. 必须掌握的核心概念

### 3.1 ThreadLocal 是什么

`ThreadLocal<T>` 为每个线程保存独立值。它适合保存一次调用链中的轻量信息，例如 Trace ID，不适合当作全局缓存。在线程池里线程长期存在，因此 `remove()` 是必要步骤。

### 3.2 捕获与恢复

捕获发生在提交任务的线程；恢复发生在执行任务的线程。应复制不可变的必要字段，不要把整个请求对象、连接或大对象塞进上下文。

### 3.3 为什么不能随便使用 InheritableThreadLocal

它只在线程创建时把父线程值复制给子线程。线程池中的线程通常早已创建，后续提交任务并不会重新继承，因此不能解决线程池传播问题。

### 3.4 清理为什么必须放 finally

业务代码可能抛异常。若清理写在正常返回之后，异常路径会跳过它，旧值就留在线程中并污染下一项任务。

## 4. 一个最小可运行示例

下面的程序实现一个只传播用户 ID 的包装器。它先捕获提交线程的值，再在工作线程恢复，并在最后清理。

```java
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ContextDemo {
    private static final ThreadLocal<String> USER = new ThreadLocal<>();

    static Runnable withUserContext(Runnable task) {
        String capturedUser = USER.get();
        return () -> {
            String previous = USER.get();
            try {
                if (capturedUser == null) USER.remove();
                else USER.set(capturedUser);
                task.run();
            } finally {
                if (previous == null) USER.remove();
                else USER.set(previous);
            }
        };
    }

    public static void main(String[] args) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            USER.set("user-A");
            pool.submit(withUserContext(
                () -> System.out.println("task1=" + USER.get())
            )).get();

            USER.set("user-B");
            pool.submit(withUserContext(
                () -> System.out.println("task2=" + USER.get())
            )).get();
        } finally {
            USER.remove();
            pool.shutdown();
        }
    }
}
```

数据来源是主线程中的 `USER`。调用 `withUserContext` 时先把字符串复制到局部变量；线程池真正执行包装后的 Runnable 时才调用 `USER.set`，这一步改变了工作线程可见的上下文。任务结束后，`finally` 恢复原值或删除，避免影响线程池下一次复用。

## 5. 逐步完成实际操作

1. 保存文件并编译：

```bash
javac ContextDemo.java
```

2. 运行程序：

```bash
java ContextDemo
```

应依次输出 `task1=user-A` 和 `task2=user-B`。

3. 暂时去掉 `withUserContext`，再次运行应输出 `null`，证明 ThreadLocal 不会跨线程自动传播。
4. 在实际项目中，把用户 ID、租户 ID 和 Trace ID 封装成不可变 `ContextSnapshot`，统一由 Executor 包装器处理。
5. 若使用 Spring Security、日志 MDC 或 Micrometer Context Propagation，优先使用框架提供的委托执行器，不要重复造多套传播逻辑。

## 6. 如何验证

使用只有一个工作线程的线程池连续提交不同用户任务，最容易暴露残留。测试应覆盖正常返回、抛异常和嵌套任务三种情况。日志同时打印任务编号、用户 ID 和线程名，确认同一线程复用时用户仍然正确。

## 7. 常见问题与排错

- **异步任务读到 null**：确认捕获动作发生在提交线程，而不是工作线程开始后。
- **偶发用户串号**：搜索所有 `ThreadLocal.set`，确认每条路径都有 `finally remove`。
- **内存持续增长**：检查 ThreadLocal 是否保存大对象，以及线程池是否长期存活。
- **嵌套任务覆盖外层上下文**：像示例一样保存并恢复 `previous`，不能一律删除。
- **使用 CompletableFuture 后丢上下文**：显式传入包装过的 Executor，不要默认依赖公共线程池。

上下文传播的关键不是“让 ThreadLocal 自动工作”，而是在异步边界显式完成捕获、恢复和清理，并尽量把真正需要的数据作为方法参数传递。
