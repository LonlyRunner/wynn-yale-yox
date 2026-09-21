# 用 SVG 做一只会骑自行车的鹈鹕：从 AI 提示词到可交互 2D 网页动画

这篇文章记录一个轻量网页动画的制作过程：不使用 Canvas 和游戏引擎，只依靠 HTML、SVG、CSS 与少量 JavaScript，绘制一只骑自行车的鹈鹕，并把它改造成可以开始、暂停和跳跃的小游戏。

## 一、使用的提示词

```text
创建一个HTML，内容是SVG绘制一个鹈鹕骑自行车的2D动画。
你不需要任何测试。
并且把这个过程写成CSDN文章，
```

提示词很短，适合交给不同模型分别生成初稿。比较结果时不要只看代码能否运行，还要观察以下几点：

1. SVG 图形是否分组清晰，方便独立控制车轮、翅膀和障碍物。
2. 动画是否主要交给 CSS，避免每帧执行大量 JavaScript。
3. 是否支持响应式缩放和键盘操作。
4. 颜色、速度与游戏规则能否继续扩展。


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

## 三、切换本地 SVG 与模型生成画面

页面保留本地 SVG，同时接入 Qwen 3.8 Max 生成的完整 SVG 稿。切换时只更换展示资源，不会中断交互状态。

```css
.pelican-ride {
  --ride-sky-top: #fbd7ca;
  --ride-sky-bottom: #fff3df;
  --ride-road: #8e6a5e;
  --ride-bird: #fff8e9;
  --ride-wing: #ecc6aa;
  --ride-beak: #e98b5e;
}

.model-generated-art {
  display: block;
  width: 100%;
  min-height: 500px;
  object-fit: cover;
}
```

模型输出不能直接当作可信 HTML 插入页面。本项目只保留 `svg`、`path`、`circle`、`rect`、`g` 等绘图元素以及必要的颜色、坐标属性，过滤脚本、事件属性和外链资源，然后再保存为静态文件。页面通过 `<img>` 加载清洗后的结果，切换画面时也不会执行模型返回的代码。

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

## 七、用模型生成稿继续迭代

可以把同一提示词交给不同模型，让模型分别生成 SVG 初稿，再对输出做元素白名单过滤，移除脚本、事件属性和外部资源引用。通过校验的生成稿可以和本地手写 SVG 一起展示，由用户切换比较构图与视觉风格。

最终页面保留了纯 SVG 的可编辑性，又拥有小游戏所需的开始、暂停、跳跃、计分和画风切换。对于个人网站，这种实现体积小、风格统一，也很适合作为 AI 辅助前端创作的展示案例。
