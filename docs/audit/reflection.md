# 反射域静态审计 — reflection/

## 文件清单（21）
契约层（2）
- `reflection/contract/ReflectionContract.java` — 契约记录类型（modId/class/field/method 签名 + MemberOrigin）
- `reflection/contract/ContractValidation.java` — 启动期统一校验 + 目标字段回填

探针层（19，均带 `@ModReflection`）
- probes/ModReflection.java（注解定义）
- probes/AetherworksReflection · BackpackReflection · CrabbersDelightReflection · CrockPotReflection · DistantWorldsReflection · EidolonReflection · EmbersReflection · FAReflection · FarmersDelightReflection · FarmingForBlockheadsReflection · FRReflection · GoetyReflection · ImmersalsDelightReflection · JEIReflection · MalumReflection · TLMReflection · WRReflection · YHKReflection

## 一句话总评
**本域未触碰历史崩溃坑**：19 个探针只对第三方 mod 的类名做 `Class.forName(name, false, cl)`，没有任何一处反射原版 Minecraft/Forge 的方法或字段，因此不存在 SRG 重映射导致的运行时崩溃。校验流程设计稳健（跳过 `<clinit>`、`ModList.isLoaded` 前置、版本区间过滤、可选契约静默降级）。真正的问题集中在**健壮性与诊断层面**：`isAvailable()` 只查单字段无法覆盖部分解析失败、字段/方法签名校验机制全域未启用（安全网未部署）、`MethodContract` 缺 obfuscation 处理（是历史坑的潜伏复现点）、`catch(Exception)` 漏接 `LinkageError`。无 P0；下列 P2 均为条件触发或潜伏风险。

—

## 逐条发现（按严重度）

### [P2] isAvailable() 只查单字段，部分解析失败会留下 null 字段导致下游 NPE/崩溃
> ✅ **已于 2026-07-23 在工作区修复**：`validateAll` 记录 required 契约失败所属的 probe，并在校验结束后清空该 probe 的全部已解析目标字段，确保部分解析不能让 `isAvailable()` 误返回 true。
- 文件: reflection/probes/FAReflection.java:61（同型见 Aetherworks:50、Goety:89、YHK:98、Malum:40 等全部探针）
- 维度: Null 与异常 / 健壮性
- 现象: 每个探针的 `isAvailable()` 只判断**一个**字段（通常是 BE 类）非 null，例如 `hephaestusForgeBEClass != null`。但一个探针 required 契约会回填十几个字段（FA 有 16 个）。`ContractValidation.validateAll()` 对 required 契约解析失败时只 `LOGGER.error` + `failed++`，并不停用模块或清空同探针其它字段。
- 风险: 目标 mod 升级后仅**部分**类被移动/改名时，被移动的那个字段保持 null，而 `isAvailable()` 检查的另一个字段已解析成功 → 返回 true → 模块照常激活 → 运行时读取 null 的 `Class<?>` 字段做反射时 NPE。若发生在方块交互/配方解析路径上会直接崩（可升级为 P1）。
- 证据: `public static boolean isAvailable() { return hephaestusForgeBEClass != null; }`；validateAll 失败分支 `failed++; LOGGER.error(...)`（ContractValidation.java:125-128）未联动禁用。

### [P2] MethodContract 无 MemberOrigin/obfuscation 处理 —— 历史 SRG 崩溃坑的潜伏复现点
> ✅ **已于 2026-07-23 在工作区修复**：`MethodContract` 新增 `MemberOrigin`，默认 MOD 保持兼容；VANILLA 方法统一通过 `ObfuscationReflectionHelper.findMethod` 解析。
- 文件: reflection/contract/ReflectionContract.java:57；reflection/contract/ContractValidation.java:109-112
- 维度: 反射安全
- 现象: `FieldContract` 有 `MemberOrigin`（MOD / VANILLA），VANILLA 走 `ObfuscationReflectionHelper.findField`（正确的 SRG 感知路径，ContractValidation.java:177-179）。但 `MethodContract` **没有** origin 字段，校验一律走 `clazz.getDeclaredMethod(mc.name(), mc.parameterTypes())`，用的是原始（deobf）方法名。
- 风险: 一旦将来有人给某契约登记一个继承自原版的方法（如 `setChanged`/`getItems`），运行时会因 SRG 重映射 `getDeclaredMethod("setChanged")` 找不到而抛异常 —— 正是记忆库里"别反射原版方法"的历史崩溃坑。当前 19 个探针**均未登记任何 MethodContract**，尚未触发（待确认：全域无 method 契约使用），但机制本身缺一道防线，是给未来埋的陷阱。
- 证据: `public record MethodContract(String name, Class<?>... parameterTypes) {}` 无 origin；ContractValidation 直接 `getDeclaredMethod`。

