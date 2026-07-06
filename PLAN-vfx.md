# 高能爆发 VFX — 网络传输视觉特效

## 目标

把物品在 RS 网络和绑定机器之间传输的过程，从"不可见的物流操作"升级为**有科技魔法感的视觉演出**。核心设计理念：

- **无抖动** — 不靠屏幕震动这种"廉价手段"掩盖视觉贫乏，玩家的视线永远锁定在机器上
- **动量感** — 起步极速加速、命中瞬间停顿 + 模型压扁拉伸，让能量流有"物理肉感"
- **体积感** — 三层蒙版叠加（光晕 → 核心流 → 碎片），不是平贴的线
- **叙事感** — 四阶段生命周期：蓄能 → 畸变飞行 → 物理撞击 → 余韵消散
- **工业感** — 光束的粗细与混乱程度随任务规模动态变化，玩家目视即可判断当前网络调度压力

真正的高阶视觉体验（《Create》《Factorio》后期）靠的是物理反馈、能量流态和材质爆发，不靠镜头的取巧。

---

## 一、四阶段生命周期 (The VFX Pipeline)

```
CHARGING (0.3s)  →  FLYING (0.3-0.6s)  →  IMPACT (3 ticks)  →  DONE
   蓄能吸附             畸变飞行              物理撞击              移除
```

### Phase 1: 蓄能 (Charging) — 0.3s

不要一点击合成就瞬间飞出。在物品飞出前，**目标机器周围产生"能量吸附"效果**。

- 地面上的粒子向机器中心汇聚（目标点锁定机器中心，非随机运动）
- 粒子缩小、加速、聚集，形成一个小的致密能量球
- 能量球亮度从 0 → 1.0，颜色从模组主题色过渡到亮白
- 0.3s 结束时达到临界密度——光束射出

```java
void tickCharging(BeamState beam) {
    float progress = beam.phaseProgress(); // 0.0 → 1.0 over 0.3s
    for (ChargeParticle p : beam.chargeParticles) {
        Vec3 toTarget = beam.targetPos.subtract(p.pos).normalize();
        float speed = 0.02f + progress * 0.15f;
        p.pos = p.pos.add(toTarget.scale(speed));
        p.scale = 1.0f - progress * 0.7f;
        p.alpha = 0.3f + progress * 0.7f;
    }
}
```

蓄能阶段不发射光束——只有粒子汇聚。这给了玩家 0.3s 的"预期"，让后续的光束射出更有冲击力。

### Phase 2: 畸变飞行 (Turbulent Flight) — 0.3~0.6s

给飞行路径加上 **Perlin Noise（柏林噪声）**——不是平滑贝塞尔，而是"能量不稳定"的抖动和扭曲。

```java
public Vec3 sampleTurbulent(float t, BeamState beam) {
    Vec3 bezier = sampleBezier(t, beam.source, beam.midPoint, beam.target);
    float noiseAmp = 0.12f * (float) Math.sin(t * Math.PI); // 中段最剧烈
    double nx = ImprovedNoise.noise(t * 3.0 + beam.seed, beam.seed, 0) * noiseAmp;
    double ny = ImprovedNoise.noise(t * 3.0 + beam.seed, 0, beam.seed) * noiseAmp;
    double nz = ImprovedNoise.noise(0, t * 3.0 + beam.seed, beam.seed) * noiseAmp;
    return bezier.add(nx, ny, nz);
}
```

**加速度设计**：前 10 帧极速启动，t 的映射不是线性的——

```java
float easedT = t < 0.15f
    ? t * t * 22f                        // 前 15%: 二次加速
    : 1.0f - (float) Math.pow(1 - t, 3); // 后 85%: 三次缓出
```

### Phase 3: 物理撞击 (Impact) — 3 ticks

这是整套 VFX 的灵魂。不靠屏幕抖动，靠三个机制传达"能量砸进机器"的重量感：

#### 3.1 压扁与拉伸 (Squash & Stretch)

物品模型在撞击瞬间经历 2D 动画式的物理变形——不是简单地消失，而是"撞上去 → 压扁 → 反弹拉长"：

```
Tick 0 (接触帧): Y 轴压扁  scale(1.5, 0.5, 1.5)  ← 横向扩张，纵向压缩
Tick 1 (反弹帧): Y 轴拉长  scale(0.7, 1.4, 0.7)  ← 能量反弹，模型纵向弹起
Tick 2 (恢复帧): 回弹      scale(0.9, 1.1, 0.9)  ← 弹性衰减，趋于正常
Tick 3        : 销毁，进入 DONE
```

