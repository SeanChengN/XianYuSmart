# 竞品 SKU 数据源核对

## 当前结论

`CompetitorDetailService` 已接入 `mtop.taobao.idle.trade.order.render` v7.0，从确认页响应提取逐 SKU 价格。此前仅查询 `pc.detail` 并解析 `skuList` / `idleItemSkuList`，没有覆盖本样本实际使用的确认页规格来源。

已从闲鱼官方公开页面及其脚本确认另一条链路：`mtop.taobao.idle.trade.order.render` v7.0 用于确认页预览，首次以商品 ID 初始化请求。真正创建订单使用另一个接口，规格采集不应调用创建订单、支付、地址查询或任何提交动作。

这一来源缺口与容器收到 RGV587 是两个问题。更换采集来源不保证风控恢复；不得在收到限制后继续切换接口探测或清除熔断。本次测试容器对现有公开商品的一次按需调用已成功，其他商品和账号仍需逐项核验。

## 官方静态证据

- 页面：<https://www.goofish.com/create-order>，匿名只读获取。
- 脚本：<https://g.alicdn.com/idle-pc/xy-site/0.0.176/js/p_create-order-index.js>。
- 本地证据：忽略目录 `target/sku-order-source/`。只保存匿名 HTML 与公开脚本，没有保存用户提供的签名 URL、Cookie 或交易响应。
- 脚本 SHA256：`da659332289296c7b7848d131d61e5be828f0775e356153f2d64f1805ecbe5d3`。

页面从响应 `renderVO.components` 中匹配 `render === "skuSelectorVO"`，然后读取组件的 `data`：

| 字段 | 静态脚本确认的用途 |
| --- | --- |
| `skuInfoDetails` | 以完整规格路径为键的映射；行中读取 `skuId` 与 `quantity` |
| `specificationInfoList` | 规格分组，读取 `specificationId` / `specificationName` |
| `specificationList` | 选项，读取 `id` / `value` / `priceText` |
| `selectedSkuData.selectedPath` | 当前选中的完整规格路径 |

组合路径由 `规格ID:值ID` 拼成，多个规格用 `#` 分隔。当前选中组合通过完整路径索引 `skuInfoDetails`，不能用某个选项的 `priceText` 代替组合 SKU 售价；`priceText` 的组合语义、真实返回类型和价格单位还需核对响应。页面切换规格会重新执行预览请求，不应为采集所有价格无界遍历所有组合。

`itemDescVO` 只提供页面展示的标题、图片和当前价格等信息，不代表完整商品描述。响应还可能包含用户或交易预览资料；实现时只提取规格事实，不能存储整个预览响应。

## 历史验收缺口（已由下述真实响应补齐）

需要用户下单确认页已有 `order.render` 成功响应中的脱敏规格片段：`skuInfoDetails`、`specificationInfoList` 和实际价格字段。只提供这些字段，不包含 Cookie、地址、手机号、签名链接或完整响应。

拿到片段后才能确定映射兼容、组合售价字段与价格单位，补入统一竞品采集服务并验证限流、熔断中止、快照保护和真实样本对照。当前只完成来源定位，不宣称真实 SKU 价格已经恢复。

## 同类项目源码核对（2026-10-07）

以下是固定版本的源码检查结果，没有执行第三方工具、账号恢复、平台请求或下单流程。

### goofish-cli

核对版本：`469cac533759f5fc3f5e3314e63b344d1dc454a5`。

