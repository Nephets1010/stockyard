# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号暂为 `0.1.0-SNAPSHOT`（未发版）。

## [Unreleased]

### Added — 模块拆分

- 模块从 3 个拆为 5 个，依赖方向单向无环：
  `stockyard-contract` ← `stockyard-store` ← `stockyard-core` ← `stockyard-starter` ← `stockyard-sample`
  - `stockyard-contract`：纯类型层。库存水位、SKU/仓库/租户值对象、预占状态机与不可变 `Reservation`、
    幂等扣减记录，以及 `ReservationStore` / `StockPositionStore` / `DeductionStore` 三个存储接口。
  - `stockyard-store`：`InMemory*` 三个有界 LRU 存储实现。
  - `stockyard-core`：`StockyardEngine`（分片锁 / 状态机 / 幂等扣减）。
  - `stockyard-starter`：Spring Boot 自动装配。
  - `stockyard-sample`：示例应用。
- **所有包名与全限定类名保持不变**（仍是 `io.github.nephets.stockyard.core.*`），只移动物理目录与 pom 依赖，
  外部使用者的 `import` 一行都不用改。
- 新增 `support/Observation`：观测路径（打日志 / 采集指标 / 记账）的统一守卫，落实「观测路径永不抛异常」。

### Added — 时间可注入

- `StockyardEngine` 新增构造期 `java.time.Clock` 注入，并暴露 `clock()` 访问器。
- 新增无时间戳重载：`confirm(String)`、`cancel(String, String)`、`expireDue()`、`deduct(..., long)`，
  时间一律取注入时钟。
- `Reservation.pending(...)`、`DeductionRecord.success(...)`、`DeductionRecord.failed(...)` 新增带 `Clock` 的
  重载；原有重载保留并委托到 `Clock.systemUTC()`，行为不变。
- 自动装配新增默认 `Clock` Bean；用户注册任意 `Clock` Bean 即可整体替换（集成测试可冻结时间）。

### Fixed — 并发正确性（重要）

下面三处此前存在「判定在锁外、动作在锁内」的 check-then-act 竞态，本次全部收敛进同一个临界区：

1. `reserve`：幂等查重原先在锁外，两个线程携带同一个 `reservationId` 并发提交会各自判定为「首次」，
   结果是库存被占 2 份却只有 1 条预占单记得住——那 1 份永远释放不掉（库存泄漏）。
2. `confirm` / `cancel` / `expireDue`：状态判定原先在锁外，两个线程同时确认同一条预占会各扣一次
   （库存凭空少一份），同时取消会各释放一次（凭空造货）。
3. `deduct`：幂等查重与记账原先在锁外，同一 `requestId` 并发首次提交会被扣两次（资损）。

### Fixed — 确认扣减的判定基准

- `StockPosition.deduct(qty)` 的越界判据从 `qty > available()` 改为 `qty > allocated()`。
  旧判据会误杀合法操作：`onHand=100 / allocated=80` 时确认那笔 80 件的预占，会因为「可用只有 20」
  而被拒；而它扣的正是自己当初占住的 80 件。现在只在「确认量超过当初预占量」时拒绝。
  - **行为差异（请注意）**：`qty` 落在 `allocated < qty <= onHand - allocated` 区间的调用，
    此前会被接受（并产生负的 `allocated`），现在抛 `OversellException`。这在业务上是修正而非回归：
    这个区间本身就是「确认了不属于自己的预占量」。

### Added — 测试

- `@Test` 数 92 → **360**（`stockyard-contract` 166 / `stockyard-store` 48 / `stockyard-core` 87 /
  `stockyard-starter` 52 / `stockyard-sample` 7）。
- 新增的并发证明见 `StockyardEngineStripedLockConcurrencyTest`：并发预占不超卖 + 总数守恒 +
  分片撞槽与不撞槽两条路径 + 并发确认/取消/幂等扣减各只生效一次。
- 新增 `StockyardEngineLockStripeDistributionTest`：分片函数的确定性、有界性与分布均匀性。
- 新增 `StockyardEngineClockTest`：用可手动推进的时钟替代 `Thread.sleep`，TTL 回收变成确定性断言。
- 新增 `ObservationTest`：钉住「观测代码抛异常不影响业务结果」这条纪律。

### Added — 文档

- 新增 `CHANGELOG.md`、`CONTRIBUTING.md`、`SECURITY.md`、`README.zh-CN.md`。
- `README.md` 与 `README.zh-CN.md` 均新增「设计决策」章节。

## 已知遗留（本次刻意不改，留待破坏性变更时处理）

这些是已经识别、但修正需要改公开契约或公开行为的问题，因此**只记录不实施**：

1. **`StockPosition.consume(qty)` 的判据是 `onHand` 而不是 `onHand - allocated`。**
   直接对水位调用 `consume` 传入 `allocated < qty <= onHand` 的值，会把可用量算成负数。
   引擎的 `deduct` 在调用 `consume` 之前已用 `available()` 前置检查兜住，因此走引擎不会触发；
   只有直接使用值对象才会遇到。修它需要改判据语义，属于行为变更。

2. **防超卖仅在单 JVM 内成立。**
   `StockyardEngine` 的分片锁是 JVM 内的 `synchronized`。多实例部署时，跨节点的并发预占不受保护。
   正确做法是把「读—算—写」下沉成存储层的一次原子操作（Redis Lua / `UPDATE ... WHERE on_hand - allocated >= ?`），
   而不是把这里的锁换成分布式锁——后者会把一次库存操作变成「网络往返 + 锁租约」，吞吐掉一个数量级。

3. **幂等保证不跨进程。**
   同 `requestId` 的「只扣一次」依赖本进程的分片锁与 `DeductionStore`。换成 Redis/DB 实现时，
   必须用 `SETNX` / 唯一索引承接 put-if-absent 语义，否则两个节点会各扣一次。

4. **`InMemory*` 存储的淘汰会降级业务能力。**
   容量打满后：预占单被丢表现为「从来没存在过」，水位被丢表现为「库存凭空归零」，幂等记录被丢表现为
   「同一笔业务被扣两次」。三者都通过 `evictedCount()` 暴露，**生产必须把它接入监控**。

5. **`expireDue` 是全表扫描。**
   它遍历 `reservationStore.findAll()`，复杂度 O(总预占单数)。预占单量级上到百万后需要换成
   「按到期时间排序的索引 + 只取头部」。

6. **HTTP 状态码映射偏粗。**
   领域异常（预占缺失 / 冲突 / 非法状态流转）目前都冒泡成 500，只有 `OversellException` 映射成 409。
   细化成 404 / 409 / 422 是对外协议变更，需要配合版本号一起发。

7. **`Reservation.pending` 的 `ttlSeconds` 不做上限校验。**
   传入极大的值（例如 `Long.MAX_VALUE`）会让 `expiresAt` 计算抛出 `DateTimeException`，
   而这个异常不是 `StockyardException` 的子类，调用方按领域异常捕获时会漏掉。
   目前没有把「非法 TTL」翻译成领域异常，属于契约缺口。
