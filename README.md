# stockyard

库存与预占套件：用一套不可变的值对象与状态机，把「库存水位、可预占/释放、带 TTL 的预占、幂等扣减、防超卖」这几件事讲清楚。核心库零 Spring 依赖，Spring Boot 侧只做自动装配。

**一句话**：stockyard 是一个把「不超卖」做成可被测试证明的库存内核——定长分片锁串行化库存水位、预占—确认—取消状态机、按 `requestId` 幂等扣减。

[中文完整版见 README.zh-CN.md](README.zh-CN.md) · [设计决策](#设计决策) · [已知局限](#已知局限)

## 模块

依赖方向单向无环：`contract` ← `store` ← `core` ← `starter` ← `sample`。

| 模块 | 说明 |
| --- | --- |
| `stockyard-contract` | 纯类型层：库存水位 `StockPosition`、SKU/仓库/租户值对象、预占状态机与不可变 `Reservation`、幂等扣减记录，以及三个 `Store` 接口。仅依赖 slf4j-api。 |
| `stockyard-store` | 可插拔存储的内存实现：`InMemoryStockPositionStore` / `InMemoryReservationStore` / `InMemoryDeductionStore`，全部是有界 LRU、淘汰可观测。 |
| `stockyard-core` | 引擎 `StockyardEngine`：64 槽定长分片锁、预占状态机、幂等扣减。面向 `contract` 的接口编程。 |
| `stockyard-starter` | Spring Boot 自动装配：时钟 / 存储 / 引擎 / 租户过滤器 / REST 端点，Bean 全部可被覆盖。 |
| `stockyard-sample` | 示例应用：演示预占—确认—扣减、预占超时释放、幂等扣减防超卖。 |

> 拆成 5 个模块不是凑数：类型、存储实现、引擎混在一个 jar 里时，调用方想替换存储就必须把引擎也拖进
> classpath，而引擎又会反向依赖存储实现形成环。拆开之后生产依赖可以只取
> `contract + core + 自己的 store 实现`，内存实现一行都不会被打进产物。

## 核心概念

- **StockPosition**：某个仓库内某个 SKU 的库存水位，不可变。在库 `onHand`、已预占 `allocated`，可用 `available = onHand - allocated`。
  - `receive(qty)` 入库（只增在库）
  - `allocate(qty)` 预占（增已预占，超出可用抛 `OversellException`）
  - `release(qty)` 释放预占
  - `deduct(qty)` 确认扣减（在库与已预占同时减，用于预占确认）
  - `consume(qty)` 直接消耗（只减在库，用于不挂预占的扣减）
- **Reservation**：一次预占，状态机 `PENDING → ACTIVE → CONFIRMED`，或 `→ CANCELLED` / `→ EXPIRED`。带 TTL，超时由 `expireDue` 统一回收并释放库存。
- **DeductionRecord**：幂等扣减记录，按 `requestId` 去重；库存不足返回 `FAILED` 而不抛异常。
- **StockyardEngine**：把上述概念串起来——`stockIn` / `reserve` / `confirm` / `cancel` / `expireDue` / `deduct`。

## 引入

```xml
<dependency>
  <groupId>io.github.nephets</groupId>
  <artifactId>stockyard-starter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

配置（`application.yml`）：

```yaml
stockyard:
  enabled: true
  tenant-header: X-Tenant-Id
```

## 代码示例

```java
StockyardEngine engine = ...;
StockyardTenant tenant = StockyardTenant.of("shop-1");
Sku sku = Sku.of("SKU-1");
Warehouse wh = Warehouse.of("WH-1");

engine.stockIn(sku, wh, 100);
Reservation r = engine.reserve("R1", tenant, sku, wh, 10, null); // 幂等：同 id 同参返回首次结果
engine.confirm(r.id());                                          // 扣减库存，时间取注入的 Clock
DeductionRecord d = engine.deduct("D1", tenant, sku, wh, 5);     // 幂等，重复调用不重复扣
```

想冻结时间（例如验证 TTL 回收），只需在构造期注入一个 `Clock`：

```java
Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
StockyardEngine engine = new StockyardEngine(reservations, positions, deductions, clock);
```

## HTTP 端点

所有端点都从 `X-Tenant-Id` 头解析租户；缺头返回 400。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/stockyard/reservations` | 预占（body: `sku`,`warehouse`,`qty`,`ttlSeconds`） |
| POST | `/stockyard/reservations/{id}/confirm` | 确认预占并扣减 |
| POST | `/stockyard/reservations/{id}/cancel` | 取消并释放 |
| POST | `/stockyard/reservations/expire` | 回收超时预占 |
| GET | `/stockyard/positions?sku=&warehouse=` | 查询库存水位 |
| POST | `/stockyard/deductions` | 幂等扣减（库存不足返回 409） |

```bash
curl -H "X-Tenant-Id: shop-1" -H "Content-Type: application/json" \
  -d '{"sku":"SKU-1","warehouse":"WH-1","qty":10}' \
  http://localhost:8080/stockyard/reservations
```

---

# 设计决策

这一节写的是「为什么这样做」。改动这些代码前请先读它——下面每个决定都有代价，改错了会直接变成资损。

## 一、为什么用定长分片锁，而不是按 SKU 一把锁

库存引擎最核心的一段是「读水位 → 算出新副本 → 写回」。这三步不是原子的：两个线程同时读到
`available = 1`，各自算出 `available = 0` 再写回，一件货就卖了两遍。所以同一个 `(sku, warehouse)`
的这三步必须串行。

最直白的写法是「按需建锁，用完淘汰」（`ConcurrentHashMap<Key, Object>`），它有两条死路：

- **不淘汰**：锁表条目数等于出现过的 `(sku, warehouse)` 组合数，是长生命周期的**无界集合**，
  SKU × 仓库规模一大就是内存泄漏，而且一直被强引用，永远成不了 GC 的回收目标。
- **做 LRU 淘汰**：更危险。同一 key 的锁被淘汰后，新请求会拿到**另一把**锁，「读—算—写」不再串行，
  直接退化成超卖——而这次超卖没有任何日志痕迹，因为它看起来像正常请求。

定长分片锁两条死路都绕开了：构造后容量恒定（天然有上限），同一 key 恒定映射到同一把锁，互斥语义不变。
代价是不同 key 可能共用一把锁、并行度略降——**这是安全方向上的取舍**。

**为什么是 64**：①2 的幂，`floorMod(hash, 64)` 等价于 `hash & 63`，取模不走除法；②目标并发度是
「几十个线程打不同 SKU」，64 槽把碰撞概率压到约 1/64，再往上翻收益已经不明显；③锁数组常驻内存
64 × 16 B ≈ 1 KB，分片过多不会更正确，只会浪费内存。

**为什么哈希要同时吃 sku 和 warehouse**：同 SKU 多仓是常态，只按 SKU 分片会把「同 SKU 不同仓」全部
挤到一把锁上，多仓并行发货的吞吐直接退化成串行。同理必须用 `Math.floorMod` 而不是 `%`——
`Objects.hash` 可能返回负数，`hash % 64` 会得到负索引直接越界。

**为什么不用分布式锁**：跨节点保证的正确做法是把「读—算—写」下沉成存储层的一次原子操作
（Redis Lua / `UPDATE ... WHERE on_hand - allocated >= ?`），而不是把这里的 `synchronized` 换成
分布式锁——后者会把一次库存操作变成「网络往返 + 锁租约」，吞吐掉一个数量级。

## 二、预占—确认—取消状态机与超卖防护的口径

```
PENDING --activate--> ACTIVE --confirm--> CONFIRMED（终态）
   |                    |
   +------cancel--------+------cancel--> CANCELLED（终态）
   |                    |
   +------expire--------+------expire--> EXPIRED（终态）
```

**为什么是两段式**：下单到支付之间有时间窗口。下单即扣减，未支付订单会长时间占住库存（**少卖**）；
不占库存直接扣，支付时才发现没货（**超卖**）。两段式把库存从 `onHand` 里划出一块 `allocated`，
可用量 = `onHand - allocated`：既让未支付订单不阻塞真实销售（TTL 到期自动回收），又保证已支付订单一定有货。

**「不超卖」的完整口径**（三条缺一不可）：

1. **不超卖**：成功预占/扣减的总量 ≤ 入库总量；
2. **总数守恒**：`onHand` 恒定，`available + 已确认消耗量 == 入库量`；
3. **水位不为负**：`allocated <= onHand` 恒成立。

只断言第 1 条是不够的——**一个把所有请求都拒掉的实现也能通过**。

**为什么非法迁移要打日志、成功迁移不打**：能调用到 `confirm(非 ACTIVE)` 说明调用方绕过了状态机，
或存在没被锁覆盖的并发路径，这是必须留证据的信号；而值对象的成功路径在秒杀里会被逐条调用，
逐条打点会淹没日志并拖慢扣减，成功迁移由引擎在业务边界统一记录。

## 三、幂等键的语义

`requestId` 由**调用方**生成并保证「同一次业务意图 = 同一个 id」。引擎先按它查记录，查到就直接返回
上次的结论，**连库存水位都不读**。同一个订单行重发 10 次只扣 1 次，10 个不同订单行各扣 1 次——
**幂等的粒度完全由调用方决定**，这是设计而不是缺陷。

**为什么库存不足也写一条 FAILED 记录**：抛异常意味着调用方必须立刻处理，而「库存不足」在秒杀里是
常态而非故障；更重要的是不落记录的话，同 `requestId` 重试会重新判定，库存补货后就变成
「上次失败、这次成功」的不一致结果。落 FAILED 记录把结论固化下来，重试拿到的是稳定答案。

**为什么幂等判定必须在锁内**：放锁外确实更快（重放流量不用抢锁），但两个线程会同时查不到记录、
各自判定为首次，同一笔业务被扣两次。库存是钱，正确性优先于这点吞吐。

**幂等不覆盖什么**：本实现保证「同一 `requestId` 在同一 JVM 内只扣一次」。跨进程部署时两个节点
同时首次提交仍会各扣一次——跨节点必须让 `DeductionStore` 提供 put-if-absent 原子语义
（Redis `SETNX` / DB 唯一索引），而不是靠这里的锁。

## 四、为什么模块要这样切

1. **打破环**：接口与值对象放在引擎模块里，存储实现就得反向依赖引擎。下沉到最底层后依赖变成单向链。
2. **可替换**：生产依赖只取 `contract + core + 自己的 store 实现`，内存实现不打进产物。
   只有 `starter` 为了「开箱即用」才显式依赖它，且所有 Bean 都是 `@ConditionalOnMissingBean`。
3. **零 Spring 内核**：`contract` / `store` / `core` 严禁引入 `org.springframework.*`。
   库存是业务内核，它应该能在单元测试里裸跑，不需要起 Spring 上下文。

## 五、为什么时间必须可注入

「下单 30 分钟未支付自动释放」是唯一依赖时间的业务规则。用 `Instant.now()` 写的话，验证它只能靠
`Thread.sleep(31 * 60 * 1000)`（慢到没法进 CI），或把 TTL 调成几百毫秒赌线程调度（CI 繁忙时随机失败）。

所以 `Clock` 在构造期一次注入，并暴露 `clock()`。**构造期钉死而不是方法上默认取系统时钟**，
是因为时钟是引擎的全局事实来源：一半方法用注入时钟、一半用系统时钟，TTL 判定就会自相矛盾。

## 六、可观测性的三条纪律

1. **观测路径永不抛异常**：一次扣减要么成功要么失败，但「这次扣减能不能被记录下来」绝不能反过来决定
   「扣减能不能成功」。所有只负责留痕的代码都包在 `Observation.run(...)` 里，抛出的 `RuntimeException`
   一律吞掉并计数（计数而不是补打一条日志——补打走的正是刚刚失败的那条路径）。
2. **构造期拒绝 null，不把缺陷推迟到调用点**：一个 null 混进 key 不会立刻报错，而是变成一条 key 为 null
   的水位，然后被别的 SKU 查出来。
3. **长生命周期集合必须有界，且淘汰要可观测**：容量打满后的 LRU 淘汰是静默的数据丢失，既要打 WARN
   （首次淘汰一条，之后每 10 000 条一条，避免热路径刷爆日志），也要暴露 `evictedCount()` 让运维一眼
   看出「容量够不够」。

---

# 已知局限

1. **防超卖仅在单 JVM 内成立**：分片锁是 JVM 内的 `synchronized`，多实例部署时跨节点并发不受保护，
   需要把原子更新下沉到存储层。
2. **幂等不跨进程**：换 Redis/DB 实现时必须用 `SETNX` / 唯一索引承接 put-if-absent 语义。
3. **多租户隔离不做鉴权**：`StockyardTenantFilter` 只解析请求头，不校验身份。能伪造 `X-Tenant-Id`
   就能读到别家库存——生产必须在网关层鉴权，并把过滤器放在鉴权之后。
4. **内存存储的淘汰会降级业务能力**：预占单消失、库存归零、幂等失效。`evictedCount() > 0` 就是报警信号。
5. **`expireDue` 是全表扫描**（O(总预占单数)），百万级之后需要换成按到期时间排序的索引。
6. **HTTP 状态码映射偏粗**：只有 `OversellException` 映射成 409，其余领域异常冒泡成 500。

更完整的清单见 [`CHANGELOG.md`](CHANGELOG.md) 的「已知遗留」，安全问题见 [`SECURITY.md`](SECURITY.md)。

---

## 构建与测试

```bash
mvn -B verify
```

- 测试：**360 个 `@Test`**（contract 166 / store 48 / core 87 / starter 52 / sample 7）
- 行覆盖率门禁：`stockyard-contract` / `stockyard-store` / `stockyard-core` 为 **0.75**，整体 0.60
- 格式化：spotless 2.44.4 + google-java-format (AOSP)，`verify` 时自动 apply

### 并发测试怎么证明不超卖

`StockyardEngineStripedLockConcurrencyTest` 的每个场景都同时断言「不超卖 + 总数守恒 + 水位不为负」，
并刻意覆盖两条路径：**撞槽**（两个哈希落到同一锁槽的 key 必须互斥）与**不撞槽**
（64 个互不撞槽的 key 各占 1 件，全部并发预占必须全部成功——保护的是「分片确实提供了并行度」）。
只测不撞槽等于没测防超卖，只测撞槽会掩盖吞吐塌掉。

## 架构约束

`stockyard-contract` / `stockyard-store` / `stockyard-core` 严禁引入任何 `org.springframework.*` 依赖；
CI 的 `architecture-boundaries` 任务会扫描其主源码，出现即失败。
`stockyard-store` 在 `stockyard-core` 中只能是 `test` scope。

## 参与贡献与安全

见 [`CONTRIBUTING.md`](CONTRIBUTING.md) 与 [`SECURITY.md`](SECURITY.md)。
