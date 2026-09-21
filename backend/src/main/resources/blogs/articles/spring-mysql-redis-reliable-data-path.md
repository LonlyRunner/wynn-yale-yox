用户点击一次“创建订单”，页面收到成功提示，刷新后订单却不存在；或者订单已经创建，库存和缓存仍显示旧值。代码看起来只是依次调用几个方法，为什么结果会分裂？因为一次业务写入同时跨过了请求校验、Spring 事务、MySQL 并发控制和 Redis 缓存，每层都有自己的成功边界。本文用一个简化下单接口建立完整数据链路，解释事务代理、幂等键、条件更新和 Cache Aside，并给出可直接参考的表结构与服务代码，让你能够验证重复请求和库存竞争。

## 1. 从一个实际问题开始

浏览器因网络超时重试了一次下单请求。两个请求几乎同时执行，都先查询到“订单不存在”和“库存充足”，随后各自插入订单并扣减库存。如果数据库没有唯一约束，按钮置灰也无法阻止双订单；如果缓存先更新、数据库后回滚，用户还会短暂读到不存在的数据。

可靠性来自每一层都承担明确责任，而不是希望请求永远只到达一次。

## 2. 先建立整体认识

```text
HTTP 请求
→ 参数与身份校验
→ 幂等键唯一约束
→ Spring 事务
→ MySQL 条件扣库存并写订单
→ 事务提交
→ 删除 Redis 缓存
→ 返回结果
```

Spring 事务决定一组数据库操作何时共同提交；MySQL 的锁和唯一约束处理并发竞争；Redis 提升读取速度，但不应成为订单正确性的唯一依据。

## 3. 必须掌握的核心概念

### 3.1 `@Transactional` 为什么可能失效

默认情况下，Spring 通过代理拦截 Bean 的外部方法调用并开启事务。同一个类里使用 `this.create()` 调用另一个事务方法会绕过代理。异常被 `catch` 后吞掉，代理也看不到失败。默认回滚规则主要针对运行时异常和 `Error`，受检异常需要按业务显式配置。

### 3.2 幂等必须落到唯一约束

客户端为一次业务操作生成幂等键。服务端把它与用户绑定，并在数据库建立唯一索引。两个请求并发到达时，即使都没查到记录，最终也只有一个能插入成功。

### 3.3 条件更新比“先查再扣”可靠

库存扣减可以在一条 SQL 中同时检查数量并更新。受影响行数为 0 表示库存不足或已被其他请求抢先修改，避免在 Java 中留下竞争窗口。

### 3.4 Cache Aside 的顺序

读取时先查缓存，未命中再查数据库并回填；写入时先提交数据库，再删除缓存。删除失败需要重试、消息或过期时间兜底。缓存是数据副本，因此最终依据仍是数据库。

## 4. 一个最小可运行示例

先创建用于防重、订单和库存的关键约束：

```sql
create table idempotency_record (
  id bigint primary key auto_increment,
  user_id bigint not null,
  idem_key varchar(80) not null,
  order_id bigint null,
  unique key uk_user_idem (user_id, idem_key)
);

create table inventory (
  product_id bigint primary key,
  available int not null
);

create table orders (
  id bigint primary key auto_increment,
  user_id bigint not null,
  product_id bigint not null,
  quantity int not null,
  status varchar(20) not null
);
```

下面的 Spring 服务把防重、扣库存和创建订单放在同一事务中。Repository 方法可以用 JPA 或 MyBatis 实现，关键是数据库操作顺序和约束。

```java
@Service
public class OrderService {
    private final IdempotencyRepository idempotency;
    private final InventoryRepository inventory;
    private final OrderRepository orders;
    private final StringRedisTemplate redis;

    public OrderService(IdempotencyRepository idempotency,
                        InventoryRepository inventory,
                        OrderRepository orders,
                        StringRedisTemplate redis) {
        this.idempotency = idempotency;
        this.inventory = inventory;
        this.orders = orders;
        this.redis = redis;
    }

    @Transactional
    public long create(long userId, String idemKey, long productId, int quantity) {
        if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive");

        IdempotencyRecord existing = idempotency.find(userId, idemKey);
        if (existing != null && existing.orderId() != null) return existing.orderId();

        idempotency.insert(userId, idemKey); // 唯一索引处理并发重复
        int changed = inventory.decreaseIfEnough(productId, quantity);
        if (changed != 1) throw new IllegalStateException("库存不足");

        long orderId = orders.insert(userId, productId, quantity, "CREATED");
        idempotency.bindOrder(userId, idemKey, orderId);
        return orderId;
    }

    public void evictProductCacheAfterCommit(long productId) {
        redis.delete("product:" + productId);
    }
}
```

对应的库存 SQL 是真正改变库存状态的地方：

```sql
update inventory
set available = available - #{quantity}
where product_id = #{productId}
  and available >= #{quantity};
```

请求数据先进入 `create`，幂等表决定它是不是同一次业务操作；条件更新原子地检查并扣减库存；订单和幂等结果在同一事务提交。Redis 删除必须在事务成功后触发，可使用事务同步回调或提交后的领域事件，避免数据库回滚时提前删除或写入错误缓存。

## 5. 逐步完成实际操作

1. 建表并插入一条库存数据：

```sql
insert into inventory(product_id, available) values (1001, 10);
```

2. 实现 Repository，并确认条件更新会返回受影响行数。
3. 从客户端生成一次幂等键，同一个请求重试时必须复用该键。
4. 使用两个并发请求提交相同用户和幂等键。正常结果应只创建一个订单。
5. 事务提交后删除商品缓存，再读取商品详情，确认缓存由数据库新值重建。

## 6. 如何验证

检查三个不变量：同一用户和幂等键最多一条记录；库存永不小于 0；成功订单数量与库存减少量一致。慢 SQL 使用慢查询日志定位，再用 `EXPLAIN ANALYZE` 查看实际扫描行数、排序和回表，不要仅凭 SQL 外观猜索引。

## 7. 常见问题与排错

- **事务方法执行了却没回滚**：确认 Bean 由 Spring 创建、调用经过代理、异常没有被吞掉。
- **重复请求仍生成两单**：确认数据库唯一索引存在，不能只依赖“先查再插”。
- **库存出现负数**：检查是否仍使用先查询后更新，以及更新 SQL 是否带 `available >= quantity`。
- **缓存长期是旧值**：记录删除失败，配置合理 TTL，并使用重试或消息补偿。
- **事务时间很长**：不要在数据库事务中执行慢 HTTP 调用；先缩小事务范围。
- **加了分布式锁仍重复**：锁可能过期或释放错误，唯一约束和幂等记录仍应作为最后防线。

一条可靠数据链路不是由某个注解保证的。事务、约束、条件更新和缓存策略各自解决一部分问题，组合后才能让失败可发现、重复可识别、结果可恢复。
