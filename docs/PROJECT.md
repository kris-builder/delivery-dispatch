# 项目手册

## 项目解决什么问题

本项目服务一个派件网点和它管理的下属驿站。网点收货后，按已确认的收件约定分成网点直派与驿站分拨。驿站接收实物后负责自取和改上门服务。收件人可查包裹进度、实际去向和后续联系入口。 [模块设计](MODULES.md) · [模块规格](specs/README.md)

驿站有独立库存、站长和配送员。智能快递柜与商铺仍可作为经确认的外部自取点，按各自的真实交接凭证查件。 [分流与交接](MODULES.md#上门派送与到点自取)

## 谁在用

| 参与方 | 需要解决的问题 |
| --- | --- |
| 网点操作员 | 扫码实收、按去向备货、确认实际分拨出库，核对网点库存和发往驿站的差异。 |
| 网点调度 | 维护网点片区与运力，发布网点直派清单，按批次把合适的件分拨给下属驿站。 |
| 网点配送员 | 从本网点扫码领取直派件，办理上门投递、交约定的快递柜或商铺，以及装车回库和异常返站。 |
| 网点转运司机 | 按调拨单从网点接手整批货，送到指定驿站，当场共同核对正常接收件和异常件。 |
| 驿站分拣员 | 处理本站已签收件的上架、备货和异常反馈，与网点操作员共用分拣工作台。 |
| 驿站站长 | 按调拨清单扫码收货、盘点漏件、办理自取，并把收件人改上门的任务分给本驿站配送员。 |
| 驿站配送员 | 从所属驿站领取已分给本人的上门任务，记录实际投递，能查本人的已完成服务和计件记录。 |
| 收件人 | 验证手机号后查看本人快件，也可通过运单号添加关注。能查当前进度、预计时段和实际投递去向，并在该票件下向智能客服提问。需要核实未收到或希望重新安排时，可联系网点员工。 |

## 业务流程

1. **核对上游应到与实收。** 上游提供入站批次和应到明细，网点扫码确认实到实物。收货时发现破损或无可辨认面单，登记异常并隔离；无单号用临时异常编号。完成当次核对后，同批应到、实见、异常暂存及未收到的件都可查。
2. **确认分流。** 约定上门、投柜或交普通商铺代收点的件，进入网点配送员的直派安排。约定下属驿站自取的件走分拨。地址、去向或收件约定无法确定时先保留实收，由人员核对。
3. **网点交件、驿站当场验收。** 调度发布分拨并指派网点转运司机，网点与司机确认实发和交出时的状态。到驿站后，司机和驿站站长当场核对。正常件核对后一次批量签收，缺件、破损及错件逐票登记为差异，驿站只对实际签收的正常件入库。
4. **驿站自取或上门。** 实物在驿站备好后可通知领取。收件人电话要求改上门时，驿站站长确认运单、地址及实物，再交给本驿站配送员。本项目首版提供两次免费上门机会，成功交付后按实际完成者记一笔驿站计件。
5. **登记投递和异常。** 每次实际投递及失败原因单独登记。两次免费投递未妥投后，网点直派件可按已获同意的约定改投驿站或快递柜，缺少同意时交回网点跟进。驿站上门件由驿站实物收回，联系上后安排自取，或先告知并协商费用再续派上门。 [投递异常规则](MODULES.md#联系不上收件人的异常件)

## 核心业务规则

- **片区与人员。** 配送片区是地址的地理归属，交接区是单位内的备货位置。网点调度只安排本网点运力与下属驿站分拨，驿站站长只安排本驿站配送员。两类清单和实际保管人各自可查。
- **入站与查件。** 运单号在派件网点内唯一，分拨到驿站沿用原运单和包裹标识。重复扫码仍是同一件。总部或承运商发往网点的应到差异以可信上游清单为前提。网点向驿站分拨的差异按本项目内的分拨单和实际发出核对。
- **异常来源与跟进。** 网点首次收货发现的问题先记在上游交接环节，由网点提交上游核查。网点完好交出后在驿站验收发现的问题先记在网点至驿站转运交接环节，由网点跟进。实际保管人与异常归口分别记录。
- **异常件放行。** 缺单号由网点调度联系上游核对后补齐并放行。网点发现外包装破损时，由操作员拍照并联系收件人，按其选择退回理赔或带着异常标记继续送，交付时当面验收。已确认内件破损的，通知收件人并办理退回理赔。 [异常处置](MODULES.md#入站及转运验收异常)
- **缺失件处理。** 网点、驿站、司机和配送员按职责查看异常。下落不明到期进入遗失待核，确认后由责任方发起赔付申请。期限内找回的实物按原运单扫码恢复库存。 [异常归属、遗失与找回](MODULES.md#异常归属遗失与找回)
- **计划与实物。** 草案记录候领清单，发布后同一包裹最多出现在一份生效清单。首笔领件成功时建立本趟，配送员扫完可直接发车。装不下的件由配送员放回交接区并复扫，恢复「未出库」，从本趟实领扣除，留给后续趟次正常领件。现场清点与台账有差异时进入人工复核。
- **调整与交接。** 尚未扫码领件时，网点调度可直接变更现行授权。已扫码领走的件先按状态核对，再由调度确认转交方案并记录实际交接。记录改派人、原因、前后人员和时间。实际保管随扫码交接改变。
- **上门与自取。** 已约定在本站自取的件，由员工确认已备好后进入待领取。需要送到收件地址或外部自取点的件，按趟扫码领取并交接。到点可取、实际领取和上门收件分别记录。投柜或交外部代收点按已确认的约定办理，改点要核对收件人同意的范围。 [《快递市场管理办法》第二十八条](https://sc.spb.gov.cn/scsyzglj/c100065/c100066/202401/89f3fa88e3b44702b108e1e20cae974c.shtml)

## 交付顺序

1. **网点与运力。** 能查片区的常规责任和指定时点在岗的配送员。 [网点与运力 Spec](specs/station/0001-station-areas-and-capacity.md)
2. **到站与查件。** 用合成上游应到清单验扫码、自动分区、实收对账、破损和无单号异常。 [包裹履约 Spec](specs/fulfillment/0001-inbound-scan-and-area-matching.md)
3. **账号权限与网点直派。** 按单位校验员工身份，网点调度生成并发布直派清单，网点配送员扫码接手。 [账号权限](specs/identity/0001-station-accounts-and-access.md) · [配送计划](specs/planning/0001-area-pickup-plan.md)
4. **分拨与驿站入库。** 按逐票应收清单核对实际发出及收到的件。 [驿站交接 Spec](specs/pickup-station/0001-transfer-and-receiving.md)
5. **驿站自取与上门服务。** 完成领取或将上门任务交给本驿站配送员，送达后核对计件。 [驿站上门 Spec](specs/pickup-station/0002-home-delivery-service.md)
6. **收件人服务与估时。** 包裹详情、客服、最近位置与预计时段复用分拨和两类配送的履约结果。 [模块规格索引](specs/README.md)

端到端演示用一票网点直派件和一个驿站分拨批次。先验证直派、分拨和发出未收，再让一票已入驿站的件由收件人电话改上门，核对任务归属、实际签收及一次计件。返站暂存、再投和退回继续作为各流程的异常验收。 [履约设计](MODULES.md#履约状态与计划发布)

## 参考依据

- 从 Fleet-Ops 借鉴任务、司机、配送清单、执行结果和送达凭证的工作流。 [Fleet-Ops 核心概念](https://fleetbase.io/docs/fleet-ops/getting-started/core-concepts)
- 从 Onfleet 借鉴班次、预约时段、趟次限制和批次调度规则。 [Onfleet 批次调度](https://docs.onfleet.com/reference/team-auto-dispatch)
- 路线优化可参考 jsprit 的容量和时间窗约束示例，用合成运单、班次和可复放的耗时数据做对比。 [jsprit 约束](https://github.com/graphhopper/jsprit/blob/master/docs/Walkthrough%20%20%20Constraints.md)
- ETA 使用公开历史数据做留出评估，仓库保留数据来源、处理方式和可公开样例。出站估时和途中更新各按对应的预测时点核对可用输入。 [菜鸟 LaDe-D](https://huggingface.co/datasets/Cainiao-AI/LaDe-D)

### 实现时回查的源码

本机检出位于 `E:\reference`，链接固定到本次阅读的提交。实现对应模块时打开相关入口。

- **配送进度与在途查件。** Fleet-Ops 保存配送员位置，把部分实时更新交给后台并限制算路频率。本网点按运单查件时，先从交接记录找实际配送员，筛掉已处理的停靠点，距离累加到目标站。只在请求剩余路程或预计时段时算路。 [配送状态入口](https://github.com/fleetbase/fleetops/blob/f91a197586b9e41c179f3cf7f730d0fa7f1c256a/server/src/Http/Controllers/Api/v1/OrderController.php#L1361) · [定位上报](https://github.com/fleetbase/fleetops/blob/f91a197586b9e41c179f3cf7f730d0fa7f1c256a/server/src/Http/Controllers/Api/v1/DriverController.php#L321) · [剩余站点](https://github.com/fleetbase/fleetops/blob/f91a197586b9e41c179f3cf7f730d0fa7f1c256a/server/src/Tracking/TrackingContextBuilder.php#L59) · [后台更新](https://github.com/fleetbase/fleetops/blob/f91a197586b9e41c179f3cf7f730d0fa7f1c256a/server/src/Jobs/PublishTrackingUpdate.php#L33)
- **批量排线。** jsprit 用停靠点间的耗时和距离矩阵求解，容量和时间窗有可复用的测试样例。若后续做路线优化，可用同一矩阵对照规则基线，核对预约时窗和每趟容量。求解结果能列出未安排任务，未安排的业务原因由本网点规则给出。 [容量测试](https://github.com/graphhopper/jsprit/blob/da13281b178f0c3c128763a718d87674db53fd6a/jsprit-core/src/test/java/com/graphhopper/jsprit/core/algorithm/CapacityConstraint_IT.java#L39) · [时间窗与矩阵测试](https://github.com/graphhopper/jsprit/blob/da13281b178f0c3c128763a718d87674db53fd6a/jsprit-core/src/test/java/com/graphhopper/jsprit/core/algorithm/MeetTimeWindowConstraint_IT.java#L268) · [未安排任务](https://github.com/graphhopper/jsprit/blob/da13281b178f0c3c128763a718d87674db53fd6a/jsprit-core/src/main/java/com/graphhopper/jsprit/core/problem/solution/VehicleRoutingProblemSolution.java#L118)
- **任务接口与运营事件。** `pyonfleet` 封装路线计划、批量任务和班次 API。`onfleet-developer` 演示导入预约起止时间，以及接收任务完成和失败的回调。两份源码用于参考请求和事件的边界。 [SDK](https://github.com/onfleet/pyonfleet/blob/c8ad6733c89b3940c22690b2a32d0232b042a3f7/onfleet/onfleet.py#L49) · [导入示例](https://github.com/onfleet/developer/blob/924a3ba3320c326bb5e4c60934518e1086e73ddb/api-tools/import-tasks/task-import.py#L69) · [完成与失败事件](https://github.com/onfleet/developer/blob/924a3ba3320c326bb5e4c60934518e1086e73ddb/api-tools/webhooks/python/testwebhooks.py#L44)
- **估时离线验证。** LaDe 用历史配送事件构造剩余任务和实际耗时标签。其速度基线评测直接读取实际投递顺序。我们的估时评测使用预测时已经知道的配送顺序和位置，按时间切分后，用同一留出集比较历史中位数和模型的误差及覆盖率。 [派件样本构造](https://github.com/wenhaomin/LaDe/blob/4d64660aa0c49c7dc9cf627411743faac7753e6f/time_prediction/data/dataset_delivery.py#L159) · [速度基线](https://github.com/wenhaomin/LaDe/blob/4d64660aa0c49c7dc9cf627411743faac7753e6f/time_prediction/algorithm/speed/speed.py#L79)
