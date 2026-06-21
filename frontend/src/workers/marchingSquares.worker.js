const MARCHING_SQUARES_TABLE = new Int32Array([
  0, 0, 0, 0,
  3, 0, 0, 0,
  0, 0, 1, 0,
  3, 0, 1, 0,
  1, 2, 0, 0,
  1, 2, 3, 0,
  1, 0, 1, 0,
  3, 0, 1, 0,
  2, 3, 0, 0,
  2, 3, 0, 0,
  2, 0, 1, 0,
  3, 2, 1, 0,
  2, 3, 1, 0,
  2, 3, 1, 0,
  2, 3, 1, 0,
  0, 0, 0, 0
])

class MarchingSquares {
  constructor() {
    this.isodoseLevels = [0.95, 1.0]
    this.vertexCache = new Map()
  }

  setIsodoseLevels(levels) {
    this.isodoseLevels = levels
  }

  computeContours(sliceData, width, height, doseGridScaling, doseMaximum) {
    const results = []

    for (let levelIndex = 0; levelIndex < this.isodoseLevels.length; levelIndex++) {
      const relativeLevel = this.isodoseLevels[levelIndex]
      const absoluteThreshold = doseMaximum * relativeLevel * (1.0 / doseGridScaling)

      const contourPoints = this.march(sliceData, width, height, absoluteThreshold)
      const closedContours = this.extractClosedContours(contourPoints, width, height)

      results.push({
        level: this.isodoseLevels[levelIndex],
        absoluteDose: doseMaximum * relativeLevel,
        contours: closedContours
      })
    }

    return results
  }

  march(data, width, height, threshold) {
    const segments = []
    const stride = width

    for (let y = 0; y < height - 1; y++) {
      for (let x = 0; x < width - 1; x++) {
        const idx00 = y * stride + x
        const idx10 = idx00 + 1
        const idx01 = idx00 + stride
        const idx11 = idx01 + 1

        const v00 = data[idx00]
        const v10 = data[idx10]
        const v01 = data[idx01]
        const v11 = data[idx11]

        let caseIndex = 0
        if (v00 >= threshold) caseIndex |= 1
        if (v10 >= threshold) caseIndex |= 2
        if (v11 >= threshold) caseIndex |= 4
        if (v01 >= threshold) caseIndex |= 8

        caseIndex <<= 2
        const edge1 = MARCHING_SQUARES_TABLE[caseIndex]
        const edge2 = MARCHING_SQUARES_TABLE[caseIndex + 1]

        if (edge1 !== 0 && edge2 !== 0) {
          const p1 = this.interpolateEdge(x, y, edge1, v00, v10, v01, v11, threshold)
          const p2 = this.interpolateEdge(x, y, edge2, v00, v10, v01, v11, threshold)

          if (p1 && p2) {
            segments.push([p1, p2])
          }
        }
      }
    }

    return segments
  }

  interpolateEdge(x, y, edge, v00, v10, v01, v11, threshold) {
    let t

    switch (edge) {
      case 1:
        t = (threshold - v00) / (v10 - v00)
        return { x: x + t, y: y }
      case 2:
        t = (threshold - v10) / (v11 - v10)
        return { x: x + 1, y: y + t }
      case 3:
        t = (threshold - v01) / (v11 - v01)
        return { x: x + t, y: y + 1 }
      case 4:
        t = (threshold - v00) / (v01 - v00)
        return { x: x, y: y + t }
      default:
        return null
    }
  }

  extractClosedContours(segments, width, height) {
    if (segments.length === 0) return []

    const contours = []
    const used = new Uint8Array(segments.length)
    const pointToSegments = new Map()

    for (let i = 0; i < segments.length; i++) {
      const [p1, p2] = segments[i]
      const key1 = this.pointKey(p1)
      const key2 = this.pointKey(p2)

      if (!pointToSegments.has(key1)) pointToSegments.set(key1, [])
      if (!pointToSegments.has(key2)) pointToSegments.set(key2, [])

      pointToSegments.get(key1).push({ segmentIndex: i, end: 1 })
      pointToSegments.get(key2).push({ segmentIndex: i, end: 2 })
    }

    for (let i = 0; i < segments.length; i++) {
      if (used[i]) continue

      const contour = []
      let currentSeg = i
      let currentEnd = 1

      while (currentSeg !== -1 && !used[currentSeg]) {
        used[currentSeg] = 1
        const [p1, p2] = segments[currentSeg]

        if (currentEnd === 1) {
          contour.push({ x: p1.x, y: p1.y })
          const next = this.findNextSegment(p2, pointToSegments, currentSeg, used)
          currentSeg = next.segmentIndex
          currentEnd = next.end
        } else {
          contour.push({ x: p2.x, y: p2.y })
          const next = this.findNextSegment(p1, pointToSegments, currentSeg, used)
          currentSeg = next.segmentIndex
          currentEnd = next.end
        }
      }

      if (contour.length >= 3) {
        contours.push(contour)
      }
    }

    return contours
  }

  findNextSegment(point, pointToSegments, currentSeg, used) {
    const key = this.pointKey(point)
    const connected = pointToSegments.get(key)

    if (!connected) return { segmentIndex: -1, end: -1 }

    for (const conn of connected) {
      if (conn.segmentIndex !== currentSeg && !used[conn.segmentIndex]) {
        return {
          segmentIndex: conn.segmentIndex,
          end: conn.end === 1 ? 2 : 1
        }
      }
    }

    return { segmentIndex: -1, end: -1 }
  }

  pointKey(p) {
    return `${p.x.toFixed(6)},${p.y.toFixed(6)}`
  }
}

const marchingSquares = new MarchingSquares()

self.onmessage = (e) => {
  const { type, data } = e.data

  switch (type) {
    case 'SET_LEVELS':
      marchingSquares.setIsodoseLevels(data.levels)
      break

    case 'COMPUTE_CONTOURS':
      try {
        const startTime = performance.now()
        const results = marchingSquares.computeContours(
          data.sliceData,
          data.width,
          data.height,
          data.doseGridScaling,
          data.doseMaximum
        )
        const duration = performance.now() - startTime

        self.postMessage({
          type: 'CONTOURS_READY',
          data: {
            sliceIndex: data.sliceIndex,
            results,
            processingTime: duration
          }
        }, [])
      } catch (error) {
        self.postMessage({
          type: 'ERROR',
          data: { message: error.message }
        })
      }
      break
  }
}

self.postMessage({ type: 'READY' })
