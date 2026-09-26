# feature001 · task #20 收口（版本号单一来源）+ 等你点头的四件事

登记时间：2026-09-26 23:35（`date` 现测）。仓库 HEAD = `b1eb2ed`。
**别信本文任何数字**，文末每条都给了现测命令，跑一遍再决定。

一句话现状：**#20 已经闭合**（全仓版本从 24 处字面量收成 1 处 `<revision>`，
两台机器各 54 类 / 414 测试 / 0 问题且逐行相同，13 支变异电池 `VERDICT: OK`）；
收口过程中在 250 上撞出**一个新缺陷并已修好但未提交**（flatten 1.6.0 打死门禁机），
另有 4 件事在你拍板之前我不会动。

> ⚠ 本目录名叫 `feature001_hnsw_v2`，但**这一格装的是 #20 的收口**（你 23:0x 指定的内容）。
> HNSW v2 的计划本体还没有写，名字和内容的错位由这次登记造成，别把它当成"HNSW v2 已有计划"。

---

## 1. #20 到底改了什么（全部现测）

| 项 | 改前（`8ff0e58^` 那棵树） | 改后（HEAD + 工作树） |
|---|---|---|
| 本仓自身版本的字面量 | 8 份 pom 里 **24 处** `<version>1.0.3</version>` | **0 处**，只有聚合 pom `<properties>` 里 **1 个** `<revision>1.0.3</revision>` |
| 模块自己的 `<version>` | 每个模块写死 | 一律不写，走 `<parent>${revision}` 继承 |
| 兄弟依赖 | 各自 pin 字面量 | 无 `<version>`，由聚合 `dependencyManagement` 统一给 `${project.version}` |
| flatten-maven-plugin | 只挂在 `central` profile 上 ⇒ `mvn install` 不带 `-P central` 时装进 m2 的 pom 留着 `${revision}` | 挪进常开的 `<build><plugins>`，`updatePomFile=true` |

**不在 #20 范围内、故意没动的**：4 处 `io.github.yuku123:z-util-*` 的 `1.0.9`，以及
spring-boot / jackson / slf4j / junit 那些第三方版本字面量 —— 那是**别人的**版本，不是本仓版本。
（`grep` 时会连着数到，别误判成"漏改"。）

---

## 2. 四把尺 + 一条 install 面（这次的真正交付）

`z-vector-server/src/test/.../PomVersionContractTest.java` 5 条，全部读 DOM 不读正则
（`PomFiles.java`：`<parent>` 的 1.0.0-SNAPSHOT、`<version>${revision}</version>` 这个占位、
`<revision>` 的真值三个"版本"同住一个文件，只有直接子节点语义 + 两值不同的替身 pom 分得开）：

| 尺 | 判什么 |
|---|---|
| P1 | `<revision>` 是全仓唯一定义点（出现两次当场炸，不返回"其中一个"） |
| P2 | 模块不许重复版本、不许另定义 `<revision>`；`<parent>` 必须是 `z-vector` + `${revision}` |
| P3 | 仓内依赖不许自己 pin —— 漏了会让"少 `-am`"的构建**静默编译 m2 里的线上旧字节** |
| P5 | flatten 必须在常开位置且 `updatePomFile=true`；**并且钉住版本不许 ≥ 1.6.0**（见 §3） |

install 面（JUnit 够不着的那一层）：`~/.cache/zv-version/zv_install_check.py`
—— 用一次性版本号 `-Drevision=9.9.9-ziptest` 真装一遍，验 8 份**装出去的** pom：
无 `${revision}`、无 `<parent>`、带死版本、兄弟依赖都带版本，跑完自己清 m2。

```
$ python3 ~/.cache/zv-version/zv_install_check.py | tail -5
对照（源码 pom，同一套判据）: 8/8 份被判出问题（预期=全部，因为 flatten 不改源码文件）
参考（m2 里的 1.0.3 已装件）: 共 4 份，带 <parent> 的 0 份 []
清干净: 删掉 8 个 9.9.9-ziptest 目录，剩余 0
VERDICT: OK — 8 份装出去的 pom 全部自包含（无 ${revision}/无 <parent>/兄弟依赖带死版本），而同一套判据在源码 pom 上判出 8/8 份 ⇒ 尺确实在区分两种形状，不是空跑
```

