export class DoseCanvasRenderer {
  constructor(canvas) {
    this.canvas = canvas
    this.ctx = canvas.getContext('2d')
    this.backBuffer = document.createElement('canvas')
    this.backCtx = this.backBuffer.getContext('2d')

    this.width = 0
    this.height = 0
    this.sliceWidth = 0
    this.sliceHeight = 0
    this.pixelSpacingX = 1.0
    this.pixelSpacingY = 1.0

    this.scale = 1.0
    this.offsetX = 0
    this.offsetY = 0

    this.ctImageData = null
    this.doseHeatmapData = null
    this.contours = []
    this.colormap = this.createColormap()

    this.isodoseColors = {
      0.95: '#48bb78',
      1.0: '#f56565',
      1.07: '#ecc94b'
    }

    this.doseMaximum = 0
    this.doseGridScaling = 1.0
    this.ctWindowLevel = 40
    this.ctWindowWidth = 400

    this.renderPending = false
    this.animationFrameId = null
  }

  resize(width, height) {
    this.width = width
    this.height = height
    this.canvas.width = width
    this.canvas.height = height
    this.backBuffer.width = width
    this.backBuffer.height = height
    this.scheduleRender()
  }

  setSliceDimensions(sliceWidth, sliceHeight, pixelSpacingX, pixelSpacingY) {
    this.sliceWidth = sliceWidth
    this.sliceHeight = sliceHeight
    this.pixelSpacingX = pixelSpacingX
    this.pixelSpacingY = pixelSpacingY
    this.computeFitTransform()
  }

  setDoseParameters(doseMaximum, doseGridScaling) {
    this.doseMaximum = doseMaximum
    this.doseGridScaling = doseGridScaling
  }

  setCTImageData(data) {
    this.ctImageData = data
    this.scheduleRender()
  }

  setDoseData(sliceData) {
    if (this.sliceWidth > 0 && this.sliceHeight > 0) {
      this.doseHeatmapData = this.generateDoseHeatmap(sliceData)
      this.scheduleRender()
    }
  }

  setContours(contourResults) {
    this.contours = contourResults
    this.scheduleRender()
  }

  setIsodoseColors(colors) {
    this.isodoseColors = { ...this.isodoseColors, ...colors }
    this.scheduleRender()
  }

  setCTWindow(level, width) {
    this.ctWindowLevel = level
    this.ctWindowWidth = width
    this.scheduleRender()
  }

  zoom(factor, centerX, centerY) {
    const oldScale = this.scale
    this.scale = Math.max(0.1, Math.min(10, this.scale * factor))
    const scaleChange = this.scale / oldScale
    this.offsetX = centerX - (centerX - this.offsetX) * scaleChange
    this.offsetY = centerY - (centerY - this.offsetY) * scaleChange
    this.scheduleRender()
  }

  pan(dx, dy) {
    this.offsetX += dx
    this.offsetY += dy
    this.scheduleRender()
  }

  resetView() {
    this.computeFitTransform()
    this.scheduleRender()
  }

  computeFitTransform() {
    if (this.sliceWidth === 0 || this.sliceHeight === 0 || this.width === 0 || this.height === 0) {
      return
    }

    const displayWidth = this.sliceWidth * this.pixelSpacingX
    const displayHeight = this.sliceHeight * this.pixelSpacingY

    const scaleX = (this.width * 0.95) / displayWidth
    const scaleY = (this.height * 0.95) / displayHeight
    this.scale = Math.min(scaleX, scaleY)

    this.offsetX = (this.width - displayWidth * this.scale) / 2
    this.offsetY = (this.height - displayHeight * this.scale) / 2
  }

  generateDoseHeatmap(sliceData) {
    if (!sliceData || this.sliceWidth === 0 || this.sliceHeight === 0) return null

    const width = this.sliceWidth
    const height = this.sliceHeight
    const imageData = this.backCtx.createImageData(width, height)
    const pixels = imageData.data

    const maxDose = this.doseMaximum / this.doseGridScaling

    for (let y = 0; y < height; y++) {
      for (let x = 0; x < width; x++) {
        const idx = y * width + x
        const pixelIdx = idx * 4

        const rawDose = sliceData[idx]
        const normalizedDose = Math.min(1.0, rawDose / maxDose)

        if (normalizedDose > 0.01) {
          const color = this.getDoseColor(normalizedDose)
          pixels[pixelIdx] = color.r
          pixels[pixelIdx + 1] = color.g
          pixels[pixelIdx + 2] = color.b
          pixels[pixelIdx + 3] = Math.floor(normalizedDose * 180)
        } else {
          pixels[pixelIdx + 3] = 0
        }
      }
    }

    return imageData
  }

  getDoseColor(normalizedDose) {
    const idx = Math.min(this.colormap.length - 1, Math.floor(normalizedDose * this.colormap.length))
    return this.colormap[idx]
  }

  createColormap() {
    const colors = []
    const numPoints = 256

    for (let i = 0; i < numPoints; i++) {
      const t = i / (numPoints - 1)
      let r, g, b

      if (t < 0.25) {
        const nt = t / 0.25
        r = 0
        g = Math.floor(0 + nt * 255)
        b = 255
      } else if (t < 0.5) {
        const nt = (t - 0.25) / 0.25
        r = 0
        g = 255
        b = Math.floor(255 - nt * 255)
      } else if (t < 0.75) {
        const nt = (t - 0.5) / 0.25
        r = Math.floor(nt * 255)
        g = 255
        b = 0
      } else {
        const nt = (t - 0.75) / 0.25
        r = 255
        g = Math.floor(255 - nt * 255)
        b = 0
      }

      colors.push({ r, g, b })
    }

    return colors
  }

