# stockyard

库存与预占套件：用一套不可变的值对象与状态机，把「库存水位、可预占/释放、带 TTL 的预占、幂等扣减、防超卖」这几件事讲清楚。核心库零 Spring 依赖，Spring Boot 侧只做自动装配。

## 模块

| 模块 | 说明 |
| --- | --- |
| `stockyard-core` | 纯 Java（零 Spring）核心库：库存水位、预占状态机、幂等扣减、防超卖异常。 |
| `stockyard-starter` | Spring Boot 自动装配：注册存储与引擎 Bean，按 `X-Tenant-Id` 解析租户，提供 REST 端点。 |
| `stockyard-sample` | 示例应用：演示预占—确认—扣减、预占超时释放、幂等扣减防超卖。 |

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
Reservation r = engine.reserve("R1", tenant, sku, wh, 10, null); // 幂等：同 id 重复调用返回首次结果
engine.confirm(r.id(), Instant.now());                            // 扣减库存
DeductionRecord d = engine.deduct("D1", tenant, sku, wh, 5, Instant.now()); // 幂等，重复调用同结果
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

## 构建与测试

```bash
mvn -B verify
```

`stockyard-core` 行覆盖率门禁为 0.75，整体为 0.60（由 CI 在 Linux 上校验）。

## 架构约束

`stockyard-core` 严禁引入任何 `org.springframework.*` 依赖；CI 的 `architecture-boundaries` 任务会扫描其主源码，出现即失败。
