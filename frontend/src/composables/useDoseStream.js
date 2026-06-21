import { ref, onMounted, onUnmounted, shallowRef } from 'vue'
import { websocketService } from '../services/websocket'

export function useDoseStream() {
  const isConnected = ref(false)
  const isStreaming = ref(false)
  const currentSlice = shallowRef(null)
  const currentContours = shallowRef(null)
  const totalSlices = ref(0)
  const currentSliceIndex = ref(0)
  const processingTime = ref(0)
  const slicesProcessed = ref(0)
  const calibrationValue = ref(0)
  const error = ref(null)

  let wsUnsubscribers = []
  let worker = null

  const initWorker = () => {
    worker = new Worker(
      new URL('../workers/marchingSquares.worker.js', import.meta.url),
      { type: 'module' }
    )

    worker.onmessage = (e) => {
      const { type, data } = e.data
      if (type === 'CONTOURS_READY') {
        currentContours.value = data.results
        processingTime.value = data.processingTime
        slicesProcessed.value++
      } else if (type === 'ERROR') {
        error.value = data.message
      } else if (type === 'READY') {
        console.log('[MarchingSquares] Worker ready')
      }
    }

    worker.onerror = (e) => {
      error.value = `Worker error: ${e.message}`
      console.error('[MarchingSquares] Worker error:', e)
    }
  }

  const connect = async (url) => {
    try {
      await websocketService.connect(url)
      isConnected.value = true
      error.value = null

      wsUnsubscribers.push(
        websocketService.on('sliceData', handleSliceData),
        websocketService.on('connected', () => { isConnected.value = true }),
        websocketService.on('disconnected', () => {
          isConnected.value = false
          isStreaming.value = false
        }),
        websocketService.on('STREAM_STARTING', () => {
          isStreaming.value = true
          slicesProcessed.value = 0
        }),
        websocketService.on('STREAM_COMPLETE', () => {
          isStreaming.value = false
        }),
        websocketService.on('STREAM_STOPPED', () => {
          isStreaming.value = false
        }),
        websocketService.on('CALIBRATION_UPDATED', (data) => {
          calibrationValue.value = data.calibratedValue
        }),
        websocketService.on('ERROR', (data) => {
          error.value = data.message
        })
      )
    } catch (e) {
      error.value = e.message
      throw e
    }
  }

  const handleSliceData = (slice) => {
    currentSlice.value = slice
    currentSliceIndex.value = slice.sliceIndex
    totalSlices.value = slice.totalSlices
    calibrationValue.value = slice.filteredCalibrationValue

    if (slice.doseData && slice.doseData.length > 0) {
      computeContours(slice)
    }
  }

  const computeContours = (slice) => {
    if (!worker) return

    worker.postMessage({
      type: 'COMPUTE_CONTOURS',
      data: {
        sliceIndex: slice.sliceIndex,
        sliceData: slice.doseData,
        width: slice.columns,
        height: slice.rows,
        doseGridScaling: slice.doseGridScaling,
        doseMaximum: slice.doseMaximum
      }
    }, [])
  }

  const setIsodoseLevels = (levels) => {
    if (worker) {
      worker.postMessage({
        type: 'SET_LEVELS',
        data: { levels }
      })
    }
    websocketService.setIsodoseLevels(levels)
  }

  const startStream = (startIndex, endIndex) => {
    websocketService.startStream(startIndex, endIndex)
  }

  const stopStream = () => {
    websocketService.stopStream()
    isStreaming.value = false
  }

  const requestSlice = (index) => {
    websocketService.requestSlice(index)
  }

  const requestRange = (start, end) => {
    websocketService.requestRange(start, end)
  }

  onMounted(() => {
    initWorker()
  })

  onUnmounted(() => {
    wsUnsubscribers.forEach(unsub => unsub())
    websocketService.disconnect()
    if (worker) {
      worker.terminate()
      worker = null
    }
  })

  return {
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
    requestRange,
    setIsodoseLevels
  }
}
