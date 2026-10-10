# Xy Broadcast Metro

Minecraft 1.21.11 Fabric **服务端**地铁广播模组：是Metro插件的fabric移植版  
此模组使用了Vibe Coding (✿◡‿◡)   
喜欢的点个星标口八

## 功能

- **站台信息屏**：玩家站在停靠区范围内时，以 Title 形式展示「线路·站名 / 开往方向 / 下一站 / 可换乘线路」，按刷新间隔自动更新
- **车内告示牌广播**：矿车行进途中自动检测轨道侧方 1-2 格内的告示牌，将面向轨道一侧的文字以 Title 展示给车内玩家（支持 `&` 色号）<--新增内容，Metro没有滴
- **金锄头选区**：手持金锄头右键方块设置角点，快速框选站台区域（建议在创造模式下且要有op）
- **矿车调度**：右键发车点铁轨自动生成矿车并倒计时发车，到站滑行精准停车、停车计时、终点站强制下车、环线支持、前方受阻自动重试
- **权限管理**： LuckPerms 深度集成（`xy.command.*` 权限节点），未安装 LuckPerms 时自动回退原版 OP 等级
- **服务端文案包**：全部提示文本、Title 样式集中在 `lang.json`，改完 `/xy reload` 即时生效

## 环境要求

| 项目 | 要求 |
|------|------|
| Minecraft | 1.21.11 |
| Fabric Loader | 0.16.0+ |
| Fabric API | 0.141.6+1.21.11（必需） |
| LuckPerms | 可选（权限精细化时安装） |
| 运行端 | 仅服务端；客户端为原版即可 |

## 安装

1. 从 [Releases](https://github.com/xingyu0414/Metro/releases) 下载 `xy_broadcast-2.0.2.jar`
2. 放入服务端 `mods/` 目录，同时放入 Fabric API
3. 启动服务端，首次运行自动生成 `config/xy_broadcast/` 配置目录

## 快速上手

```
1. 手持金锄头，右键方块 → 设置角点1；潜行右键 → 设置角点2（框选站台范围）
2. /xy stop create platform1 一号站台
3. 站上站台内的铁轨，面朝列车行进方向，执行 /xy stop setdirect
4. /xy line create m1 一号线
5. /xy line setcolor m1 &c            # 设置线路颜色（& 色号）
6. /xy line addstop m1 platform1      # 按运营顺序依次加站
7. /xy line setterminus m1 开往朝阳站  # 设置终点方向名
8. 玩家站在站台内 → 看到信息屏；右键发车点铁轨 → 生成矿车并自动发车
9. 沿途轨道侧方放置告示牌并写上文字 → 车内玩家经过时自动收到广播
```

## 命令

所有命令以 `/xy` 开头，ID 补全自动提示。

### 停靠区 `/xy stop`

| 命令 | 说明 | 默认权限 |
|------|------|---------|
| `list` | 列出全部停靠区 | 所有人 |
| `info <站>` | 查看站点详情（角点/发车点/换乘） | 所有人 |
| `listtransfers <站>` | 查看可换乘线路 | 所有人 |
| `create <id> <名称>` | 用金锄头选区创建停靠区 | OP 2 |
| `setcorners <id>` | 用当前金锄头选区重设角点 | OP 2 |
| `setdirect` | 将脚下铁轨设为该站发车点（面朝方向即发车朝向） | OP 2 |
| `rename <id> <新名>` | 修改展示名 | OP 2 |
| `addtransfer <站> <线>` | 添加可换乘线路 | OP 2 |
| `deltransfer <站> <线>` | 移除可换乘线路 | OP 2 |
| `delete <id> confirm` | 删除停靠区（需二次确认） | OP 2 |
| `tp <站>` | 传送至该站 | OP 2 |

### 线路 `/xy line`

| 命令 | 说明 | 默认权限 |
|------|------|---------|
| `list` | 列出全部线路 | 所有人 |
| `stops <线>` | 按运营顺序查看线路站点 | 所有人 |
| `create <id> <名称>` | 创建线路 | OP 2 |
| `setcolor <id> <色号>` | 设置线路颜色（如 `&c`、`&a`） | OP 2 |
| `setterminus <id> <方向名>` | 设置终点方向名（留空则自动取末站名） | OP 2 |
| `addstop <线> <站> [位置]` | 加站，可选插入位置（从 1 起） | OP 2 |
| `delstop <线> <站>` | 从线路移除站点 | OP 2 |
| `setcircle <线>` | 切换环线模式（开/关） | OP 2 |
| `rename <id> <新名>` | 修改展示名 | OP 2 |
| `delete <id> confirm` | 删除线路（需二次确认） | OP 2 |

### 其他

| 命令 | 说明 | 默认权限 |
|------|------|---------|
| `/xy reload` | 重载语言文件 | OP 2 |

## 权限

模组读取 LuckPerms 权限节点 `xy.command.<子命令>`，例如 `xy.command.stop.create`、`xy.command.line.addstop`。

- **安装 LuckPerms**：按节点授权即可；显式拒绝（设为 false）优先级最高
- **未安装 LuckPerms**：自动回退原版等级——查询类命令所有人可用，管理类命令需要 OP 2（服主/管理员默认满足）

## 配置文件

位于 `config/xy_broadcast/`，全部为 JSON：

| 文件 | 说明 |
|------|------|
| `lang.json` | 全部提示文案（语言配置） + 站台 Title 样式（`title` 段可调：开关、淡入/停留/淡出时长、刷新间隔、上下屏模板） |
| `stops.json` | 停靠区数据（命令修改后自动落盘，无需手改） |
| `lines.json` | 线路数据（命令修改后自动落盘，无需手改） |

文案与模板均支持 `&` 色号和 `{占位符}`（如 `{stop_name}`、`{terminal}`、`{next}`）。

## 机制
- **使用**：玩家仅需右键铁轨，下车自动回收
- **环线**：开启环线的线路到末站后继续循环运行

## 从源码构建

```bash
# 首次构建需联网下载 Minecraft 与映射文件
./gradlew build

# 产物位于 build/libs/xy_broadcast-2.0.2.jar
```
