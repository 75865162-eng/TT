# 用你自己的腾讯云域名 + Cloudflare Tunnel 固定访问（操作清单）

## 核心结论（先读这段）

- **我的出口 IP 不固定**：实测连续两次查询得到 `175.178.173.95` 和 `43.139.84.32` 两个不同地址，是腾讯云 NAT 出口池在轮换；沙箱休眠/重建也会变。
- **所以域名不能用 A 记录指向我的 IP**：那个地址明天可能就不是我了，且服务在内网、外网没有端口映射，直连本就不通。
- **正确做法**：让域名指向"隧道"，隧道由我从沙箱内主动连到 Cloudflare。域名解析权交给 Cloudflare，IP 变化对你完全透明。
- **域名注册商仍在腾讯云**：Cloudflare Tunnel 只要求 DNS nameserver（NS）托管到 Cloudflare，域名所有权、购买、续费都在腾讯云不动。

---

## 你在腾讯云侧要做的（约 5 分钟）

### 第 1 步：确认域名在腾讯云 DNS 解析（DNSPod）的当前状态
记下现有解析记录，尤其是正在用的业务记录。切 NS 后这些记录需要在 Cloudflare 里重建（或导入）。

### 第 2 步：把域名添加到 Cloudflare
1. 注册/登录 [Cloudflare](https://dash.cloudflare.com)。
2. 点 **Add a site**，输入你的域名（如 `example.com`），选免费计划。
3. Cloudflare 会扫描现有 DNS 记录并导入——确认无误后继续。
4. Cloudflare 给出两个 nameserver（形如 `xxx.ns.cloudflare.com`）。
5. **到腾讯云控制台**：域名管理 → 修改 DNS 服务器 → 改成 Cloudflare 给的那两个 NS。
6. 等待生效（几分钟到几小时）。CF 会发邮件通知 "active"。

> 提示：切 NS 不影响域名在腾讯云的续费，只是解析交由 Cloudflare。原有业务 DNS 记录已被 CF 扫描导入，核对一遍即可。

### 第 3 步：创建 Tunnel 并拿 token
1. Cloudflare 控制台 → **Zero Trust**（首次进入需选免费计划并绑定支付方式，可后取消）→ **Networks** → **Tunnels**。
2. **Create a tunnel** → 选 **Cloudflared** → 命名（如 `wimoor`）→ 保存。
3. 复制那串 **Tunnel token**（长 base64 字符串）——**这就是要给我的东西**。

### 第 4 步：配置 Public Hostname（决定用哪个域名访问）
在刚建的 tunnel 里 → **Public Hostname** → **Add a public hostname**：
- Subdomain：填 `erp`（或你想要的任意前缀，如 `wms`、`oa`）
- Domain：选你的域名
- Service：类型 **HTTP**，URL 填 `localhost:8080`
- 保存

---

## 你把这两项发给我

1. **Tunnel token**（第 3 步复制的那串）
2. **完整域名**（如 `erp.example.com`，第 4 步配的那个）

---

## 我这边会做的（收到 token 后）

1. 把 token 写入 `deploy/wimoor-p0/.cf-token`（已加入 .gitignore，不会入库）。
2. 用 `deploy/wimoor-p0/cf-tunnel.sh start` 拉起 cloudflared（二进制已备好 v2026.11.2）。
3. 用你的域名实测：登录页、AI health、登录接口是否通。
4. 把 CF Tunnel 纳入 `restore.sh`，沙箱休眠后一键恢复仍是同一个固定域名。

---

## 备选方案（如果不想把 NS 迁到 Cloudflare）

| 方案 | DNS 托管 | 域名稳定性 | 成本 | 说明 |
|---|---|---|---|---|
| ngrok 固定域名 | 留腾讯云 DNSPod | 固定 | ngrok 付费 | 分配固定 `*.ngrok-free.app`，再用 DNSPod 给域名加 CNAME 指向它 |
| lhr 临时域名 | 留腾讯云 | 会轮换 | 免费 | 当前 `https://ef5d6869b35580.app.lhr.life`，休眠后变，仅临时用 |

当前临时链接仍可用：`https://ef5d6869b35580.app.lhr.life`（实测 HTTP 200）。
登录账号：`admin@wimoor.com` / `123456`。
