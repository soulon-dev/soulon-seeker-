# 标准化开发指南（覆盖完整 SDLC）

适用范围：本仓库（Android App + Cloudflare Workers 后端）。目标：把“拉取代码 → 开发 → 测试 → 预上线 → 上线 → 回滚/复盘”流程标准化，确保环境隔离、质量门禁、可追溯与可审计。

---

## 1. 角色与职责

- 开发（Dev）：只可访问 test/staging 环境；负责实现、单元测试、集成测试与自测报告。
- 测试（QA）：负责测试计划、用例执行、验收记录与质量签字。
- 运维/发布负责人（Ops/Release）：唯一可触发生产发布/回滚；负责变更窗口、发布审批、监控与应急响应。
- 代码所有者（Code Owners）：对关键文件/工作流/环境配置拥有最终审阅权（参见 `.github/CODEOWNERS`）。

---

## 2. 环境与资源隔离要求（强制）

### 2.1 环境划分

- test：开发联调与日常验证
- staging：预发布/验收环境（UAT）
- production：正式线上

### 2.2 强制隔离项

每个环境必须独立、不得复用：

- 数据库（D1）
- KV（会话/配置/缓存等）
- R2（对象存储，如启用）
- Secrets（API Key、JWT Secret、加密密钥等）
- 域名/路由（test/staging 不得覆盖生产域名）

### 2.3 生产保护（切换前不影响现网）

- 在未完成验收与批准切换前，任何部署不得绑定现网生产域名与现网生产资源。
- 生产相关变更必须走受保护 PR + 生产环境审批（GitHub Environments）。

---

## 3. 版本号与发布节奏

### 3.1 版本号规范（统一口径）

- 测试版：`X.Y.Z-SNAPSHOT.<run>`（示例：`1.2.3-SNAPSHOT.120`）
- 预发布：`X.Y.Z-rc.<run>`（示例：`1.2.3-rc.5`）
- 正式版：`X.Y.Z`（SemVer）

### 3.2 可追溯要求

- 每次部署必须可追溯到：commit SHA、构建号、版本号。
- 后端 `/health`（或等价端点）必须返回：`APP_VERSION`、`GIT_SHA`、`environment`。

---

## 4. 代码管理策略（Git Flow 简化版）

### 4.1 分支定义

- `develop`：test 环境（日常开发合并入口）
- `release/*`：staging 环境（预发布验收分支）
- `master`：production 环境（唯一生产发布入口）
- `hotfix/*`：从 `master` 切出，紧急修复；合并回 `master` 后必须同步回 `develop`（受控例外）

### 4.2 单向合并规则（强制）

- `develop` → `release/*` → `master` 为主链路
- 禁止任何分支直接覆盖生产（除 `master`）
- 禁止从生产回流到测试（除 hotfix 同步回 `develop` 的受控路径）

### 4.3 提交与 PR 规范

- 必须通过 PR 合并，禁止直接 push 到 `master/develop`。
- PR 必须包含：
  - 变更说明与影响范围
  - 测试证据（日志/截图/报告链接）
  - 风险评估与回滚方案
- 生产相关文件变更必须 Code Owners（Ops）审阅通过。
- 建议提交格式：`<type>: <summary>`，type 可选 `feat|fix|refactor|docs|chore|release`。

---

## 5. 本地开发流程

### 5.1 拉取与初始化

1) 克隆仓库
2) 安装依赖（Android + Backend）
3) 复制本地配置样例并填写（不得提交到仓库）

### 5.2 Android（本地）

环境准备：

- JDK 17
- Android SDK / Android Studio
- `local.properties`（路径等）
- 可选 Firebase：`app/google-services.json`（本地用；禁止提交）

建议构建目标：

- dev 调试：`assembleDevDebug`
- 生产构建：`assembleProdRelease`

签名与密钥：

- release keystore 与口令必须通过本地/CI 的安全变量提供（不得写入仓库文件）。
- 如需在 CI 出包，使用 GitHub Secrets / 环境变量注入。

### 5.3 Backend（本地）

环境准备：

- Node.js 20+
- 安装依赖并运行单测
- 本地开发使用测试环境变量/Secrets（不得使用生产密钥）

Cloudflare Workers：

- 本地开发使用 `wrangler dev`（对应 test/dev 的资源绑定）
- 环境配置参见 `backend/wrangler.toml` 的 `env.test/env.staging/env.production`

---

## 6. 技术规范（最小但强制）

### 6.1 安全基线

- 禁止提交：API Key、私钥、签名口令、JWT Secret、加密密钥、生产数据库/存储 ID、访问令牌。
- 所有 Secrets 只能通过平台 Secret 管理注入（GitHub/Cloudflare）。
- 日志中不得打印：用户明文隐私、密钥、完整 Token、个人身份信息。

### 6.2 依赖与变更控制

- 新增依赖必须说明用途、替代方案与安全影响。
- 生产相关配置（部署工作流、wrangler、路由）变更必须标注风险并提供回滚步骤。

---

## 7. 测试策略与质量门禁

### 7.1 测试分层

- 单元测试（Unit）：业务逻辑/工具方法，必须覆盖关键分支与异常路径。
- 集成测试（Integration）：API 路由、鉴权、数据库读写、缓存交互。
- 端到端验证（E2E/Smoke）：关键用户路径的最小验证（上线前必做）。

