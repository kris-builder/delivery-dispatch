# 快递网点配送运营系统

系统帮派件网点收货、按片区直派或批量分拨给下属驿站。驿站扫码入库、核对漏件，按需安排本站配送员上门并记录实际完成计件。收件人登录后可查本人运单并向 AI 智能客服提问。 [开工技术基线](docs/ARCHITECTURE.md#技术栈)

## 从这里开始

| 你想做什么 | 打开哪份 |
| --- | --- |
| 准备开工、分派 Agent 或做前后端联调 | [从开工到 MVP 的路线图](docs/ROADMAP.md)。从基线和网点与运力的最小查询开始。 |
| 看当前要审什么、后续怎么拆任务 | [模块设计](docs/MODULES.md)。先核对实体、模块接口和实施依赖。 |
| 了解给谁用、解决什么、各阶段做什么 | [项目手册](docs/PROJECT.md)。 |
| 查技术栈、部署、事务和技术取舍 | [架构设计](docs/ARCHITECTURE.md)。 |
| 查 ER 图、字段、索引和写入约束 | [数据库设计](docs/DATABASE.md)。 |
| 查 API、登录权限、异常日志和测试要求 | [工程规范](docs/ENGINEERING.md)。 |
| 查各模块的任务入口 | [模块规格索引](docs/specs/README.md)，实施顺序见路线图。 |
| 了解 AI 在这个项目里怎样开工和交付 | [AGENTS.md](AGENTS.md)，供 AI 每轮开工时读取。 |

## 本地开工冒烟

需要 Java 21、可运行的 Docker Compose 和支持 Vite 的 Node.js，推荐 Node 24 LTS。本机若已将便携工具放进 `.archive/toolchain`，先在各终端从根目录运行 `./scripts/use-local-tools.ps1`，只作用于当前终端。首次从项目根目录复制 `.env.example` 为 `.env`，用本地随机密码替换两处密码占位符。同一实例保留原密码，`.env` 随本机环境保存。

```powershell
docker compose up -d --wait mysql
Set-Location delivery
.\mvnw.cmd '-Dspring-boot.run.profiles=local' spring-boot:run
```

Java 启动时由 Flyway 执行组织表迁移。默认在 `http://127.0.0.1:8080/api/v1/system/health` 查看服务及数据源状态。另开终端，从项目根目录执行以下命令，把 [统一合成样例](fixtures/mvp.json) 中的网点加载进数据库，再启动前端。

```powershell
.\scripts\seed-demo-org.ps1
Set-Location web
npm ci
npm run dev
```

打开 `http://127.0.0.1:5173` 进入员工登录。种子脚本只装载一个样例网点，可重复执行。后端冒烟在 `delivery/` 执行 `./mvnw.cmd test`，前端构建在 `web/` 执行 `npm run build`。

### 实测员工查询权限

使用独立演示库。在工作树根目录的 `.env` 为 MySQL 与 Java 选用空闲端口，为 `MYSQL_DATABASE` 配置独立库名，并设置 `DELIVERY_DEMO_TEMPORARY_PASSWORD` 为自己选定的演示临时密码。用独立 Compose 项目启动 MySQL。Java 同时启用 `local` 和 `identity-demo`，首次启动会装载两个网点、两个驿站、上午班和合成员工账号。再次启动会保留已修改的密码和已存在的样例记录。

```powershell
docker compose -p delivery-identity-demo up -d --wait mysql
Set-Location delivery
.\mvnw.cmd '-Dspring-boot.run.profiles=local,identity-demo' spring-boot:run
```

另开终端，在 `web/` 运行 `npm ci` 和 `npm run dev -- --port 5176`。打开 `http://127.0.0.1:5176`，用 `dispatcher-zhou` 和所设的临时密码登录，按页面要求修改密码。页面默认查北京时间 2026 年 11 月 25 日 08:00，预期在岗为陈立、刘洋。把查询时间改为同日北京时间 `12:00`，点「查当班」，预期为 0 人。

退出后用 `operator-xu` 登录，完成首次改密。页面应能看到江岸的东、西片区。在浏览器同一会话打开 `http://127.0.0.1:5176/api/v1/staff/workers?at=2026-11-25T00:00:00Z`，预期 HTTP `403`，错误码为 `FORBIDDEN`，响应没有在岗名单。同一地址在 `dispatcher-zhou` 会话中返回 HTTP `200` 和两人名单。对照网点的调度账号 `dispatcher-other` 查同一时间只返回王平。甲、乙驿站站长分别用 `station-leader-a`、`station-leader-b` 登录。

## 你和 AI 怎么分工

1. **拆任务前**，你和 AI 先审模块设计，明确实体归属、模块接口和前置能力，再按顺序定每项的 Spec 和 Ticket。开工时核对当前任务和 Git 基线，再按协作规则开 worktree。
2. **实现时**，AI 先跑出预期的失败，再改代码并重跑。你审一个关键点，例如同一票件并发扫码后为什么只增加一件实收。
3. **验收时**，你亲自改一个业务条件再复跑，例如给一票新件使用未配置的小区，核对它是否计入实收并进入待分区清单。AI 把验证证据写回对应任务单。

换一个对话时，在项目根目录打开新对话，可以说「按 AGENTS.md 和 README 接着审当前模块，核对它的接口和前置依赖」。
