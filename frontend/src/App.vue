<template>
  <div class="app-container">
    <header class="app-header">
      <div class="header-title">
        <div class="logo-icon">☢</div>
        <div class="title-text">
          <h1>放疗计划独立验核系统</h1>
          <p class="subtitle">RT Plan Independent Verification Workstation</p>
        </div>
      </div>
      <div class="header-status">
        <div :class="['status-indicator', connectionStatusClass]">
          <span class="status-dot"></span>
          <span>{{ connectionStatusText }}</span>
        </div>
        <div class="header-actions">
          <button class="btn btn-primary" @click="connectToServer" :disabled="isConnected">
            连接服务器
          </button>
          <button class="btn btn-danger" @click="disconnectFromServer" :disabled="!isConnected">
            断开连接
          </button>
        </div>
      </div>
    </header>

    <div class="app-body">
      <aside class="left-panel">
        <div class="panel">
          <div class="panel-header">
            <span>📁 DICOM 数据上传</span>
          </div>
          <div class="panel-body">
            <div class="upload-section">
              <label class="file-upload-label">
                <input type="file" accept=".dcm" @change="handleDoseFileUpload" :disabled="uploadingDose">
                <span v-if="uploadingDose">⏳ 上传中...</span>
                <span v-else-if="doseFileName">✅ {{ doseFileName }}</span>
                <span v-else>📤 上传 RT Dose 文件</span>
              </label>
              <p class="upload-hint">选择 RTDOSE.dcm 三维剂量分布文件</p>
            </div>

            <div class="upload-section" style="margin-top: 16px;">
              <label class="file-upload-label">
                <input type="file" accept=".dcm" @change="handleStructureFileUpload" :disabled="uploadingStructure">
                <span v-if="uploadingStructure">⏳ 上传中...</span>
                <span v-else-if="structureFileName">✅ {{ structureFileName }}</span>
                <span v-else>📤 上传 RT Structure 文件</span>
              </label>
              <p class="upload-hint">选择 RTSTRUCT.dcm 轮廓结构文件</p>
            </div>
          </div>
        </div>

        <div class="panel" style="margin-top: 16px;">
          <div class="panel-header">
            <span>⚡ 数据流控制</span>
          </div>
          <div class="panel-body">
            <div class="control-group">
              <label>切片范围</label>
              <div class="range-inputs">
                <input type="number" v-model.number="startSlice" :min="0" :max="Math.max(0, totalSlices - 1)" class="range-input">
                <span class="range-separator">—</span>
                <input type="number" v-model.number="endSlice" :min="0" :max="Math.max(0, totalSlices - 1)" class="range-input">
              </div>
            </div>

            <div class="control-group" style="margin-top: 16px;">
              <label>当前切片: {{ currentSliceIndex }} / {{ totalSlices }}</label>
              <input type="range" v-model.number="currentSliceIndex" :min="0" :max="Math.max(0, totalSlices - 1)" @input="onSliderChange">
            </div>

            <div class="button-group" style="margin-top: 16px;">
              <button class="btn btn-success" @click="startStreaming" :disabled="!isConnected || isStreaming || totalSlices === 0">
                ▶ 开始流式传输
              </button>
              <button class="btn btn-danger" @click="stopStreaming" :disabled="!isStreaming">
                ⏹ 停止传输
              </button>
            </div>
          </div>
        </div>

        <div class="panel" style="margin-top: 16px;">
          <div class="panel-header">
            <span>📊 等剂量线设置</span>
          </div>
          <div class="panel-body">
            <div class="isodose-item">
              <div class="isodose-header">
                <span class="isodose-color" style="background: var(--dose-95);"></span>
                <span>95% 处方剂量</span>
              </div>
              <label class="switch">
                <input type="checkbox" v-model="showIsodose95" @change="updateIsodoseLevels">
                <span class="slider"></span>
              </label>
            </div>

            <div class="isodose-item">
              <div class="isodose-header">
                <span class="isodose-color" style="background: var(--dose-100);"></span>
                <span>100% 处方剂量</span>
              </div>
              <label class="switch">
                <input type="checkbox" v-model="showIsodose100" @change="updateIsodoseLevels">
                <span class="slider"></span>
              </label>
            </div>

            <div class="isodose-item">
              <div class="isodose-header">
                <span class="isodose-color" style="background: var(--dose-107);"></span>
                <span>107% 处方剂量</span>
              </div>
              <label class="switch">
                <input type="checkbox" v-model="showIsodose107" @change="updateIsodoseLevels">
                <span class="slider"></span>
              </label>
            </div>
          </div>
        </div>

        <div class="panel" style="margin-top: 16px;">
          <div class="panel-header">
            <span>🔧 视图控制</span>
          </div>
          <div class="panel-body">
            <div class="control-group">
              <label>缩放: {{ (scale * 100).toFixed(0) }}%</label>
              <input type="range" v-model.number="scale" :min="0.1" :max="5" :step="0.1" @input="onScaleChange">
            </div>

            <div class="button-group" style="margin-top: 12px;">
              <button class="btn" @click="resetView">
                🔄 重置视图
              </button>
            </div>
          </div>
        </div>
      </aside>

      <main class="main-content">
        <div class="canvas-container" ref="canvasContainer">
          <canvas ref="doseCanvas" class="dose-canvas"></canvas>
          
          <div class="canvas-overlay top-left">
            <div v-if="currentSlice" class="slice-info">
              <div>切片: {{ currentSlice.sliceIndex }} / {{ currentSlice.totalSlices }}</div>
              <div>剂量网格: {{ currentSlice.columns }} × {{ currentSlice.rows }}</div>
              <div>最大剂量: {{ (currentSlice.doseMaximum * currentSlice.doseGridScaling).toFixed(2) }} Gy</div>
            </div>
          </div>

          <div class="canvas-overlay top-right">
            <div v-if="hoverDoseValue" class="dose-tooltip">
              <div>剂量值: {{ hoverDoseValue.absolute.toFixed(3) }} Gy</div>
              <div>相对值: {{ (hoverDoseValue.relative * 100).toFixed(1) }}%</div>
            </div>
          </div>

          <div v-if="error" class="error-overlay">
            <div class="error-content">
              <span class="error-icon">⚠</span>
              <span>{{ error }}</span>
              <button class="btn btn-small" @click="clearError">关闭</button>
            </div>
          </div>

          <div v-if="!isConnected" class="no-connection-overlay">
            <div class="no-connection-content">
              <span class="no-connection-icon">🔌</span>
              <h3>未连接到服务器</h3>
              <p>请点击右上角"连接服务器"按钮开始</p>
            </div>
          </div>
        </div>
      </main>

      <aside class="right-panel">
        <div class="panel">
          <div class="panel-header">
            <span>📈 实时统计</span>
          </div>
          <div class="panel-body">
            <div class="stat-item">
              <span class="stat-label">已处理切片</span>
              <span class="stat-value">{{ slicesProcessed }}</span>
            </div>
            <div class="stat-item">
              <span class="stat-label">处理耗时</span>
              <span class="stat-value">{{ processingTime.toFixed(1) }} ms</span>
            </div>
            <div class="stat-item">
              <span class="stat-label">处理帧率</span>
              <span class="stat-value">{{ processingTime > 0 ? (1000 / processingTime).toFixed(1) : 0 }} FPS</span>
            </div>
            <div class="stat-item">
              <span class="stat-label">校准因子</span>
              <span class="stat-value">{{ calibrationValue.toFixed(6) }}</span>
            </div>
          </div>
        </div>

        <div class="panel" style="margin-top: 16px;">
          <div class="panel-header">
            <span>🎯 处方剂量信息</span>
          </div>
          <div class="panel-body">
            <div class="stat-item">
              <span class="stat-label">95% 剂量覆盖</span>
              <span class="stat-value" style="color: var(--dose-95);">{{ isodose95Dose.toFixed(2) }} Gy</span>
            </div>
            <div class="stat-item">
              <span class="stat-label">100% 剂量覆盖</span>
              <span class="stat-value" style="color: var(--dose-100);">{{ isodose100Dose.toFixed(2) }} Gy</span>
            </div>
            <div class="stat-item">
              <span class="stat-label">107% 剂量覆盖</span>
              <span class="stat-value" style="color: var(--dose-107);">{{ isodose107Dose.toFixed(2) }} Gy</span>
            </div>
          </div>
        </div>

        <div class="panel" style="margin-top: 16px;">
          <div class="panel-header">
            <span>ℹ️ 系统信息</span>
          </div>
          <div class="panel-body">
            <div class="system-info">
              <div class="info-row">
                <span class="info-label">DICOM 解析</span>
                <span class="info-value good">纯 Java 实现</span>
              </div>
              <div class="info-row">
                <span class="info-label">等值线算法</span>
                <span class="info-value good">Marching Squares</span>
              </div>
              <div class="info-row">
                <span class="info-label">噪声滤波</span>
                <span class="info-value good">滑动加权平均</span>
              </div>
              <div class="info-row">
                <span class="info-label">缓冲机制</span>
                <span class="info-value good">无锁环形缓冲</span>
              </div>
              <div class="info-row">
                <span class="info-label">通信协议</span>
                <span class="info-value good">WebSocket 二进制</span>
              </div>
            </div>
          </div>
        </div>

        <div class="panel" style="margin-top: 16px;">
          <div class="panel-header">
            <span>⚙️ 剂量校准模拟</span>
          </div>
          <div class="panel-body">
            <p class="calibration-hint">模拟电离室剂量仪实时校准脉冲</p>
            <div class="button-group">
              <button class="btn" @click="sendCalibrationPulse(1.0)">
                发送正常脉冲
              </button>
              <button class="btn btn-danger" @click="sendCalibrationPulse(100.0)">
                发送噪声脉冲
              </button>
            </div>
          </div>
        </div>
      </aside>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, watch, nextTick } from 'vue'
