一个 Java 服务刚上线时运行平稳，流量升高后接口越来越慢，监控同时出现 CPU 100%、线程池队列增长和 Full GC。初学者很容易把这些现象当成三个独立问题：加线程、加堆内存、重启服务。结果往往只是短暂恢复，因为真正的链路可能是任务进入速度超过处理速度，队列保存大量对象，GC 更频繁，线程又在竞争 CPU。本文从这类线上现象出发，串起共享状态、线程池容量和 JVM 内存，给出一个可运行的有界线程池示例，并说明怎样用证据判断瓶颈究竟在哪里。

## 1. 从一个实际问题开始

假设接口每秒收到 500 个任务，每个任务平均需要 50ms，而线程池只能稳定完成每秒 200 个。剩余任务会进入队列。若队列无界，应用不会立即拒绝，却会把请求对象一直留在堆中。延迟、内存和 GC 压力同时增长，最后才以 OOM 或长时间停顿暴露。

所以“没有报错”并不代表系统健康。排队本身就是一种尚未显式失败的过载。

## 2. 先建立整体认识

```text
请求到达
→ 线程池接收或排队
→ 任务读取共享状态
→ CPU 执行与下游等待
→ 临时对象进入堆
→ GC 回收不可达对象
→ 返回结果
```

线程池控制同时运行和排队的任务数量；Java 内存模型规定线程何时能看到共享数据；JVM 堆保存对象；GC 回收不再被引用的对象。它们互相关联，却解决不同问题。

## 3. 必须掌握的核心概念

### 3.1 可见性、原子性与有序性

可见性表示一个线程的写入何时能被其他线程看到；原子性表示操作不会被拆开观察；有序性表示编译器和处理器的重排不能破坏规定的先后关系。`volatile` 能提供可见性和特定有序性，但 `count++` 仍由读取、加一、写回组成，不具备原子性。

单变量计数可使用 `AtomicLong`；多个字段必须共同满足条件时使用锁或不可变对象。

### 3.2 ConcurrentHashMap 的边界

它保证容器内部结构在并发访问时安全，但“先查再改”是两个操作，仍可能竞争。针对一个键的更新可使用 `compute`，跨多个资源的不变量则需要更高层同步或数据库约束。

### 3.3 线程池是容量控制器

核心线程数、最大线程数、队列和拒绝策略共同决定任务流向。参数不能只按 CPU 核数背公式，还要看任务耗时、到达率、下游容量和可接受延迟。无界队列把拒绝变成了不可控等待。

### 3.4 堆、栈与 GC

每个线程拥有自己的栈，局部变量和方法调用帧通常在这里；对象主要位于线程共享的堆中；类元数据位于元空间。Full GC 频繁可能来自对象创建过快、缓存无上限、队列堆积或堆配置不合理。先找对象为什么还被引用，再讨论调大内存。

## 4. 一个最小可运行示例

下面的程序使用有界队列、可命名线程和 `CallerRunsPolicy`。当线程池处理不过来时，提交线程会参与执行，从而减慢继续提交的速度。

```java
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class BoundedPoolDemo {
    private static final AtomicLong COMPLETED = new AtomicLong();

    public static void main(String[] args) throws InterruptedException {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
            2,
            4,
            30,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8),
            runnable -> {
                Thread thread = new Thread(runnable);
                thread.setName("order-worker-" + thread.threadId());
                return thread;
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
        );

        for (int i = 0; i < 30; i++) {
            int taskId = i;
            pool.execute(() -> {
                try {
                    Thread.sleep(50); // 模拟数据库或网络等待
                    COMPLETED.incrementAndGet();
                    System.out.println(Thread.currentThread().getName() + " task=" + taskId);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        pool.shutdown();
        if (!pool.awaitTermination(10, TimeUnit.SECONDS)) pool.shutdownNow();
        System.out.println("completed=" + COMPLETED.get());
    }
}
```

任务从 `main` 线程提交。前两个任务进入核心线程，随后任务先进入容量为 8 的队列；队列满后最多扩展到 4 个线程；再次饱和时，`CallerRunsPolicy` 让 `main` 自己执行。真正改变完成数量的是原子操作 `incrementAndGet()`。最后使用优雅关闭等待任务完成，而不是直接丢弃。

## 5. 逐步完成实际操作

1. 编译并运行示例：

```bash
javac BoundedPoolDemo.java
java BoundedPoolDemo
```

输出中出现 `main task=...` 表示背压生效，最终应看到 `completed=30`。

2. 记录线程池的 `activeCount`、队列长度、完成数、拒绝数和任务耗时。只有这些数据才能说明容量是否合适。
3. 在测试环境使用固定流量逐步升压，观察吞吐何时不再增长、P95 延迟何时开始陡升。
4. 出现内存问题时先保存现场，再分析 GC 日志和对象引用。

查看 Java 进程：

```bash
jcmd -l
```

查看堆与 GC 概况，其中 `<pid>` 替换为真实进程号：

```bash
jcmd <pid> GC.heap_info
jcmd <pid> VM.flags
```

抓取线程栈：

```bash
jcmd <pid> Thread.print > threads.txt
```

## 6. 如何验证判断是否正确

若队列持续增长而完成速率不变，瓶颈在处理能力或下游，不应继续扩大队列。若 GC 后堆基线持续上升，说明对象仍被引用，需要 heap dump 查引用链；若堆能降下来但分配速率很高，应减少临时对象或检查批量大小。CPU 高时用线程栈确认热线程是在计算、锁竞争还是频繁 GC。

## 7. 常见问题与排错

- **线程越多反而越慢**：线程切换和锁竞争增加，下游连接池也可能成为瓶颈。
- **队列一直不拒绝但延迟很高**：检查是否使用无界队列，把容量改为可测量的上限。
- **`volatile` 计数仍然不准**：复合更新需要原子类或锁。
- **Full GC 后内存不降**：用 heap dump 查看 dominator tree，重点检查缓存、静态集合和队列。
- **CPU 100% 直接重启**：重启前至少保存线程栈、GC 信息和关键指标，否则最有价值的证据会丢失。

排障时先画出任务、线程、对象和下游之间的链路，再用指标证明哪一段饱和。调参数应当是验证结论后的动作，而不是第一反应。