```java
void renderSquashAndStretch(PoseStack poseStack, BeamState beam, Vec3 itemPos) {
    int tick = beam.impactTick; // 0, 1, 2
    float sx, sy, sz;
    switch (tick) {
        case 0 -> { sx = 1.5f; sy = 0.5f; sz = 1.5f; } // 压扁
        case 1 -> { sx = 0.7f; sy = 1.4f; sz = 0.7f; } // 反弹拉长
        default ->{ sx = 0.9f; sy = 1.1f; sz = 0.9f; } // 弹性恢复
    }
    poseStack.translate(itemPos.x, itemPos.y, itemPos.z);
    poseStack.scale(sx, sy, sz);
    // 渲染物品模型...
}
```

玩家看到的是物品真的"撞"在机器表面、发生弹性形变——这是动作游戏打击感的基石，但完全不需要动镜头。

#### 3.2 环境光爆发 (World-Space Bloom)

撞击瞬间，目标机器被强光"吞没"——向目标方块叠加一个 emissive 发光球体，亮度拉到 `MAX_LIGHT (15728880)`：

```java
void renderImpactBloom(PoseStack poseStack, BlockPos targetPos, int tick, int modColor) {
    float progress = tick / 3.0f; // 0.0 → 1.0 over 3 ticks
    float radius = 0.5f + progress * 1.2f;  // 0.5 → 1.7 格，不断变大
    float alpha = 1.0f - progress * 0.7f;   // 1.0 → 0.3，逐渐透明

    // 发光球体 — 使用 entityTranslucent + 强制 MAX_LIGHT
    Vec3 center = Vec3.atCenterOf(targetPos).add(0, 0.5, 0);
    renderEmissiveSphere(poseStack, center, radius, alpha, modColor, MAX_LIGHT);
}
```

**实现细节**：
- 渲染一个半径不断变大的半透明发光球体，颜色取产物/模组主题色
- 使用 `LightTexture.pack(15, 15)` (= 15728880) 强制最大亮度，无视场景光照
- 球体随 tick 推移逐渐透明并缩小回 0 = "光晕扩散后消散"
- 这种"照亮机器"的效果，视觉冲击力远大于屏幕抖动，且完全不影响操作

#### 3.3 命中停顿 (Impact Freeze)

在撞击的第一帧（Tick 0），光束渲染冻结 1 tick——光效停在命中位置，不做任何位置更新。这一帧的"视觉留白"让玩家的大脑注册"撞击发生了"：

```java
if (beam.phase == Phase.IMPACT && beam.impactTick == 0) {
    beam.frozen = true; // 位置冻结，不更新 beam.getPosition()
}
```

### Phase 4: DONE

3 tick 撞击动画结束后，状态机进入 DONE，VFXManager 在下一轮 tick 中移除该 beam。

---

## 二、基于速度的拖尾密度 (Velocity-based Density) — 超载模式

拖尾的密度不应是固定值，而应与**任务规模**动态绑定。玩家通过观察"光束的粗细与混乱程度"，直观判断当前 RS 网络在进行多大规模的调度。

### 2.1 阈值分级

| 任务数 (ops) | 模式 | 粒子密度 | 湍流幅度 | 螺旋半径 | 额外效果 |
|:------------:|:----:|:--------:|:--------:|:--------:|----------|
| 1-8 | 标准 | 1× | 1× | 0.18 | — |
| 9-16 | 高载 | 1.5× | 1.3× | 0.22 | 碎片数量 +50% |
| 17+ | 超载 (Overload) | 2× | 1.8× | 0.28 | 碎片翻倍 + 随机电弧闪烁 |

### 2.2 实现

```java
// BeamState 在构造时根据 ops 计算密度系数
public BeamState(Vec3 source, Vec3 target, int modColor, List<ItemStack> items, int totalOps) {
    this.densityMultiplier = totalOps <= 8  ? 1.0f
                           : totalOps <= 16 ? 1.5f
                           : 2.0f; // Overload
    this.turbulenceMultiplier = totalOps <= 8  ? 1.0f
                              : totalOps <= 16 ? 1.3f
                              : 1.8f;
    this.spiralRadius = 0.18f * this.densityMultiplier;
    this.debrisCount = (int) (6 * this.densityMultiplier);
}
```

超载模式下螺旋半径从 0.18 扩到 0.28，加上噪声幅度 1.8×，光束呈现出"能量过载、濒临失控"的狂暴姿态——这是一种**工业级的视觉反馈**，比任何数字 UI 都直观。

