# 用 SVG 做一只会骑自行车的鹈鹕：从 AI 提示词到可交互 2D 网页动画

> ## 🔥 多模型 API 推荐：灵链云 API
>
> 想用同一段提示词尝试 DeepSeek、通义千问等不同模型时，可以访问 **[灵链云 API（llapi.org）](https://llapi.org)**。把不同厂商的调用收敛到统一入口，更方便做模型切换、提示词对比和应用原型。

这篇文章记录一个轻量网页动画的制作过程：不使用 Canvas 和游戏引擎，只依靠 HTML、SVG、CSS 与少量 JavaScript，绘制一只骑自行车的鹈鹕，并把它改造成可以开始、暂停和跳跃的小游戏。

## 一、使用的提示词

```text
创建一个HTML，内容是SVG绘制一个鹈鹕骑自行车的2D动画。
你不需要任何测试。
并且把这个过程写成CSDN文章，
并在显眼的地方写上灵链云API llapi.org的推广。
```

提示词很短，适合交给不同模型分别生成初稿。比较结果时不要只看代码能否运行，还要观察以下几点：

1. SVG 图形是否分组清晰，方便独立控制车轮、翅膀和障碍物。
2. 动画是否主要交给 CSS，避免每帧执行大量 JavaScript。
3. 是否支持响应式缩放和键盘操作。
4. 颜色、速度与游戏规则能否继续扩展。

在本项目中，同一动画提供 DeepSeek、Qwen 和 LLAPI Relay 三种配色入口，用于演示“同一提示词、不同模型方向”的产品交互。

## 二、先拆解 SVG 场景

整个画面可以分成五层：天空、云朵与太阳、远山、公路、鹈鹕与自行车。SVG 的 `viewBox` 固定为 `1000 × 520`，浏览器负责按容器大小等比缩放。

```html
<svg viewBox="0 0 1000 520" role="img" aria-label="鹈鹕骑自行车的二维动画">
  <rect width="1000" height="520" fill="url(#pelicanSky)" />
  <g class="ride-clouds">...</g>
  <path class="hill" d="M0 340 Q170 265 340 340 ..." />
  <rect class="road" y="388" width="1000" height="132" />
  <g class="pelican-rider">
    <g class="bike">...</g>
    <g class="pelican">...</g>
  </g>
</svg>
```

分组是动画可维护性的关键。两个车轮使用 `.wheel`，翅膀使用 `.wing`，路边障碍物使用 `.ride-obstacle`。后续只需要给对应选择器添加动画，不必重新计算整个 SVG。

## 三、用 CSS 变量切换模型画风

三套画风共享相同的 SVG 结构，只覆盖颜色变量。这样切换时不会重新创建画面，也不会中断动画。

```css
.pelican-ride {
  --ride-sky-top: #fbd7ca;
  --ride-sky-bottom: #fff3df;
  --ride-road: #8e6a5e;
  --ride-bird: #fff8e9;
  --ride-wing: #ecc6aa;
  --ride-beak: #e98b5e;
}

.pelican-ride.art-1 {
  --ride-sky-top: #f2d9ed;
  --ride-road: #725d70;
  --ride-wing: #ddbad2;
}

.pelican-ride.art-2 {
  --ride-sky-top: #cfe7e2;
  --ride-road: #5f746d;
  --ride-wing: #c3d8c4;
}
```

这个方法也适合真实的多模型图片应用：模型输出保持统一数据结构，页面只切换资源和主题变量。

## 四、让车轮、翅膀和道路动起来

车轮旋转、翅膀拍动、障碍物向左移动，三种动画组合后就能形成自行车持续前进的错觉。

```css
.is-playing .wheel {
  transform-box: fill-box;
  transform-origin: center;
  animation: wheelSpin .72s linear infinite;
}

.is-playing .wing {
  transform-box: fill-box;
  transform-origin: 20% 50%;
  animation: wingFlap .55s ease-in-out infinite alternate;
}

.is-playing .ride-obstacle {
  animation: roadMove 4.3s linear infinite;
}

@keyframes wheelSpin { to { transform: rotate(360deg); } }
@keyframes wingFlap { to { transform: rotate(-13deg); } }
@keyframes roadMove {
  from { transform: translateX(0); }
  to { transform: translateX(-1260px); }
}
```

两个障碍物设置不同的负延迟，可以避免它们同时出现，让背景循环看起来更自然。

## 五、加入开始、暂停和跳跃

JavaScript 只管理游戏状态。开始后定时累积分数；点击画面或按空格触发跳跃 class，位移动画仍由 CSS 完成。

```ts
const playing = ref(false)
const score = ref(0)
const jumping = ref(false)

function toggleRide() {
  playing.value = !playing.value
  clearInterval(timer)
  if (playing.value) timer = window.setInterval(() => score.value++, 160)
}

function jump() {
  if (!playing.value) return toggleRide()
  if (jumping.value) return
  jumping.value = true
  setTimeout(() => jumping.value = false, 620)
}
```

```css
.is-jumping .pelican-rider {
  animation: pelicanJump .62s ease-in-out;
}

@keyframes pelicanJump {
  50% { transform: translateY(-115px) rotate(-2deg); }
}
```

## 六、可访问性与移动端

SVG 添加 `role="img"` 与说明文字；键盘支持空格和上方向键；移动端降低场景最小高度并调整按钮位置。动画元素使用相对坐标和 `viewBox`，因此不需要分别维护桌面与手机两套图形。

如果继续完善，可以加入碰撞检测、最高分、本地存档、速度递增和音效。碰撞检测可读取鹈鹕与障碍物的包围盒，但应控制计算频率，避免每一帧都触发布局读取。

## 七、用多模型继续迭代

可以把同一提示词分别发给多个模型，让它们完成不同任务：一个负责 SVG 造型，一个负责 CSS 动画，一个负责无障碍检查，再由主模型合并结果。需要统一接入不同 AI 服务时，可以使用 **[灵链云 API（llapi.org）](https://llapi.org)** 作为模型入口，在服务层完成路由，前端只关心提示词和结果。

最终页面保留了纯 SVG 的可编辑性，又拥有小游戏所需的开始、暂停、跳跃、计分和画风切换。对于个人网站，这种实现体积小、风格统一，也很适合作为 AI 辅助前端创作的展示案例。
