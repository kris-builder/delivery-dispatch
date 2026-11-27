# 配送草案验收

## 01、02 固定样例

在 `delivery/`、Java 21 环境中执行：

```powershell
./mvnw.cmd '-Dtest=DraftGeneratorTests,MixedDestinationDraftTests' test
java -cp 'target/classes;target/test-classes' org.example.delivery.planning.DraftPreview
```

浏览器打开命令输出的 `target/planning-0001-preview.html`。先看小陈在岗和请假的两组东区运单，再核对混合去向的分组和排除明细。

## 03 保存和重开

使用独立演示库，按项目 README 的启动方式配置 MySQL。建议设置独立 Compose 项目、库名和端口，例如 `delivery-planning03`、`delivery_planning03`、MySQL `3319`、Java `8093`。Java 以 `local,identity-demo,planning-demo` 启动。Vite 使用 `DELIVERY_API_PORT=8093` 和 `npm run dev -- --port 5193`。前者在 worktree 根目录的 `.env` 配置，也可在启动 Vite 的终端设置。

从员工入口分别用 `dispatcher-zhou` 和 `operator-xu` 登录并完成首次改密。然后在仓库根目录执行：

```powershell
./scripts/seed-planning-draft-demo.ps1 -ApiPort 8093 -Dispatcher (Get-Credential dispatcher-zhou) -Operator (Get-Credential operator-xu)
```

脚本用已有入站入口导入同批 3 票，操作员实收其中 2 票。回到浏览器用 `dispatcher-zhou` 登录同一前端地址，打开 `http://127.0.0.1:5193/planning-draft.html`。选 `PLANNING-03-DEMO` 和北京时间 08:00 上午班保存，预期总数 2、已拟派 1、待安排 1。记下草案编号，刷新页面后从「重新打开」查询。核对版本 0、创建账号、北京时间和相同逐票结果。

03 的写入入口只在 `planning-demo` 模式开放，样例已确认约定由服务端固定提供，实际实收、班次和片区均来自 MySQL。正式履约约定和备货查询归 07，应用主页面接线另行协调。

在 `delivery/` 用真实 MySQL Testcontainers 运行保存、拒绝、回滚及迁移回归：

```powershell
./mvnw.cmd '-Dtest=DraftPlanHttpTests,InboundMigrationTests,DraftGeneratorTests,MixedDestinationDraftTests' test
```