---

## 三、多层蒙版叠加 (Layered Masking)

《Create》的质感来自于从不只渲染一层特效。三层叠加产生"体积感"：

| 层级 | 内容 | 透明度 | 颜色 | 范围 |
|------|------|:------:|------|:----:|
| **底层：泛光光晕** | 大尺寸柔光 quad，始终面向相机 | 0.15-0.25 | 模组主题色（深） | 半径 1.5 格 |
| **中间层：核心数据流** | 双螺旋光束 + 物品实体 | 0.9-1.0 | 纯白 → 主题色渐变 | 半径 0.18-0.28 格 |
| **上层：奥术碎片** | 不规则发光点，模拟能量泄露 | 0.4-0.7 | 亮白/金色 | 随机偏移 0.3 格 |

```java
void renderBeam(PoseStack poseStack, BeamState beam, float partialTicks) {
    renderGlowAura(poseStack, beam, partialTicks);     // 1. 底层光晕
    renderSpirals(poseStack, beam, partialTicks);      // 2. 核心螺旋 + 物品
    renderArcaneDebris(poseStack, beam, partialTicks); // 3. 能量泄露碎片
}
```

**底层光晕**：billboard quad + 径向渐变纹理（中心亮、边缘透明）+ 加色混合 + 呼吸感正弦微调 alpha。

**上层碎片**：6-10 个发光点沿螺旋线分布，每个有独立的相位偏移、径向抖动和随机微动。

---

## 四、动态材质扭曲 (Flowing Energy Stream)

在绘制螺旋线段时，动态偏移 UV 坐标的 V 分量，根据 `GameTime` 让纹理沿光束方向滚动，产生光流在管子里"奔涌"的效果：

```java
float vOffset = (beam.elapsedTicks * 0.5f + segmentIndex * 0.1f) % 1.0f;
buffer.vertex(mat, x0, y0, z0).color(r, g, b, a).uv(u0, v0 + vOffset).uv2(light).normal(nx, ny, nz).endVertex();
```

v1 使用 CPU 侧 UV 偏移 + MC 内置 `POSITION_COLOR_TEX_LIGHTMAP` shader，不做自定义 GLSL。效果一致，实现简单。v2 再考虑下移到 GPU。

---

## 五、程序化粒子系统 (Custom Particle System)

### 5.1 为什么不用原版 ParticleEngine

| 问题 | 说明 |
|------|------|
| 硬编码生命周期 | 粒子参数在 spawn 时固定，无法随 beam 进度动态调整 |
| GC 压力 | 每 tick 大量 `Particle` 对象创建/销毁 |
| 无法做复杂数学变换 | 不能让粒子在飞行中 scale: 1.0 → 0.0 → 2.0 → 0.0 |

### 5.2 自定义轻量粒子

```java
public class VFXParticle {
    Vec3 pos, prevPos;
    Vec3 velocity;
    float scale, prevScale;
    float alpha;
    float lifetime, age;
    int color;
    boolean alive = true;

    public void tick() {
        prevPos = pos;
        prevScale = scale;
        age++;
        pos = pos.add(velocity);
        float t = age / lifetime;
        scale = (float) (Math.sin(t * Math.PI * 3) * (1 - t));
        alpha = 1.0f - t;
        if (age >= lifetime) alive = false;
    }
}
```

### 5.3 多束干涉火花 (Interference Sparks)

负载均衡多机并行时，多道光束在空间中交叉，在交叉点产生微小粒子火花：

```java
void detectInterference(List<BeamState> activeBeams) {
    for (int i = 0; i < activeBeams.size(); i++) {
        for (int j = i + 1; j < activeBeams.size(); j++) {
            Vec3 cross = findClosestApproach(activeBeams.get(i), activeBeams.get(j));
            if (cross != null) {
                spawnSparks(cross, 0xFFD700, 3 + random.nextInt(3));
            }
        }
    }
}
```

沿两束 beam 以 0.05 步长采样，距离 < 0.3 格即视为交叉点。检测频率每 3 tick 一次，避免每帧 O(n²) 开销。

---

## 六、加色混合 — Additive Glow

"高能感"的本质是**加色混合**——光效重叠处亮度叠加，接近纯白。