### 7.2 覆盖率指标（门禁建议）

可按模块逐步推进，最低门禁建议：

- Backend：行覆盖率 ≥ 70%，关键模块（鉴权/支付/写入路径）≥ 85%
- Android：核心业务模块单测覆盖率 ≥ 60%，关键流程（登录/上传/支付）必须有自动化或可复验脚本

说明：如果当前测试框架暂未输出覆盖率报告，先以“关键模块必须有测试 + CI 必须跑过”为门禁，覆盖率门禁作为下一阶段改进项。

### 7.3 CI 必须通过（强制）

- PR 必须通过 CI（backend tests + Android build）。
- 任何合并到 `master` 前必须完成：
  - staging 验收记录
  - 回滚预案确认
  - 生产审批通过

---

## 8. 部署流程（从开发到上线）

### 8.1 测试环境（test）

- 触发条件：合并/推送到 `develop`
- 自动动作：
  - 运行测试
  - 部署到 test
  - 写入可追溯信息（版本号/commit SHA）

交付物：

- Test Build 版本号
- 自测清单与结果

### 8.2 预发布环境（staging）

- 触发条件：推送到 `release/*`
- 自动动作：
  - 运行测试
  - 部署到 staging
  - 写入可追溯信息（rc 版本号/commit SHA）

验收要求（门禁）：

- QA 完成测试计划与验收记录
- 关键路径 smoke 测试通过
- 性能与安全检查（按本次变更范围）通过或有可接受豁免记录

交付物：

- Release Candidate 版本号
- 测试报告（含未解决问题列表与风险评估）

### 8.3 正式上线（production）

- 触发条件：合并/推送到 `master`
- 强制要求：
  - 只能由发布负责人触发（环境审批）
  - 必须使用 production 专用 token/secrets
  - 默认不得变更现网生产域名绑定；如需切换路由必须单独审批

交付物：

- Release Notes
- 生产变更记录（含回滚步骤）
- 上线验收（监控指标稳定窗口）

---

## 9. 部署 Checklist（上线前必填）

### 9.1 通用

- [ ] 变更范围与影响域明确（功能/数据/权限/成本）
- [ ] 对应分支策略正确（develop/release/master）
- [ ] CI 全绿（含测试与构建）
- [ ] Secrets 未提交，且生产 Secrets 未用于 test/staging
- [ ] 数据库迁移脚本已评审（如有），并验证可回滚
- [ ] 监控/告警项已确认（可观测性可用）

### 9.2 后端（Workers）

- [ ] 资源隔离确认（D1/KV/R2/Secrets）
- [ ] 路由确认（test/staging 不覆盖生产；生产切换独立审批）
- [ ] `/health` 可正确返回版本/commit/environment
- [ ] 关键接口 smoke 验证通过（鉴权/写入/支付或核心路径）

### 9.3 Android

- [ ] 构建目标正确（dev/staging/prod）
- [ ] Base URL 指向正确环境
- [ ] Firebase 配置（如需要）通过安全渠道提供且未入库
- [ ] 发布渠道与版本号符合规范

---

## 10. 回滚机制与应急响应

### 10.1 回滚策略

- 后端：使用“回滚工作流”按指定 git ref（tag/commit）重新部署 production。
- Android：按发布渠道策略执行撤回/停止分发/回滚到上一版本。

### 10.2 触发条件（建议）

- 可用性显著下降（错误率、超时、关键路径失败）
- 数据污染/安全事故
- 成本异常飙升（外部 API 调用、存储费用）

### 10.3 应急流程（最小闭环）

1) 监控告警确认 + 影响面评估
2) 止血（功能开关/限流/回滚）
3) 恢复后补充修复与回归验证
4) 复盘：根因、改进项、门禁补强

---

## 11. 交付物模板（复制即用）

### 11.1 PR 描述模板

标题：`type: summary`

- 目的/背景：
- 变更内容：
- 影响范围：
- 测试证据：
- 风险与回滚：
- 需要运维/安全审阅的点：

### 11.2 测试计划（Test Plan）

- 版本号：
- 环境（test/staging）：
- 测试范围：
- 不测范围与理由：
- 用例清单（链接/附件）：
- 通过标准：
- 未解决问题与风险：

### 11.3 发布说明（Release Notes）

- 版本号：
- 变更摘要：
- 影响范围（用户/运营/数据/接口）：
- 迁移说明（如有）：
- 回滚步骤：
- 监控关注点：

### 11.4 上线验收（Go/No-Go）

- staging 验收人：
- 关键指标基线：
- smoke 测试结果：
- 是否批准上线（Go/No-Go）：
- 批准人（Ops/Release）：

### 11.5 事故复盘（Postmortem）

- 摘要（发生了什么）：
- 时间线：
- 根因：
- 影响范围：
- 处置与恢复：
- 改进项（门禁/监控/测试/流程）：

---

## 12. 参考文件（本仓库）

- 环境隔离与发布运行手册：`DEPLOYMENT_RUNBOOK.md`
- 生产相关文件审阅规则：`.github/CODEOWNERS`
- PR 检查清单：`.github/pull_request_template.md`