import { useDoseStream } from './composables/useDoseStream'
import { DoseCanvasRenderer } from './rendering/DoseCanvasRenderer'
import { uploadRtDose, uploadRtStructure, sendCalibrationPulse as sendCalibrationPulseApi } from './services/api'

const doseCanvas = ref(null)
const canvasContainer = ref(null)

const {
  isConnected,
  isStreaming,
  currentSlice,
  currentContours,
  totalSlices,
  currentSliceIndex,
  processingTime,
  slicesProcessed,
  calibrationValue,
  error,
  connect,
  startStream,
  stopStream,
  requestSlice,
  setIsodoseLevels
} = useDoseStream()

const doseFileName = ref('')
const structureFileName = ref('')
const uploadingDose = ref(false)
const uploadingStructure = ref(false)
const showIsodose95 = ref(true)
const showIsodose100 = ref(true)
const showIsodose107 = ref(false)
const startSlice = ref(0)
const endSlice = ref(0)
const hoverDoseValue = ref(null)
const scale = ref(1.0)

let renderer = null
let resizeObserver = null

const connectionStatusClass = computed(() => {
  if (isStreaming.value) return 'status-streaming'
  if (isConnected.value) return 'status-connected'
  return 'status-disconnected'
})

const connectionStatusText = computed(() => {
  if (isStreaming.value) return '数据流传输中'
  if (isConnected.value) return '已连接'
  return '未连接'
})