```java
public static final RenderType GLOW = RenderType.create(
    "rsi_glow",
    DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP,
    VertexFormat.Mode.QUADS, 256,
    false, true,
    RenderType.CompositeState.builder()
        .setShaderState(RenderStateShard.POSITION_COLOR_TEX_LIGHTMAP_SHADER)
        .setLightmapState(RenderStateShard.LIGHTMAP)
        .setTransparencyState(RenderStateShard.ADDITIVE_TRANSPARENCY)
        .setWriteMaskState(RenderStateShard.COLOR_WRITE)
        .setCullState(RenderStateShard.NO_CULL)
        .createCompositeState(false)
);
```

双螺旋缠绕最紧密处 + 三层蒙版重叠处自然增亮，形成"能量压缩"的视觉暗示。`NO_DEPTH_TEST` 保证远处可见，需配合 32 格渲染距离限制。

---

## 七、特效指令流架构 (VFX Instruction Stream)

指令式渲染，非状态式渲染。状态机精确分离每个阶段：

```java
public enum Phase { CHARGING, FLYING, IMPACT, DONE }

public class VFXManager {
    private static final List<BeamState> activeBeams = new ArrayList<>();
    private static final List<VFXParticle> particles = new ArrayList<>();

    public static void playCraftBeam(Vec3 source, Vec3 target, int modColor,
                                      List<ItemStack> items, int totalOps) {
        if (activeBeams.size() >= MAX_BEAMS) {
            activeBeams.get(0).forceImpact(); // 最旧的加速结束
        }
        BeamState beam = new BeamState(source, target, modColor, items, totalOps);
        beam.phase = Phase.CHARGING;
        activeBeams.add(beam);
    }

    public static void onClientTick() {
        for (BeamState beam : activeBeams) {
            switch (beam.phase) {
                case CHARGING -> tickCharging(beam);
                case FLYING   -> tickFlying(beam);
                case IMPACT   -> tickImpact(beam);
            }
        }
        for (VFXParticle p : particles) p.tick();
        detectInterference(activeBeams);
        activeBeams.removeIf(b -> b.phase == Phase.DONE);
        particles.removeIf(p -> !p.alive);
    }

    public static void onWorldRenderLast(PoseStack poseStack, Matrix4f proj, float pt) {
        for (BeamState beam : activeBeams) {
            switch (beam.phase) {
                case CHARGING -> renderChargingParticles(poseStack, beam, pt);
                case FLYING   -> renderBeam(poseStack, beam, pt);
                case IMPACT   -> {
                    renderSquashAndStretch(poseStack, beam, pt);
                    renderImpactBloom(poseStack, beam, pt);
                }
            }
        }
        for (VFXParticle p : particles) renderParticle(poseStack, p, pt);
    }
}
```

---

## 八、触发场景 + 对应 VFX

| 场景 | VFX 事件 | 蓄能位置 | 飞行方向 | 冲击效果 |
|------|----------|:--------:|:--------:|----------|
| 合成链下发材料到机器 | 完整四阶段 | 控制器上方 | 控→机 | Squash & Stretch + Bloom 光球 |
| 产物从机器回收入 RS | 反向光束 | 机器上方 | 机→控 | 控制器处弱 Bloom（半透明） |
| 容器一键传输 | 容器上方漩涡 | 容器 | 吸入式螺旋 | 无冲击，粒子向内坍缩 |
| 合成链全部完成 | 全部机器同时冲击 | — | — | 每台机器金色 Bloom cascade |
| 合成链失败 | 红色闪烁 | 机器 | — | 红色 Bloom（不扩展，仅闪 0.2s） |
| 多机并行（负载均衡） | 多束并行 + 干涉火花 | 各机器各自蓄能 | 控→各机 | 各机冲击时间微偏移 cascade |
| 大负载 (ops > 16) | 超载模式 | 蓄能粒子密度翻倍 | 螺旋更粗更狂暴 | Bloom 半径增大 30% |

---

## 九、性能约束

| 约束 | 值 | 原因 |
|------|-----|------|
| 最大渲染距离 | 32 格 | 超出看不到，省 GPU |
| 同时活跃 beam 数 | ≤ 8 | 超过时最旧的 forceImpact() |
| 蓄能阶段 | 0.3s (6 tick) | 够产生预期，不拖沓 |
| 飞行阶段 | 0.3~0.6s（距离相关） | 远距更长，但不超 0.6s |
| 撞击阶段 | 3 tick | 压扁→反弹→恢复，节奏紧凑 |
| 命中停顿 | 1 tick | 刚好让大脑注册"撞击发生了" |
| 自定义粒子上限 | 200 | 超出时删最旧的 |
| 干涉检测频率 | 每 3 tick | 不需要每 tick 检测交叉 |
| 圆环面片顶点 | 32 边形 | 32 格内人眼分不出更高精度 |
| 不用原版 ParticleEngine | 全部自管粒子 + quad | 避开 GC 分配 + 硬编码限制 |
| Bloom 球体复杂度 | 16 面 icosphere | 光球对精度不敏感 |