- [`item/view.py`](https://github.com/fancyboi999/goofish-cli/blob/469cac533759f5fc3f5e3314e63b344d1dc454a5/src/goofish_cli/commands/item/view.py) 在系统 Chrome 的商品页上下文调用 `window.lib.mtop.request`，仍使用 `pc.detail` v1.0；提取商品展示价和详情，没有确认页 SKU 解析。
- [`core/browser.py`](https://github.com/fancyboi999/goofish-cli/blob/469cac533759f5fc3f5e3314e63b344d1dc454a5/src/goofish_cli/core/browser.py) 默认启动有界面的系统 Chrome，每次使用独立临时 profile，注入 Cookie 记录并保留 domain/path；不是直接复用用户当前的 Chrome 标签页或 profile。浏览器传输可以借鉴，但不能据此保证当前账号解除 RGV587。
- [`core/mtop.py`](https://github.com/fancyboi999/goofish-cli/blob/469cac533759f5fc3f5e3314e63b344d1dc454a5/src/goofish_cli/core/mtop.py) 对 token/session 失效最多自动刷新并重试一次；RGV587/USER_VALIDATE 抛出风控错误，不进入此恢复路径。`ILLEGAL_ACCESS` 也不在自动恢复列表内。
- 在所检查的 src、tests、README、CHANGELOG 中，没有发现 `order.render`、`skuSelectorVO`、`skuInfoDetails`、`skuList`、`idleItemSkuList` 或 `create-order`。因此不是现成的确认页逐 SKU 价格采集器。
- 风控指引区分本地熔断与平台限制：本地 reset 不解除服务端限制。文档中的 Cookie、等待时长及浏览器成功率属于上游建议，不能当作本账号已验证结论。

### xianyu-auto-reply 与 pyxianyu

- [`XianyuOrderClient.render`](https://github.com/zhinianboke/xianyu-auto-reply/blob/1be6493c36f8c66547b91d3940ac32580b1937b9/common/services/xianyu_order_client.py) 使用 `order.render` v7.0 和 `{itemId}`，读取 `commonData.itemBuyInfo` 为创建订单准备参数；该文件没有完整 SKU 组合价格解析。
- [`xianyu_mtop.py`](https://github.com/zhinianboke/xianyu-auto-reply/blob/1be6493c36f8c66547b91d3940ac32580b1937b9/common/services/xianyu_mtop.py) 遇到风控立即返回失败，并通过 `extract_punish_url` 提取响应 `data.url`。可借鉴为人工验证入口，但链接可能缺失或失效，不能承诺能解除限制。竞品采集应保留当前账号并停止，不照搬换账号继续的流程。
- [`pyxianyu TradeApi.order_render`](https://github.com/DoLovya/pyxianyu/blob/6d666037df8d007443c0d8a83df0b928b57d9a0b/src/pyxianyu/apis/trade_api.py) 同样获取并透传 `itemBuyInfo`；其[接口说明](https://github.com/DoLovya/pyxianyu/blob/6d666037df8d007443c0d8a83df0b928b57d9a0b/docs/mtop_taobao_idle_trade_order_render.md) 明确写明尚未在本项目通过浏览器抓包交叉验证。示例展示价不是全 SKU 价格或单位的验收依据。

### 当时的实施评估

有可借鉴的组件，但所检查的版本中尚未找到可直接移植并证明有效的完整方案。下一步需要同时补齐确认页规格解析和可用的人工验证入口。可评估本机浏览器采集桥接，先核对用户已能打开的确认页响应，再按需采集；这会新增本机组件，并非仅修改 Java 容器的 HTTP 请求。若响应携带验证链接，应只对所属账号用户提供经 HTTPS/平台域名校验的入口，不记录链接中的验证参数或持久化完整交易响应。

真实组合价格字段和单位仍需与确认页逐项核对。浏览器方案、更新 Cookie、切换数据源或清除本地熔断均不能替代真实 SKU 验收。

## 2026-10-07：Chrome 响应与本机容器核验

商品 `1078417108918` 的官方确认页 `order.render` v7.0 成功响应实际位于 `data.components`。解析同时兼容静态脚本中的 `data.renderVO.components`。`skuSelectorVO.data.skuInfoDetails` 一次包含全部 12 个完整路径；不逐个遍历规格，不调用创建订单或支付接口。

实际组合售价字段为 `skuPrice`，单位是元：选中“2美”时为 `15.00`，页面显示 ¥15.00，`itemDescVO` 小计 `priceInNumber` 为 `1500`；“3美”分别为 `25.00`、¥25.00 与 `2500`。固化的换算仅适用于该来源的 `skuPrice`，未知详情 `price` 的单位不作推测。多规格只读取完整组合的售价，不能使用选项 `priceText` 替代。

| 面值 | 售价（元） | 库存 |
| --- | ---: | ---: |
| 1美 | 7.30 | 0 |
| 2美 | 15.00 | 7867 |
| 3美 | 25.00 | 7988 |
| 5美 | 36.00 | 7894 |
| 10美 | 71.00 | 7846 |
| 15美 | 108.00 | 7957 |
| 20美 | 142.00 | 7627 |
| 25美 | 178.00 | 7952 |
| 30美 | 216.00 | 7955 |
| 50美 | 350.00 | 7920 |
| 100美 | 700.00 | 7840 |
| 200美 | 1398.00 | 7882 |

以上是本次采集时的公开商品数据，不代表后续价格或库存。脱敏回归样本 `src/test/resources/competitor/order-render-public-sku.json` 只保存 SKU、规格和公开小计字段，不保留 Cookie、地址、交易公共数据或完整响应。

测试容器通过统一竞品服务调用该接口成功，数据库快照为 `AVAILABLE / ORDER_RENDER_API`，12 行，“2美”保存为 1500 分。确认页没有完整描述或卖家资料，结果标为 `ORDER_PREVIEW`；省略相应字段以保留旧资料。原有缓存、账号串行、至少 3 秒间隔及风控中止仍适用，不在失败后改试其他接口。