const isodose95Dose = computed(() => {
  if (!currentSlice.value) return 0
  return currentSlice.value.doseMaximum * currentSlice.value.doseGridScaling * 0.95
})

const isodose100Dose = computed(() => {
  if (!currentSlice.value) return 0
  return currentSlice.value.doseMaximum * currentSlice.value.doseGridScaling * 1.0
})

const isodose107Dose = computed(() => {
  if (!currentSlice.value) return 0
  return currentSlice.value.doseMaximum * currentSlice.value.doseGridScaling * 1.07
})

const initRenderer = () => {
  if (!doseCanvas.value || !canvasContainer.value) return

  renderer = new DoseCanvasRenderer(doseCanvas.value)
  
  const container = canvasContainer.value
  renderer.resize(container.clientWidth, container.clientHeight)

  resizeObserver = new ResizeObserver((entries) => {
    for (const entry of entries) {
      const { width, height } = entry.contentRect
      renderer.resize(width, height)
    }
  })
  resizeObserver.observe(container)

  setupCanvasEvents()
}

const setupCanvasEvents = () => {
  if (!doseCanvas.value) return

  const canvas = doseCanvas.value
  let isDragging = false
  let lastX = 0
  let lastY = 0

  canvas.addEventListener('mousedown', (e) => {
    isDragging = true
    lastX = e.clientX
    lastY = e.clientY
  })

  canvas.addEventListener('mousemove', (e) => {
    const rect = canvas.getBoundingClientRect()
    const x = e.clientX - rect.left
    const y = e.clientY - rect.top

    if (isDragging && renderer) {
      const dx = e.clientX - lastX
      const dy = e.clientY - lastY
      renderer.pan(dx, dy)
      lastX = e.clientX
      lastY = e.clientY
    }

    if (renderer && currentSlice.value && currentSlice.value.doseData) {
      const doseValue = renderer.getDoseValueAt(x, y, currentSlice.value.doseData)
      hoverDoseValue.value = doseValue
    }
  })

  canvas.addEventListener('mouseup', () => {
    isDragging = false
  })

  canvas.addEventListener('mouseleave', () => {
    isDragging = false
    hoverDoseValue.value = null
  })

  canvas.addEventListener('wheel', (e) => {
    if (!renderer) return
    e.preventDefault()
    const rect = canvas.getBoundingClientRect()
    const x = e.clientX - rect.left
    const y = e.clientY - rect.top
    const factor = e.deltaY > 0 ? 0.9 : 1.1
    renderer.zoom(factor, x, y)
    scale.value = renderer.scale
  })
}

