# 本机 SKU 验收容器

入口：[http://localhost:12410/login](http://localhost:12410/login)。仅绑定本机回环地址。

这是独立数据库，请先注册本地系统账号，再到“连接管理”或“扫码登录”添加真实闲鱼账号。NAS 的系统登录信息和业务数据没有复制过来。数据库、账号 Cookie、货源快照及日志保留在专用 Docker 卷中，停止或重启测试容器不会清空它们。

## 验收步骤

1. 注册并进入本地系统，添加闲鱼账号；平台要求验证时先完成人工验证。
2. 全站比价搜索商品，点击“查看规格”，核对组合、SKU 售价及库存，再检查“刷新规格”。
3. 商机发掘查看规格、导入货源并整理商品，确认自己的售价和库存需要手动填写。
4. 货源库点击“查看规格”，确认已保存资料可回看。获取失败时应保留上次资料并提示失败。
5. 出现验证错误时，到任务记录确认“等待人工验证”，完成验证后手动重试；不要把搜索展示价当作某个 SKU 的真实价格。

旧详情中仅有未核对 `price` 字段的 SKU 仍显示“单位待确认”。本次确认页来源的 `skuPrice` 已用真实响应、页面和分单位小计核对为元；只固化这一来源的换算规则。平台不返回 SKU 时仍显示数据源缺口。

## 当前启动策略

采用全新空库；AI 关闭，未配置自动发布、擦亮、评价或发货规则。通过启动配置将运营及商品自动化调度的首次执行延后 24 小时，随后按 24 小时间隔调度；手动采集立即可执行。这个设置是延后执行，并非永久关闭。登录保活保持现有行为。

## 构建与运行证据

- 镜像：`xianyusmart:sku-review-20261007`；Compose 项目：`xys-sku-review`。
- 容器：`xys-sku-review-app`、`xys-sku-review-mysql`，均已健康。
- 配置与构建目录：`target/local-sku-review/`；随机数据库密码与 JWT 密钥只保存在该忽略目录的 `.env`，不提交。
- 数据卷：`xys-sku-review_mysql-data`、`xys-sku-review_app-data`、`xys-sku-review_app-logs`。
- 运行时基础镜像固定摘要，新后端和前端重新打包替换 JAR；容器与本机 JAR SHA256 一致：`03e7ee09dfa329fe60f655bd2183709baa525ef283adea68830b2414abfb12ef`。
- 17 个 Flyway 迁移成功；`/actuator/health` 为 `UP`；登录页返回 200，页面索引与新前端构建一致。
- 本机 MySQL 已验证任务实际 Mapper SQL：停止重试、停止任务不进入到期查询、手动恢复次数、手动任务重新进入到期查询。合成任务在事务内回滚。
- 本轮没有调用真实闲鱼详情接口，真实 SKU 验收由用户登录后进行。已有本机业务容器和 NAS 未重启。

在项目根目录查看或停止本次容器：

```powershell
docker compose --project-directory target/local-sku-review -f target/local-sku-review/compose.yaml ps
docker compose --project-directory target/local-sku-review -f target/local-sku-review/compose.yaml stop
```

再次启动：

```powershell
docker compose --project-directory target/local-sku-review -f target/local-sku-review/compose.yaml up -d
```

请保留专用卷和配置目录用于后续复验，不执行卷清理。

## 2026-10-07：风控提示更新

本机 app 容器已替换为最新提示修复版本，MySQL 未重启。账号数量仍为 1，运行中运营任务数量为 0，原数据卷保留。HTTP 登录页 200、健康检查 UP、运行 JAR 与本机构建哈希一致，服务实际输出的页面索引与独立前端构建逐字节一致。

当前 JAR SHA256：`7e6968396c6b1afbfbc40d65895e9a53dc2df197f1df4a3c721b78c9253219fc`。上文 `03e7...` 是初次版本，仍有回滚副本。

回滚镜像：`xianyusmart:sku-review-20261007-before-risk-guidance`。旧 JAR、Compose 配置与一致性数据库备份位于忽略的 `target/local-sku-review/rollback-risk-guidance-20261007/`；数据库备份非空（86,967 字节），SHA256 为 `7024b46e0c04885cf3cbc33ac7043f4bb907d9cea71c7df1662ecc7f989301e4`。备份包含真实测试账号资料，仅供本机恢复，不提交或分享。

RGV587 现在显示平台访问受限；明确 USER_VALIDATE/CAPTCHA 才显示等待人工验证。两者都停止详情自动重试。旧货源或历史任务中的已保存错误文字会保留；重新执行时才记录新的分类提示，不批量改写历史证据。

用户已完成 Chrome 滑块且确认官方商品页能显示规格，容器详情请求仍被拒绝。成功请求的接口、版本和规格响应字段待对照。该更新修复了误导提示和诊断可见性，不宣称已恢复真实 SKU 获取。

## 2026-10-07：真实规格采集通过

已从 Chrome 确认官方下单确认页使用 `order.render` v7.0，并接入统一竞品采集服务。测试容器对商品 `1078417108918` 进行一次真实按需请求成功；货源保存 12 个 SKU 的价格、库存和规格。“1美”¥7.30、库存 0；“2美”¥15.00、库存 7867；“3美”¥25.00、库存 7988。未创建订单，未重置熔断，未复制浏览器快照冒充服务端采集。

随后修复了系统 Long 字段序列化为字符串造成的“¥ --”显示。最终版本重启后，仅回看已保存快照，全部 12 行金额显示正常；390px 手机表格在弹窗内横向滚动，窗口尺寸已恢复。桌面与手机截图已查看。

- 自动验证：25 个后端测试、3 个价格测试、10 项合成页面检查、类型检查和独立前端构建通过。最终打包只替换前端资源，后端代码与已测试版本一致。
- 真实验证：1 件公开商品成功，12 行组合和价格/库存与 Chrome 响应一致。实际双规格商品、其他账号及其他商品尚未验收，不能据此保证后续访问不受平台限制。
- 最终运行 JAR SHA256：`008b6796699bb99d2c18a9620bb14d12afb62600eab1da9bf20b0d7275f6306c`；本机与容器一致，前端索引逐字节一致，HTTP 200，健康检查 UP，两容器健康。
- MySQL 保持原启动时间和原卷，只更新测试 app。NAS 未部署，未提交或推送。
- 回滚镜像：`xianyusmart:sku-review-20261007-before-order-source`。旧 JAR、Compose 和一致性数据库备份保存在忽略目录 `target/local-sku-review/rollback-order-source-20261007-1656/`。数据库备份 88,890 字节，SHA256 `5da20f65e2a454292be146ce453b5551a00959aee9a74e528f13e4b507d26686`。备份包含真实测试账号凭证，仅供本机恢复，不提交或分享。

现在可进入 [货源库](http://localhost:12410/supplies) 点击“查看规格”直接验收已有结果；全站比价和商机发掘共用同一采集服务。需要更新时再手动刷新，不进行批量或连续详情探测。
