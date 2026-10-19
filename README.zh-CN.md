# stockyard

库存与预占套件：用一套不可变的值对象与状态机，把「库存水位、可预占/释放、带 TTL 的预占、幂等扣减、防超卖」这几件事讲清楚。

[English-ish 版本见 README.md](README.md) · [设计决策](#设计决策) · [已知局限](#已知局限)

---

## 一句话

**stockyard 是一个把「不超卖」做成可被测试证明的库存内核**：定长分片锁串行化库存水位、预占—确认—取消状态机、
按 `requestId` 幂等扣减，外加一层 Spring Boot 自动装配。核心库零 Spring 依赖。

## 模块

依赖方向单向无环：`contract` ← `store` ← `core` ← `starter` ← `sample`。

| 模块 | 职责 | 依赖 |
| --- | --- | --- |
| `stockyard-contract` | 纯类型层：库存水位 `StockPosition`、SKU/仓库/租户值对象、预占状态机与不可变 `Reservation`、幂等扣减记录，以及三个 `Store` 接口 | 仅 slf4j-api |
| `stockyard-store` | 可插拔存储的内存实现：`InMemoryStockPositionStore` / `InMemoryReservationStore` / `InMemoryDeductionStore`，全部是有界 LRU | contract |
| `stockyard-core` | 引擎 `StockyardEngine`：64 槽定长分片锁、预占状态机、幂等扣减 | contract（store 仅 test scope） |
| `stockyard-starter` | Spring Boot 自动装配：时钟 / 存储 / 引擎 / 租户过滤器 / REST 端点 | core + store |
| `stockyard-sample` | 示例应用：演示三类能力 | starter |

> 拆成 5 个模块不是凑数。类型、存储实现、引擎混在一个 jar 里时，调用方想替换存储就必须把引擎也拖进
> classpath，而引擎又会反向依赖存储实现形成环。拆开之后，生产依赖可以只取
> `contract + core + 自己的 store 实现`，内存实现一行都不会被打进产物。

## 快速开始

```xml
<dependency>
  <groupId>io.github.nephets</groupId>
  <artifactId>stockyard-starter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```yaml
stockyard:
  enabled: true
  tenant-header: X-Tenant-Id
```

```java
StockyardEngine engine = ...;                       // 由 starter 装配，或自己 new
StockyardTenant tenant = StockyardTenant.of("shop-1");
Sku sku = Sku.of("SKU-1");
Warehouse wh = Warehouse.of("WH-1");

engine.stockIn(sku, wh, 100);
Reservation r = engine.reserve("R1", tenant, sku, wh, 10, null); // 幂等：同 id 同参返回首次结果
engine.confirm(r.id());                                          // 时间取注入的 Clock
DeductionRecord d = engine.deduct("D1", tenant, sku, wh, 5);     // 幂等：重复调用不重复扣
```

```bash
curl -H "X-Tenant-Id: shop-1" -H "Content-Type: application/json" \
  -d '{"sku":"SKU-1","warehouse":"WH-1","qty":10}' \
  http://localhost:8080/stockyard/reservations
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

---

# 设计决策

这一节写的是「为什么这样做」，不是「做了什么」。如果你要改这些代码，请先读它——
下面每个决定都有代价，改错了会直接变成资损。

## 一、为什么用定长分片锁，而不是按 SKU 一把锁

库存引擎最核心的一段是「读水位 → 算出新副本 → 写回」。这三步不是原子的：
两个线程同时读到 `available = 1`，各自算出 `available = 0` 再写回，一件货就卖了两遍。
所以同一个 `(sku, warehouse)` 的这三步必须串行。

最直白的写法是「按需建锁，用完淘汰」，也就是 `ConcurrentHashMap<Key, Object>`。它有两条死路：

- **不淘汰**：锁表条目数等于出现过的 `(sku, warehouse)` 组合数，是长生命周期的**无界集合**。
  SKU × 仓库规模一大就是内存泄漏，而且因为一直被强引用，永远成不了 GC 的回收目标。
- **做 LRU 淘汰**：更危险。同一个 key 的锁被淘汰后，新请求会拿到**另一把**锁，
  「读—算—写」就不再串行，直接退化成超卖——而这次超卖没有任何日志痕迹，因为它看起来像正常请求。

定长分片锁两条死路都绕开了：构造后容量恒定（天然有上限），且同一 key 恒定映射到同一把锁，互斥语义不变。
代价是不同 key 可能共用一把锁、并行度略降——**这是安全方向上的取舍，我们接受**。

### 为什么是 64

三个理由，缺一不可：

1. **它是 2 的幂**：`floorMod(hash, 64)` 等价于 `hash & 63`，取模不必走除法；
2. **够用**：本引擎的目标并发度是「几十个线程同时打不同 SKU」。64 个槽在热点均匀分布时把碰撞概率
   压到约 1/64，再往上翻（1024、4096）换来的并行度提升已经不明显；
3. **便宜**：锁数组常驻内存，64 × 对象头（16 B）≈ 1 KB。分片过多不会破坏正确性，只会浪费内存和
   增加 GC 扫描成本——所以取一个偏保守但不浪费的数。

如果你的热点 SKU 只有个位数，64 依然是安全的。

### 为什么哈希要同时吃 sku 和 warehouse

`Sku.hashCode()` 分布已经够均匀，但**同 SKU 多仓是常态**。只按 SKU 分片会把「同 SKU 不同仓」的全部操作
挤在同一把锁上，多仓并行发货的吞吐直接退化成串行。把两者一起哈希能让它们分散到不同锁上。

同理必须用 `Math.floorMod` 而不是 `%`：`Objects.hash` 可能返回负数，`hash % 64` 会得到负索引直接越界。

### 为什么不用分布式锁

如果有一天你需要跨节点保证，正确做法**不是**把这里的 `synchronized` 换成分布式锁，而是把
「读—算—写」下沉成存储层的一次原子操作（Redis Lua，或 `UPDATE ... WHERE on_hand - allocated >= ?`）。
分布式锁会把一次库存操作变成「一次网络往返 + 一次锁租约」，吞吐掉一个数量级；而原子更新只是把
同样的语义换个地方执行。

## 二、预占—确认—取消状态机与超卖防护的口径

```
PENDING --activate--> ACTIVE --confirm--> CONFIRMED（终态）
   |                    |
   +------cancel--------+------cancel--> CANCELLED（终态）
   |                    |
   +------expire--------+------expire--> EXPIRED（终态）
```

### 为什么是两段式

下单到支付之间有一段时间窗口。若下单即扣减，未支付订单会长时间占住库存（**少卖**）；
若不占库存直接扣，支付时才发现没货（**超卖**）。两段式把库存从「在库 `onHand`」里划出一块
「已预占 `allocated`」，可用量 = `onHand - allocated`：既让未支付订单不阻塞真实销售（TTL 到期自动回收），
又保证已支付的订单一定有货。

### 「不超卖」的口径

三个层次，缺一不可：

1. **不超卖**：成功预占/扣减的总量 ≤ 入库总量；
2. **总数守恒**：`onHand` 恒定，`available + 已确认消耗量 == 入库量`——没有任何一件货既被卖掉又留在库里，
   也没有凭空消失；
3. **水位不为负**：`allocated <= onHand` 恒成立。

只断言第 1 条是不够的：**一个把所有请求都拒掉的实现也能通过**。所以并发测试必须三条一起断言。

### 为什么每个非法迁移都要打日志

能调用到 `confirm(非 ACTIVE)` 说明调用方绕过了引擎直接改状态，或存在没被锁覆盖的并发路径。
这是「状态机被绕过」的信号而不是普通的业务失败，必须留下证据。而它是低频失败路径，不在热循环上，
打日志的代价可以接受。

反过来，**值对象的成功路径刻意不打日志**：一次秒杀里这些方法会被逐条调用，逐条打点会淹没日志并拖慢扣减。
成功迁移由引擎在业务边界统一记录。

## 三、幂等键的语义

`requestId` 由**调用方**生成并保证「同一次业务意图 = 同一个 id」（通常用订单号 + 行号，或上游消息 id）。
引擎在扣减前先按它查记录：查到就直接返回上次的结论，**连库存水位都不读**。

- 同一个订单行重发 10 次 → 只扣 1 次；
- 10 个不同订单行 → 各扣 1 次。

**幂等的粒度完全由调用方决定**，这是设计而不是缺陷——引擎无法替你判断「这两次请求是不是同一笔业务」。

### 为什么库存不足也写一条 FAILED 记录，而不是抛异常

抛异常意味着调用方必须立刻处理，而「库存不足」在秒杀里是常态而非故障。更重要的是：
不落记录的话，下一次同 `requestId` 重试会重新走一遍判定，库存补货后就变成「上次失败、这次成功」的
不一致结果。落 FAILED 记录把「这个请求已经判过，结论是不可用」固化下来，重试拿到的是稳定答案。

代价是 FAILED 记录会长期占位，所以调用方要对 `requestId` 设合理有效期，或者给存储配足够容量。

### 幂等判定为什么必须在锁内

把幂等查询放在锁外确实更快（重放流量不用抢锁），但两个线程会同时查不到记录、各自判定为首次，
同一笔业务被扣两次。**库存是钱，正确性优先于这点吞吐。**

### 幂等不覆盖什么

本实现保证「同一 `requestId` 在同一 JVM 内只扣一次」。跨进程部署时，两个节点同时首次提交同一
`requestId` 仍会各扣一次——跨节点成立必须让 `DeductionStore` 提供 put-if-absent 原子语义
（Redis `SETNX` / DB 唯一索引），而不是靠这里的锁。

## 四、为什么模块要这样切

```
contract（纯类型）← store（内存实现）← core（引擎）← starter（自动装配）← sample（示例）
```

三个理由：

1. **打破环**：接口与值对象如果放在引擎模块里，存储实现就不得不反向依赖引擎。下沉到最底层后依赖变成单向链。
2. **可替换**：生产依赖可以只引 `contract + core + 自己的 store 实现`，完全不把内存实现打进产物。
   只有 `starter` 为了「开箱即用」才显式依赖内存实现，而它的所有 Bean 都是 `@ConditionalOnMissingBean`，
   用户注册自己的即可覆盖。
3. **零 Spring 内核**：`contract` / `store` / `core` 三个模块严禁引入 `org.springframework.*`。
   库存是业务内核，它应该能在单元测试里裸跑，不需要起 Spring 上下文。CI 会扫描源码强制这一点。

## 五、为什么时间必须可注入

「下单 30 分钟未支付自动释放」是库存系统里唯一依赖时间的业务规则。用 `Instant.now()` 写的话，
验证它只能靠 `Thread.sleep(31 * 60 * 1000)`——慢到没法进 CI；或者把 TTL 调成几百毫秒去赌线程调度——
在 CI 繁忙时随机失败。

所以 `Clock` 在构造期一次注入，并暴露 `clock()` 访问器。**构造期钉死而不是方法上默认取系统时钟**，
是因为时钟是引擎的全局事实来源：一半方法用注入时钟、一半用系统时钟，TTL 判定就会自相矛盾。

同理，自动装配把时钟做成一个 Bean：集成测试只要注册一个 `Clock.fixed`，整个引擎的时间观就被替换了。

## 六、可观测性的三条纪律

1. **观测路径永不抛异常。** 一次扣减要么成功要么失败，但「这次扣减能不能被记录下来」绝不能反过来决定
   「扣减能不能成功」。所有只负责留痕的代码都包在 `Observation.run(...)` 里，抛出的 `RuntimeException`
   一律吞掉并计数（计数而不是补打一条日志——补打走的正是刚刚失败的那条路径）。
2. **构造期拒绝 null，不把缺陷推迟到调用点。** 一个 null 混进 key 不会立刻报错，而是变成一条 key 为 null
   的水位，然后被别的 SKU 查出来。
3. **长生命周期集合必须有界，且淘汰要可观测。** 容量打满后的 LRU 淘汰是**静默的数据丢失**，
   必须可观测：既打 WARN（首次淘汰一条，之后每 10 000 条一条，避免热路径刷爆日志），
   也暴露 `evictedCount()` 让运维能一眼看出「容量够不够」。

---

# 已知局限

读一遍，否则会踩坑：

1. **防超卖仅在单 JVM 内成立。** 多实例部署时跨节点并发不受保护，需要把原子更新下沉到存储层。
2. **幂等不跨进程。** 换 Redis/DB 实现时必须用 `SETNX` / 唯一索引承接 put-if-absent 语义。
3. **多租户隔离不做鉴权。** `StockyardTenantFilter` 只解析请求头，不校验身份。
   能伪造 `X-Tenant-Id` 就能读到别家库存——生产必须在网关层鉴权，并把过滤器放在鉴权之后。
4. **内存存储的淘汰会降级业务能力**：预占单消失、库存归零、幂等失效。`evictedCount() > 0` 就是报警信号。
5. **`expireDue` 是全表扫描**（O(总预占单数)），百万级之后需要换成按到期时间排序的索引。
6. **HTTP 状态码映射偏粗**：只有 `OversellException` 映射成 409，其余领域异常冒泡成 500。

更完整的清单（含非安全类的行为缺口）见 [`CHANGELOG.md`](CHANGELOG.md) 的「已知遗留」。

---

## 构建与测试

```bash
mvn -B verify
```

- 测试：**360 个 `@Test`**（contract 166 / store 48 / core 87 / starter 52 / sample 7）
- 行覆盖率门禁：`stockyard-contract` / `stockyard-store` / `stockyard-core` 为 **0.75**，整体 0.60
- 格式化：spotless 2.44.4 + google-java-format (AOSP)，`verify` 时自动 apply

### 并发测试怎么证明不超卖

`StockyardEngineStripedLockConcurrencyTest` 里的每个场景都同时断言「不超卖 + 总数守恒 + 水位不为负」，
并且刻意覆盖两条路径：

- **撞槽**：挑两个哈希落到同一个锁槽的 key，它们必须互斥；
- **不撞槽**：64 个互不撞槽的 key 各占 1 件，全部并发预占必须全部成功——
  这条断言保护的是「分片确实提供了并行度」，而不是退化成一把全局锁。

只测不撞槽等于没测防超卖，只测撞槽会掩盖吞吐塌掉。两组都要。

## 架构约束

`stockyard-contract` / `stockyard-store` / `stockyard-core` 严禁引入任何 `org.springframework.*` 依赖；
CI 的 `architecture-boundaries` 任务会扫描其主源码，出现即失败。
`stockyard-store` 在 `stockyard-core` 中只能是 `test` scope。

## 参与贡献

见 [`CONTRIBUTING.md`](CONTRIBUTING.md)。安全问题见 [`SECURITY.md`](SECURITY.md)。

## 许可

Apache License 2.0，见 [LICENSE](LICENSE)。