---

## 十、文件清单

### 新增（8 个）

| 文件 | 职责 |
|------|------|
| `client/render/BeamState.java` | 状态机数据模型（Phase 枚举、源/目标/中点、物品列表、modColor、seed、densityMultiplier、impactTick、frozen） |
| `client/render/BeamRenderer.java` | 三层蒙版（光晕 + 双螺旋 + 碎片）+ 动态 UV + 物品飞行 + Squash & Stretch |
| `client/render/ImpactRenderer.java` | 撞击特效：Squash & Stretch 模型变形 + World-Space Bloom 发光球体 + emissive lightmap 覆盖 |
| `client/render/RSIVFXRenderTypes.java` | 自定义 RenderType（GLOW 加色混合 / emissive translucent） |
| `client/render/VFXManager.java` | 指令流架构：状态机 tick、渲染调度、距离裁剪、数量上限、干涉检测、超载模式切换 |
| `client/render/VFXParticle.java` | 轻量自定义粒子（pos/velocity/scale 曲线/alpha/color） |
| `client/render/ImprovedNoise.java` | Perlin Noise 实现（畸变飞行路径扰动） |
| `network/VFXSyncPacket.java` | S→C 网络包（事件类型、源/目标坐标、modColor、物品 ID 列表、totalOps） |

### 修改（2 个）

| 文件 | 改动 |
|------|------|
| `RSIntegrationMod.java` | 注册客户端 tick → `VFXManager.onClientTick()`；注册 `WorldRenderLast` → 渲染 beam + 粒子 + bloom |
| `AsyncCraftManager.java` | 链提交/完成/失败时发 `VFXSyncPacket`（携带 totalOps 用于超载判定） |

---

## 十一、配置项

```toml
[client.vfx]
enableBeamVFX = true              # 光束传输特效（蓄能 + 飞行 + 撞击）
enableImpactBloom = true          # 撞击环境光爆发（World-Space Bloom）
enableInterferenceSparks = true   # 多束干涉火花
enableOverloadMode = true         # 超载模式（大负载时粒子密度/湍流加倍）
beamRenderDistance = 32           # 光束最大渲染距离（格）
maxActiveBeams = 8                # 同时活跃光束数上限
maxParticles = 200                # 自定义粒子数上限
```

全部为客户端配置，不同步到服务器。注意：**没有** `enableCameraShake`、`enableFOVBounce`——这套 VFX 系统不依赖任何镜头操作。

---

## 十二、实施步骤

### Step 1: 基础类型 + 渲染管线（2h）
1. `VFXParticle.java` — 自定义粒子类
2. `ImprovedNoise.java` — Perlin Noise
3. `BeamState.java` — 状态机数据模型（Phase 枚举 + densityMultiplier + impactTick）
4. `RSIVFXRenderTypes.java` — GLOW + emissive translucent RenderType
5. `BeamRenderer.java` — 三层蒙版 + 动态 UV + 物品飞行 + 超载模式变体
6. `ImpactRenderer.java` — Squash & Stretch + Bloom 光球
7. `VFXManager.java` — 指令流架构

### Step 2: 网络协议 + 触发集成（30min）
8. `VFXSyncPacket.java`
9. `AsyncCraftManager` 发包（携带 totalOps）
10. 客户端接收 → VFXManager 入口

### Step 3: 多束干涉 + 超载联动（30min）
11. `detectInterference()` + 火花生成
12. 超载模式 densityMultiplier 实际渲染验证

### Step 4: 配置 + 调参（30min）
13. 客户端 config
14. 实际游戏验证：蓄能节奏、畸变幅度、Squash & Stretch 弹性手感、Bloom 光球亮度、超载模式视觉差异

---

## 十三、不做的

- 不做屏幕震动（刻意排除——靠物理反馈和光影传达力量）
- 不做 FOV 弹跳（刻意排除——不操纵玩家视野）
- 不做"传输中物品可被右键捡起"（破坏自动化语义）
- 不做流体传输特效（v1 只做物品）
- 不做自定义 GLSL shader（v1 用 CPU UV 偏移 + MC 内置 shader）
- 不做跨维度 beam 渲染（跨维度的物品传输本身不可见）
- 不把特效参数写进服务端配置（全客户端侧）