### [P2] 字段/方法签名校验机制全域未启用，只校验类存在 —— 宣称的安全网未部署
- 文件: reflection/contract/ReflectionContract.java:22-27（4 参便捷构造，fields/methods 传空数组）；全部 19 探针的 register()
- 维度: 反射安全 / 代码质量
- 现象: 所有探针都用 `new ReflectionContract(MOD, description, className, required)` 这个只带类名的便捷构造，`fields[]`/`methods[]` 恒为空。ContractValidation 里成熟的字段类型校验（:97-106）和精确方法签名校验（:109-112）因此**从不执行**。文档注释宣称 "Field and method contracts specify exact signatures, preventing overload mismatches"，实际全域无一处启用。
- 风险: 启动校验退化成"类是否存在"这一层。真正高风险的运行时反射（各 mod 模块里对已解析类做 `getField`/`getMethod`，如 GoetyReflection.java:36-53 定义的 `F_*`/`M_*` 名字常量所对应的运行时调用）在启动期**得不到任何签名校验**：目标 mod 改了字段类型/方法重载时，启动依旧全绿，直到运行时才炸。给了假的安全感。
- 证据: Goety 集中定义 `F_CURRENT_RITUAL_RECIPE`、`M_GET_RITUAL` 等（GoetyReflection.java:36-53）供运行时反射用，却无对应 FieldContract/MethodContract 登记。

### [P2] validateAll 的 catch(Exception) 漏接 LinkageError，与"启动不崩"设计自相矛盾
> ✅ **复核当前工作区已修复**：捕获范围已为 `Exception | LinkageError`。以下为旧基线发现。
- 文件: reflection/contract/ContractValidation.java:123
- 维度: Null 与异常
- 现象: 校验循环 `catch (Exception e)`。`Class.forName(name, false, cl)` 对缺失类抛 `ClassNotFoundException`（被接住，正常降级）。但 `false` 仅跳过初始化，**链接**仍会加载父类/接口；若目标类存在但其父类/接口缺失，链接期抛 `NoClassDefFoundError` —— 属于 `Error` 而非 `Exception`，**不被接住**。
- 风险: 整个设计意图是"任何契约失败都只记日志、绝不崩启动"（required 失败也只 error+continue）。`LinkageError` 家族逃逸会直接掀掉 `onCommonSetup()`，破坏该不变量，把一个本应优雅降级的 mod 兼容问题变成硬崩。低概率但违背设计意图。
- 证据: `} catch (Exception e) {`（未 `catch (Throwable)` 或显式处理 `LinkageError`）。

### [P3] WR 迁移契约把新类名标 required=true，旧版本 WR 上必报一次假 ERROR
- 文件: reflection/probes/WRReflection.java:32-35
- 维度: 代码质量 / 诊断噪音
- 现象: `crystalRitualBEClass` 由两条契约回填：旧名 `CrystalRitualBlockEntity`（required=false）+ 新名 `CrystalBlockEntity`（required=true），靠登记顺序让新名后写胜出。
- 风险: 在只有旧类的老版本 WR 上，旧名契约成功回填字段，但新名契约 required=true 解析失败 → `LOGGER.error` + `failed++`。功能其实正常（字段已被旧名填上），却每次启动打一条误导性 ERROR 并污染 failed 计数。类名迁移场景应两条都 optional，再靠 `isAvailable()` 兜底。
- 证据: 第二条 `register("...crystal.CrystalBlockEntity", "crystalRitualBEClass")` 默认 required=true。对照 YHK 的 `steamerPotBlockClass` 迁移用的是两条 `registerOptional`（YHKReflection.java:69-70），无此噪音，是正确写法。

