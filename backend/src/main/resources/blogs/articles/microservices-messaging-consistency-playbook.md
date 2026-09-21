支付服务已经把订单标记为“已支付”，库存服务却因为短暂断网没有收到通知；消息恢复后又投递了两次，优惠券被重复发放。单体应用里一次事务可以完成的事情，拆成微服务后会经历网络超时、重复、乱序和部分成功。本文用“支付完成后扣库存并发券”的场景解释最终一致性，建立业务写入、Outbox、消息系统和幂等消费者之间的链路，并给出最小表结构、生产流程、验证方法和积压排查步骤。

## 1. 从一个实际问题开始

业务代码常写成：更新订单成功后发送 MQ。若数据库已经提交、发送前进程崩溃，消息永远丢失；若先发消息再提交数据库，消费者可能看到一个最终回滚的订单。即使开启生产者确认和手动 ACK，消费者在业务提交后、ACK 前崩溃，消息仍会再次投递。

因此可靠消息通常追求“至少一次投递”，并通过幂等消费处理重复，而不是幻想网络只传一次。

## 2. 先建立整体认识

```text
支付请求
→ 本地事务：更新订单 + 写 Outbox
→ 后台发布器读取 Outbox
→ MQ 持久化并确认
→ 消费者收到消息
→ 本地事务：业务更新 + 记录 event_id
→ ACK
```

Outbox 是业务数据库中的待发送事件表。它像寄件登记单：业务提交时先保证登记单和订单一起保存，快递员之后可以反复尝试投递。类比的局限是，真实消息可能乱序，消费者还要依据业务版本判断旧事件。

## 3. 必须掌握的核心概念

### 3.1 本地事务只能覆盖一个数据库

订单库事务无法直接保证 MQ 和库存库同时提交。把订单变化和 Outbox 放在同一数据库事务中，可以保证“有业务变化就一定有待发送记录”。

### 3.2 至少一次意味着可能重复

发布确认、持久化队列和手动 ACK 能降低丢失概率，但网络断开时发送方无法区分“服务端未收到”和“已收到但响应丢失”。安全做法是重试，并让消费者识别重复事件。

### 3.3 消费幂等必须有数据库约束

消费者把 `event_id` 写入处理记录表，并建立唯一索引。业务更新和处理记录在同一事务中提交。两个实例同时消费相同事件时，唯一约束会让其中一个失败或识别为已处理。

### 3.4 顺序只在必要范围内保证

同一订单的事件可以按订单 ID 路由到同一分区，保留局部顺序。追求全局顺序会把并行系统退化为单队列，还不能替代版本检查。

## 4. 一个最小可运行示例

先创建 Outbox 和消费记录表：

```sql
create table outbox_event (
  event_id varchar(64) primary key,
  aggregate_id bigint not null,
  event_type varchar(50) not null,
  payload text not null,
  status varchar(20) not null,
  retry_count int not null default 0,
  next_retry_at datetime null,
  created_at datetime not null
);

create table processed_event (
  consumer_name varchar(80) not null,
  event_id varchar(64) not null,
  processed_at datetime not null,
  primary key (consumer_name, event_id)
);
```

支付服务在同一事务中更新订单并写事件：

```java
@Transactional
public void markPaid(long orderId, String eventId) {
    int changed = orderRepository.markPaidIfPending(orderId);
    if (changed != 1) throw new IllegalStateException("订单状态不允许支付");

    String payload = "{\"orderId\":" + orderId + "}";
    outboxRepository.insert(eventId, orderId, "ORDER_PAID", payload, "NEW");
}
```

消费者把防重记录和业务修改放在同一事务中：

```java
@Transactional
public void consume(OrderPaid event) {
    if (processedEventRepository.exists("inventory", event.eventId())) return;

    int changed = inventoryRepository.reserve(event.orderId());
    if (changed != 1) throw new IllegalStateException("库存预留失败");

    processedEventRepository.insert("inventory", event.eventId());
}
```

`markPaid` 真正改变订单状态，同时写入待发送证据。发布器只负责扫描 `NEW` 事件、发送并在确认后改为 `SENT`。消费者收到数据后先判断事件是否处理过，再修改库存，并保存处理记录。若事务回滚，MQ 不应 ACK，消息稍后重投。

## 5. 逐步完成实际操作

1. 创建两张表，并确保主键或唯一索引真实存在。
2. 在订单事务中写 Outbox，不要在提交后临时拼一条内存消息。
3. 编写定时发布器，每次领取有限批次；发送失败时增加重试次数并计算下一次时间。
4. 打开生产者确认、持久化交换机/队列和消费者手动 ACK。
5. 消费失败超过上限后进入死信队列，保留错误原因和原事件，供人工处理。

## 6. 如何验证

进行三次故障演练：数据库提交后立即停止支付服务，重启后 Outbox 应继续发送；消费者业务提交前抛异常，消息应重投且只产生一次库存变化；消费者业务提交后模拟 ACK 丢失，第二次收到同一 event ID 时应直接返回。监控要包含 Outbox 未发送数量、最老事件年龄、MQ 积压、消费失败率和死信数量。

## 7. 常见问题与排错

- **消息看似丢失**：沿 event ID 检查 Outbox、发布日志、Broker 确认和消费者日志。
- **重复发券或扣库存**：确认处理记录与业务更新在同一事务，并有唯一约束。
- **MQ 积压**：比较生产速率、消费速率和失败率；先隔离毒消息，再决定扩容。
- **重试导致雪崩**：使用指数退避和随机抖动，并限制最大次数。
- **事件顺序错乱**：按业务键分区，在消费者处校验版本号，忽略过期事件。
- **服务关闭时丢在途任务**：先停止接收新消息，等待当前事务结束，再关闭连接。

微服务无法消除部分失败，但可以把失败变成有记录的状态。Outbox 保证事件可再次发送，幂等消费者保证重复不会破坏业务，监控和死信则让无法自动恢复的情况进入人工处理。