const connectToServer = async () => {
  try {
    await connect('/rt-verification/ws/dose-stream')
  } catch (e) {
    console.error('Failed to connect:', e)
  }
}

const disconnectFromServer = () => {
  if (isStreaming.value) {
    stopStream()
  }
}

const handleDoseFileUpload = async (e) => {
  const file = e.target.files[0]
  if (!file) return

  uploadingDose.value = true
  try {
    const response = await uploadRtDose(file)
    const result = response.data
    doseFileName.value = file.name
    if (result.totalSlices) {
      totalSlices.value = result.totalSlices
      startSlice.value = 0
      endSlice.value = result.totalSlices - 1
    }
  } catch (err) {
    console.error('Upload failed:', err)
  } finally {
    uploadingDose.value = false
  }
}

const handleStructureFileUpload = async (e) => {
  const file = e.target.files[0]
  if (!file) return

  uploadingStructure.value = true
  try {
    await uploadRtStructure(file)
    structureFileName.value = file.name
  } catch (err) {
    console.error('Upload failed:', err)
  } finally {
    uploadingStructure.value = false
  }
}

const startStreaming = () => {
  startStream(startSlice.value, endSlice.value)
}

const stopStreaming = () => {
  stopStream()
}

const onSliderChange = () => {
  if (isConnected.value && !isStreaming.value && totalSlices.value > 0) {
    requestSlice(currentSliceIndex.value)
  }
}

const onScaleChange = () => {
  if (renderer) {
    renderer.scale = scale.value
    renderer.scheduleRender()
  }
}

