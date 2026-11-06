<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'

// 首页背景的 three.js 场景：中央内核（AgentOS 底座）、三条轨道上的 Agent、
// 内核与 Agent 之间往返的工具调用光点、星空与极坐标网格。
// 固定铺满视口，随滚动后退并渐隐。three.js 只在浏览器端按需加载，不影响 VitePress 的 SSR。
const host = ref(null)
const failed = ref(false)
let cleanup = null
let disposed = false

const C = {
  bg: 0x050816,
  core: 0xeafcff,
  teal: 0x2dd4bf,
  sky: 0x38bdf8,
  indigo: 0x818cf8,
  amber: 0xfbbf24,
  orange: 0xf97316
}

onMounted(async () => {
  const el = host.value
  if (!el) return

  const THREE = await import('three')
  if (disposed || !host.value) return
  const { EffectComposer } = await import('three/addons/postprocessing/EffectComposer.js')
  if (disposed || !host.value) return
  const { RenderPass } = await import('three/addons/postprocessing/RenderPass.js')
  const { UnrealBloomPass } = await import('three/addons/postprocessing/UnrealBloomPass.js')
  if (disposed || !host.value) return
  const { OutputPass } = await import('three/addons/postprocessing/OutputPass.js')
  if (disposed || !host.value) return

  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  const small = window.innerWidth < 768

  let renderer
  try {
    renderer = new THREE.WebGLRenderer({ antialias: true, powerPreference: 'high-performance' })
  } catch (e) {
    failed.value = true // 不支持 WebGL 时退回 CSS 渐变背景
    return
  }
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, small ? 1.5 : 2))
  renderer.setSize(window.innerWidth, window.innerHeight)
  renderer.setClearColor(C.bg, 1)
  renderer.toneMapping = THREE.ACESFilmicToneMapping
  el.appendChild(renderer.domElement)

  const scene = new THREE.Scene()
  scene.fog = new THREE.FogExp2(C.bg, 0.018)
  const camera = new THREE.PerspectiveCamera(50, window.innerWidth / window.innerHeight, 0.1, 400)
  camera.position.set(0, 0, 26)

  const disposables = []
  const track = (o) => (disposables.push(o), o)

  // 整个场景挂在 root 上：鼠标视差、滚动位移都作用在 root
  const root = new THREE.Group()
  scene.add(root)

  // ---------- 内核：发光核心 + 线框外壳 + 六边形环 ----------
  const kernel = new THREE.Group()
  root.add(kernel)

  const coreMat = track(new THREE.MeshBasicMaterial({ color: C.core }))
  const core = new THREE.Mesh(track(new THREE.IcosahedronGeometry(1.15, 3)), coreMat)
  kernel.add(core)

  const haloMat = track(new THREE.MeshBasicMaterial({
    color: C.teal, transparent: true, opacity: 0.12, depthWrite: false
  }))
  const halo = new THREE.Mesh(track(new THREE.SphereGeometry(2.1, 32, 32)), haloMat)
  kernel.add(halo)

  const shellMat = track(new THREE.MeshBasicMaterial({
    color: C.sky, wireframe: true, transparent: true, opacity: 0.35
  }))
  const shell = new THREE.Mesh(track(new THREE.IcosahedronGeometry(2.6, 1)), shellMat)
  kernel.add(shell)

  const hexMat = track(new THREE.LineBasicMaterial({ color: C.core, transparent: true, opacity: 0.85 }))
  const hexPts = []
  for (let i = 0; i <= 6; i++) {
    const a = (i / 6) * Math.PI * 2 + Math.PI / 6
    hexPts.push(new THREE.Vector3(Math.cos(a) * 3.4, Math.sin(a) * 3.4, 0))
  }
  const hex = new THREE.Line(track(new THREE.BufferGeometry().setFromPoints(hexPts)), hexMat)
  kernel.add(hex)
  const hex2 = hex.clone()
  hex2.scale.setScalar(1.18)
  hex2.material = track(hexMat.clone())
  hex2.material.opacity = 0.3
  kernel.add(hex2)

  // ---------- 三条轨道与轨道上的 Agent ----------
  const orbitDefs = [
    { r: 6.2, tilt: [1.15, 0.0, 0.2], speed: 0.22, count: small ? 4 : 6, color: C.amber },
    { r: 8.6, tilt: [1.35, 0.6, -0.4], speed: -0.15, count: small ? 5 : 8, color: C.orange },
    { r: 11.2, tilt: [1.05, -0.5, 0.6], speed: 0.1, count: small ? 6 : 10, color: C.amber }
  ]
  const agentGeo = track(new THREE.SphereGeometry(0.26, 20, 20))
  const agentGlowGeo = track(new THREE.SphereGeometry(0.62, 16, 16))
  const orbits = []
  const agents = []

  orbitDefs.forEach((d, oi) => {
    const g = new THREE.Group()
    g.rotation.set(d.tilt[0], d.tilt[1], d.tilt[2])
    root.add(g)

    const ringMat = track(new THREE.MeshBasicMaterial({
      color: oi === 1 ? C.indigo : C.sky, transparent: true, opacity: 0.22, depthWrite: false
    }))
    g.add(new THREE.Mesh(track(new THREE.TorusGeometry(d.r, 0.018, 8, 220)), ringMat))

    const mat = track(new THREE.MeshBasicMaterial({ color: d.color }))
    const glowMat = track(new THREE.MeshBasicMaterial({
      color: d.color, transparent: true, opacity: 0.16, depthWrite: false
    }))
    for (let i = 0; i < d.count; i++) {
      const m = new THREE.Mesh(agentGeo, mat)
      m.add(new THREE.Mesh(agentGlowGeo, glowMat))
      g.add(m)
      agents.push({ mesh: m, orbit: d, phase: (i / d.count) * Math.PI * 2 + oi, pulse: 0 })
    }
    orbits.push(g)
  })

  // ---------- 工具调用光点：内核 → Agent（调用），Agent → 内核（结果） ----------
  const packetGeo = track(new THREE.SphereGeometry(0.11, 10, 10))
  const packets = []
  const _a = new THREE.Vector3()
  const _b = new THREE.Vector3()
  for (let i = 0; i < 48; i++) {
    const mat = track(new THREE.MeshBasicMaterial({ color: C.teal, transparent: true }))
    const mesh = new THREE.Mesh(packetGeo, mat)
    mesh.visible = false
    root.add(mesh)
    const lineGeo = track(new THREE.BufferGeometry().setFromPoints([new THREE.Vector3(), new THREE.Vector3()]))
    const lineMat = track(new THREE.LineBasicMaterial({ color: C.teal, transparent: true, opacity: 0 }))
    const line = new THREE.Line(lineGeo, lineMat)
    line.visible = false
    root.add(line)
    packets.push({ mesh, line, active: false, t: 0, dur: 1, agent: null, inbound: false })
  }

  // Agent 在 root 坐标系里的当前位置
  const agentPos = (agent, out) => {
    agent.mesh.getWorldPosition(out)
    return root.worldToLocal(out)
  }

  const spawn = (agent, inbound) => {
    const p = packets.find((x) => !x.active)
    if (!p) return
    p.active = true
    p.t = 0
    p.dur = 0.9 + Math.random() * 0.7
    p.agent = agent
    p.inbound = inbound
    const color = inbound ? C.amber : C.teal
    p.mesh.material.color.setHex(color)
    p.line.material.color.setHex(color)
    p.mesh.visible = true
    p.line.visible = true
  }

  // ---------- 星空 ----------
  const starCount = small ? 1800 : 4200
  const starPos = new Float32Array(starCount * 3)
  const starCol = new Float32Array(starCount * 3)
  const palette = [new THREE.Color(C.sky), new THREE.Color(C.teal), new THREE.Color(0xffffff), new THREE.Color(C.indigo)]
  for (let i = 0; i < starCount; i++) {
    const r = 30 + Math.random() * 120
    const th = Math.random() * Math.PI * 2
    const ph = Math.acos(2 * Math.random() - 1)
    starPos[i * 3] = r * Math.sin(ph) * Math.cos(th)
    starPos[i * 3 + 1] = r * Math.sin(ph) * Math.sin(th)
    starPos[i * 3 + 2] = r * Math.cos(ph)
    const c = palette[i % palette.length]
    starCol[i * 3] = c.r
    starCol[i * 3 + 1] = c.g
    starCol[i * 3 + 2] = c.b
  }
  const starGeo = track(new THREE.BufferGeometry())
  starGeo.setAttribute('position', new THREE.BufferAttribute(starPos, 3))
  starGeo.setAttribute('color', new THREE.BufferAttribute(starCol, 3))

  // 圆形柔边的星点贴图
  const sprite = document.createElement('canvas')
  sprite.width = sprite.height = 64
  const sctx = sprite.getContext('2d')
  const grad = sctx.createRadialGradient(32, 32, 0, 32, 32, 32)
  grad.addColorStop(0, 'rgba(255,255,255,1)')
  grad.addColorStop(0.3, 'rgba(255,255,255,0.6)')
  grad.addColorStop(1, 'rgba(255,255,255,0)')
  sctx.fillStyle = grad
  sctx.fillRect(0, 0, 64, 64)
  const starTex = track(new THREE.CanvasTexture(sprite))

  const starMat = track(new THREE.PointsMaterial({
    size: 0.55, map: starTex, vertexColors: true, transparent: true, opacity: 0.85,
    depthWrite: false, blending: THREE.AdditiveBlending, sizeAttenuation: true
  }))
  const stars = new THREE.Points(starGeo, starMat)
  scene.add(stars)

  // ---------- 底部极坐标网格 ----------
  const grid = new THREE.PolarGridHelper(36, 24, 12, 96, C.sky, C.sky)
  grid.position.y = -10
  grid.material.transparent = true
  grid.material.opacity = 0.08
  track(grid.geometry)
  track(grid.material)
  root.add(grid)

  // ---------- 后期：辉光 ----------
  const composer = new EffectComposer(renderer)
  composer.addPass(new RenderPass(scene, camera))
  const bloom = new UnrealBloomPass(new THREE.Vector2(window.innerWidth, window.innerHeight), 1.15, 0.6, 0.12)
  composer.addPass(bloom)
  composer.addPass(new OutputPass())

  // ---------- 交互：鼠标视差、滚动推进 ----------
  const pointer = { x: 0, y: 0 }
  const onPointer = (e) => {
    pointer.x = (e.clientX / window.innerWidth) * 2 - 1
    pointer.y = (e.clientY / window.innerHeight) * 2 - 1
  }
  let scrollP = 0
  const onScroll = () => {
    scrollP = Math.min(window.scrollY / window.innerHeight, 1.6)
  }
  // 宽屏时内核偏右，给左侧文字留出空间
  const baseX = () => (window.innerWidth >= 1100 ? 7 : 0)

  // ---------- 动画循环 ----------
  const clock = new THREE.Clock()
  let raf = 0
  let spawnTimer = 0
  let elapsed = 0

  const frame = () => {
    const dt = Math.min(clock.getDelta(), 0.05)
    elapsed += dt

    // 内核自转与呼吸
    kernel.rotation.y += dt * 0.25
    shell.rotation.x -= dt * 0.18
    hex.rotation.z += dt * 0.12
    hex2.rotation.z -= dt * 0.08
    const breath = 1 + Math.sin(elapsed * 2.2) * 0.04
    core.scale.setScalar(breath)
    halo.scale.setScalar(1 + Math.sin(elapsed * 1.4) * 0.08)

    // Agent 沿轨道运行；收到光点时短暂放大
    for (const a of agents) {
      const ang = a.phase + elapsed * a.orbit.speed
      a.mesh.position.set(Math.cos(ang) * a.orbit.r, Math.sin(ang) * a.orbit.r, 0)
      a.pulse = Math.max(0, a.pulse - dt * 2.5)
      a.mesh.scale.setScalar(1 + a.pulse * 0.9)
    }

    // 定时发起一次"工具调用"
    spawnTimer -= dt
    if (spawnTimer <= 0) {
      spawnTimer = 0.12 + Math.random() * 0.22
      spawn(agents[(Math.random() * agents.length) | 0], false)
    }

    // 光点沿内核与 Agent 之间的直线飞行
    for (const p of packets) {
      if (!p.active) continue
      p.t += dt / p.dur
      agentPos(p.agent, _b)
      _a.set(0, 0, 0)
      const from = p.inbound ? _b : _a
      const to = p.inbound ? _a : _b
      const e = p.t < 1 ? 1 - Math.pow(1 - p.t, 3) : 1
      p.mesh.position.lerpVectors(from, to, e)
      const pos = p.line.geometry.attributes.position
      pos.setXYZ(0, from.x, from.y, from.z)
      pos.setXYZ(1, p.mesh.position.x, p.mesh.position.y, p.mesh.position.z)
      pos.needsUpdate = true
      p.line.material.opacity = 0.35 * (1 - p.t)
      p.mesh.material.opacity = 1 - Math.max(0, p.t - 0.85) * 6
      if (p.t >= 1) {
        p.active = false
        p.mesh.visible = false
        p.line.visible = false
        if (p.inbound) {
          core.scale.setScalar(breath * 1.25) // 结果回到内核
        } else {
          p.agent.pulse = 1
          spawn(p.agent, true) // 工具执行完，结果返回内核
        }
      }
    }

    orbits.forEach((g, i) => (g.rotation.z += dt * 0.02 * (i % 2 ? -1 : 1)))
    stars.rotation.y += dt * 0.008
    stars.rotation.x += dt * 0.003

    // 视差 + 滚动：越往下，场景越远、越暗
    const targetX = baseX() - scrollP * 3
    root.position.x += (targetX - root.position.x) * 0.05
    root.position.y += (scrollP * 4 - root.position.y) * 0.05
    root.rotation.y += (pointer.x * 0.35 - root.rotation.y) * 0.04
    root.rotation.x += (pointer.y * 0.2 - root.rotation.x) * 0.04
    camera.position.z += (26 + scrollP * 10 - camera.position.z) * 0.05
    bloom.strength = 1.15 - Math.min(scrollP, 1) * 0.45
    renderer.domElement.style.opacity = String(1 - Math.min(scrollP, 1) * 0.55)

    composer.render()
    raf = requestAnimationFrame(frame)
  }

  const onResize = () => {
    const w = window.innerWidth
    const h = window.innerHeight
    camera.aspect = w / h
    camera.updateProjectionMatrix()
    renderer.setSize(w, h)
    composer.setSize(w, h)
    bloom.setSize(w, h)
  }

  // 页面不可见时暂停，回来时重置时钟，避免一帧跳太远
  const onVisibility = () => {
    if (document.hidden) {
      cancelAnimationFrame(raf)
    } else if (!reduceMotion) {
      clock.getDelta()
      raf = requestAnimationFrame(frame)
    }
  }

  root.position.x = baseX()
  window.addEventListener('pointermove', onPointer, { passive: true })
  window.addEventListener('scroll', onScroll, { passive: true })
  window.addEventListener('resize', onResize)
  document.addEventListener('visibilitychange', onVisibility)

  if (reduceMotion) {
    frame() // 只渲染一帧静态画面
    cancelAnimationFrame(raf)
  } else {
    raf = requestAnimationFrame(frame)
  }

  cleanup = () => {
    cancelAnimationFrame(raf)
    window.removeEventListener('pointermove', onPointer)
    window.removeEventListener('scroll', onScroll)
    window.removeEventListener('resize', onResize)
    document.removeEventListener('visibilitychange', onVisibility)
    disposables.forEach((d) => d.dispose && d.dispose())
    bloom.dispose && bloom.dispose()
    composer.dispose && composer.dispose()
    renderer.dispose()
    renderer.domElement.remove()
  }
})

onBeforeUnmount(() => {
  disposed = true
  cleanup && cleanup()
})
</script>

<template>
  <div ref="host" class="hero-scene" :class="{ fallback: failed }" aria-hidden="true"></div>
</template>

<style>
.hero-scene {
  position: fixed;
  inset: 0;
  z-index: 0;
  pointer-events: none;
}
.hero-scene canvas {
  display: block;
  width: 100% !important;
  height: 100% !important;
  transition: opacity 0.2s linear;
}
.hero-scene.fallback {
  background:
    radial-gradient(60% 50% at 70% 40%, rgba(45, 212, 191, 0.25), transparent 70%),
    radial-gradient(50% 40% at 20% 70%, rgba(129, 140, 248, 0.2), transparent 70%),
    #050816;
}
</style>