这条在 §3 把 flatten 降到 1.5.0 **之后重跑过一遍**（23:3x）：换 flatten 版本有可能换掉
展出来的 pom 内容，所以"降版本没弄坏自包含"这句必须有读数，不能靠推断。

变异电池：`~/.cache/zv-version/zv_pom_teeth.py` + 注册表 md5 `dc3556d4da559bd000995754ce0b6e2c`，
运行目录 `~/.cache/zv-version/teeth/pom-run-20260926233028`。

| 注入 | 形状 | 结果 |
|---|---|---|
| M1 | core 声明自己的 `<version>1.0.3` | KILLED exact（P1） |
| M2 | api 另定义 `<revision>` | KILLED exact（P1） |
| M3a | storage 的 parent 版本单独退回字面量 | **REFUSED-BY-MAVEN**（构建期就拒，尺轮不到） |
| M3b | 聚合 `<version>` 退回字面量、子模块仍 `${revision}` | **REFUSED-BY-MAVEN**（同上） |
| M3c | **整仓**退回字面量（= 改前的真实形状，能构建） | KILLED exact（P1+P2） |
| M4 | core 的兄弟依赖重新 pin `${project.version}` | KILLED exact（P3） |
| M5 | flatten 从常开 `<build><plugins>` 删掉 | KILLED exact（P5） |
| M6 | `updatePomFile` 改 false | KILLED exact（P5） |
| M7 | flatten 抬到 1.6.0 | KILLED exact（P5）← §3 新加的闸 |
| V1 | `VERSION` 退回字面量 | KILLED exact（/health） |
| V2 | 资源过滤关掉 | KILLED exact（/health） |
| V3 | 读 `<revision>` 改成读第一个 `<version>` | KILLED exact（3 条） |
| V4 | 资源路径改成不存在 | KILLED exact（/health） |

`DRIFT: none`。**M3a/M3b 这两支记的是"构建级覆盖"，不是尺的杀绩** ——
混用 `${revision}` 和字面量时 Maven 在读 pom 阶段就报
`Non-resolvable parent POM … z-vector:pom:1.0.3 (absent)`，
所以"混着改"这种形状根本流不到尺前面；能流到的是"整仓一起退回字面量"（M3c，真杀）。

---

## 3. 收口时在 250 撞出来的新缺陷（已修，**未提交**）

`mvn clean test` 在 250 上 rc=1，8 个模块一条测试都没跑到：

```
[ERROR] Failed to execute goal org.codehaus.mojo:flatten-maven-plugin:1.6.0:clean
        (flatten.clean) on project z-vector:
        The plugin … requires Maven version 3.6.3
```

250 是 **Maven 3.6.0**。改前 flatten 只挂在 `central` profile 上，日常构建碰不到它；
#20 把它变成常开 ⇒ **插件的 Maven 要求变成了全仓构建的要求**。这是 #20 自己的连带后果，
不是"250 环境坏"。

在 250 上逐版本实测（同一棵树，每次只改版本号，rc 当场取）：

| flatten | 250 `mvn -o clean` |
|---|---|
| 1.2.1 | rc=0 |
| 1.5.0 | **rc=0** |
| 1.6.0 | rc=1，`requires Maven version 3.6.3` |
| 1.7.2 / 1.7.3 | rc=1，同上 |

⇒ 钉 **1.5.0**（能跑的最高已测版本），并在 P5 里加一条版本闸 + M7 那支变异，
让"以后有人抬版本"必须先在 250 实测才能改判据。改动只有两处：
`pom.xml`（版本 + 为什么钉在这里的注释）、`PomVersionContractTest.java`（版本闸 + 5 个边界样本：
1.2.1/1.5.0 不许误伤，1.6.0/1.7.3/2.0.0 必须报）。

---

## 4. 跨 JDK 对账（这次是修完 §3 之后重跑的）