const resetView = () => {
  if (renderer) {
    renderer.resetView()
    scale.value = renderer.scale
  }
}

const updateIsodoseLevels = () => {
  const levels = []
  if (showIsodose95.value) levels.push(0.95)
  if (showIsodose100.value) levels.push(1.0)
  if (showIsodose107.value) levels.push(1.07)
  setIsodoseLevels(levels)
}

const sendCalibrationPulse = async (value) => {
  try {
    await sendCalibrationPulseApi(value)
  } catch (err) {
    console.error('Failed to send calibration pulse:', err)
  }
}

const clearError = () => {
  error.value = null
}

watch(currentSlice, (slice) => {
  if (!slice || !renderer) return

  renderer.setSliceDimensions(slice.columns, slice.rows, slice.pixelSpacingX, slice.pixelSpacingY)
  renderer.setDoseParameters(slice.doseMaximum, slice.doseGridScaling)
  renderer.setDoseData(slice.doseData)
})

watch(currentContours, (contours) => {
  if (renderer && contours) {
    renderer.setContours(contours)
  }
})

watch(scale, (newScale) => {
  if (renderer && renderer.scale !== newScale) {
    renderer.scale = newScale
    renderer.scheduleRender()
  }
})

onMounted(() => {
  nextTick(() => {
    initRenderer()
    updateIsodoseLevels()
  })
})

onUnmounted(() => {
  if (resizeObserver) {
    resizeObserver.disconnect()
  }
  if (renderer) {
    renderer.dispose()
  }
})
</script>

<style scoped>
.app-container {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.app-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 24px;
  background: var(--bg-secondary);
  border-bottom: 1px solid var(--border-color);
  flex-shrink: 0;
}

.header-title {
  display: flex;
  align-items: center;
  gap: 16px;
}

.logo-icon {
  font-size: 36px;
  color: var(--accent-cyan);
  filter: drop-shadow(0 0 10px rgba(56, 178, 172, 0.5));
}

.title-text h1 {
  font-size: 18px;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0;
}

.subtitle {
  font-size: 11px;
  color: var(--text-muted);
  margin: 2px 0 0 0;
  letter-spacing: 0.5px;
}

.header-status {
  display: flex;
  align-items: center;
  gap: 16px;
}

.header-actions {
  display: flex;
  gap: 8px;
}

.app-body {
  display: flex;
  flex: 1;
  overflow: hidden;
}

.left-panel,
.right-panel {
  width: 300px;
  padding: 16px;
  background: var(--bg-primary);
  overflow-y: auto;
  flex-shrink: 0;
}

.main-content {
  flex: 1;
  display: flex;
  overflow: hidden;
  background: var(--bg-primary);
}

.canvas-container {
  flex: 1;
  position: relative;
  background: var(--bg-primary);
  overflow: hidden;
}

.dose-canvas {
  width: 100%;
  height: 100%;
  cursor: grab;
}

.dose-canvas:active {
  cursor: grabbing;
}

.canvas-overlay {
  position: absolute;
  pointer-events: none;
  z-index: 10;
}

.top-left {
  top: 16px;
  left: 16px;
}

.top-right {
  top: 16px;
  right: 16px;
}

.slice-info {
  background: rgba(10, 14, 20, 0.9);
  border: 1px solid var(--border-color);
  border-radius: 6px;
  padding: 12px 16px;
  font-size: 12px;
  font-family: 'Consolas', 'Monaco', monospace;
  line-height: 1.8;
  color: var(--text-secondary);
}

.slice-info div {
  white-space: nowrap;
}

.dose-tooltip {
  background: rgba(10, 14, 20, 0.95);
  border: 1px solid var(--accent-blue);
  border-radius: 6px;
  padding: 10px 14px;
  font-size: 12px;
  font-family: 'Consolas', 'Monaco', monospace;
  line-height: 1.8;
  color: var(--text-primary);
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.4);
}