### [P3] 探针字段名笔误会触发 ExceptionInInitializerError，掀掉整个校验/启动
- 文件: reflection/contract/ContractValidation.java:150-172（ensureProbeClassesLoaded）；各探针 register 的 catch 分支，如 CrabbersDelightReflection.java:25-27
- 维度: Null 与异常 / 健壮性
- 现象: 探针 static 块里 `getDeclaredField(fieldName)` 失败会抛 `RuntimeException`。该 static 块由 `ensureProbeClassesLoaded()` 主动触发；`<clinit>` 抛异常 → `ExceptionInInitializerError` 逃出 `ensureProbeClassesLoaded()` → 中断整个 `validateAll()`。
- 风险: 任一探针的字段名与实际声明对不上（纯编码笔误）都会让全 mod 在 setup 期硬崩，且 `ExceptionInInitializerError` 堆栈相对晦涩。属编译期本应发现的 dev-time 失误，fail-fast 可接受，但爆炸半径是"全部探针"而非单个。
- 证据: `throw new RuntimeException("... field not found: " + fieldName, e);`

### [P3] 回填的 public static Class 字段非 volatile，跨线程可见性依赖生命周期屏障
- 文件: reflection/probes/AetherworksReflection.java:12-20（同型见所有探针）；ContractValidation.java:114-118 的 `target.set(null, clazz)`
- 维度: 并发与线程安全
- 现象: 字段声明为 `public static Class<?>`（非 volatile）。写入发生在并行 mod-loading 线程的 `onCommonSetup()`，读取发生在之后的服务端/客户端游戏线程。
- 风险: 非 volatile 静态字段的跨线程写后读，严格来说依赖外部 happens-before。实践中 Forge 生命周期阶段切换提供了同步屏障，且 `Class` 对象本身不可变，风险很低；但注释宣称的 "zero locking runtime read" 未用 volatile 显式保证可见性。待确认：依赖 Forge 并行派发 join 屏障，通常安全。

### [P3] TARGET_FIELDS 以 MOD+简单类名为 key，同 mod 内同名类会静默覆盖（潜伏）
- 文件: reflection/contract/ContractValidation.java:50,67-70,115；各探针 `description = MOD + "." + simpleName`
- 维度: 算法正确性
- 现象: `TARGET_FIELDS` 用 `description`（modId + 简单类名）做 key。若同一 mod 里两个不同包的类恰好简单名相同，第二次 `registerTarget` 会覆盖前者，两条契约随后 `TARGET_FIELDS.get(description)` 拿到同一个目标字段。
- 风险: 当前 21 文件内未发现同 mod 同简单名冲突（未触发），但这是随 mod 增长的潜伏脆弱点：一旦出现，其中一个字段将永远回填不上（对应 [P2] 部分解析 → NPE）。建议 key 用全限定类名。
- 证据: `String description = MOD + "." + className.substring(className.lastIndexOf('.') + 1);`

### [P3] BackpackReflection 的 @ModReflection modId 与实际登记契约不一致
- 文件: reflection/probes/BackpackReflection.java:7,21
- 维度: 代码质量
- 现象: 类注解 `@ModReflection(modId = SOPHISTICATED_BACKPACKS)`，static 块里却登记了一条 `BETTER_COMBAT` 的 `PlayerAttackHelper` 契约（契约内部 modId 传的是正确的 BETTER_COMBAT，功能无误）。
- 风险: 仅元数据/可读性误导，无运行时影响。`isAvailable()` 只查 `backpackBEClass`，`playerAttackHelperClass` 正确标 required=false。

—

## 附：非缺陷但需留意的观察
- **DistantWorlds 是 MCreator 生成 mod**（`net.mcreator.distantworlds.*`，含大量 `*Procedure` 类，DistantWorldsReflection.java:27-32）。MCreator 在版本间重命名 procedure/类的概率远高于手写 mod，10 条全 required=true 属高脆弱面；但均为第三方类、非原版，无 SRG 问题，且 `hasAltarContract()/hasFuelContract()` 有分级门控，设计上已考虑缺失降级，可接受。
- **契约设计整体正确的点**：`Class.forName(..., false, ...)` 跳过 `<clinit>`（避免过早初始化崩溃）、`ModList.isLoaded` 前置、min/maxVersion 区间过滤、可选契约静默 skip、运行时纯字段读无锁 —— 这些都是稳妥做法，值得保留。
- **头等好消息**：本域没有 `getMethod("setChanged")` 之类对原版方法的反射，历史崩溃坑在此包内未复发。真正做运行时 `getField`/`getMethod` 的代码在各 mod 模块（本次审计范围外），且如 [P2] 所述未被启动契约覆盖，建议后续把那些模块的运行时反射目标补成 Field/MethodContract 才能真正闭环。