| | 本机 macOS/arm64 JDK 25 | 250 Linux/x86_64 JDK 1.8.0_362 + Maven 3.6.0 |
|---|---|---|
| `mvn -o -fae clean test` | BUILD SUCCESS，01:13 min | BUILD SUCCESS |
| 全量 tally | **54 类 / 414 测试 / 0 问题** | **54 类 / 414 测试 / 0 问题** |
| 源码清单 | 136 文件 | 136 文件，**与本机逐行相同** |
| `pom.xml` md5 | `d447403d4612bd15f8529b0b06fa8191` | 同一个 |

（上一轮基线是 53 类 / 409 测试；+1 类 / +5 条就是 `PomVersionContractTest`。）

---

## 5. 等你的四件事（我不会自己拍）

1. **[待点头] 把 §3 那两处改动提交**（`pom.xml` + `PomVersionContractTest.java`，工作树现在就是这两条 `M`）。
   不 push —— 你先前只授权过 `f265b6e`、`694a6bc` 两次推送，我不当"授权会传染"。
2. **[待决策] 250 上那个容器要不要重建**。09-26 23:3x 实测：`z-vector:v1` 起了 36 小时、
   `curl 127.0.0.1:6333/health` 仍返回 `{"collections":0,"version":"1.0.1","status":"ok"}`
   —— 源码/构件已经 1.0.3，线上还是 1.0.1。**源码修好≠线上修好**。
   重建共享机上的运行服务属于会影响别人的动作，等你说一句。
3. **[待决策] 要不要发 1.0.4**。现状实测（repo1，8 个坐标都 200）：
   已发布的 **1.0.3 的 8 份 pom 全部 `parent=0`、无 `${revision}`** ⇒ 悬空 parent 这个雷
   只在更早的 **1.0.1 / 1.0.2** 上（实测 `z-vector-server:1.0.2` pom 里 `<parent>` = 1）。
   也就是说：**pom 形状不需要紧急补发**；要发的话是为了让消费方拿到 #19/#20 修好的
   `/health` 版本通道。发 Central 是另一回事，按规矩单独等你点头。
4. **[待排产] 已记账但还没动的缺陷**（按我判断的收益排序，你打乱也行）：
   - gRPC 广告与端口：`z-vector-grpc-server` 里**零** gRPC 依赖、`VectorServiceGrpc` 零调用方，
     Dockerfile `EXPOSE 9090` vs README 的 8181/8182 —— 三个说法互相不打照面；
   - `OpenApiSpec` 生成了但没有任何端点服务它；
   - 独立 server 没有 default-index 开关（永远是 FLAT，starter 侧 #18 才接通）；
   - `PageStore` 每次操作 open/close 文件、`write()` 每次都让 mmap 失效；
     `PayloadIndex.ExactIndex.get()` 每次命中约 38 B 拷贝；
   - `remove()` 的反向边清扫（这一条里"maxLevel"那半句是我先前的误判，已作废，只剩清扫本身）。

---

## 6. 取数命令（本文每个数字都能这么复现）

```bash
R=/Users/zifang/workplace/ceo_workplace/z-opc-foundation/z-vector
cd $R && mvn -o -fae -Dmaven.test.failure.ignore=true clean test   # 必须 clean：陈旧报告会虚报 +1 类
python3 ~/.cache/zv-pagestore/tally.py $R                          # 只吃 surefire XML，空输入必 FATAL
python3 ~/.cache/zv-version/manifest_v2.py $R | tail -1
python3 ~/.cache/zv-version/zv_pom_teeth.py | tail -3              # 13 支电池
python3 ~/.cache/zv-version/zv_install_check.py | tail -4          # install 面（自己清 m2 的 9.9.9-ziptest）
git -C $R show 8ff0e58^:pom.xml | grep -c '<version>1\.0\.[0-9]</version>'   # 只有聚合 pom ⇒ 1，别拿它当 24
# 改前那 24 处的正确口径是**八份 pom 一起数**（照下面这条跑，输出 24 + 4 处 z-util 的 1.0.9）：
cd $R && { git show 8ff0e58^:pom.xml; for m in api core storage protocol grpc-server spring-boot-starter server; do git show 8ff0e58^:z-vector-$m/pom.xml; done; } \
  | grep -oE '<version>1\.0\.[0-9]</version>' | sort | uniq -c
```
