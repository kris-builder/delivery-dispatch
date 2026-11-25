# 模块规格

Spec 定义一个可验收的业务结果。编号在模块内递增，引用时带模块名，例如 `station/0001`。

| 模块或操作入口 | 当前入口 | 接续条件 |
| --- | --- | --- |
| 网点与运力 `station` | [0001 片区责任与当班人员](station/0001-station-areas-and-capacity.md) | 可独立验收片区、班次和当前在岗人员。 |
| 包裹履约 `fulfillment` | [0001 扫码入站、自动分区与查件](fulfillment/0001-inbound-scan-and-area-matching.md) · [0002 异常跟进、遗失与找回](fulfillment/0002-exception-ownership-and-recovery.md) | 验实收与分区，再验异常归属、按期限处理、赔付申请与扫码找回。 |
| 配送计划 `planning` | [0001 按片区生成和调整领件草案](planning/0001-area-pickup-plan.md) | 使用当班人员和已到站待安排件，可先用固定样例验草案。 |
| 账号与身份 `identity` | [0001 网点与驿站员工权限](identity/0001-station-accounts-and-access.md) · [0002 收件人账号与登录](identity/0002-recipient-accounts-and-access.md) | 验网点调度、驿站站长与两类配送员的所属单位和权限隔离。 |
| 下属驿站 `pickup-station` | [0001 分拨交接与收货对账](pickup-station/0001-transfer-and-receiving.md) · [0002 上门、失败续派与计件](pickup-station/0002-home-delivery-service.md) | 先验司机现场交接、驿站批签和未签收差异，再验本站上门、失败处理和计件。 |
| 转运司机工作台 `transfer-driver` | [0001 网点转运司机工作台](transfer-driver/0001-station-transfer-workbench.md) | 使用驿站分拨批次和履约交接，验司机接手、现场核对和异常回交。 |
| 配送员工作台 `courier` | [0001 按趟领件与配送工作台](courier/0001-trip-delivery-workbench.md) | 先用固定数据验页面，联调依赖配送员登录、已发布清单和履约交接操作。 |
| 收件人服务 `tracking` | [0001 收件人查件与通知演示](tracking/0001-recipient-parcel-tracking.md) · [0002 收件人 AI 智能客服](tracking/0002-recipient-ai-support.md) | 查件依赖收件人登录与履约记录。客服复用逐票授权与可见详情，用模拟模型和合成联系方式验收。 |
| 在途查件 `presence` | [0001 在途位置与剩余路程](presence/0001-trip-location-and-remaining-route.md) | 依赖实际趟次与配送员确认的停靠顺序。用真实 Redis 和高德模拟接口核对位置、剩余站数与道路耗时。 |
| 预计送达 `eta` | [0001 在途预计送达时段](eta/0001-in-transit-delivery-estimate.md) | 依赖在途路线和实际履约结果，先用固定数据验 FastAPI 计算，再验证网点与收件人的展示。 |

业务主线先核对[网点直派与分拨](../MODULES.md#上门派送与到点自取)，再跑通下属驿站收货、上门和异常处理。[履约状态与计划发布](../MODULES.md#履约状态与计划发布)提供两类配送共用的领件、退回与异常规则。 [实施依赖](../MODULES.md#拆-spec-和-ticket-的顺序)

并行工作时，先对齐共享输入和输出。独立模块可用固定数据过自己的业务测试，联调再换成实际模块接口。有基线提交后，各任务按协作规则使用独立 worktree、端口和测试库。