  scheduleRender() {
    if (this.renderPending) return
    this.renderPending = true

    if (this.animationFrameId) {
      cancelAnimationFrame(this.animationFrameId)
    }

    this.animationFrameId = requestAnimationFrame(() => {
      this.render()
      this.renderPending = false
    })
  }

  render() {
    const ctx = this.backCtx
    ctx.clearRect(0, 0, this.width, this.height)

    ctx.fillStyle = '#0a0e14'
    ctx.fillRect(0, 0, this.width, this.height)

    ctx.save()
    ctx.translate(this.offsetX, this.offsetY)
    ctx.scale(this.scale, this.scale)
    ctx.scale(this.pixelSpacingX, this.pixelSpacingY)

    this.renderCTBackground(ctx)
    this.renderDoseHeatmap(ctx)
    this.renderContours(ctx)

    ctx.restore()

    this.renderOverlay()

    this.ctx.clearRect(0, 0, this.width, this.height)
    this.ctx.drawImage(this.backBuffer, 0, 0)
  }

  renderCTBackground(ctx) {
    if (this.ctImageData) {
      ctx.putImageData(this.ctImageData, 0, 0)
      return
    }

    const gradient = ctx.createLinearGradient(0, 0, 0, this.sliceHeight)
    gradient.addColorStop(0, '#1a1f26')
    gradient.addColorStop(0.5, '#252b36')
    gradient.addColorStop(1, '#1a1f26')
    ctx.fillStyle = gradient
    ctx.fillRect(0, 0, this.sliceWidth, this.sliceHeight)

    ctx.strokeStyle = 'rgba(74, 85, 104, 0.3)'
    ctx.lineWidth = 0.5
    const gridSpacing = 10

    for (let x = 0; x <= this.sliceWidth; x += gridSpacing) {
      ctx.beginPath()
      ctx.moveTo(x, 0)
      ctx.lineTo(x, this.sliceHeight)
      ctx.stroke()
    }

    for (let y = 0; y <= this.sliceHeight; y += gridSpacing) {
      ctx.beginPath()
      ctx.moveTo(0, y)
      ctx.lineTo(this.sliceWidth, y)
      ctx.stroke()
    }

    ctx.strokeStyle = '#4299e1'
    ctx.lineWidth = 2
    ctx.strokeRect(0, 0, this.sliceWidth, this.sliceHeight)
  }

  renderDoseHeatmap(ctx) {
    if (this.doseHeatmapData) {
      ctx.putImageData(this.doseHeatmapData, 0, 0)
    }
  }

  renderContours(ctx) {
    if (!this.contours || this.contours.length === 0) return

    for (const result of this.contours) {
      const level = result.level
      const color = this.isodoseColors[level] || '#ffffff'

      ctx.strokeStyle = color
      ctx.lineWidth = 1.5 / this.scale
      ctx.lineJoin = 'round'
      ctx.lineCap = 'round'

      ctx.shadowColor = color
      ctx.shadowBlur = 4

      for (const contour of result.contours) {
        if (contour.length < 3) continue

        ctx.beginPath()
        ctx.moveTo(contour[0].x, contour[0].y)

        for (let i = 1; i < contour.length; i++) {
          ctx.lineTo(contour[i].x, contour[i].y)
        }

        ctx.closePath()
        ctx.stroke()
      }

      ctx.shadowBlur = 0
    }
  }

  renderOverlay() {
    const ctx = this.backCtx

    if (this.contours && this.contours.length > 0) {
      let y = 15
      ctx.font = 'bold 12px Segoe UI, Microsoft YaHei, sans-serif'
      ctx.textBaseline = 'top'

      for (const result of this.contours) {
        const level = result.level
        const color = this.isodoseColors[result.level] || '#ffffff'

        ctx.fillStyle = color
        ctx.fillRect(15, y, 12, 12)
        ctx.strokeStyle = 'rgba(255,255,255,0.3)'
        ctx.lineWidth = 1
        ctx.strokeRect(15, y, 12, 12)

        ctx.fillStyle = '#e6e6e6'
        const displayDose = (result.absoluteDose).toFixed(2)
        ctx.fillText(`${(level * 100).toFixed(0)}% 等剂量线 (${displayDose} Gy)`, 32, y + 1)
        y += 20
      }
    }
  }

  screenToSlice(screenX, screenY) {
    const x = (screenX - this.offsetX) / (this.scale * this.pixelSpacingX)
    const y = (screenY - this.offsetY) / (this.scale * this.pixelSpacingY)
    return { x, y }
  }

  sliceToScreen(sliceX, sliceY) {
    const x = sliceX * this.pixelSpacingX * this.scale + this.offsetX
    const y = sliceY * this.pixelSpacingY * this.scale + this.offsetY
    return { x, y }
  }

  getDoseValueAt(screenX, screenY, sliceData) {
    const { x, y } = this.screenToSlice(screenX, screenY)
    const ix = Math.floor(x)
    const iy = Math.floor(y)

    if (ix < 0 || ix >= this.sliceWidth || iy < 0 || iy >= this.sliceHeight) {
      return null
    }

    const idx = iy * this.sliceWidth + ix
    const rawDose = sliceData[idx]
    return {
      raw: rawDose,
      absolute: rawDose * this.doseGridScaling,
      relative: rawDose / (this.doseMaximum / this.doseGridScaling)
    }
  }

  dispose() {
    if (this.animationFrameId) {
      cancelAnimationFrame(this.animationFrameId)
    }
    this.ctImageData = null
    this.doseHeatmapData = null
    this.contours = []
  }
}