.error-overlay {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  z-index: 100;
}

.error-content {
  background: rgba(245, 101, 101, 0.95);
  border: 2px solid var(--accent-red);
  border-radius: 8px;
  padding: 16px 24px;
  display: flex;
  align-items: center;
  gap: 12px;
  color: white;
  font-size: 14px;
  box-shadow: 0 8px 32px rgba(245, 101, 101, 0.4);
}

.error-icon {
  font-size: 24px;
}

.btn-small {
  padding: 4px 12px;
  font-size: 12px;
  background: rgba(255, 255, 255, 0.2);
  border-color: rgba(255, 255, 255, 0.4);
}

.btn-small:hover {
  background: rgba(255, 255, 255, 0.3);
}

.no-connection-overlay {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(10, 14, 20, 0.9);
  z-index: 50;
}

.no-connection-content {
  text-align: center;
  color: var(--text-secondary);
}

.no-connection-icon {
  font-size: 64px;
  margin-bottom: 16px;
  display: block;
}

.no-connection-content h3 {
  color: var(--text-primary);
  margin: 0 0 8px 0;
  font-size: 18px;
}

.no-connection-content p {
  margin: 0;
  font-size: 13px;
}

.upload-section {
  margin-bottom: 8px;
}

.upload-hint {
  font-size: 11px;
  color: var(--text-muted);
  margin: 8px 0 0 0;
  text-align: center;
}

.control-group {
  margin-bottom: 12px;
}

.control-group label {
  display: block;
  font-size: 12px;
  color: var(--text-secondary);
  margin-bottom: 8px;
}

.range-inputs {
  display: flex;
  align-items: center;
  gap: 8px;
}

.range-input {
  flex: 1;
  padding: 6px 10px;
  background: var(--bg-tertiary);
  border: 1px solid var(--border-color);
  border-radius: 4px;
  color: var(--text-primary);
  font-size: 13px;
  font-family: 'Consolas', 'Monaco', monospace;
}

.range-input:focus {
  outline: none;
  border-color: var(--accent-blue);
}

.range-separator {
  color: var(--text-muted);
  font-size: 14px;
}

.button-group {
  display: flex;
  gap: 8px;
}

.button-group .btn {
  flex: 1;
}

.isodose-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 0;
  border-bottom: 1px solid var(--bg-tertiary);
}

.isodose-item:last-child {
  border-bottom: none;
}

.isodose-header {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
  color: var(--text-primary);
}

.isodose-color {
  width: 16px;
  height: 16px;
  border-radius: 3px;
  box-shadow: 0 0 8px currentColor;
}

.switch {
  position: relative;
  display: inline-block;
  width: 40px;
  height: 22px;
}

.switch input {
  opacity: 0;
  width: 0;
  height: 0;
}

.slider {
  position: absolute;
  cursor: pointer;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background-color: var(--bg-tertiary);
  transition: 0.2s;
  border-radius: 22px;
  border: 1px solid var(--border-color);
}

.slider:before {
  position: absolute;
  content: "";
  height: 14px;
  width: 14px;
  left: 3px;
  bottom: 3px;
  background-color: var(--text-muted);
  transition: 0.2s;
  border-radius: 50%;
}

input:checked + .slider {
  background-color: var(--accent-blue);
  border-color: var(--accent-blue);
}

input:checked + .slider:before {
  transform: translateX(18px);
  background-color: white;
}

.system-info {
  font-size: 12px;
}

.info-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 0;
  border-bottom: 1px solid var(--bg-tertiary);
}

.info-row:last-child {
  border-bottom: none;
}

.info-label {
  color: var(--text-secondary);
}

.info-value {
  font-family: 'Consolas', 'Monaco', monospace;
  font-weight: 500;
}

.info-value.good {
  color: var(--accent-green);
}

.calibration-hint {
  font-size: 11px;
  color: var(--text-muted);
  margin: 0 0 12px 0;
}
</style>
