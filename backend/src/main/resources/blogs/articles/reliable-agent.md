你做了一个能查天气、搜知识库、调用订单接口的 Agent。演示时它回答流畅，真正连续运行后却开始出问题：同一个工具被反复调用，接口超时后无限重试，用户只是询问退款规则，它却差点真的提交退款。代码没有明显报错，模型也能正常回复，问题出在哪里？关键不在于再写一段更长的 Prompt，而在于应用没有明确记录“现在进行到哪一步、还允许做什么、失败后应该去哪”。本文会从一次失控的工具调用开始，建立 Agent 的状态模型，实现一个最小可运行的 Java 状态机，并学会验证停止条件、重试和人工确认。

## 1. 从一个实际问题开始

假设用户输入“帮我看看订单 A100，能退的话告诉我”。模型先调用查询订单工具，接着生成了退款参数。若程序采用无限循环，只要模型继续返回工具调用，应用就可能直接执行退款，甚至在网络超时时重复执行。

这里同时混在一起的有三个问题：模型给出的只是建议；工具可能产生真实副作用；网络错误并不代表操作没有成功。只有把三者分开，程序才能决定继续、重试、等待确认还是停止。

## 2. 先建立整体认识

Agent 是一个由模型参与决策的任务执行程序。模型负责根据上下文提出下一步动作，应用负责保存状态、校验权限并执行工具。它不负责替代后端鉴权，也不能保证每次计划都正确。

```text
用户目标
→ Agent 状态
→ 模型提出动作
→ 服务端校验
→ 工具执行
→ 结果写回状态
→ 继续、暂停或结束
```

可以把它类比成导航：模型负责建议下一条路线，状态保存当前位置，工具是真正控制车辆的动作，安全规则决定某条路是否允许走。类比的局限是，真实 Agent 的工具调用可能不可逆，所以不能仅靠模型“觉得安全”。

## 3. 必须掌握的核心概念

### 3.1 状态不是聊天记录

聊天记录只是输入的一部分。可恢复的状态还应包含目标、当前步骤、工具结果、重试次数、剩余预算和是否等待人工确认。没有这些字段，服务重启后无法判断一笔操作是否已经执行。

### 3.2 工具调用是一份待校验的请求

模型输出工具名和参数，并不等于获得执行权限。应用仍要检查 JSON 结构、当前用户、资源归属、参数范围、幂等键和超时。查询类工具通常可以自动执行；退款、发信、删数据等操作应先展示影响并等待确认。

### 3.3 停止条件必须写进程序

最大步骤数、最大重试次数、时间预算和 token 预算都应是代码中的硬限制。若只在 Prompt 中写“不要无限循环”，模型仍可能在异常上下文中继续尝试。

### 3.4 重试必须考虑幂等

幂等表示同一个请求重复执行，业务结果与执行一次相同。查询可以安全重试；扣款或退款必须携带幂等键，并由服务端用唯一约束保证不会重复处理。

## 4. 一个最小可运行示例

下面的程序不依赖具体模型 SDK，用固定 Planner 模拟模型动作，重点展示状态如何流动。保存为 `AgentDemo.java` 后可以直接运行。

```java
import java.util.ArrayList;
import java.util.List;

public class AgentDemo {
    enum Status { RUNNING, WAITING_APPROVAL, DONE, FAILED }
    record Action(String tool, String argument, boolean needsApproval) {}

    static final class State {
        final String goal;
        final List<String> events = new ArrayList<>();
        int steps;
        Status status = Status.RUNNING;
        State(String goal) { this.goal = goal; }
    }

    interface Planner { Action next(State state); }

    static String callTool(Action action) {
        if (!action.tool().equals("query_order")) {
            throw new IllegalArgumentException("tool not allowed");
        }
        return "订单 " + action.argument() + " 状态：已发货，不可直接退款";
    }

    static State run(State state, Planner planner) {
        int maxSteps = 3;
        while (state.status == Status.RUNNING && state.steps < maxSteps) {
            Action action = planner.next(state);
            state.steps++;
            if (action == null) {
                state.status = Status.DONE;
            } else if (action.needsApproval()) {
                state.events.add("等待确认：" + action.tool());
                state.status = Status.WAITING_APPROVAL;
            } else {
                state.events.add(callTool(action));
            }
        }
        if (state.status == Status.RUNNING) state.status = Status.FAILED;
        return state;
    }

    public static void main(String[] args) {
        Planner planner = state -> state.events.isEmpty()
            ? new Action("query_order", "A100", false)
            : null;
        State result = run(new State("查看订单 A100 是否能退款"), planner);
        System.out.println(result.status);
        result.events.forEach(System.out::println);
    }
}
```

程序先把用户目标放进 `State`。Planner 根据当前事件提出查询动作；`callTool` 进行白名单校验后才真正改变外部交互状态；结果写回 `events`，下一轮 Planner 看到已有证据后结束。`maxSteps` 是最后一道停止保护，即使 Planner 一直返回动作，循环也会终止。

## 5. 逐步完成实际操作

1. 新建文件并复制代码。这样先验证状态机，不把模型和网络问题混进来。正常情况下文件可被编译器识别。
2. 编译程序：

```bash
javac AgentDemo.java
```

没有输出表示编译成功。

3. 运行程序：

```bash
java AgentDemo
```

应看到 `DONE` 和订单查询结果，说明动作被执行一次并正常停止。

4. 把 Planner 第二个分支改成退款动作并标记 `needsApproval=true`。再次运行应看到 `WAITING_APPROVAL`，而不是直接执行。
5. 接入真实模型时，只替换 Planner；状态、白名单、审批和工具执行仍留在服务端。

## 6. 如何验证系统真的可靠

至少覆盖四组测试：正常查询能完成；Planner 一直返回动作时在最大步数后失败；危险操作进入等待确认；同一幂等键重复提交时业务只执行一次。日志应记录任务 ID、步骤、工具名、耗时和结果摘要，但不要记录密钥和完整敏感数据。

## 7. 常见问题与排错

- **工具被重复调用**：检查状态是否保存了上次结果，以及 Planner 是否能看到；为写操作增加幂等键。
- **服务重启后任务丢失**：不要只把 State 放内存，将检查点保存到数据库或持久化任务系统。
- **模型调用了不存在的工具**：服务端只接受注册表中的工具，未知工具返回结构化错误。
- **失败后无限重试**：同时限制单工具重试次数和整个任务步骤数，并使用指数退避。
- **模型绕过审批**：审批应由状态机和权限系统控制，不能依赖 Prompt 中的一句话。

当每一步都有状态、每个工具都有边界、每次失败都有去向时，Agent 才从“能演示”变成“可恢复、可验证的程序”。
